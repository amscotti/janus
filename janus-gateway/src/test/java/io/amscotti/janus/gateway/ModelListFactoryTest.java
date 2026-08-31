package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.JanusConfig.ModelListEntry;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.provider.AnthropicAdapter;
import io.amscotti.janus.provider.DeepSeekAdapter;
import io.amscotti.janus.provider.OpenAiCompatibleAdapter;
import io.amscotti.janus.provider.ProviderAdapter;
import io.amscotti.janus.provider.ProviderAuth;
import io.amscotti.janus.router.ChatBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * builder unit tests, list form — pure JVM (canned entries, fake {@code env}
 * function, fake {@link ModelListFactory.ProviderConstructor} returning a stub {@link
 * ProviderAdapter}; no Micronaut, no network). Pins: config-order map building with
 * {@link ProviderAdapterChatBackend} wrapping; <b> grouping semantics</b> — duplicate
 * aliases become ordered multi-backend candidate lists (LiteLLM {@code model_name} →
 * deployments index), per-entry env/base-url/provider-block resolution preserved, an
 * in-group duplicate provider name fails fast naming alias + provider; unknown-provider
 * fail-fast listing the ServiceLoader's known providers (and the wire-format
 * construction fallback for unknown names with a {@code [janus.providers.*]} block);
 * blank name/provider rejection (record-level, at binding time); missing/blank env var
 * → blank credential (no {@code Authorization} header → 401 envelope path);
 * absent {@code baseUrl} pass-through + production default; empty/absent list → empty
 * map; {@code env} never consulted for entries without {@code apiKeyEnv}.
 */
class ModelListFactoryTest {

    private static ModelListEntry entry(String name, String provider, String apiKeyEnv, String baseUrl) {
        return new ModelListEntry(name, provider, apiKeyEnv, baseUrl);
    }

