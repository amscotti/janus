package io.amscotti.janus.gateway;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.JanusConfig.ModelListEntry;
import io.amscotti.janus.JanusConfig.ProviderEntry;
import io.amscotti.janus.provider.AnthropicAdapter;
import io.amscotti.janus.provider.ProviderAdapter;
import io.amscotti.janus.router.ChatBackend;
import io.micronaut.core.annotation.Nullable;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Turns the TOML {@code [[janus.model-list]]} entries into
 * the router's route map — the pure-logic, testable half of the composition root.
 * {@link RouterFactory} supplies the production {@code env} function ({@code
 * System::getenv}) and the provider-constructor switch; this class owns the rules.
 * The {@code [janus.providers.<name>]} block merge adds per-provider
 * {@code base-url}/{@code api-key-env} defaults fill entries that omit them (the entry's
 * own values win; see {@link #buildBackendLists(List, Map, Function,
 * ProviderConstructor)}), the four {@code [janus.timeouts]} override keys follow
 * the same entry-over-block-over-global merge, and the block's
 * {@code wire-format} is consumed as the
 * construction fallback for provider names the ServiceLoader does not know.
 *
 * <p><b>Env resolution lives only here</b> (composition root; contract: "the SPI
 * never reads environment variables or configuration files"). Missing or blank
 * {@code api-key-env} → no credential; missing/blank env-var value → {@code ""} ( * the adapter omits {@code Authorization} when the secret is blank → a real upstream
 * returns 401, which exercises the auth error-envelope path; a boot warning is
 * logged so the omission is not silent). Startup is deliberately <b>not</b> failed on a
 * missing key — config without secrets in CI/dev must keep working.
 *
 * <p><b>Provider resolution</b> — two halves, both fail-fast at boot (the reference implementation
 * {@code registry.ex} {@code integrity_check/0} philosophy):
 *
 * <ol>
 * <li>ServiceLoader validation: {@code ServiceLoader.load(ProviderAdapter.class)}
 * names ('s {@code META-INF/services} registration; iterating here also keeps
 * the registration rooted in native-image). An entry {@code provider} not in the
 * set → {@link IllegalStateException} listing the known providers — unless the
 * {@code [janus.providers.<name>]} block's {@code wire-format} names a known
 * family (the declarative hint is then <em>consumed</em> as the construction
 * fallback — the family's adapter class is constructed <em>under the entry's
 * provider name</em>: for the generic {@code "openai-compatible"} family the
 * adapter is named by the entry's provider (two distinct fallback providers under
 * one alias stay distinguishable), while the {@code "anthropic"}
 * family adapter keeps its fixed name, one Anthropic upstream).
 * <li>Explicit constructor registry in {@link RouterFactory} (no runtime reflection —
 * native-image discipline, AGENTS.md), which also applies the per-provider
 * {@code baseUrl} default when the TOML entry omits it.
 * </ol>
 *
 * <p><b>Duplicate aliases are the multi-provider feature (; supersedes 's
 * duplicate-alias rejection).</b> Two {@code [[janus.model-list]]} entries with the same
 * {@code name} are two backends in one ordered candidate list for that alias — LiteLLM's
 * {@code model_name} → deployments index (read-only reference: {@code litellm/router.py}
 * {@code Router.__init__} model_list index; the reference defers exactly
 * this to +). Per entry, all / resolution applies unchanged (provider-block
 * defaults, env-key resolution, adapter construction, config order). The router's
 * {@code Router.route} returns the <i>first</i> candidate ( semantics), so the
 * {@code ModelsController} listing is unaffected. <b>In-group uniqueness:</b> within one
 * alias's candidate list, resolved backend names ({@link ChatBackend#name} — the
 * adapter/provider name) must be unique — two entries for one alias with the same
 * provider (e.g. two {@code deepseek} entries with different keys) are a config error
 * ({@link IllegalStateException} naming the alias + provider) — as is the subtler
 * collision of two <em>different</em> custom provider names that both fall back to a
 * fixed-name wire-format family ({@code my-claude} and {@code claude-proxy}, both
 * {@code wire-format = "anthropic"} → the shared fixed backend name {@code
 * "anthropic"}), whose error names that family collision instead of claiming a
 * reused provider (see {@link #resolvedBackendName} — the naming rule). Rationale:
 * the weighted
 * strategy keys weights by backend name, and documented "backend names are
 * expected unique per alias list (/'s duplicate-handling concern, not re-litigated
 * here)" — is that designated decision point; multiple <i>different</i> providers
 * per alias is the supported shape (the gate's two-provider failover).
 *
 * <p>Insertion order = config order (a {@link LinkedHashMap}) so {@code Router.models}
 * and {@code GET /v1/models} stay deterministic ('s models listing depends on it).
 * An empty/absent {@code model-list} is a valid boot state → empty unmodifiable map →
 * empty router ({@code /v1/models} → {@code []}, any chat → 404 {@code model_not_found}).
 *
 * <p><b>TOML spelling.</b> Micronaut 5.1 binds record components from kebab-case
 * TOML keys: {@code [[janus.model-list]]} with {@code api-key-env}/{@code base-url}.
 * The underscore spellings ({@code model_list}, {@code api_key_env}) bind {@code name}/
 * {@code provider} but silently null the rest — pinned by
 * {@code ModelListBindingTest.underscoreKeysSilentlyNullCredentialFields} so an operator
 * following the wrong shape gets a boot warning, not a silent 401.
 */
final class ModelListFactory {

    private static final Logger LOG = System.getLogger("io.amscotti.janus.gateway.ModelListFactory");

    private ModelListFactory() {}

    /** Production: {@link RouterFactory}'s explicit constructor switch. Test: a fake. */
    @FunctionalInterface
    interface ProviderConstructor {
        /**
         * @param providerName the entry's <b>provider</b> name — the display name the
         * backend is known by ({@link ChatBackend#name}, the weighted-strategy key,
         * the {@code owned_by} value). Preserved even when construction falls back to
         * a wire-format family, so two distinct fallback providers under one
         * alias stay distinguishable instead of both surfacing the generic family key.
         * @param constructorKey the constructor switch key — the entry's provider name
         * when it is a ServiceLoader-registered adapter name, else the
         * {@code [janus.providers.<name>]} block's wire-format family
         * @param baseUrl resolved base URL (entry value, else the provider block's default)
         * @param apiKey resolved credential
         * @param timeouts the <b>per-backend resolved</b> timeout config (never null
         * on the production path — {@code backendFor} merges entry-over-
         * provider-block-over-global before calling, the exact {@code baseUrl}/
         * {@code apiKeyEnv} merge pattern); the constructor turns it into the
         * adapter's connect/header/body-read {@code Duration}s
         */
        ProviderAdapter create(
                String providerName,
                String constructorKey,
                String baseUrl,
                String apiKey,
                JanusConfig.TimeoutsConfig timeouts);
    }

    /**
     * form: {@code [[janus.model_list]]} entries → alias → ordered candidate backend
     * list map — the router's balanced/resilient route shape (LiteLLM {@code model_name}
     * → deployments index semantics; see the class javadoc for the grouping rule and the
     * in-group name-uniqueness check). The single-backend map form is gone:
     * production only calls this list-building form (keeping both would invite drift),
     * and a one-entry alias yields a one-element candidate list — single-backend
     * behavior unchanged.
     *
     * @param entries the bound {@code [[janus.model_list]]} entries; null (absent list)
     * or empty → empty unmodifiable map
     * @param providers the bound {@code [janus.providers.<name>]} blocks,
     * keyed by provider name; per-provider {@code baseUrl}/{@code apiKeyEnv} defaults
     * are merged under matching entries that omit them (the entry's own values win).
     * A block's {@code wireFormat} also acts as the construction fallback: an entry
     * whose {@code provider} name is not a registered adapter name boots when the
     * block names a known wire-format family (the family's adapter class is
     * constructed). Never null — pass {@code Map.of} for no blocks.
     * @param env credential lookup, keyed by {@code apiKeyEnv}; production:
     * {@code System::getenv}
     * @param timeouts the (resolved) global {@code [janus.timeouts]} config — the
     * fallback level of the per-backend merge ({@code RouterFactory} resolves the
     * section before calling; absent section ⇒ {@link
     * JanusConfig.TimeoutsConfig#DEFAULTS}); each entry's overrides merge over it
     * per key (entry &gt; provider block &gt; this global)
     * @param constructor provider construction; production: {@link RouterFactory}'s
     * explicit switch (applies the per-provider {@code baseUrl} default)
     */
    static Map<String, List<ChatBackend>> buildBackendLists(
            List<ModelListEntry> entries,
            Map<String, ProviderEntry> providers,
            Function<String, String> env,
            JanusConfig.TimeoutsConfig timeouts,
            ProviderConstructor constructor) {
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(constructor, "constructor");
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> knownProviders = knownProviders();
        Map<String, List<ChatBackend>> grouped = new LinkedHashMap<>();
        // alias → resolved backend name → the entry provider that first claimed it:
        // the uniqueness check needs BOTH colliding provider names to tell the two
        // collision kinds apart (see duplicateBackendMessage) — the shared backend
        // name alone cannot.
        Map<String, Map<String, String>> claimed = new LinkedHashMap<>();
        for (ModelListEntry entry : entries) {
            Objects.requireNonNull(entry, "model_list entry");
            String alias = entry.name();
            ChatBackend backend = backendFor(entry, providers, knownProviders, env, timeouts, constructor);
            String firstProvider = claimed.computeIfAbsent(alias, a -> new LinkedHashMap<>())
                    .putIfAbsent(backend.name(), entry.provider());
            if (firstProvider != null) {
                throw new IllegalStateException(
                        duplicateBackendMessage(alias, firstProvider, entry.provider(), backend.name()));
            }
            grouped.computeIfAbsent(alias, a -> new ArrayList<>()).add(backend);
        }
        Map<String, List<ChatBackend>> routes = new LinkedHashMap<>();
        for (Map.Entry<String, List<ChatBackend>> entry : grouped.entrySet()) {
            routes.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(routes);
    }

    /**
     * The in-group uniqueness error, split by collision kind: two entries with the
     * SAME provider (two {@code deepseek} rows) is the plain duplicate, but two
     * DIFFERENT custom provider names that both fall back to a fixed-name
     * wire-format family ({@code my-claude} and {@code claude-proxy}, both
     * {@code wire-format = "anthropic"} → the shared fixed backend name {@code
     * "anthropic"}) is a family-name collision — the operator did use different
     * providers, so the message names both and the shared fixed name instead of
     * claiming a reused provider.
     */
    private static String duplicateBackendMessage(
            String alias, String firstProvider, String secondProvider, String backendName) {
        if (backendName.equals(firstProvider) && backendName.equals(secondProvider)) {
            return "duplicate provider \"" + backendName + "\" for model alias \"" + alias
                    + "\" (two entries for one alias must use different providers)";
        }
        return "duplicate backend name \"" + backendName + "\" for model alias \"" + alias + "\": providers \""
                + firstProvider + "\" and \"" + secondProvider + "\" are different providers but both fall back to"
                + " the fixed-name \"" + backendName + "\" wire-format family, whose adapter keeps one fixed"
                + " backend name (two entries for one alias must resolve to different backend names — use distinct"
                + " model aliases, or a wire-format family that keeps each provider's own name)";
    }

    /**
     * The backend display name ({@link ChatBackend#name}) an entry's provider
     * resolves to — the naming rule {@link #backendFor}'s construction follows,
     * formalized so the SSE stream-idle overrides can be keyed by the name the
     * dispatch observer reports: the entry's provider name when the provider is a
     * ServiceLoader-registered adapter name or the block's wire-format is the
     * generic {@code "openai-compatible"} family (that adapter is named by the
     * entry's provider), else the fixed-name family's adapter name
     * ({@link AnthropicAdapter#NAME} for {@code wire-format = "anthropic"}).
     * Returns null when no known provider or known wire-format family resolves —
     * the entry {@link #buildBackendLists} then fails fast on; callers keying
     * per-backend config treat null as "keys nothing".
     */
    static String resolvedBackendName(
            ModelListEntry entry, Map<String, ProviderEntry> providers, Set<String> knownProviders) {
        String provider = entry.provider();
        if (knownProviders.contains(provider)) {
            return provider;
        }
        ProviderEntry block = providers.get(provider);
        if (block != null && JanusConfig.WIRE_FORMAT_OPENAI_COMPATIBLE.equals(block.wireFormat())) {
            return provider;
        }
        if (block != null && JanusConfig.WIRE_FORMAT_ANTHROPIC.equals(block.wireFormat())) {
            return AnthropicAdapter.NAME;
        }
        return null;
    }

    /**
     * The resolved backend name a {@code [janus.providers.<name>]} block's entries
     * dispatch under — null when no {@code [[janus.model-list]]} entry references
     * the provider (the block contributes no backend, so per-backend config keyed
     * off it keys nothing).
     */
    static String resolvedBackendNameFor(
            String blockKey,
            @Nullable List<ModelListEntry> modelList,
            Map<String, ProviderEntry> providers,
            Set<String> knownProviders) {
        if (modelList == null) {
            return null;
        }
        for (ModelListEntry entry : modelList) {
            if (blockKey.equals(entry.provider())) {
                return resolvedBackendName(entry, providers, knownProviders);
            }
        }
        return null;
    }

    /** Adapter names from the ServiceLoader — the fail-fast known-provider set. */
    static Set<String> knownProviders() {
        Set<String> names = new TreeSet<>();
        for (ProviderAdapter adapter : ServiceLoader.load(ProviderAdapter.class)) {
            names.add(adapter.name());
        }
        return names;
    }

    private static ChatBackend backendFor(
            ModelListEntry entry,
            Map<String, ProviderEntry> providers,
            Set<String> knownProviders,
            Function<String, String> env,
            JanusConfig.TimeoutsConfig timeouts,
            ProviderConstructor constructor) {
        String provider = entry.provider();
        String constructorKey = provider;
        if (!knownProviders.contains(provider)) {
            // Fallback: a [janus.providers.<name>] block with a known
            // wire-format selects the adapter class for an otherwise-unknown provider
            // name — the declarative half of provider definition is consumed here, not
            // just validated. Without a block the entry is a config error (fail-fast).
            ProviderEntry block = providers.get(provider);
            if (block == null
                    || block.wireFormat() == null
                    || block.wireFormat().isBlank()) {
                throw new IllegalStateException(
                        "model_list entry \"" + entry.name() + "\" references unknown provider \"" + provider
                                + "\" (known providers: " + knownProviders + ")");
            }
            constructorKey = block.wireFormat();
        }
        // The [janus.providers.<name>] block supplies per-provider defaults
        // for entries that omit them; the entry's own values always win. Defaults stay
        // keyed by the entry's provider name even when construction falls back to the
        // block's wire-format family. The backend's display name follows
        // resolvedBackendName's rule: the entry's provider name for the generic
        // openai-compatible family (so a fallback provider is known by its own name —
        // two distinct fallback providers under one alias stay distinguishable), the
        // fixed family name for a fixed-name family (anthropic).
        ProviderEntry providerBlock = providers.get(provider);
        String baseUrl =
                entry.baseUrl() != null ? entry.baseUrl() : (providerBlock == null ? null : providerBlock.baseUrl());
        String apiKeyEnv = entry.apiKeyEnv() != null
                ? entry.apiKeyEnv()
                : (providerBlock == null ? null : providerBlock.apiKeyEnv());
        // : the four timeout overrides follow the exact same merge — entry
        // component > provider-block component > global [janus.timeouts], per key
        // (null falls through level by level) — producing the fully-resolved
        // per-backend config the constructor receives instead of one global.
        JanusConfig.TimeoutsConfig resolvedTimeouts = resolveTimeouts(timeouts, providerBlock, entry);
        return new ProviderAdapterChatBackend(constructor.create(
                provider, constructorKey, baseUrl, resolveApiKey(apiKeyEnv, entry, env), resolvedTimeouts));
    }

    /** A provider block with every component null (the no-block merge identity). */
    private static final ProviderEntry NO_BLOCK = new ProviderEntry(null, null, null, null, null, null, null);

    /**
     * Per-backend timeout resolution : entry component &gt; provider-block
     * component &gt; global, per key — a null component at any level falls through
     * to the next (the exact merge pattern {@code baseUrl}/{@code apiKeyEnv}
     * follow; a fully-null entry and block resolve the global verbatim, so the
     * no-override boot is byte-identical to the single-section wiring).
     *
     * @param global the resolved {@code [janus.timeouts]} config (never null on
     * the production path — {@code RouterFactory} resolves the section before
     * calling); supplies every component neither the entry nor the block sets
     */
    private static JanusConfig.TimeoutsConfig resolveTimeouts(
            JanusConfig.TimeoutsConfig global, @Nullable ProviderEntry block, ModelListEntry entry) {
        ProviderEntry b = block == null ? NO_BLOCK : block;
        return new JanusConfig.TimeoutsConfig(
                firstNonNull(entry.connectTimeoutSeconds(), b.connectTimeoutSeconds(), global.connectTimeoutSeconds()),
                firstNonNull(entry.headerTimeoutSeconds(), b.headerTimeoutSeconds(), global.headerTimeoutSeconds()),
                firstNonNull(
                        entry.bodyReadTimeoutSeconds(), b.bodyReadTimeoutSeconds(), global.bodyReadTimeoutSeconds()),
                firstNonNull(
                        entry.streamIdleTimeoutSeconds(),
                        b.streamIdleTimeoutSeconds(),
                        global.streamIdleTimeoutSeconds()));
    }

    /** First non-null of entry &gt; provider block &gt; global (null falls through). */
    private static Integer firstNonNull(Integer entryValue, Integer blockValue, Integer globalValue) {
        if (entryValue != null) {
            return entryValue;
        }
        return blockValue != null ? blockValue : globalValue;
    }

    private static String resolveApiKey(String apiKeyEnv, ModelListEntry entry, Function<String, String> env) {
        String envName = apiKeyEnv;
        if (envName == null || envName.isBlank()) {
            LOG.log(
                    Level.WARNING,
                    "model_list entry \"{0}\": no api-key-env configured (entry or [janus.providers.{1}]"
                            + " block) — the adapter will send no Authorization header (a real upstream"
                            + " returns 401; if the key was set in TOML, use the kebab-case spelling"
                            + " api-key-env — Micronaut 5.1 does not bind api_key_env)",
                    entry.name(),
                    entry.provider());
            return ""; // no credential reference — the adapter omits Authorization
        }
        String secret = env.apply(envName);
        if (secret == null || secret.isBlank()) {
            LOG.log(
                    Level.WARNING,
                    "model_list entry \"{0}\": env var {1} is unset/blank — the adapter will send no"
                            + " Authorization header (a real upstream returns 401)",
                    entry.name(),
                    envName);
            return "";
        }
        return secret;
    }
}
