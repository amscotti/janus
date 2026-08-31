package io.amscotti.janus;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Top-level {@code janus.*} configuration bound from TOML ({@code application.toml} +
 * the operator's {@code config.toml} via {@code micronaut.config.files}). The only place
 * TOML is parsed: {@code modelList} carries the {@code [[janus.model-list]]}
 * array-of-tables — model aliases + provider entries the {@code RouterFactory} turns
 * into the router's route map — {@code providers} carries the {@code
 * [janus.providers.<name>]} blocks — declarative mapping hints + per-provider defaults —
 * {@code router} carries the {@code [janus.router]} block — the routing knobs
 * (strategy, weights, retry counts, health thresholds, breaker knobs) the {@code
 * RouterFactory} wires into the router — and {@code keys} carries the
 * {@code [janus.keys]} block — the governance switch (master-key env-var name) — and
 * {@code pricing} carries the {@code [janus.pricing]} block — the per-model price
 * table rows — and {@code limits} carries the {@code [janus.limits]} block — the
 * rate-limiter window, soft-cap fraction, notifier webhook URL and ledger retention the
 * {@code GovernanceFactory} wires into the accounting layer — and
 * {@code store} carries the {@code [janus.store]} block — the store backend switch
 * (memory default | postgres), the JDBC env references and the call-ring retention
 * the {@code CallStoreFactory} wires into the / store — and
 * {@code timeouts} carries the {@code [janus.timeouts]} block — the four upstream
 * deadlines (connect, header-arrival, non-stream body-read, stream-idle) the
 * {@code RouterFactory} resolves into the provider adapters' timeout constructors
 * and the face controllers' SSE watchdog.
 *
 * <p>Nothing constructs this record manually (verified by grep — tests inject it); the
 * binding test ({@code ModelListBindingTest}) pins the shape. {@code modelList},
 * {@code providers}, {@code router}, {@code keys}, {@code pricing},
 * {@code limits} and {@code store} are null when their TOML sections are absent
 * ({@code @Nullable} — Micronaut otherwise fails binding on an absent property) — valid
 * boot states the factories treat as empty / defaults. {@code keys} follows the
 * {@code [janus.router]} precedent: Micronaut 5.1 nested-record binding keeps the bean
 * alive with all {@code @Nullable} components null (verified by {@code
 * JanusConfigTest}) — the {@code MasterKeyProvider} resolves the default env name and
 * fails the boot when {@code auth = "on"} (the default) and the env is unresolvable.
 * Absent {@code [janus.pricing]}/{@code
 * [janus.limits]} likewise leave null components ⇒ the {@code GovernanceFactory}
 * applies its documented defaults (no pricing rows ⇒ zero-rate metering; fixed-window
 * limiter; logger-only notifier) — noop-level governance for minimal configs.
 * Absent {@code [janus.store]} ⇒ the {@code CallStoreFactory} applies its documented
 * defaults (memory backend, retention 1000, pool 10) — default behavior
 * byte-identical. {@code timeouts} likewise keeps the bean alive with null
 * components ⇒ the {@code RouterFactory} resolves {@link TimeoutsConfig#DEFAULTS}
 * (the pinned code constants) — default deadlines byte-identical.
 */
@ConfigurationProperties("janus")
public record JanusConfig(
        String name,
        String version,
        @Nullable List<ModelListEntry> modelList,
        @Nullable Map<String, ProviderEntry> providers,
        @Nullable RouterConfig router,
        @Nullable KeysConfig keys,
        @Nullable PricingConfig pricing,
        @Nullable LimitsConfig limits,
        @Nullable StoreConfig store,
        @Nullable PrivacyConfig privacy,
        @Nullable TimeoutsConfig timeouts) {

    /** Pre-timeouts arity (the pre-privacy test fixtures): deadlines = defaults. */
    public JanusConfig(
            String name,
            String version,
            @Nullable List<ModelListEntry> modelList,
            @Nullable Map<String, ProviderEntry> providers,
            @Nullable RouterConfig router,
            @Nullable KeysConfig keys,
            @Nullable PricingConfig pricing,
            @Nullable LimitsConfig limits,
            @Nullable StoreConfig store,
            @Nullable PrivacyConfig privacy) {
        this(name, version, modelList, providers, router, keys, pricing, limits, store, privacy, null);
    }

    /** Pre-privacy-section arity (the store-only test fixtures): content logging off. */
    public JanusConfig(
            String name,
            String version,
            @Nullable List<ModelListEntry> modelList,
            @Nullable Map<String, ProviderEntry> providers,
            @Nullable RouterConfig router,
            @Nullable KeysConfig keys,
            @Nullable PricingConfig pricing,
            @Nullable LimitsConfig limits,
            @Nullable StoreConfig store) {
        this(name, version, modelList, providers, router, keys, pricing, limits, store, null);
    }

    /**
     * Out-of-scope divergence: {@code [janus.limits]
     * window = "sliding"} combined with {@code [janus.store] type = "postgres"} is
     * rejected at binding time. Today {@code CallStoreFactory} selects the
     * token-bucket limiter only in the memory branch — a postgres node always uses
     * {@code PgRateLimiter} (fixed-window), so the combination would silently
     * enforce different semantics than the operator asked for. Fail-fast at boot
     *, recorded in {@code config.toml} + {@code docs/clustering.md}:
     * Postgres mode is fixed-window only.
     *
     * <p>Also the {@code [janus.limits] window} value itself is
     * validated here, at binding time — a typo like {@code "slidng"} or {@code
     * "bucket"} must never silently degrade to the fixed-window limiter. A blank
     * window counts as absent (the factory applies the {@code "fixed"} default); a
     * present non-blank value must be exactly {@code "fixed"} or {@code "sliding"}
     * (the fail-fast discipline the {@code LimitsConfig} javadoc promised).
     */
    public JanusConfig {
        if (limits != null && limits.window() != null && !limits.window().isBlank()) {
            String window = limits.window();
            if (!"fixed".equals(window) && !"sliding".equals(window)) {
                throw new IllegalArgumentException(
                        "[janus.limits] window must be \"fixed\" or \"sliding\" (got \"" + window + "\")");
            }
        }
        if (limits != null
                && StoreConfig.TYPE_POSTGRES.equals(store == null ? null : store.type())
                && "sliding".equals(limits.window())) {
            throw new IllegalArgumentException("[janus.limits] window = \"sliding\" with [janus.store] type ="
                    + " \"postgres\" is not supported: the Postgres store enforces fixed-window rate limits only"
                    + " (see config.toml and docs/clustering.md)");
        }
    }

    /** Declarative wire-format hint values; see {@link ProviderEntry}. */
    public static final String WIRE_FORMAT_OPENAI_COMPATIBLE = "openai-compatible";

    public static final String WIRE_FORMAT_ANTHROPIC = "anthropic";

    /**
     * One {@code [[janus.model_list]]} entry: the model
     * alias callers use (the router key) + the provider entry that serves it.
     * {@code apiKeyEnv} names the environment variable holding the credential (never the
     * key itself); {@code baseUrl} is the provider endpoint (omitted → the provider
     * block's default or the adapter's default, applied by the production provider
     * constructor).
     *
     * <p><b>TOML spelling.</b> Array-of-tables element keys in
     * {@code [[janus.model-list]]} keep their <em>raw</em> spellings — Micronaut does
     * <em>not</em> normalize {@code _} and {@code -} here, so the kebab-case keys
     * {@code api-key-env} / {@code base-url} are the only binding spellings and
     * {@code api_key_env} / {@code base_url} silently bind null (a 401-at-runtime
     * footgun; pinned by
     * {@code ModelListBindingTest.underscoreKeysSilentlyNullCredentialFields}). The
     * {@code _}/{@code -} equivalence holds for plain-section scalar keys only (see
     * {@link RouterConfig}); the kebab spelling stays the documented one (documented in
     * {@code config.toml} and {@code docs/routing.md}). Blank {@code name} /
     * {@code provider} are rejected at binding time (fail-fast at boot — a routing entry
     * without a name or provider is always a config error); absent {@code apiKeyEnv} /
     * {@code baseUrl} are tolerated (null).
     *
     * <p><b>Per-entry timeout overrides.</b> The four {@code [janus.timeouts]}
     * keys, same kebab {@code -seconds} spelling, nullable: a null component falls
     * through to the provider block, then the global section (entry &gt; provider
     * block &gt; {@code [janus.timeouts]} — the exact merge pattern {@code base-url}/
     * {@code api-key-env} follow). A non-positive value is rejected by the compact
     * constructor, the error naming the key (the {@link TimeoutsConfig}
     * compact-constructor precedent); at TOML-bind time a rejected element is
     * dropped by Micronaut's collection-element binding (pinned by {@code
     * ModelListBindingTest.nonPositiveOverrideDropsTheElementAtBinding}).
     *
     * @param name model alias callers use (router key); non-blank
     * @param provider {@code ProviderAdapter.name} (ServiceLoader-validated); non-blank
     * @param apiKeyEnv env var name holding the credential; null when the entry has no
     * credential
     * @param baseUrl provider base URL; null → the provider block's default, else the
     * adapter's default
     * @param connectTimeoutSeconds entry-level {@code connect-timeout-seconds}
     * override; null → provider block, then global
     * @param headerTimeoutSeconds entry-level {@code header-timeout-seconds}
     * override; null → provider block, then global
     * @param bodyReadTimeoutSeconds entry-level {@code body-read-timeout-seconds}
     * override; null → provider block, then global
     * @param streamIdleTimeoutSeconds entry-level {@code stream-idle-timeout-seconds}
     * — legal but INEFFECTIVE here: the value merges into the per-backend adapter
     * config the provider constructor receives (harmless — the SSE watchdog never
     * reads it there), while the watchdog honors the key ONLY at the provider-block
     * level (it resolves per dispatch by the serving provider — see {@code
     * StreamIdleTimeoutResolver}); a non-null entry value triggers the boot warning
     * ({@code RouterFactory.warnAboutEntryStreamIdleTimeouts}). Null → provider
     * block, then global (the adapter-config merge only)
     */
    public record ModelListEntry(
            String name,
            String provider,
            String apiKeyEnv,
            String baseUrl,
            @Nullable Integer connectTimeoutSeconds,
            @Nullable Integer headerTimeoutSeconds,
            @Nullable Integer bodyReadTimeoutSeconds,
            @Nullable Integer streamIdleTimeoutSeconds) {

        /** Pre-timeouts arity (the pre-fixtures): no per-entry overrides. */
        public ModelListEntry(String name, String provider, String apiKeyEnv, String baseUrl) {
            this(name, provider, apiKeyEnv, baseUrl, null, null, null, null);
        }

        public ModelListEntry {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("model_list entry name must be non-blank");
            }
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("model_list entry provider must be non-blank");
            }
            requirePositiveTimeout(connectTimeoutSeconds, "model_list entry", "connect-timeout-seconds");
            requirePositiveTimeout(headerTimeoutSeconds, "model_list entry", "header-timeout-seconds");
            requirePositiveTimeout(bodyReadTimeoutSeconds, "model_list entry", "body-read-timeout-seconds");
            requirePositiveTimeout(streamIdleTimeoutSeconds, "model_list entry", "stream-idle-timeout-seconds");
        }
    }

    /**
     * One {@code [janus.providers.<name>]} block: the
     * declarative half of provider definition. The block key equals the {@code
     * model_list} entry's {@code provider} name. {@code wireFormat} names the
     * wire-format family the provider speaks ({@code "openai-compatible"} or {@code
     * "anthropic"}) — a mapping <em>hint</em>: all actual field translation stays code
     * (the {@code OpenAiMessageCodec} / {@code AnthropicMessageCodec}); the TOML
     * block documents the declarative-vs-code split (written up in
     * {@code docs/adding-a-provider.md}).
     * {@code baseUrl}/{@code apiKeyEnv} are per-provider <em>defaults</em> merged under
     * matching {@code model_list} entries that omit them (the entry's own values win).
     *
     * <p>Absent members are tolerated (null); a present {@code wireFormat} must be a
     * known family name (rejected at binding time — fail-fast at boot).
     *
     * <p><b>Per-provider timeout overrides.</b> The four {@code [janus.timeouts]}
     * keys, same kebab {@code -seconds} spelling, nullable: a null component falls
     * through to the global section under entries that do not override it (entry
     * &gt; provider block &gt; {@code [janus.timeouts]} — the exact merge pattern
     * {@code base-url}/{@code api-key-env} follow). A block's {@code
     * stream-idle-timeout-seconds} additionally overrides the SSE idle watchdog for
     * every request dispatched to this provider's backends, on all three faces
     * ({@code StreamIdleTimeoutResolver} keys the override by the <b>resolved
     * backend name</b> the dispatch observer reports — the entry's provider name,
     * which for a fixed-name wire-format family fallback ({@code "anthropic"} under
     * a custom provider name) is the family's fixed adapter name, not the block
     * key; a boot warning names the shared key). A non-positive value is
     * rejected by the compact constructor, the error naming the key (the {@link
     * TimeoutsConfig} compact-constructor precedent); at TOML-bind time a rejected
     * block is dropped by Micronaut's collection-element binding (pinned by {@code
     * ModelListBindingTest.nonPositiveOverrideDropsTheElementAtBinding}).
     *
     * @param wireFormat adapter-family hint: {@code "openai-compatible"} or
     * {@code "anthropic"}; null when omitted
     * @param baseUrl default provider base URL for entries that omit {@code base-url}
     * @param apiKeyEnv default env-var name for entries that omit {@code api-key-env}
     * @param connectTimeoutSeconds provider-level {@code connect-timeout-seconds}
     * override; null → global
     * @param headerTimeoutSeconds provider-level {@code header-timeout-seconds}
     * override; null → global
     * @param bodyReadTimeoutSeconds provider-level {@code body-read-timeout-seconds}
     * override; null → global
     * @param streamIdleTimeoutSeconds provider-level {@code stream-idle-timeout-seconds}
     * override — also the per-dispatch SSE watchdog deadline for requests served by
     * this provider; null → global
     */
    public record ProviderEntry(
            String wireFormat,
            String baseUrl,
            String apiKeyEnv,
            @Nullable Integer connectTimeoutSeconds,
            @Nullable Integer headerTimeoutSeconds,
            @Nullable Integer bodyReadTimeoutSeconds,
            @Nullable Integer streamIdleTimeoutSeconds) {

        /** Pre-timeouts arity (the pre-fixtures): no per-provider overrides. */
        public ProviderEntry(String wireFormat, String baseUrl, String apiKeyEnv) {
            this(wireFormat, baseUrl, apiKeyEnv, null, null, null, null);
        }

        public ProviderEntry {
            if (wireFormat != null
                    && !wireFormat.isBlank()
                    && !WIRE_FORMAT_OPENAI_COMPATIBLE.equals(wireFormat)
                    && !WIRE_FORMAT_ANTHROPIC.equals(wireFormat)) {
                throw new IllegalArgumentException("providers entry wire-format must be \"openai-compatible\""
                        + " or \"anthropic\" (got \"" + wireFormat + "\")");
            }
            requirePositiveTimeout(connectTimeoutSeconds, "providers entry", "connect-timeout-seconds");
            requirePositiveTimeout(headerTimeoutSeconds, "providers entry", "header-timeout-seconds");
            requirePositiveTimeout(bodyReadTimeoutSeconds, "providers entry", "body-read-timeout-seconds");
            requirePositiveTimeout(streamIdleTimeoutSeconds, "providers entry", "stream-idle-timeout-seconds");
        }
    }

    /**
     * Rejects a non-positive per-entry / per-provider timeout override at binding
     * time, the error naming the key (the {@link TimeoutsConfig} compact-constructor
     * precedent — a typo'd {@code 0} deadline is never silently swapped for the
     * inherited level). Null (absent key) is the fall-through case and passes.
     */
    private static void requirePositiveTimeout(@Nullable Integer seconds, String where, String key) {
        if (seconds != null && seconds <= 0) {
            throw new IllegalArgumentException(where + " " + key + " must be positive (got " + seconds + ")");
        }
    }

    /**
     * One {@code [janus.router]} block: the routing knobs bound
     * from TOML — selection strategy, weights, latency EMA alpha, retry counts/backoff,
     * health thresholds and circuit-breaker knobs — handed to the {@code RouterFactory}
     * composition root, which wires them into the router (the six
     * strategies, {@code RetryPolicy}, {@code PassiveUpstreamHealth},
     * {@code CircuitBreaker}). Bound as a nested configuration-properties record: the
     * {@code @ConfigurationProperties("router")} prefix is relative to the parent
     * {@code @ConfigurationProperties("janus")}, so the TOML keys live under
     * {@code [janus.router]} (pinned by {@code ModelListBindingTest}).
     *
     * <p><b>TOML spelling (kebab-case is the documented convention; on the
     * rule).</b> The documented keys are {@code strategy}, {@code latency-alpha},
     * {@code weights} (an inline table), {@code max-retries}, {@code backoff-base-ms},
     * {@code backoff-max-ms}, {@code jitter}, {@code allowed-fails},
     * {@code cooldown-time}, {@code breaker-failure-threshold},
     * {@code breaker-window-seconds}, {@code breaker-cooldown-seconds}. Micronaut 5.1
     * normalizes {@code _} and {@code -} in plain-section scalar keys, so
     * {@code latency_alpha} binds identically to {@code latency-alpha} (pinned by
     * {@code ModelListBindingTest.underscoreSpellingsBindForSectionKeys}) — the same
     * "underscores silently null" rule applies to {@code [[janus.model-list]]}
     * array-of-tables element keys only, whose maps keep their raw spellings. The kebab
     * spelling stays the documented one for consistency with the model-list rule and
     * {@code docs/routing.md}. (A key must still spell exactly its component's
     * kebab-case: {@code cooldown-time} binds {@code cooldownTime};
     * {@code cooldown-time-seconds} would not.)
     *
     * <p><b>Units (the #1 operator footgun — key names carry the suffix on purpose).</b>
     * {@code backoff-base-ms}/{@code backoff-max-ms} are <b>millis</b> (as in {@code
     * RetryPolicy}); {@code cooldown-time} and the {@code breaker-*} keys are
     * <b>seconds</b> (LiteLLM/the reference vocabulary) — the factory converts cooldown seconds
     * to millis at {@code PassiveUpstreamHealth} construction
     * ({@code cooldownMillis = seconds * 1000}, pinned by test).
     *
     * <p><b>Defaults live in the factory, not here.</b> All components are nullable —
     * absent keys bind null — and the defaults are applied by the factory
     * ({@code LoadBalancerFactory.resolve}, then the {@code RouterFactory} wiring), not
     * in this record: binding stays mechanical, and validation lives at construction, in
     * the strategy/health/breaker constructors ({@code LatencyBasedLoadBalancer} rejects
     * {@code alpha ∉ (0, 1]}, {@code RetryPolicy} rejects out-of-range retries/backoff,
     * {@code PassiveUpstreamHealth} rejects {@code allowedFails < 1},
     * {@code CircuitBreakerConfig} rejects zero/negative knobs) — a bad value fails at
     * boot, not on first request. {@link #DEFAULTS} documents the defaults; an absent
     * {@code [janus.router]} section leaves the {@code router} bean with all components
     * null except {@code weights} (empty map — never null; {@code @Nullable} components,
     * Micronaut 5.1 nested-record binding), which the
     * factory resolves to {@code DEFAULTS} — a valid boot state (default
     * wiring: retryable failures are retried with backoff even on single-backend
     * configs; {@code max-retries = 0} restores the one-attempt behavior).
     *
     * @param strategy selection strategy config key: {@code "round-robin"} |
     * {@code "least-inflight"} | {@code "latency-based"} | {@code "cost-based"} |
     * {@code "weighted"} | {@code "session-affinity"} (default {@code "round-robin"})
     * @param latencyAlpha EMA smoothing factor for {@code "latency-based"}, in
     * {@code (0, 1]} (default {@code 0.3})
     * @param weights inline-table weights for {@code "weighted"}, keyed by backend
     * (provider) name (default empty map)
     * @param maxRetries retries AFTER the first attempt — at most {@code maxRetries + 1}
     * tries total (default {@code 2})
     * @param backoffBaseMs exponential backoff base, <b>millis</b> (default {@code 200})
     * @param backoffMaxMs backoff cap, <b>millis</b> (default {@code 2000})
     * @param jitter fractional jitter of the capped delay, {@code [0, 1]} (default
     * {@code 0.2})
     * @param allowedFails consecutive failures before an upstream flips unhealthy
     * (default {@code 3})
     * @param cooldownTime cooldown probation, <b>seconds</b> (default {@code 10}) —
     * converted to millis at {@code PassiveUpstreamHealth} construction
     * @param breakerFailureThreshold failures in the rolling window before OPEN (default
     * {@code 5})
     * @param breakerWindowSeconds rolling failure window, <b>seconds</b> (default
     * {@code 60})
     * @param breakerCooldownSeconds OPEN → half-open cooldown, <b>seconds</b> (default
     * {@code 30})
     */
    @ConfigurationProperties("router")
    public record RouterConfig(
            @Nullable String strategy,
            @Nullable Double latencyAlpha,
            @Nullable Map<String, Integer> weights,
            @Nullable Integer maxRetries,
            @Nullable Long backoffBaseMs,
            @Nullable Long backoffMaxMs,
            @Nullable Double jitter,
            @Nullable Integer allowedFails,
            @Nullable Integer cooldownTime,
            @Nullable Integer breakerFailureThreshold,
            @Nullable Integer breakerWindowSeconds,
            @Nullable Integer breakerCooldownSeconds) {

        /** Documented defaults (the predecessor gateway / vocabulary); see the record javadoc. */
        public static final RouterConfig DEFAULTS =
                new RouterConfig("round-robin", 0.3, Map.of(), 2, 200L, 2000L, 0.2, 3, 10, 5, 60, 30);
    }

    /**
     * The {@code [janus.privacy]} section: controls whether conversation content
     * (prompts, completions, tool arguments and results, image payloads) may appear
     * in log output. Absent section ⇒ content logging OFF (the default and the safe
     * posture — the string form of the canonical records prints structure only).
     * Metrics, call records, token counts and costs are always logged and are not
     * affected by this switch.
     */
    @ConfigurationProperties("privacy")
    public record PrivacyConfig(@Nullable Boolean logContent) {

        /** True only when the operator explicitly set {@code log-content = true}. */
        public boolean effectiveLogContent() {
            return Boolean.TRUE.equals(logContent);
        }
    }

    /**
     * One {@code [janus.keys]} block: the governance
     * switch. {@code master-key-env} names the environment variable holding the admin
     * <b>master key</b> — an env-reference, <b>never the value in
     * TOML</b>, never logged, never in exception messages. The master key
     * authenticates the admin API only ({@code /key/generate|delete|list}); model
     * routes require Janus-issued virtual keys.
     *
     * <p><b>Auth posture (hardened): auth is ON when a master key resolves; auth-off
     * is an explicit declaration.</b> With {@code auth = "on"} (the default) and no
     * resolvable key the boot <b>fails fast</b> — a deployment that forgets the env
     * var must not silently run unauthenticated (the admin API mints keys). Auth-off
     * remains available for development and benchmarks via {@code [janus.keys]
     * auth = "off"} — an explicit line in a config the operator controls, loudly
     * logged at boot. The shipped compose memory profile is auth-on (admin smoke).
     *
     * <p><b>Binding.</b> Bound as a nested configuration-properties record: the
     * {@code @ConfigurationProperties("keys")} prefix is relative to the parent
     * {@code @ConfigurationProperties("janus")}, so the TOML key lives under
     * {@code [janus.keys]} as {@code master-key-env}. Micronaut normalizes
     * {@code _} and {@code -} in plain-section scalar keys , so
     * {@code master_key_env} binds identically — kebab stays the documented spelling
     * (pinned by {@code ModelListBindingTest}). An absent section leaves the bean with
     * all {@code @Nullable} components null ( {@code [janus.router]} precedent);
     * absent {@code master-key-env} leaves the component null — both valid boot states
     * resolved by {@link #effectiveMasterKeyEnv}.
     *
     * @param masterKeyEnv env var name holding the admin master key; null → {@link
     * #DEFAULT_MASTER_KEY_ENV}
     */
    @ConfigurationProperties("keys")
    public record KeysConfig(
            @Nullable String masterKeyEnv, @Nullable String auth) {

        /** The default env var name when {@code [janus.keys]} / {@code master-key-env} is absent. */
        public static final String DEFAULT_MASTER_KEY_ENV = "JANUS_MASTER_KEY";

        /** {@code auth = "on"} (default): auth required — a resolvable key enables it,
         * a missing one fails the boot. */
        public static final String AUTH_ON = "on";

        /** {@code auth = "off"}: explicit auth-off (dev/bench posture) —
         * admin and model routes run unauthenticated, loudly logged. */
        public static final String AUTH_OFF = "off";

        /**
         * The env var name to resolve: {@code master-key-env} when present and
         * non-blank, else {@link #DEFAULT_MASTER_KEY_ENV} (blank counts as absent — a
         * blank env name can never resolve, so falling back keeps auth-off semantics
         * predictable instead of erroring at boot).
         */
        public String effectiveMasterKeyEnv() {
            return masterKeyEnv == null || masterKeyEnv.isBlank() ? DEFAULT_MASTER_KEY_ENV : masterKeyEnv;
        }

        /**
         * The validated {@code auth} mode: {@link #AUTH_ON} when absent (the default),
         * {@link #AUTH_OFF} when explicitly declared. Any other spelling fails fast at
         * boot — an auth typo must never be interpreted as either posture silently.
         */
        public String effectiveAuth() {
            if (auth == null || auth.isBlank() || AUTH_ON.equals(auth)) {
                return AUTH_ON;
            }
            if (AUTH_OFF.equals(auth)) {
                return AUTH_OFF;
            }
            throw new IllegalStateException(
                    "[janus.keys] auth must be \"" + AUTH_ON + "\" | \"" + AUTH_OFF + "\" (got \"" + auth + "\")");
        }
    }

    /**
     * One {@code [janus.pricing]} block: the per-model
     * price table — {@code [[janus.pricing.models]]} rows keyed by <b>model alias</b>
     * (the router key the client sends, consistent with scope-by-alias), built by
     * the {@code GovernanceFactory} into the {@code PriceTable} the {@code Governance}
     * collaborator prices every request against. Bound as a nested
     * configuration-properties record: the {@code @ConfigurationProperties("pricing")}
     * prefix is relative to the parent {@code @ConfigurationProperties("janus")}, so
     * the TOML keys live under {@code [janus.pricing]} (pinned by
     * {@code ModelListBindingTest}).
     *
     * <p><b>TOML spelling (kebab-case only inside {@code [[...]]}).</b>
     * The {@code [[janus.pricing.models]]} element keys keep their raw spellings
     * ({@code input-per-1k}, {@code output-per-1k}, {@code cache-read-per-1k},
     * {@code cache-creation-per-1k}, {@code default-max-tokens}); underscore spellings
     * ({@code input_per_1k}) silently bind null — documented in {@code config.toml}
     * exactly like the model-list note. {@code name} is the model alias; absent
     * optional components bind null (the factory substitutes 0-rate/absent fields).
     * An absent {@code [janus.pricing]} section leaves the whole {@code pricing} bean
     * null ⇒ the factory builds an empty table (every model meters at $0 until rows
     * are added — valid boot, logged once per unknown model at request time).
     *
     * @param models the price rows, in TOML order; null when the section is absent
     * @param requirePriced when true, a configured alias with no price row fails
     *     boot (and a miss at request time is 400 instead of metering at $0)
     */
    @ConfigurationProperties("pricing")
    public record PricingConfig(
            @Nullable List<PricingModel> models, @Nullable Boolean requirePriced) {

        public PricingConfig(@Nullable List<PricingModel> models) {
            this(models, null);
        }

        /**
         * One {@code [[janus.pricing.models]]} row: USD-per-1K-token rates for a model
         * alias (the {@code PriceTable} row the {@code Governance} looks up per
         * request). {@code default-max-tokens} is the per-model reserve factor for
         * budget/TPM estimates when the request omits {@code max_tokens} (the reference
         *; the gateway falls back to 4096 when a row has none).
         *
         * <p><b>Component spelling.</b> Micronaut hyphenates
         * record components into the property paths it binds: a digit+uppercase
         * boundary splits, so {@code inputPer1K} would require the TOML key
         * {@code input-per-1-k}. The components therefore spell the digit lowercase
         * ({@code inputPer1k} → {@code input-per-1k}) — the kebab keys in
         * {@code config.toml} and {@code [[janus.pricing.models]]} bind directly
         * (pinned by {@code ModelListBindingTest}).
         *
         * @param name the model alias (must be non-blank — a row without a name is
         * always a config error)
         * @param inputPer1k USD per 1K prompt tokens; null ⇒ 0
         * @param outputPer1k USD per 1K completion tokens; null ⇒ 0
         * @param cacheReadPer1k USD per 1K cache-read input tokens; null ⇒ 0
         * @param cacheCreationPer1k USD per 1K cache-creation input tokens; null ⇒ 0
         * @param defaultMaxTokens reserve factor; null ⇒ 0 ⇒ the gateway's 4096 fallback
         * @param webSearchPer1k USD per 1K hosted searches; null ⇒ 0
         * @param longContextThreshold prompt-token floor for the long-context tier; null/0 = off
         * @param longInputPer1k long-tier input rate; null ⇒ 0
         * @param longOutputPer1k long-tier output rate; null ⇒ 0
         * @param longCacheReadPer1k long-tier cache-read rate; null ⇒ 0
         * @param longCacheCreationPer1k long-tier cache-creation rate; null ⇒ 0
         */
        public record PricingModel(
                String name,
                @Nullable Double inputPer1k,
                @Nullable Double outputPer1k,
                @Nullable Double cacheReadPer1k,
                @Nullable Double cacheCreationPer1k,
                @Nullable Integer defaultMaxTokens,
                @Nullable Double webSearchPer1k,
                @Nullable Integer longContextThreshold,
                @Nullable Double longInputPer1k,
                @Nullable Double longOutputPer1k,
                @Nullable Double longCacheReadPer1k,
                @Nullable Double longCacheCreationPer1k) {

            /** Pre-long-context / pre-search arity used by existing tests. */
            public PricingModel(
                    String name,
                    @Nullable Double inputPer1k,
                    @Nullable Double outputPer1k,
                    @Nullable Double cacheReadPer1k,
                    @Nullable Double cacheCreationPer1k,
                    @Nullable Integer defaultMaxTokens) {
                this(
                        name,
                        inputPer1k,
                        outputPer1k,
                        cacheReadPer1k,
                        cacheCreationPer1k,
                        defaultMaxTokens,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
            }

            public PricingModel {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("pricing model name must be non-blank");
                }
            }
        }
    }

    /**
     * One {@code [janus.limits]} block: the
     * governance knobs the {@code GovernanceFactory} applies — the rate-limiter
     * variant, the budget soft-cap fraction, the notifier webhook URL and the spend
     * ledger's ring retention. Bound as a nested configuration-properties record: the
     * {@code @ConfigurationProperties("limits")} prefix is relative to the parent
     * {@code @ConfigurationProperties("janus")}, so the TOML keys live under
     * {@code [janus.limits]} (pinned by {@code ModelListBindingTest}).
     *
     * <p><b>Defaults live in the factories, not here ( {@code [janus.router]}
     * precedent).</b> All components are nullable — absent keys bind null — and the
     * {@code GovernanceFactory} applies the soft-cap/notifier defaults (soft cap 0.8,
     * logger-only notifier) while {@code CallStoreFactory} applies the window default
     * (fixed) and the {@code ledgerRetention} default (1000-entry spend-ledger ring —
     * wired into both store backends). An absent
     * {@code [janus.limits]} section leaves the whole {@code limits} bean null ⇒ the
     * factory's defaults — and since enforcement is key-scoped (a key's null
     * {@code rpm}/{@code tpm}/{@code budgetUsd} mean "no cap"), minimal configs
     * behave byte-identically (noop-level governance).
     *
     * <p><b>Spelling.</b> Plain-section scalar keys normalize {@code _} and
     * {@code -} : {@code soft_cap_fraction} binds identically to
     * {@code soft-cap-fraction}; kebab stays the documented convention. An invalid
     * {@code window} value (not {@code "fixed"} or {@code "sliding"}) is rejected at
     * binding time (the {@code JanusConfig} compact constructor), and a non-positive
     * {@code ledger-retention} is rejected in this record's own compact constructor
     * (the {@code [janus.store]} {@code retention}/{@code max-pool-size} precedent) —
     * fail-fast at boot, discipline: a typo'd ring size is never silently
     * swapped for the 1000 default.
     *
     * @param window rate-limiter variant: {@code "fixed"} (default) |
     * {@code "sliding"}
     * @param softCapFraction budget soft tier as a fraction of the hard cap, in
     * {@code [0, 1]} (default {@code 0.8})
     * @param notifierWebhookUrl URL to POST {@code :budget_exceeded} events to; absent
     * ⇒ logger-only notifier
     * @param ledgerRetention per-key spend-ledger ring-buffer size, positive when
     * present (default {@code 1000}; wired into both store backends by {@code
     * CallStoreFactory})
     */
    @ConfigurationProperties("limits")
    public record LimitsConfig(
            @Nullable String window,
            @Nullable Double softCapFraction,
            @Nullable String notifierWebhookUrl,
            @Nullable Integer ledgerRetention) {

        /** Documented defaults (the predecessor gateway / vocabulary); applied by the factory. */
        public static final LimitsConfig DEFAULTS = new LimitsConfig("fixed", 0.8, null, 1000);

        /**
         * A {@code soft-cap-fraction} outside {@code [0, 1]} and a non-positive
         * {@code ledger-retention} are rejected here, at binding time, so the boot
         * error names the offending {@code [janus.limits]} key (the {@code window}/
         * {@code [janus.store] retention} precedent). Before the ledger-retention
         * guard, {@code CallStoreFactory} silently substituted the 1000 default for a
         * typo'd {@code 0}/negative value — never again. The {@code Governance}
         * constructor's own {@code [0, 1]} guard and the store constructors'
         * positivity guards stay as defense-in-depth; out-of-range values are always
         * config errors, null values (absent keys) are the factory-default cases.
         */
        public LimitsConfig {
            if (softCapFraction != null && (softCapFraction < 0.0 || softCapFraction > 1.0)) {
                throw new IllegalArgumentException(
                        "[janus.limits] soft-cap-fraction must be in [0, 1] (got " + softCapFraction + ")");
            }
            if (ledgerRetention != null && ledgerRetention <= 0) {
                throw new IllegalArgumentException(
                        "[janus.limits] ledger-retention must be positive (got " + ledgerRetention + ")");
            }
        }
    }

    /**
     * One {@code [janus.store]} block: the store backend
     * switch and the call-ring retention the {@code CallStoreFactory} wires into the
     * {@link io.amscotti.janus.store.CallStore} bean — {@code "memory"} (the default;
     * Default behavior byte-identical, zero extra config) or {@code "postgres"} (the
     * JDBC store). Bound as a nested configuration-properties record: the
     * {@code @ConfigurationProperties("store")} prefix is relative to the parent
     * {@code @ConfigurationProperties("janus")}, so the TOML keys live under
     * {@code [janus.store]} (pinned by {@code ModelListBindingTest}).
     *
     * <p><b>Env-reference pattern — never the URL in TOML.</b> {@code jdbc-url-env}
     * names the environment variable holding the <b>full JDBC URL</b>
     * ({@code jdbc:postgresql://...}; credentials may be embedded in it); the optional
     * {@code user-env}/{@code password-env} refs override URL-embedded credentials.
     * The factory resolves the env vars at boot; an unresolvable env var or an
     * unreachable database fails the boot fast with a clear error naming the <b>env
     * var</b> (never the URL — credentials may be embedded). {@code retention} is the
     * per-key call-ring retention for <b>both</b> impls ('s deferred TOML wiring;
     * default {@code InMemoryCallStore.DEFAULT_RETENTION = 1000}).
     *
     * <p><b>Fail-fast validation lives in this record.</b> An unknown
     * {@code type} (not {@code "memory"}|{@code "postgres"}) and {@code type =
     * "postgres"} without {@code jdbc-url-env} are rejected at binding time — a
     * misconfigured store section refuses the node to start. An absent section leaves
     * all components null ⇒ the factory's defaults (memory, retention 1000, pool 10) —
     * a valid boot state. The documented defaults live in the factory, not here (
     * {@code [janus.router]} precedent), so the record lists no defaults besides the
     * type names and the retention constant.
     *
     * @param type backend: {@code "memory"} (default) | {@code "postgres"}; null when
     * absent
     * @param jdbcUrlEnv env var name holding the full JDBC URL; required for
     * {@code "postgres"}
     * @param userEnv optional env var name holding the pool username (overrides
     * URL-embedded credentials)
     * @param passwordEnv optional env var name holding the pool password
     * @param maxPoolSize HikariCP pool size (default 10)
     * @param retention per-key call-ring retention for both impls (default
     * {@code InMemoryCallStore.DEFAULT_RETENTION})
     */
    @ConfigurationProperties("store")
    public record StoreConfig(
            @Nullable String type,
            @Nullable String jdbcUrlEnv,
            @Nullable String userEnv,
            @Nullable String passwordEnv,
            @Nullable Integer maxPoolSize,
            @Nullable Integer retention) {

        /** The documented backend variants (the fail-fast error lists exactly these). */
        public static final String TYPE_MEMORY = "memory";

        public static final String TYPE_POSTGRES = "postgres";

        /** Documented defaults (applied by the factory, not here). */
        public static final StoreConfig DEFAULTS = new StoreConfig(TYPE_MEMORY, null, null, null, 10, 1000);

        public StoreConfig {
            // All-null = absent section = valid boot state (factory applies DEFAULTS).
            if (type != null && !type.isBlank() && !TYPE_MEMORY.equals(type) && !TYPE_POSTGRES.equals(type)) {
                throw new IllegalArgumentException("[janus.store] type must be \"" + TYPE_MEMORY + "\" or \""
                        + TYPE_POSTGRES + "\" (got \"" + type + "\")");
            }
            if (TYPE_POSTGRES.equals(type) && (jdbcUrlEnv == null || jdbcUrlEnv.isBlank())) {
                throw new IllegalArgumentException("[janus.store] type = \"postgres\" requires jdbc-url-env"
                        + " (the env var name holding the JDBC URL — never the URL in TOML)");
            }
            if (retention != null && retention <= 0) {
                throw new IllegalArgumentException("[janus.store] retention must be positive (got " + retention + ")");
            }
            if (maxPoolSize != null && maxPoolSize <= 0) {
                throw new IllegalArgumentException(
                        "[janus.store] max-pool-size must be positive (got " + maxPoolSize + ")");
            }
        }
    }

    /**
     * One {@code [janus.timeouts]} block: the four upstream deadlines — TCP connect,
     * header arrival, non-stream body-read and the SSE stream-idle watchdog — the
     * {@code RouterFactory} resolves into the provider adapters' timeout-aware
     * constructors and the face controllers' publishers. Bound as a nested
     * configuration-properties record: the {@code @ConfigurationProperties("timeouts")}
     * prefix is relative to the parent {@code @ConfigurationProperties("janus")}, so
     * the TOML keys live under {@code [janus.timeouts]} (pinned by
     * {@code ModelListBindingTest}).
     *
     * <p><b>Units (the {@code -seconds} suffix carries the unit on purpose — the
     * documented convention that avoids the millis/seconds footgun; the
     * {@code [janus.router]} {@code backoff-base-ms}/{@code backoff-max-ms} keys are
     * the documented millis exception).</b> All four keys are <b>seconds</b>.
     *
     * <p><b>Defaults live in {@link #DEFAULTS}, resolved by the factory.</b> All
     * components are nullable — an absent section leaves the bean alive with null
     * components (the {@code [janus.store]} precedent) and a null component means
     * "keep the code default" — per-key granularity, never all-or-nothing
     * ({@code RouterFactory.resolve} fills nulls from {@code DEFAULTS} the way
     * {@code LoadBalancerFactory.resolve} does for {@code [janus.router]}).
     * {@code DEFAULTS} carries the resolved values {@code (10, 60, 300, 60)} —
     * exactly the constants the adapters and SSE publishers pin
     * ({@code TimeoutContractTest} ties the two together), so an absent section
     * boots with the documented deadlines byte-identically.
     *
     * <p><b>The 64 MiB non-stream response-body cap
     * ({@code HttpSupport.MAX_RESPONSE_BODY_BYTES}) is NOT a key here</b> — it stays
     * a code constant (an OOM defense, not an operator deadline); documented in
     * {@code config.toml} so operators don't hunt for a fifth key.
     *
     * @param connectTimeoutSeconds TCP connect deadline to an upstream (the adapters'
     * JDK {@code HttpClient}); default {@code 10}
     * @param headerTimeoutSeconds time to first response header, non-stream and
     * stream alike (the JDK client's per-request timeout); default {@code 60}
     * @param bodyReadTimeoutSeconds non-stream body read after headers arrive
     * (stall → {@code timeout}); default {@code 300}
     * @param streamIdleTimeoutSeconds SSE idle watchdog on all three faces (chat /
     * messages / responses); default {@code 60}
     */
    @ConfigurationProperties("timeouts")
    public record TimeoutsConfig(
            @Nullable Integer connectTimeoutSeconds,
            @Nullable Integer headerTimeoutSeconds,
            @Nullable Integer bodyReadTimeoutSeconds,
            @Nullable Integer streamIdleTimeoutSeconds) {

        /**
         * Documented defaults — the exact values the adapters and SSE publishers pin
         * as code constants (the {@code LimitsConfig.DEFAULTS} real-value precedent:
         * an all-null DEFAULTS would invite "which null means what" drift).
         */
        public static final TimeoutsConfig DEFAULTS = new TimeoutsConfig(10, 60, 300, 60);

        /**
         * A non-positive value on any key (a typo'd {@code 0} or a negative number)
         * is rejected here, at binding time, so the boot error names the offending
         * {@code [janus.timeouts]} key (the {@code [janus.store]} retention /
         * {@code [janus.limits]} ledger-retention precedent) — a typo'd deadline is
         * never silently swapped for the default. Null (absent key) is the
         * factory-default case; the positivity is re-checked by the adapters'
         * timeout-aware constructors as defense-in-depth ({@code Duration}s there).
         */
        public TimeoutsConfig {
            if (connectTimeoutSeconds != null && connectTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("[janus.timeouts] connect-timeout-seconds must be positive (got "
                        + connectTimeoutSeconds + ")");
            }
            if (headerTimeoutSeconds != null && headerTimeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "[janus.timeouts] header-timeout-seconds must be positive (got " + headerTimeoutSeconds + ")");
            }
            if (bodyReadTimeoutSeconds != null && bodyReadTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("[janus.timeouts] body-read-timeout-seconds must be positive (got "
                        + bodyReadTimeoutSeconds + ")");
            }
            if (streamIdleTimeoutSeconds != null && streamIdleTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("[janus.timeouts] stream-idle-timeout-seconds must be positive (got "
                        + streamIdleTimeoutSeconds + ")");
            }
        }
    }
}