    /** form helper: no provider blocks, the resolved default timeouts. */
    private static Map<String, List<ChatBackend>> build(
            List<ModelListEntry> entries, ModelListFactory.ProviderConstructor ctor) {
        return ModelListFactory.buildBackendLists(
                entries, Map.of(), name -> "", JanusConfig.TimeoutsConfig.DEFAULTS, ctor);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void buildsBackendListsInConfigOrderWithWrappedBackends() {
        RecordingConstructor ctor = new RecordingConstructor();
        Map<String, List<ChatBackend>> routes = ModelListFactory.buildBackendLists(
                List.of(
                        entry("deepseek-v4-flash", "deepseek", "DEEPSEEK_API_KEY", "https://api.deepseek.com"),
                        entry("deepseek-v4-pro", "deepseek", "DEEPSEEK_API_KEY", null)),
                Map.of(),
                name -> "secret-" + name,
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);

        assertEquals(List.of("deepseek-v4-flash", "deepseek-v4-pro"), new ArrayList<>(routes.keySet()));
        assertInstanceOf(
                ProviderAdapterChatBackend.class,
                routes.get("deepseek-v4-flash").getFirst());
        assertEquals("deepseek", routes.get("deepseek-v4-flash").getFirst().name());
        assertEquals("deepseek", routes.get("deepseek-v4-pro").getFirst().name());
        assertEquals(List.of("deepseek", "deepseek"), ctor.providers);
        assertEquals(java.util.Arrays.asList("https://api.deepseek.com", null), ctor.baseUrls);
        assertEquals(List.of("secret-DEEPSEEK_API_KEY", "secret-DEEPSEEK_API_KEY"), ctor.apiKeys);
    }

    @Test
    void timeoutsThreadToEveryCreateCall() {
        // : the [janus.timeouts] config handed to the builder reaches every
        // ProviderConstructor.create call verbatim (the RouterFactory resolves the
        // section before calling; the values here are the fake-captured ones).
        RecordingConstructor ctor = new RecordingConstructor();
        JanusConfig.TimeoutsConfig configured = new JanusConfig.TimeoutsConfig(3, 7, 120, 9);
        ModelListFactory.buildBackendLists(
                List.of(entry("chat", "deepseek", null, null), entry("chat", "anthropic", null, null)),
                Map.of(),
                name -> "",
                configured,
                ctor);
        assertEquals(List.of(configured, configured), ctor.timeouts, "per-create capture in config order");
    }

    // ------------------------------------------------ per-provider overrides

    @Test
    void timeoutOverridesMergeEntryOverProviderOverGlobal() {
        // : the per-backend resolution is the exact merge pattern base-url /
        // api-key-env follow — entry component > [janus.providers.<name>] component
        // > global [janus.timeouts], level by level per key (a null component at
        // any level falls through to the next). Here: the entry overrides header,
        // the block overrides header+body-read (its header loses to the entry's),
        // connect and stream-idle fall through both levels to the global.
        RecordingConstructor ctor = new RecordingConstructor();
        JanusConfig.TimeoutsConfig global = new JanusConfig.TimeoutsConfig(11, 12, 130, 13);
        ModelListFactory.buildBackendLists(
                List.of(new ModelListEntry("chat", "my-ollama", null, "http://localhost:11434", null, 180, null, null)),
                Map.of(
                        "my-ollama",
                        new JanusConfig.ProviderEntry("openai-compatible", null, null, null, 120, 900, null)),
                name -> "",
                global,
                ctor);
        assertEquals(
                List.of(new JanusConfig.TimeoutsConfig(11, 180, 900, 13)),
                ctor.timeouts,
                "entry header > block header; block body-read > global; nulls fall through to the global");
    }

    @Test
    void fullyNullTimeoutOverridesResolveToTheGlobalVerbatim() {
        // A present-but-all-null provider block and entry == the global config —
        // the no-override boot stays byte-identical to the single-section
        // semantics (every create call sees the global verbatim).
        RecordingConstructor ctor = new RecordingConstructor();
        JanusConfig.TimeoutsConfig global = new JanusConfig.TimeoutsConfig(11, 12, 130, 13);
        ModelListFactory.buildBackendLists(
                List.of(entry("chat", "deepseek", null, null)),
                Map.of(
                        "deepseek",
                        new JanusConfig.ProviderEntry(
                                "openai-compatible",
                                "https://api.deepseek.com",
                                "DEEPSEEK_API_KEY",
                                null,
                                null,
                                null,
                                null)),
                name -> "",
                global,
                ctor);
        assertEquals(List.of(global), ctor.timeouts, "all-null overrides resolve to the global verbatim");
    }

    @Test
    void perEntryTimeoutOverridesArePerBackendNotGlobalForTheAlias() {
        // Two entries under one alias (the multi-provider shape): each entry's
        // overrides resolve independently — the overriding entry's backend sees
        // them, the sibling entry still sees the global for its null components.
        RecordingConstructor ctor = new RecordingConstructor();
        JanusConfig.TimeoutsConfig global = new JanusConfig.TimeoutsConfig(11, 12, 130, 13);
        ModelListFactory.buildBackendLists(
                List.of(
                        new ModelListEntry("chat", "my-ollama", null, "http://localhost:11434", 5, null, null, null),
                        entry("chat", "deepseek", null, null)),
                Map.of(
                        "my-ollama",
                        new JanusConfig.ProviderEntry("openai-compatible", null, null, null, null, null, null)),
                name -> "",
                global,
                ctor);
        assertEquals(
                List.of(new JanusConfig.TimeoutsConfig(5, 12, 130, 13), global),
                ctor.timeouts,
                "per-entry resolution in config order: only the overriding entry's backend sees it");
    }

    @Test
    void nonPositiveEntryOrProviderTimeoutRejectedAtRecordLevel() {
        // fail-fast : a typo'd 0 override refuses the boot at binding
        // time, naming the key (the [janus.timeouts] compact-constructor precedent).
        IllegalArgumentException entryEx = assertThrows(
                IllegalArgumentException.class,
                () -> new ModelListEntry("chat", "deepseek", null, null, null, 0, null, null));
        assertTrue(
                entryEx.getMessage().contains("header-timeout-seconds"),
                "the entry error names the offending key: " + entryEx.getMessage());
        IllegalArgumentException blockEx = assertThrows(
                IllegalArgumentException.class,
                () -> new JanusConfig.ProviderEntry(null, null, null, 0, null, null, null));
        assertTrue(
                blockEx.getMessage().contains("connect-timeout-seconds"),
                "the provider-block error names the offending key: " + blockEx.getMessage());
    }

    // ------------------------------------------- duplicate-alias grouping semantics

    @Test
    void duplicateAliasGroupsIntoOrderedCandidateList() {
        // Two entries with the same name are two backends in one ordered candidate list
        // (LiteLLM model_name → deployments index). Per-entry resolution still applies.
        RecordingConstructor ctor = new RecordingConstructor();
        Map<String, List<ChatBackend>> routes = ModelListFactory.buildBackendLists(
                List.of(
                        entry("deepseek-v4-flash", "deepseek", "DEEPSEEK_API_KEY", "https://api.deepseek.com"),
                        entry("deepseek-v4-flash", "anthropic", "ANTHROPIC_API_KEY", null)),
                Map.of(),
                name -> "secret-" + name,
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);

        assertEquals(List.of("deepseek-v4-flash"), new ArrayList<>(routes.keySet()), "one alias key for both entries");
        List<ChatBackend> candidates = routes.get("deepseek-v4-flash");
        assertEquals(2, candidates.size(), "two entries → two candidates");
        assertEquals(
                List.of("deepseek", "anthropic"),
                candidates.stream().map(ChatBackend::name).toList());
        assertEquals(List.of("deepseek", "anthropic"), ctor.providers, "per-entry constructor keys in config order");
        assertEquals(
                java.util.Arrays.asList("https://api.deepseek.com", null),
                ctor.baseUrls,
                "per-entry base-url resolution preserved under grouping");
        assertEquals(
                List.of("secret-DEEPSEEK_API_KEY", "secret-ANTHROPIC_API_KEY"),
                ctor.apiKeys,
                "per-entry env resolution preserved under grouping");
    }

    @Test
    void duplicateAliasSameProviderFailsFastNamingAliasAndProvider() {
        // The in-group uniqueness rule: within one alias's candidate list, resolved
        // backend names (adapter/provider names) must be unique — two deepseek entries
        // (even with different keys) are a config error, not a second backend.
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> build(
                        List.of(
                                entry("deepseek-v4-flash", "deepseek", null, null),
                                entry("deepseek-v4-flash", "deepseek", null, null)),
                        new RecordingConstructor()));
        assertTrue(ex.getMessage().contains("deepseek-v4-flash"), "error must name the alias: " + ex);
        assertTrue(ex.getMessage().contains("deepseek"), "error must name the provider: " + ex);
        assertTrue(
                ex.getMessage().contains("must use different providers"),
                "the same-provider duplicate names the classic cause: " + ex);
    }

