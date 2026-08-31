package io.amscotti.janus.gateway;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.provider.AnthropicAdapter;
import io.amscotti.janus.provider.DeepSeekAdapter;
import io.amscotti.janus.provider.OpenAiCompatibleAdapter;
import io.amscotti.janus.provider.ProviderAdapter;
import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.CircuitBreaker;
import io.amscotti.janus.router.CircuitBreakerConfig;
import io.amscotti.janus.router.PassiveUpstreamHealth;
import io.amscotti.janus.router.ResilienceConfig;
import io.amscotti.janus.router.RetryPolicy;
import io.amscotti.janus.router.Router;
import io.amscotti.janus.store.PriceTable;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Composition root producing the {@link Router} singleton from the TOML config (,
 * fully config-driven: aliases,
 * providers, base URLs, credentials, strategy, weights, retry/health/breaker knobs all
 * come from the operator's config file + environment, nothing is hardcoded here. This
 * factory is the only place environment variables are read and {@link ProviderAdapter}s
 * are constructed ("the SPI never reads env/config"), and its direct {@code
 * DeepSeekAdapter} reference keeps the ServiceLoader registration reachable in
 * native-image (the factory's ServiceLoader iteration in {@link ModelListFactory}
 * keeps it rooted).
 *
 * <p>Wiring: {@link ModelListFactory#buildBackendLists} validates the entries
 * (unknown providers and in-group duplicate provider names fail fast at boot; duplicate
 * aliases group into ordered candidate lists — the multi-provider shape),
 * resolves each credential from {@code apiKeyEnv} (blank/unset → {@code ""} — the
 * adapter omits {@code Authorization} → a real upstream returns 401, which exercises the
 * auth error-envelope path; a boot warning is logged so the omission is not silent),
 * wraps each adapter in {@link ProviderAdapterChatBackend}, and
 * preserves config order so {@code Router.models} / {@code GET /v1/models} are
 * deterministic (multi-backend aliases list once with the first candidate's name —
 * {@code Router.route} semantics). The {@code [janus.router]} section then selects the
 * strategy ({@link LoadBalancerFactory} — the six-strategy switch that roots all
 * classes in native-image; unknown strategy fails fast listing the six names), and the
 * resolved retry/health/breaker knobs build the / resilience bundle: {@link
 * RetryPolicy} (retries after the first attempt, exponential backoff + jitter),
 * {@link PassiveUpstreamHealth} ({@code cooldown-time} seconds → millis here — the #1
 * units footgun), and {@link CircuitBreaker} (defaults 5/60s/30s), with {@link
 * ProviderRetryClassifier} bridging real {@code ProviderException} retryability into the
 * router (the documented hand-off). The result is the 4-arg
 * {@link Router#resilient(Map, LoadBalancer, ResilienceConfig, CircuitBreaker)} path —
 * default wiring: retryable failures (429/5xx/network/timeout) are
 * retried with backoff even on single-backend configs; {@code max-retries = 0} restores
 * the one-attempt behavior.
 *
 * <p>An empty {@code model_list} is a valid boot state: empty router, {@code /v1/models}
 * → {@code []}, any chat → 404 {@code model_not_found} ( envelope).
 *
 * <p>Hand-off: golden fixtures + SDK-facing tests and smoke gates cover the
 * unmodified OpenAI SDK → DeepSeek through Janus, {@code GET /v1/models} listing)
 * consume this config-driven construction; runs the real {@code janus-cli --config
 * config.toml} boot. {@link #createProvider} grew
 * {@code OpenAiCompatibleAdapter} / {@code AnthropicAdapter}.
 */
@Factory
class RouterFactory {

    private static final Logger LOG = System.getLogger("io.amscotti.janus.gateway.RouterFactory");

    private final JanusConfig config;

    // The resilience bundle (UpstreamHealth + CircuitBreaker
    // + the distinct provider backends) is retained as its own @Singleton bean
    // (RouterResilience) that router populates — MetricsFactory hard-depends on it
    // for the health/breaker gauge suppliers, so no bean-ordering hazard remains (the
    // The gauges used to be read off RouterFactory's private fields,
    // populated only when router ran). No janus-router change; the instances are the
    // same ones the router consumes.
    private final RouterResilience resilience;

    /** Same price table as governance — cost-based LB prices actual usage in micro-USD. */
    private final PriceTable priceTable;

    /** The shared {@code Clock} bean (produced by {@code CallStoreFactory}) —
     * the health/breaker cooldowns and rolling windows must run on the same clock as the
     * store, ledger, rate limiter and governance (the no-real-time discipline), not a
     * private {@code Clock.systemUTC}. */
    private final Clock clock;

    RouterFactory(JanusConfig config, RouterResilience resilience, PriceTable priceTable, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.priceTable = Objects.requireNonNull(priceTable, "priceTable");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Singleton
    Router router() {
        JanusConfig.TimeoutsConfig timeouts = resolve(config.timeouts());
        Map<String, List<ChatBackend>> routes = ModelListFactory.buildBackendLists(
                config.modelList(),
                config.providers() == null ? Map.of() : config.providers(),
                System::getenv,
                timeouts,
                (providerName, constructorKey, baseUrl, apiKey, ctorTimeouts) ->
                        createProvider(providerName, constructorKey, baseUrl, apiKey, ctorTimeouts));
        JanusConfig.RouterConfig r = LoadBalancerFactory.resolve(config.router());
        if ("weighted".equals(r.strategy())) {
            LoadBalancerFactory.warnAboutWeights(routes, r.weights());
        }
        if ("cost-based".equals(r.strategy())) {
            // With no pricing rows the cost-based strategy silently pins to the
            // first candidate (0-vs-0 ties) — warn at boot exactly like weighted does.
            LoadBalancerFactory.warnAboutCosts(priceTable);
        }
        if ("session-affinity".equals(r.strategy())) {
            // weights are a weighted-strategy knob; under affinity the HRW hash decides
            // every pick — configured weights are ignored (warn, the same boot posture).
            LoadBalancerFactory.warnAboutIgnoredWeights(r.weights());
        }
        // The (r, clock) overloads — the resilience bundle shares the injected
        // Clock bean (previously a hardcoded system UTC, a seam inconsistency with the
        // rest of the wiring). Production behavior is unchanged (both are UTC); the bean
        // is what makes the bundle clock-pinnable in a production-DI context.
        ResilienceConfig resilienceConfig = toResilience(r, clock);
        CircuitBreaker breaker = toBreaker(r, clock);
        // Hand the router's own instances to the resilience bean so MetricsFactory can
        // register the gauges from them (state suppliers re-read live state on scrape).
        resilience.populate(resilienceConfig.health(), breaker, distinctBackends(routes));
        return Router.resilient(routes, LoadBalancerFactory.create(r, priceTable), resilienceConfig, breaker);
    }

    /** Identity-dedupe (same keying rule as the router strategies) preserving config order. */
    private static List<ChatBackend> distinctBackends(Map<String, List<ChatBackend>> routes) {
        Set<ChatBackend> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ChatBackend> distinct = new ArrayList<>();
        for (List<ChatBackend> candidates : routes.values()) {
            for (ChatBackend backend : candidates) {
                if (seen.add(backend)) {
                    distinct.add(backend);
                }
            }
        }
        return distinct;
    }

    /**
     * mapper: {@code [router]} retry/health keys → {@link ResilienceConfig}.
     * {@code cooldownTime} is SECONDS (LiteLLM/the reference vocabulary) and is converted to
     * millis here ({@code PassiveUpstreamHealth} takes millis) — pinned by test. The
     * classifier is the {@link ProviderRetryClassifier} bridge (see its javadoc).
     */
    static ResilienceConfig toResilience(JanusConfig.RouterConfig r) {
        return new ResilienceConfig(
                new RetryPolicy(r.maxRetries(), r.backoffBaseMs(), r.backoffMaxMs(), r.jitter()),
                new PassiveUpstreamHealth(r.allowedFails(), r.cooldownTime() * 1000L),
                ProviderRetryClassifier.INSTANCE);
    }

    /** Test seam: {@link #toResilience} with an injectable clock (cooldown probation tests). */
    static ResilienceConfig toResilience(JanusConfig.RouterConfig r, Clock clock) {
        return new ResilienceConfig(
                new RetryPolicy(r.maxRetries(), r.backoffBaseMs(), r.backoffMaxMs(), r.jitter()),
                new PassiveUpstreamHealth(r.allowedFails(), r.cooldownTime() * 1000L, clock),
                ProviderRetryClassifier.INSTANCE);
    }

    /**
     * mapper: {@code [router]} breaker keys → {@link CircuitBreakerConfig}.
     * All three keys are SECONDS (vocabulary); {@link Duration#ofSeconds}.
     */
    static CircuitBreakerConfig breakerConfig(JanusConfig.RouterConfig r) {
        return new CircuitBreakerConfig(
                r.breakerFailureThreshold(),
                Duration.ofSeconds(r.breakerWindowSeconds()),
                Duration.ofSeconds(r.breakerCooldownSeconds()));
    }

    /** mapper: resolved {@code [router]} breaker knobs → the per-upstream breaker. */
    static CircuitBreaker toBreaker(JanusConfig.RouterConfig r) {
        return CircuitBreaker.create(breakerConfig(r), Clock.systemUTC());
    }

    /** Test seam: {@link #toBreaker} with an injectable clock (state-machine tests). */
    static CircuitBreaker toBreaker(JanusConfig.RouterConfig r, Clock clock) {
        return CircuitBreaker.create(breakerConfig(r), clock);
    }

    /**
     * Resolve a (possibly absent/partial) {@code [janus.timeouts]} section to a
     * fully-defaulted one: {@code null} → {@link JanusConfig.TimeoutsConfig#DEFAULTS};
     * a present section fills only null components from the defaults (per-key
     * granularity, the {@code LoadBalancerFactory.resolve} pattern). Never returns
     * null. The values are the pinned code constants — {@code TimeoutContractTest}
     * ties {@code DEFAULTS} to the adapter/publisher constants, so an absent section
     * boots with byte-identical deadlines.
     */
    static JanusConfig.TimeoutsConfig resolve(JanusConfig.TimeoutsConfig t) {
        if (t == null) {
            return JanusConfig.TimeoutsConfig.DEFAULTS;
        }
        JanusConfig.TimeoutsConfig d = JanusConfig.TimeoutsConfig.DEFAULTS;
        return new JanusConfig.TimeoutsConfig(
                t.connectTimeoutSeconds() == null ? d.connectTimeoutSeconds() : t.connectTimeoutSeconds(),
                t.headerTimeoutSeconds() == null ? d.headerTimeoutSeconds() : t.headerTimeoutSeconds(),
                t.bodyReadTimeoutSeconds() == null ? d.bodyReadTimeoutSeconds() : t.bodyReadTimeoutSeconds(),
                t.streamIdleTimeoutSeconds() == null ? d.streamIdleTimeoutSeconds() : t.streamIdleTimeoutSeconds());
    }

    /**
     * The SSE idle-watchdog deadline for the face controllers: resolved from
     * {@code [janus.timeouts] stream-idle-timeout-seconds} (absent ⇒ the pinned 60 s
     * constant) — the global level of the per-dispatch resolution below.
     */
    static Duration streamIdleTimeout(JanusConfig.TimeoutsConfig timeouts) {
        return Duration.ofSeconds(resolve(timeouts).streamIdleTimeoutSeconds());
    }

    /**
     * The per-dispatch SSE idle-watchdog resolver : global default from
     * {@code [janus.timeouts]} + one override per resolved <b>backend name</b> whose
     * {@code [janus.providers.<name>]} block sets {@code stream-idle-timeout-seconds}
     * — the key the dispatch observer reports, not the raw block key: an
     * openai-compatible-family fallback backend dispatches under the entry's provider
     * name (same as the block key), but a fixed-name family
     * ({@code wire-format = "anthropic"}) fallback dispatches under the family's
     * fixed adapter name, so keying by the block key would leave the override dead
     * (the exact drift this re-keying fixes). Boot warnings name dead and ambiguous
     * overrides ({@link #providerStreamIdleOverrides}). Null sections are the
     * no-override boot — every dispatch resolves the pinned 60 s constant,
     * byte-identical to the single-value wiring.
     *
     * @param timeouts the raw {@code [janus.timeouts]} section; null (absent) ⇒
     * {@code DEFAULTS}
     * @param providers the raw {@code [janus.providers.<name>]} blocks; null (no
     * blocks) ⇒ no overrides
     * @param modelList the raw {@code [[janus.model-list]]} entries — the
     * provider-block-to-backend-name resolution input; null (absent list) ⇒ every
     * block override keys nothing (warned)
     */
    static StreamIdleTimeoutResolver streamIdleTimeoutResolver(
            @Nullable JanusConfig.TimeoutsConfig timeouts,
            @Nullable Map<String, JanusConfig.ProviderEntry> providers,
            @Nullable List<JanusConfig.ModelListEntry> modelList) {
        Duration global = streamIdleTimeout(timeouts);
        List<String> warnings = new ArrayList<>();
        Map<String, Duration> overrides = providerStreamIdleOverrides(providers, modelList, warnings);
        for (String warning : warnings) {
            LOG.log(Level.WARNING, warning);
        }
        return new StreamIdleTimeoutResolver(global, overrides);
    }

    /**
     * The stream-idle override map keyed by resolved backend name
     * ({@link ModelListFactory#resolvedBackendNameFor} — the name the dispatch
     * observer reports): for each {@code [janus.providers.<name>]} block that sets
     * {@code stream-idle-timeout-seconds}, the backends built from that block's
     * {@code [[janus.model-list]]} entries get the override under the name they
     * dispatch under. Three boot warnings keep the mapping honest (the
     * {@link #warnAboutEntryStreamIdleTimeouts} posture — dead or ambiguous config
     * must WARN, not stay silent):
     *
     * <ol>
     * <li>a block whose override keys no backend (no entry references the provider)
     * — dead config;</li>
     * <li>a block keying a resolved backend name other than its own key (the
     * fixed-name families: every {@code wire-format = "anthropic"} fallback
     * dispatches under the shared {@code "anthropic"} name, so the override reaches
     * sibling anthropic backends too);</li>
     * <li>two blocks colliding on one resolved backend name with different values —
     * the first in config order wins.</li>
     * </ol>
     *
     * @param warnings the sink for the boot-warning texts (the caller logs them;
     * tests pin them here — the {@code warnAboutEntryStreamIdleTimeouts} pattern)
     */
    static Map<String, Duration> providerStreamIdleOverrides(
            @Nullable Map<String, JanusConfig.ProviderEntry> providers,
            @Nullable List<JanusConfig.ModelListEntry> modelList,
            List<String> warnings) {
        Objects.requireNonNull(warnings, "warnings");
        if (providers == null || providers.isEmpty()) {
            return Map.of();
        }
        Set<String> knownProviders = ModelListFactory.knownProviders();
        Map<String, Duration> overrides = new LinkedHashMap<>();
        // resolved backend name → the block key that claimed it (collision reporting)
        Map<String, String> claimedBy = new LinkedHashMap<>();
        for (Map.Entry<String, JanusConfig.ProviderEntry> block : providers.entrySet()) {
            Integer seconds = block.getValue().streamIdleTimeoutSeconds();
            if (seconds == null) {
                continue;
            }
            String blockKey = block.getKey();
            String backendName =
                    ModelListFactory.resolvedBackendNameFor(blockKey, modelList, providers, knownProviders);
            if (backendName == null) {
                warnings.add("[janus.providers." + blockKey + "] sets stream-idle-timeout-seconds = " + seconds
                        + " but no [[janus.model-list]] entry references provider \"" + blockKey
                        + "\" — the override keys no backend and is ignored");
                continue;
            }
            if (!backendName.equals(blockKey)) {
                warnings.add("[janus.providers." + blockKey + "] stream-idle-timeout-seconds = " + seconds
                        + " keys the SSE watchdog by the resolved backend name \"" + backendName
                        + "\" (the fixed-name \"" + backendName + "\" wire-format family's adapter name), not the"
                        + " block key \"" + blockKey + "\" — the override applies to every \"" + backendName
                        + "\" backend");
            }
            String firstClaim = claimedBy.putIfAbsent(backendName, blockKey);
            if (firstClaim == null) {
                overrides.put(backendName, Duration.ofSeconds(seconds));
            } else if (!overrides.get(backendName).equals(Duration.ofSeconds(seconds))) {
                warnings.add("[janus.providers." + blockKey + "] stream-idle-timeout-seconds = " + seconds
                        + " collides with [janus.providers." + firstClaim + "] (both resolve to backend name \""
                        + backendName + "\") — the first block in config order wins with "
                        + overrides.get(backendName).toSeconds() + " s");
            }
        }
        return Collections.unmodifiableMap(overrides);
    }

    /**
     * The per-dispatch stream-idle resolver bean (the {@code Clock} producer
     * pattern — a raw constructor param Micronaut can't supply on its own). The
     * three face controllers resolve from it after {@code router.stream(...)}
     * returns, keyed by the dispatched backend name. Warns at boot when a
     * {@code [[janus.model-list]]} entry sets {@code stream-idle-timeout-seconds}
     * — dead config for the watchdog ({@link #warnAboutEntryStreamIdleTimeouts}).
     */
    @Singleton
    StreamIdleTimeoutResolver streamIdleTimeouts() {
        warnAboutEntryStreamIdleTimeouts(config.modelList());
        return streamIdleTimeoutResolver(config.timeouts(), config.providers(), config.modelList());
    }

    /**
     * Entry-level stream-idle boot warning: an entry's {@code
     * stream-idle-timeout-seconds} is legal but dead for the SSE watchdog — the
     * watchdog resolves per dispatch by the serving provider, and provider names
     * are not unique across aliases, so it keys on {@code [janus.providers.<name>]}
     * blocks only; the entry value's sole effect is merging into the per-backend
     * adapter config, which the watchdog never reads. Mirrors {@link
     * LoadBalancerFactory#warnAboutWeights}: logs each warning and returns the
     * texts so tests can pin them. Quiet when no entry sets the key.
     */
    static List<String> warnAboutEntryStreamIdleTimeouts(@Nullable List<JanusConfig.ModelListEntry> modelList) {
        if (modelList == null || modelList.isEmpty()) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        for (JanusConfig.ModelListEntry entry : modelList) {
            if (entry.streamIdleTimeoutSeconds() != null) {
                warnings.add("model-list entry \"" + entry.name() + "\" sets stream-idle-timeout-seconds = "
                        + entry.streamIdleTimeoutSeconds() + " — the key is ignored here (stream-idle"
                        + " resolves by provider and is honored only on [janus.providers.<name>] blocks;"
                        + " set it on [janus.providers." + entry.provider() + "] instead)");
            }
        }
        for (String warning : warnings) {
            LOG.log(Level.WARNING, warning);
        }
        return Collections.unmodifiableList(warnings);
    }

    /**
     * Explicit provider-constructor registry — no runtime reflection (native-image
     * discipline, AGENTS.md), keyed by {@code ProviderAdapter.name} (or, for the
     * wire-format fallback, the {@code [janus.providers.<name>]} block's wire-format
     * family — {@link ModelListFactory} resolves both to this switch's keys before
     * calling it). {@code baseUrl} defaults to the adapter's default when the TOML entry
     * omits it. Since the resolved {@code [janus.timeouts]} deadlines thread into
     * the adapters' public timeout-aware constructors (a default section reproduces
     * the code constants exactly); since the deadlines are the per-backend
     * merged resolution {@code ModelListFactory.backendFor} computes (entry &gt;
     * provider block &gt; global).
     *
     * @param providerName the display name the backend is known by — the entry's
     * <b>provider</b> name, preserved through the fallback so two distinct
     * fallback providers under one alias stay distinguishable). Only the
     * generic {@link OpenAiCompatibleAdapter} family consumes it (its name is a
     * constructor parameter); {@code DeepSeekAdapter}/{@code AnthropicAdapter} have
     * fixed names, so the value is ignored there.
     * @param constructorKey the switch key: the entry's provider name, or the block's
     * wire-format family when the name is not ServiceLoader-registered
     * @param timeouts the per-backend merged timeout config (entry &gt; provider
     * block &gt; global — {@code ModelListFactory.backendFor} computes it), with
     * null components filled here from {@link JanusConfig.TimeoutsConfig#DEFAULTS}
     * (the direct-call convenience; the production path threads the merged
     * resolution from {@link #router})
     */
    static ProviderAdapter createProvider(
            String providerName,
            String constructorKey,
            String baseUrl,
            String apiKey,
            JanusConfig.TimeoutsConfig timeouts) {
        JanusConfig.TimeoutsConfig t = resolve(timeouts);
        Duration connectTimeout = Duration.ofSeconds(t.connectTimeoutSeconds());
        Duration headerTimeout = Duration.ofSeconds(t.headerTimeoutSeconds());
        Duration bodyReadTimeout = Duration.ofSeconds(t.bodyReadTimeoutSeconds());
        switch (constructorKey) {
            case DeepSeekAdapter.NAME -> {
                String resolvedBase = baseUrl == null || baseUrl.isBlank() ? DeepSeekAdapter.DEFAULT_BASE_URL : baseUrl;
                return new DeepSeekAdapter(resolvedBase, apiKey, connectTimeout, headerTimeout, bodyReadTimeout);
            }
            case OpenAiCompatibleAdapter.NAME -> {
                // Generic OpenAI-format upstream (OpenRouter, xAI, Ollama,...): no built-in
                // default base URL — the operator must supply one (entry base-url or the
                // [janus.providers.<name>] block default); a blank base fails fast here.
                // The adapter's name is the operator's provider name (providerName), NOT
                // the generic "openai-compatible" family key — the entry's provider is the
                // router/weighted-strategy/owned_by identity.
                return new OpenAiCompatibleAdapter(
                        providerName, baseUrl, apiKey, connectTimeout, headerTimeout, bodyReadTimeout);
            }
            case AnthropicAdapter.NAME -> {
                String resolvedBase =
                        baseUrl == null || baseUrl.isBlank() ? AnthropicAdapter.DEFAULT_BASE_URL : baseUrl;
                return new AnthropicAdapter(resolvedBase, apiKey, connectTimeout, headerTimeout, bodyReadTimeout);
            }
            default ->
                throw new IllegalStateException(
                        "no provider constructor registered for provider \"" + constructorKey + "\"");
        }
    }
}
