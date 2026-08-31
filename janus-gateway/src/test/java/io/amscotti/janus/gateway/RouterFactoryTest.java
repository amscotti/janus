package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.JanusConfig.ModelListEntry;
import io.amscotti.janus.JanusConfig.TimeoutsConfig;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.provider.AnthropicAdapter;
import io.amscotti.janus.provider.DeepSeekAdapter;
import io.amscotti.janus.provider.OpenAiCompatibleAdapter;
import io.amscotti.janus.provider.ProviderAdapter;
import io.amscotti.janus.provider.ProviderAuth;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code [janus.timeouts]} resolution and threading — the factory seams, pure
 * JVM (no Micronaut, no network):
 *
 * <ul>
 * <li>{@link RouterFactory#resolve} null-tolerance: an absent section (null record)
 * ⇒ {@link TimeoutsConfig#DEFAULTS}; a present section fills only null components
 * from the defaults (the {@code LoadBalancerFactory.resolve} pattern);</li>
 * <li>the fake-constructor capture pattern (the {@code RecordingConstructor} shape
 * from {@code ModelListFactoryTest}): the resolved config threads to <b>every</b>
 * {@code ProviderConstructor.create} call — absent section ⇒ (10 s, 60 s, 300 s,
 * 60 s) on every leg including DeepSeek; a configured section ⇒ the exact values;</li>
 * <li>{@link RouterFactory#createProvider} constructs all three adapter families
 * through the public timeout-aware constructors (the resolved {@code Duration}s are
 * the constructor arguments — the fake seam above pins the values, and the adapters'
 * timeout accessors pin them on the real constructed legs);</li>
 * <li>the stream-idle producer seam: {@link RouterFactory#streamIdleTimeout}
 * resolves the SSE watchdog deadline from the section;</li>
 * <li>the entry-level stream-idle boot warning seam: {@link
 * RouterFactory#warnAboutEntryStreamIdleTimeouts} — the {@code
 * warnAboutWeights} pattern (dead config must WARN at boot, not stay
 * silent);</li>
 * <li>the per-provider stream-idle override keying: overrides key the
 * <b>resolved backend name</b> ({@link RouterFactory#providerStreamIdleOverrides})
 * — an anthropic-family fallback block's override reaches the dispatch observer's
 * fixed {@code "anthropic"} name, and dead/ambiguous overrides warn at boot.</li>
 * </ul>
 */
class RouterFactoryTest {

    private static ModelListEntry entry(String name, String provider, String apiKeyEnv, String baseUrl) {
        return new ModelListEntry(name, provider, apiKeyEnv, baseUrl);
    }

    // ------------------------------------------------------------- resolution

    @Test
    void absentTimeoutsSectionResolvesTheDocumentedDefaults() {
        assertEquals(new TimeoutsConfig(10, 60, 300, 60), RouterFactory.resolve(null));
    }

    @Test
    void nullTimeoutComponentsFillFromTheDefaults() {
        // A present-but-partial section: only the configured key wins, every null
        // component fills from DEFAULTS (the LoadBalancerFactory.resolve pattern) —
        // per-key granularity, never all-or-nothing.
        assertEquals(
                new TimeoutsConfig(10, 7, 300, 60), RouterFactory.resolve(new TimeoutsConfig(null, 7, null, null)));
        assertEquals(new TimeoutsConfig(3, 60, 300, 9), RouterFactory.resolve(new TimeoutsConfig(3, null, null, 9)));
    }

    @Test
    void resolveIsIdempotentOnAResolvedSection() {
        TimeoutsConfig configured = new TimeoutsConfig(3, 7, 120, 9);
        assertEquals(configured, RouterFactory.resolve(configured));
    }

    // ---------------------------------------------- constructor-call threading

    @Test
    void absentSectionThreadsTheDefaultDeadlinesToEveryConstructorCall() {
        RecordingConstructor ctor = new RecordingConstructor();
        ModelListFactory.buildBackendLists(
                List.of(entry("chat", "deepseek", null, null), entry("chat", "anthropic", null, null)),
                Map.of(),
                name -> "",
                RouterFactory.resolve(null),
                ctor);
        assertEquals(
                List.of(new TimeoutsConfig(10, 60, 300, 60), new TimeoutsConfig(10, 60, 300, 60)),
                ctor.timeouts,
                "absent [janus.timeouts] ⇒ (10 s, 60 s, 300 s) + 60 s idle on every leg");
        assertEquals(List.of("deepseek", "anthropic"), ctor.providers, "the DeepSeek leg included");
    }

    @Test
    void configuredSectionThreadsExactValuesToEveryConstructorCall() {
        RecordingConstructor ctor = new RecordingConstructor();
        TimeoutsConfig configured = new TimeoutsConfig(3, 7, 120, 9);
        ModelListFactory.buildBackendLists(
                List.of(
                        entry("chat", "deepseek", null, null),
                        entry("chat", "anthropic", null, null),
                        entry("chat2", "my-ollama", null, "http://localhost:11434")),
                Map.of("my-ollama", new JanusConfig.ProviderEntry("openai-compatible", null, null)),
                name -> "",
                configured,
                ctor);
        assertEquals(
                List.of(configured, configured, configured),
                ctor.timeouts,
                "the exact configured values reach every adapter constructor call");
        assertEquals(
                List.of("deepseek", "anthropic", "openai-compatible"),
                ctor.providers,
                "all three constructor legs captured");
    }

    // ------------------------------------------------- the real creation legs

    @Test
    void createProviderAppliesTimeoutsOnAllThreeAdapterLegs() {
        TimeoutsConfig configured = new TimeoutsConfig(3, 7, 120, 9);
        // Each leg goes through the public timeout-aware constructor: the fake seam
        // above pins the constructor arguments, and the adapters' timeout accessors
        // pin them on the real instances — a leg reverted to the
        // default-timeout constructor fails here, not just on instanceof.
        DeepSeekAdapter deepSeek = assertInstanceOf(
                DeepSeekAdapter.class, RouterFactory.createProvider("deepseek", "deepseek", null, "key", configured));
        assertEquals(Duration.ofSeconds(3), deepSeek.connectTimeout(), "deepseek: configured connect deadline");
        assertEquals(Duration.ofSeconds(7), deepSeek.headerTimeout(), "deepseek: configured header-arrival deadline");
        assertEquals(Duration.ofSeconds(120), deepSeek.bodyReadTimeout(), "deepseek: configured body-read deadline");
        assertEquals(DeepSeekAdapter.DEFAULT_BASE_URL, deepSeek.baseUrl());
        assertEquals(ProviderAuth.TYPE_BEARER, deepSeek.auth().type());

        OpenAiCompatibleAdapter openAi = assertInstanceOf(
                OpenAiCompatibleAdapter.class,
                RouterFactory.createProvider(
                        "my-ollama", "openai-compatible", "http://localhost:11434", "k", configured));
        assertEquals("my-ollama", openAi.name(), "the generic adapter carries the entry's provider name");
        assertEquals(Duration.ofSeconds(3), openAi.connectTimeout(), "openai-compatible: configured connect deadline");
        assertEquals(Duration.ofSeconds(7), openAi.headerTimeout(), "openai-compatible: header-arrival deadline");
        assertEquals(Duration.ofSeconds(120), openAi.bodyReadTimeout(), "openai-compatible: body-read deadline");

        AnthropicAdapter anthropic = assertInstanceOf(
                AnthropicAdapter.class,
                RouterFactory.createProvider("anthropic", "anthropic", null, "sk-ant", configured));
        assertEquals(Duration.ofSeconds(3), anthropic.connectTimeout(), "anthropic: configured connect deadline");
        assertEquals(Duration.ofSeconds(7), anthropic.headerTimeout(), "anthropic: header-arrival deadline");
        assertEquals(Duration.ofSeconds(120), anthropic.bodyReadTimeout(), "anthropic: body-read deadline");
        assertEquals(AnthropicAdapter.DEFAULT_BASE_URL, anthropic.baseUrl());
        assertEquals(ProviderAuth.TYPE_X_API_KEY, anthropic.auth().type());
    }

    @Test
    void createProviderTreatsANullSectionAsTheDefaults() {
        // Direct-call convenience: createProvider resolves null itself, so a caller
        // that threads the raw (absent) section gets DEFAULTS, not an NPE.
        DeepSeekAdapter deepSeek = assertInstanceOf(
                DeepSeekAdapter.class, RouterFactory.createProvider("deepseek", "deepseek", null, "key", null));
        assertEquals(Duration.ofSeconds(10), deepSeek.connectTimeout(), "DEFAULTS connect deadline (10 s)");
        assertEquals(Duration.ofSeconds(60), deepSeek.headerTimeout(), "DEFAULTS header-arrival deadline (60 s)");
        assertEquals(Duration.ofSeconds(300), deepSeek.bodyReadTimeout(), "DEFAULTS body-read deadline (300 s)");
    }

    // --------------------------------------------------- the stream-idle seam

    @Test
    void streamIdleTimeoutResolvesFromTheSection() {
        assertEquals(Duration.ofSeconds(9), RouterFactory.streamIdleTimeout(new TimeoutsConfig(3, 7, 120, 9)));
        assertEquals(Duration.ofSeconds(60), RouterFactory.streamIdleTimeout(null));
        assertEquals(
                Duration.ofSeconds(60),
                RouterFactory.streamIdleTimeout(new TimeoutsConfig(3, 7, 120, null)),
                "an absent stream-idle key fills from DEFAULTS");
    }

    // ---------------------------------------------- per-provider overrides

    @Test
    void providerBlockOverrideThreadsTheResolvedTupleToTheAdapter() {
        // adapter threading: a backend whose provider block overrides only
        // header-timeout-seconds gets the resolved (global connect, 120, global
        // body-read) tuple on the REAL adapter — pinned via the public timeout
        // accessors, through the real ModelListFactory merge
        // and the real createProvider switch.
        List<ProviderAdapter> created = new java.util.ArrayList<>();
        TimeoutsConfig global = new TimeoutsConfig(11, 12, 130, 13);
        ModelListFactory.buildBackendLists(
                List.of(entry("chat", "my-ollama", null, "http://localhost:11434")),
                Map.of(
                        "my-ollama",
                        new JanusConfig.ProviderEntry("openai-compatible", null, null, null, 120, null, null)),
                name -> "",
                global,
                (providerName, constructorKey, baseUrl, apiKey, timeouts) -> {
                    ProviderAdapter adapter =
                            RouterFactory.createProvider(providerName, constructorKey, baseUrl, apiKey, timeouts);
                    created.add(adapter);
                    return adapter;
                });
        OpenAiCompatibleAdapter adapter = assertInstanceOf(OpenAiCompatibleAdapter.class, created.getFirst());
        assertEquals(Duration.ofSeconds(11), adapter.connectTimeout(), "global connect fills the block's null");
        assertEquals(Duration.ofSeconds(120), adapter.headerTimeout(), "the block's header override wins");
        assertEquals(Duration.ofSeconds(130), adapter.bodyReadTimeout(), "global body-read fills the block's null");
    }

    @Test
    void streamIdleResolverCarriesTheGlobalDefaultAndProviderOverrides() {
        // The resolver bean's factory seam: global default from [janus.timeouts]
        // stream-idle, overrides from the [janus.providers.<name>] blocks that set
        // stream-idle-timeout-seconds (blocks without it contribute nothing), keyed
        // by the resolved backend name — here the openai-compatible family, whose
        // fallback backends dispatch under the entry's own provider name.
        StreamIdleTimeoutResolver resolver = RouterFactory.streamIdleTimeoutResolver(
                new TimeoutsConfig(11, 12, 130, 9),
                Map.of(
                        "ollama", new JanusConfig.ProviderEntry("openai-compatible", null, null, null, null, null, 17),
                        "deepseek", new JanusConfig.ProviderEntry(null, null, null, null, null, null, null)),
                List.of(entry("chat", "ollama", null, "http://localhost:11434")));
        assertEquals(Duration.ofSeconds(17), resolver.resolve("ollama"), "the overriding provider's deadline");
        assertEquals(Duration.ofSeconds(9), resolver.resolve("deepseek"), "a no-override block falls back to global");
        assertEquals(
                Duration.ofSeconds(9), resolver.resolve("unknown-provider"), "an unconfigured provider falls back");
        assertEquals(Duration.ofSeconds(9), resolver.resolve(null), "undispatched (null holder) ⇒ the global");
    }

    @Test
    void anthropicFamilyOverrideKeysTheResolvedBackendNameNotTheBlockKey() {
        // The fixed-name-family re-keying: [janus.providers.my-claude] with
        // wire-format = "anthropic" builds backends whose ChatBackend.name is the
        // fixed "anthropic" (the dispatch observer's value) — keying the override by
        // the block key left the documented override silently dead (the global
        // applied). The override now keys the resolved backend name.
        StreamIdleTimeoutResolver resolver = RouterFactory.streamIdleTimeoutResolver(
                new TimeoutsConfig(11, 12, 130, 60),
                Map.of("my-claude", new JanusConfig.ProviderEntry("anthropic", null, null, null, null, null, 45)),
                List.of(entry("claude", "my-claude", null, null)));
        assertEquals(
                Duration.ofSeconds(45),
                resolver.resolve("anthropic"),
                "the anthropic-family override applies under the family's fixed backend name");
        assertEquals(
                Duration.ofSeconds(60),
                resolver.resolve("my-claude"),
                "the block key never dispatches — no override keys it");
        assertEquals(Duration.ofSeconds(60), resolver.resolve(null), "undispatched (null holder) ⇒ the global");
    }

    @Test
    void anthropicFamilyOverrideBootWarningNamesTheSharedBackendName() {
        // The warnAboutWeights posture: a fixed-name-family override that keys a
        // name other than the block key reaches every sibling backend of that name —
        // WARN at boot, naming both names, instead of staying silent.
        List<String> warnings = new java.util.ArrayList<>();
        RouterFactory.providerStreamIdleOverrides(
                Map.of("my-claude", new JanusConfig.ProviderEntry("anthropic", null, null, null, null, null, 45)),
                List.of(entry("claude", "my-claude", null, null)),
                warnings);
        assertEquals(1, warnings.size(), "one warning: the shared-key note");
        assertTrue(warnings.getFirst().contains("[janus.providers.my-claude]"), "names the block: " + warnings);
        assertTrue(warnings.getFirst().contains("\"anthropic\""), "names the resolved backend name: " + warnings);
    }

    @Test
    void collidingFamilyOverridesWarnAndKeepTheFirstInConfigOrder() {
        // Two anthropic-family blocks with different stream-idle values both key the
        // same fixed backend name "anthropic" — ambiguous: the first block in config
        // order wins and a boot warning names the collision.
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, JanusConfig.ProviderEntry> blocks = new java.util.LinkedHashMap<>();
        blocks.put("my-claude", new JanusConfig.ProviderEntry("anthropic", null, null, null, null, null, 45));
        blocks.put("claude-proxy", new JanusConfig.ProviderEntry("anthropic", null, null, null, null, null, 90));
        Map<String, Duration> overrides = RouterFactory.providerStreamIdleOverrides(
                blocks,
                List.of(entry("claude", "my-claude", null, null), entry("claude-alt", "claude-proxy", null, null)),
                warnings);
        assertEquals(Duration.ofSeconds(45), overrides.get("anthropic"), "the first block in config order wins");
        assertEquals(
                1,
                warnings.stream()
                        .filter(w -> w.contains("collides with [janus.providers.my-claude]"))
                        .count(),
                "the collision warning names both blocks: " + warnings);
        assertEquals(3, warnings.size(), "the shared-key note per fixed-name block + the collision: " + warnings);
    }

    @Test
    void orphanBlockOverrideWarnsThatItKeysNoBackend() {
        // Dead config: a block's stream-idle override with no [[janus.model-list]]
        // entry referencing the provider keys nothing — warn (never silent).
        List<String> warnings = new java.util.ArrayList<>();
        Map<String, Duration> overrides = RouterFactory.providerStreamIdleOverrides(
                Map.of("ollama", new JanusConfig.ProviderEntry("openai-compatible", null, null, null, null, null, 17)),
                List.of(entry("chat", "deepseek", null, null)),
                warnings);
        assertTrue(overrides.isEmpty(), "no entry references the block ⇒ no override is keyed");
        assertEquals(1, warnings.size(), "the dead override warns");
        assertTrue(warnings.getFirst().contains("no [[janus.model-list]] entry references"), "names the cause");
    }

    @Test
    void quietWhenNoBlockSetsTheOverrideKey() {
        List<String> warnings = new java.util.ArrayList<>();
        assertTrue(
                RouterFactory.providerStreamIdleOverrides(null, null, warnings).isEmpty(), "no blocks ⇒ no overrides");
        assertTrue(
                RouterFactory.providerStreamIdleOverrides(Map.of(), List.of(), warnings)
                        .isEmpty(),
                "empty blocks ⇒ no overrides");
        RouterFactory.providerStreamIdleOverrides(
                Map.of("ollama", new JanusConfig.ProviderEntry("openai-compatible", null, null)),
                List.of(entry("chat", "ollama", null, null)),
                warnings);
        assertTrue(warnings.isEmpty(), "a block without the key contributes nothing — quiet");
    }

    @Test
    void absentSectionsResolveToThePinnedGlobalOnly() {
        // No [janus.timeouts], no provider blocks: every dispatch resolves the
        // pinned 60 s constant — the no-override boot is byte-identical.
        StreamIdleTimeoutResolver resolver = RouterFactory.streamIdleTimeoutResolver(null, null, null);
        assertEquals(Duration.ofSeconds(60), resolver.resolve("ollama"));
        assertEquals(Duration.ofSeconds(60), resolver.resolve(null));
    }

    // ------------------------------------------ entry stream-idle warnings

    @Test
    void entryStreamIdleKeyWarnsPointingAtTheProviderBlock() {
        // Dead-config anti-footgun (the warnAboutWeights/warnAboutIgnoredWeights
        // style): an entry-level stream-idle-timeout-seconds is legal but ignored
        // by the SSE watchdog (it resolves by provider) — the boot warning names
        // the entry and points at the provider block where the key belongs.
        List<String> warnings = RouterFactory.warnAboutEntryStreamIdleTimeouts(
                List.of(new ModelListEntry("chat", "ollama", null, null, null, null, null, 30)));
        assertEquals(1, warnings.size(), "an entry setting the key → one warning");
        assertTrue(warnings.getFirst().contains("chat"), "the warning names the entry: " + warnings);
        assertTrue(
                warnings.getFirst().contains("[janus.providers.ollama]"), "points at the provider block: " + warnings);
        assertTrue(warnings.getFirst().contains("ignored"), "the warning says the key is ignored here: " + warnings);
    }

    @Test
    void entryStreamIdleWarningQuietWhenNoEntrySetsTheKey() {
        assertTrue(
                RouterFactory.warnAboutEntryStreamIdleTimeouts(
                                List.of(new ModelListEntry("chat", "ollama", null, null)))
                        .isEmpty(),
                "entries without the key → quiet");
        assertTrue(RouterFactory.warnAboutEntryStreamIdleTimeouts(List.of()).isEmpty(), "empty list → quiet");
        assertTrue(RouterFactory.warnAboutEntryStreamIdleTimeouts(null).isEmpty(), "absent list → quiet");
    }

    // ------------------------------------------------------------- test doubles

    /** Stub {@link ProviderAdapter}: records construction args, never called for I/O. */
    private static final class StubAdapter implements ProviderAdapter {

        private final String name;

        StubAdapter(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String baseUrl() {
            return "http://fake/" + name;
        }

        @Override
        public ProviderAuth auth() {
            return new ProviderAuth(ProviderAuth.TYPE_BEARER, "");
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            throw new AssertionError("factory tests never call the adapter");
        }

        @Override
        public Stream<StreamChunk> stream(ChatRequest request) {
            throw new AssertionError("factory tests never call the adapter");
        }
    }

    /**
     * Fake {@link ModelListFactory.ProviderConstructor}: records args (including the
     * {@link TimeoutsConfig} threaded per call — the capture), returns stubs
     * mirroring production naming (the constructor switch key, except the generic
     * {@code openai-compatible} family, which takes the entry's provider name).
     */
    private static final class RecordingConstructor implements ModelListFactory.ProviderConstructor {

        final List<String> providers = new java.util.ArrayList<>();
        final List<TimeoutsConfig> timeouts = new java.util.ArrayList<>();

        @Override
        public ProviderAdapter create(
                String providerName, String constructorKey, String baseUrl, String apiKey, TimeoutsConfig timeouts) {
            providers.add(constructorKey);
            this.timeouts.add(timeouts);
            String backendName = OpenAiCompatibleAdapter.NAME.equals(constructorKey) ? providerName : constructorKey;
            return new StubAdapter(backendName);
        }
    }
}