    @Test
    void twoAnthropicFamilyFallbacksUnderOneAliasNameTheFixedNameCollision() {
        // The subtler collision: two DIFFERENT custom provider names (my-claude,
        // claude-proxy) both fall back to wire-format = "anthropic" and resolve to
        // the family's fixed backend name "anthropic" — still a boot error (the
        // router/weights/metrics cannot tell the two apart), but the error must name
        // the family-name collision, NOT claim the operator reused one provider.
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ModelListFactory.buildBackendLists(
                        List.of(entry("chat", "my-claude", null, null), entry("chat", "claude-proxy", null, null)),
                        Map.of(
                                "my-claude",
                                new JanusConfig.ProviderEntry("anthropic", "https://a.example", "K1"),
                                "claude-proxy",
                                new JanusConfig.ProviderEntry("anthropic", "https://b.example", "K2")),
                        name -> "",
                        JanusConfig.TimeoutsConfig.DEFAULTS,
                        new RecordingConstructor()));
        assertTrue(ex.getMessage().contains("chat"), "error must name the alias: " + ex);
        assertTrue(ex.getMessage().contains("my-claude"), "error must name the first provider: " + ex);
        assertTrue(ex.getMessage().contains("claude-proxy"), "error must name the second provider: " + ex);
        assertTrue(ex.getMessage().contains("\"anthropic\""), "error must name the fixed backend name: " + ex);
        assertTrue(ex.getMessage().contains("fixed"), "error must name the fixed-name family as the cause: " + ex);
        assertTrue(
                !ex.getMessage().contains("must use different providers"),
                "the operator DID use different providers — that message is wrong here: " + ex);
    }

    @Test
    void sameBackendNameViaMixedKnownAndFallbackEntriesNamesBothProviders() {
        // The collision also crosses shapes: a genuine "anthropic" entry and a
        // custom-name anthropic-family fallback both resolve to "anthropic" — the
        // message names both providers (neither is a reused provider name).
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ModelListFactory.buildBackendLists(
                        List.of(entry("chat", "my-claude", null, null), entry("chat", "anthropic", null, null)),
                        Map.of("my-claude", new JanusConfig.ProviderEntry("anthropic", null, null)),
                        name -> "",
                        JanusConfig.TimeoutsConfig.DEFAULTS,
                        new RecordingConstructor()));
        assertTrue(ex.getMessage().contains("my-claude"), "names the fallback provider: " + ex);
        assertTrue(ex.getMessage().contains("anthropic"), "names the fixed family name: " + ex);
    }

    @Test
    void resolvedBackendNameMatchesTheRealConstructorNaming() {
        // Drift guard for the naming rule ModelListFactory.resolvedBackendName
        // formalizes (the stream-idle override keying keys it): a registered provider
        // resolves to itself, an openai-compatible-family fallback to the entry's
        // provider name, and an anthropic-family fallback to the fixed family name —
        // each pinned against the REAL RouterFactory.createProvider output.
        Set<String> known = ModelListFactory.knownProviders();
        Map<String, JanusConfig.ProviderEntry> blocks = Map.of(
                "my-ollama", new JanusConfig.ProviderEntry("openai-compatible", null, null),
                "my-claude", new JanusConfig.ProviderEntry("anthropic", null, null));
        assertEquals(
                "deepseek",
                ModelListFactory.resolvedBackendName(entry("chat", "deepseek", null, null), Map.of(), known));
        assertEquals(
                "my-ollama",
                ModelListFactory.resolvedBackendName(entry("chat", "my-ollama", null, null), blocks, known));
        assertEquals(
                "anthropic",
                ModelListFactory.resolvedBackendName(entry("chat", "my-claude", null, null), blocks, known));
        assertEquals(
                "anthropic",
                ModelListFactory.resolvedBackendNameFor(
                        "my-claude", List.of(entry("chat", "my-claude", null, null)), blocks, known),
                "a block resolves to its entries' backend name (the anthropic family's fixed name)");
        assertNull(
                ModelListFactory.resolvedBackendNameFor(
                        "claude-proxy", List.of(entry("chat", "my-claude", null, null)), blocks, known),
                "a block with no referencing entry resolves to nothing");

        // The rule mirrors the production constructor's naming, leg by leg.
        assertEquals(
                "deepseek",
                RouterFactory.createProvider("deepseek", "deepseek", null, "k", JanusConfig.TimeoutsConfig.DEFAULTS)
                        .name());
        assertEquals(
                "my-ollama",
                RouterFactory.createProvider(
                                "my-ollama",
                                "openai-compatible",
                                "http://localhost:11434",
                                "k",
                                JanusConfig.TimeoutsConfig.DEFAULTS)
                        .name());
        assertEquals(
                "anthropic",
                RouterFactory.createProvider("my-claude", "anthropic", null, "k", JanusConfig.TimeoutsConfig.DEFAULTS)
                        .name());
    }

    @Test
    void threeEntriesABAPreserveConfigOrder() {
        // Entries A(deepseek), B(anthropic), A(openai-compatible): the map keys keep
        // first-occurrence (config) order and alias A's candidate list keeps the config
        // order of its two entries.
        RecordingConstructor ctor = new RecordingConstructor();
        Map<String, List<ChatBackend>> routes = ModelListFactory.buildBackendLists(
                List.of(
                        entry("a-model", "deepseek", null, null),
                        entry("b-model", "anthropic", null, null),
                        entry("a-model", "openai-compatible", null, "http://localhost:11434")),
                Map.of(),
                name -> "",
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);

        assertEquals(List.of("a-model", "b-model"), new ArrayList<>(routes.keySet()));
        assertEquals(
                List.of("deepseek", "openai-compatible"),
                routes.get("a-model").stream().map(ChatBackend::name).toList(),
                "alias A's candidates keep entry order A(1), A(2)");
        assertEquals(
                List.of("anthropic"),
                routes.get("b-model").stream().map(ChatBackend::name).toList());
    }

    @Test
    void unknownProviderFailsFastListingKnownProviders() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> build(List.of(entry("chat", "bogus-provider", null, null)), new RecordingConstructor()));
        assertTrue(ex.getMessage().contains("bogus-provider"), "error must name the unknown provider: " + ex);
        assertTrue(ex.getMessage().contains("deepseek"), "error must list the ServiceLoader's known providers: " + ex);
    }

    @Test
    void blankNameAndBlankProviderRejected() {
        assertThrows(IllegalArgumentException.class, () -> entry("  ", "deepseek", null, null));
        assertThrows(IllegalArgumentException.class, () -> entry(null, "deepseek", null, null));
        assertThrows(IllegalArgumentException.class, () -> entry("deepseek-v4-flash", " ", null, null));
        assertThrows(IllegalArgumentException.class, () -> entry("deepseek-v4-flash", null, null, null));
    }

    @Test
    void missingEnvVarYieldsBlankCredential() {
        RecordingConstructor ctor = new RecordingConstructor();
        ModelListFactory.buildBackendLists(
                List.of(entry("deepseek-v4-flash", "deepseek", "DEEPSEEK_API_KEY", null)),
                Map.of(),
                name -> null,
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);
        assertEquals(List.of(""), ctor.apiKeys, "missing env var → blank credential (no Authorization header)");
    }

    @Test
    void blankEnvValueYieldsBlankCredential() {
        RecordingConstructor ctor = new RecordingConstructor();
        ModelListFactory.buildBackendLists(
                List.of(entry("deepseek-v4-flash", "deepseek", "DEEPSEEK_API_KEY", null)),
                Map.of(),
                name -> "   ",
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);
        assertEquals(List.of(""), ctor.apiKeys, "blank env value → blank credential (no Authorization header)");
    }

    @Test
    void absentBaseUrlPassesThroughAndProductionConstructorAppliesDefault() {
        RecordingConstructor ctor = new RecordingConstructor();
        ModelListFactory.buildBackendLists(
                List.of(entry("deepseek-v4-flash", "deepseek", null, null)),
                Map.of(),
                name -> "",
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);
        assertTrue(ctor.baseUrls.contains(null), "absent base_url passes through to the constructor");

        // Production half: the explicit switch applies the per-provider default.
        ProviderAdapter adapter =
                RouterFactory.createProvider("deepseek", "deepseek", null, "key", JanusConfig.TimeoutsConfig.DEFAULTS);
        assertEquals(DeepSeekAdapter.DEFAULT_BASE_URL, adapter.baseUrl());
        assertEquals(ProviderAuth.TYPE_BEARER, adapter.auth().type());
        assertEquals("key", adapter.auth().secret());
    }

    // ------------------------------------------------- providers block

    @Test
    void providerBlockDefaultsMergeUnderMatchingEntries() {
        RecordingConstructor ctor = new RecordingConstructor();
        ModelListFactory.buildBackendLists(
                List.of(entry("deepseek-v4-flash", "deepseek", null, null)),
                Map.of(
                        "deepseek",
                        new JanusConfig.ProviderEntry(
                                "openai-compatible", "https://api.deepseek.com", "DEEPSEEK_API_KEY")),
                name -> "secret-" + name,
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);

        assertEquals(List.of("https://api.deepseek.com"), ctor.baseUrls, "block base-url fills the entry's omission");
        assertEquals(List.of("secret-DEEPSEEK_API_KEY"), ctor.apiKeys, "block api-key-env fills the entry's omission");
    }

    @Test
    void providerBlockDefaultsDoNotOverrideExplicitEntryValues() {
        RecordingConstructor ctor = new RecordingConstructor();
        ModelListFactory.buildBackendLists(
                List.of(entry("deepseek-v4-flash", "deepseek", "ENTRY_KEY_ENV", "https://entry.example")),
                Map.of(
                        "deepseek",
                        new JanusConfig.ProviderEntry("openai-compatible", "https://block.example", "BLOCK_KEY_ENV")),
                name -> "secret-" + name,
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);

        assertEquals(List.of("https://entry.example"), ctor.baseUrls, "entry base-url wins over the block default");
        assertEquals(List.of("secret-ENTRY_KEY_ENV"), ctor.apiKeys, "entry api-key-env wins over the block default");
    }

    @Test
    void providerBlockDoesNotLeakToOtherProviders() {
        RecordingConstructor ctor = new RecordingConstructor();
        ModelListFactory.buildBackendLists(
                List.of(entry("deepseek-v4-flash", "deepseek", null, null)),
                Map.of(
                        "anthropic",
                        new JanusConfig.ProviderEntry("anthropic", "https://api.anthropic.com", "ANTHROPIC_API_KEY")),
                name -> "",
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);

        assertTrue(ctor.baseUrls.contains(null), "a block for another provider must not fill this entry's base-url");
        assertEquals(List.of(""), ctor.apiKeys, "a block for another provider must not fill this entry's credential");
    }

    @Test
    void wireFormatBlockConstructsUnknownProviderName() {
        // [janus.providers.<name>] wire-format is consumed — an unknown provider
        // name with a known wire-format family constructs that family's adapter class.
        RecordingConstructor ctor = new RecordingConstructor();
        Map<String, List<ChatBackend>> routes = ModelListFactory.buildBackendLists(
                List.of(entry("claude", "my-claude", null, null)),
                Map.of(
                        "my-claude",
                        new JanusConfig.ProviderEntry("anthropic", "https://api.anthropic.com", "ANTHROPIC_API_KEY")),
                name -> "secret-" + name,
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);

        assertEquals(List.of("anthropic"), ctor.providers, "wire-format selects the adapter class");
        assertEquals(
                List.of("https://api.anthropic.com"),
                ctor.baseUrls,
                "block base-url default still fills the entry's omission");
        assertEquals(
                List.of("secret-ANTHROPIC_API_KEY"),
                ctor.apiKeys,
                "block api-key-env default still fills the entry's omission");
        // The Anthropic family adapter keeps its fixed name (one Anthropic upstream) —
        // the entry's provider name is not threaded for the anthropic family.
        assertEquals(
                List.of("anthropic"),
                routes.get("claude").stream().map(ChatBackend::name).toList());
    }

    @Test
    void deepseekAnthropicWireFormatUsesAnthropicFamilyAndCustomBase() {
        RecordingConstructor ctor = new RecordingConstructor();
        Map<String, List<ChatBackend>> routes = ModelListFactory.buildBackendLists(
                List.of(entry(
                        "deepseek-v4-flash",
                        "deepseek-anthropic",
                        "DEEPSEEK_API_KEY",
                        "https://api.deepseek.com/anthropic")),
                Map.of(
                        "deepseek-anthropic",
                        new JanusConfig.ProviderEntry(
                                "anthropic", "https://api.deepseek.com/anthropic", "DEEPSEEK_API_KEY")),
                name -> "secret-" + name,
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);

        assertEquals(List.of("anthropic"), ctor.providers);
        assertEquals(List.of("https://api.deepseek.com/anthropic"), ctor.baseUrls);
        assertEquals(List.of("secret-DEEPSEEK_API_KEY"), ctor.apiKeys);
        assertEquals(
                List.of("anthropic"),
                routes.get("deepseek-v4-flash").stream().map(ChatBackend::name).toList());
    }

    @Test
    void wireFormatBlockConstructsOpenAiCompatibleFamilyForUnknownName() {
        RecordingConstructor ctor = new RecordingConstructor();
        Map<String, List<ChatBackend>> routes = ModelListFactory.buildBackendLists(
                List.of(entry("ollama", "my-ollama", null, "http://localhost:11434")),
                Map.of("my-ollama", new JanusConfig.ProviderEntry("openai-compatible", null, null)),
                name -> "",
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);

        assertEquals(
                List.of("openai-compatible"), ctor.providers, "wire-format selects the OpenAiCompatibleAdapter class");
        assertEquals(
                List.of("http://localhost:11434"),
                ctor.baseUrls,
                "the entry's own base-url wins over the (absent) block default");
        // The generic adapter is named by the ENTRY's provider — so
        // /v1/models owned_by, the weighted-strategy key and the backend name all show
        // "my-ollama", never the generic "openai-compatible" family key.
        assertEquals(
                List.of("my-ollama"),
                routes.get("ollama").stream().map(ChatBackend::name).toList(),
                "the fallback backend keeps the entry's provider name");
    }

    @Test
    void twoWireFormatFallbackProvidersUnderOneAliasAreDistinctCandidates() {
        // Before the per-instance naming fix, both openai-compatible fallbacks resolved to the
        // generic "openai-compatible" backend name, so the in-group uniqueness check
        // rejected two distinct providers under one alias at boot. With the entry's
        // provider name threaded through, the two backends stay distinguishable.
        RecordingConstructor ctor = new RecordingConstructor();
        Map<String, List<ChatBackend>> routes = ModelListFactory.buildBackendLists(
                List.of(
                        entry("chat", "my-ollama", null, "http://localhost:11434"),
                        entry("chat", "my-groq", null, "https://api.groq.com/v1")),
                Map.of(
                        "my-ollama", new JanusConfig.ProviderEntry("openai-compatible", null, null),
                        "my-groq", new JanusConfig.ProviderEntry("openai-compatible", null, null)),
                name -> "",
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);

        List<ChatBackend> candidates = routes.get("chat");
        assertEquals(2, candidates.size(), "two distinct providers-block fallbacks under one alias boot");
        assertEquals(
                List.of("my-ollama", "my-groq"),
                candidates.stream().map(ChatBackend::name).toList(),
                "each candidate keeps its entry's provider name (not the generic family)");
    }

    @Test
    void unknownProviderWithBlockWithoutWireFormatStillFailsFast() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ModelListFactory.buildBackendLists(
                        List.of(entry("chat", "bogus-provider", null, null)),
                        Map.of("bogus-provider", new JanusConfig.ProviderEntry(null, "https://x.example", "K")),
                        name -> "",
                        JanusConfig.TimeoutsConfig.DEFAULTS,
                        new RecordingConstructor()));
        assertTrue(ex.getMessage().contains("bogus-provider"), "error must name the unknown provider: " + ex);
    }

    @Test
    void createProviderConstructsOpenAiCompatibleAndAnthropic() {
        ProviderAdapter openAiCompatible = RouterFactory.createProvider(
                "openai-compatible",
                "openai-compatible",
                "https://openrouter.example/v1",
                "key",
                JanusConfig.TimeoutsConfig.DEFAULTS);
        assertEquals("openai-compatible", openAiCompatible.name());
        assertEquals(
                "https://openrouter.example",
                openAiCompatible.baseUrl(),
                "trailing /v1 is normalized away (the reference rule)");
        assertEquals(ProviderAuth.TYPE_BEARER, openAiCompatible.auth().type());
        assertEquals("key", openAiCompatible.auth().secret());

        // The display name is threaded through: an openai-compatible-family adapter
        // constructed for the entry provider "my-ollama" is named "my-ollama", not the
        // generic family key.
        ProviderAdapter named = RouterFactory.createProvider(
                "my-ollama", "openai-compatible", "http://localhost:11434", "k", JanusConfig.TimeoutsConfig.DEFAULTS);
        assertEquals("my-ollama", named.name(), "the generic adapter carries the entry's provider name");
        assertEquals("http://localhost:11434", named.baseUrl());

        ProviderAdapter anthropic = RouterFactory.createProvider(
                "anthropic", "anthropic", null, "sk-ant", JanusConfig.TimeoutsConfig.DEFAULTS);
        assertEquals("anthropic", anthropic.name());
        assertEquals(AnthropicAdapter.DEFAULT_BASE_URL, anthropic.baseUrl());
        assertEquals(ProviderAuth.TYPE_X_API_KEY, anthropic.auth().type());
        assertEquals("sk-ant", anthropic.auth().secret());
    }

    @Test
    void invalidProviderWireFormatRejectedAtRecordLevel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JanusConfig.ProviderEntry("bogus-format", "https://x.example", "K"));
    }

    @Test
    void nullOrEmptyEntryListYieldsEmptyMap() {
        RecordingConstructor ctor = new RecordingConstructor();
        assertTrue(build(null, ctor).isEmpty());
        assertTrue(build(List.of(), ctor).isEmpty());
    }

    @Test
    void envNeverConsultedForEntriesWithoutApiKeyEnv() {
        RecordingConstructor ctor = new RecordingConstructor();
        Function<String, String> env = name -> {
            throw new AssertionError("env must not be consulted for an entry without apiKeyEnv: " + name);
        };
        Map<String, List<ChatBackend>> routes = ModelListFactory.buildBackendLists(
                List.of(
                        entry("deepseek-v4-flash", "deepseek", null, null),
                        entry("deepseek-v4-pro", "deepseek", "  ", null)),
                Map.of(),
                env,
                JanusConfig.TimeoutsConfig.DEFAULTS,
                ctor);
        assertEquals(List.of("deepseek-v4-flash", "deepseek-v4-pro"), new ArrayList<>(routes.keySet()));
        assertEquals(List.of("", ""), ctor.apiKeys, "no apiKeyEnv → blank credential without consulting env");
    }

    // ------------------------------------------------------------- test doubles

    /** Stub {@link ProviderAdapter}: records construction args, never called for I/O. */
    private static final class StubAdapter implements ProviderAdapter {

        private final String name;
        private final String baseUrl;
        private final String apiKey;

        StubAdapter(String name, String baseUrl, String apiKey) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String baseUrl() {
            return baseUrl;
        }

        @Override
        public ProviderAuth auth() {
            return new ProviderAuth(ProviderAuth.TYPE_BEARER, apiKey);
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            throw new AssertionError("builder tests never call the adapter");
        }

        @Override
        public Stream<StreamChunk> stream(ChatRequest request) {
            throw new AssertionError("builder tests never call the adapter");
        }
    }

    /**
     * Fake {@link ModelListFactory.ProviderConstructor}: records args (including the
     * {@link JanusConfig.TimeoutsConfig} threaded per call — the capture), returns
     * stubs mirroring production naming — the constructor switch key, EXCEPT for the
     * generic {@code openai-compatible} family, where the stub takes the entry's
     * provider name (what {@link ChatBackend#name} yields in production),
     * so grouping tests can pin per-entry resolution and the in-group uniqueness rule.
     */
    private static final class RecordingConstructor implements ModelListFactory.ProviderConstructor {

        final List<String> providers = new ArrayList<>();
        final List<String> baseUrls = new ArrayList<>();
        final List<String> apiKeys = new ArrayList<>();
        final List<JanusConfig.TimeoutsConfig> timeouts = new ArrayList<>();

        @Override
        public ProviderAdapter create(
                String providerName,
                String constructorKey,
                String baseUrl,
                String apiKey,
                JanusConfig.TimeoutsConfig timeouts) {
            providers.add(constructorKey);
            baseUrls.add(baseUrl);
            apiKeys.add(apiKey);
            this.timeouts.add(timeouts);
            String backendName = OpenAiCompatibleAdapter.NAME.equals(constructorKey) ? providerName : constructorKey;
            return new StubAdapter(backendName, baseUrl, apiKey);
        }
    }
}
