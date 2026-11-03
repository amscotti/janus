package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.JanusConfig.DecisionModelListEntry;
import io.amscotti.janus.JanusConfig.DecisionProviderEntry;
import io.amscotti.janus.core.codec.DecisionCodecException;
import io.amscotti.janus.core.model.DecisionQuestion;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.provider.DecisionAdapter;
import io.amscotti.janus.provider.OpenAiDecisionsAdapter;
import io.amscotti.janus.provider.ProviderAuth;
import io.amscotti.janus.router.DecisionBackend;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Decision route construction: alias grouping, block-default merge, provider
 * resolution (registered name vs wire-format fallback), the upstream-model
 * remap, and the vision gate — with a recording fake constructor, no HTTP.
 */
class DecisionModelListFactoryTest {

    private static final class RecordingAdapter implements DecisionAdapter {
        private final String name;
        private final String baseUrl;
        private final String secret;
        final AtomicReference<DecisionRequest> lastRequest = new AtomicReference<>();

        RecordingAdapter(String name, String baseUrl, String secret) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.secret = secret;
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
            return new ProviderAuth(ProviderAuth.TYPE_BEARER, secret);
        }

        @Override
        public io.amscotti.janus.core.model.DecisionResponse decide(DecisionRequest request) {
            lastRequest.set(request);
            return new io.amscotti.janus.core.model.DecisionResponse(
                    request.model(), Map.of(), null, Map.of(), request.meta());
        }
    }

    private final AtomicReference<RecordingAdapter> built = new AtomicReference<>();
    private final AtomicReference<String> builtKey = new AtomicReference<>();
    private final AtomicReference<JanusConfig.TimeoutsConfig> builtTimeouts = new AtomicReference<>();

    private DecisionModelListFactory.DecisionProviderConstructor recording() {
        return (providerName, wireFormatKey, baseUrl, apiKey, timeouts) -> {
            builtKey.set(wireFormatKey);
            builtTimeouts.set(timeouts);
            String backendName = JanusConfig.WIRE_FORMAT_OPENAI_DECISIONS.equals(wireFormatKey)
                    ? OpenAiDecisionsAdapter.NAME
                    : providerName;
            RecordingAdapter adapter = new RecordingAdapter(backendName, baseUrl, apiKey);
            built.set(adapter);
            return adapter;
        };
    }

    private static JanusConfig.TimeoutsConfig defaults() {
        return JanusConfig.TimeoutsConfig.DEFAULTS;
    }

    @Test
    void blockDefaultsMergeUnderEntryAndRemapApplies() {
        DecisionModelListEntry entry =
                new DecisionModelListEntry("clef", "cloudflare", null, null, "clef:cloud", true, null, 30, null);
        Map<String, List<DecisionBackend>> routes = DecisionModelListFactory.buildDecisionRoutes(
                List.of(entry),
                Map.of(
                        "cloudflare",
                        new DecisionProviderEntry("systemone", "https://ollama.example", "CF_TOKEN", null, 99, null)),
                env -> "CF_TOKEN".equals(env) ? "sekret" : null,
                defaults(),
                recording());

        DecisionBackend backend = routes.get("clef").get(0);
        assertEquals("cloudflare", backend.name());
        assertEquals("https://ollama.example", backend.baseUrl());
        assertEquals("sekret", built.get().auth().secret());
        // entry header timeout (30) wins over block (99); others fall to defaults
        assertEquals(30, builtTimeouts.get().headerTimeoutSeconds());
        assertEquals(10, builtTimeouts.get().connectTimeoutSeconds());

        var response = backend.decide(new DecisionRequest(
                "clef", "s", null, Map.of("q", new DecisionQuestion.NoulQuestion("?", null)), Map.of(), Map.of()));
        assertEquals(
                "clef:cloud", built.get().lastRequest.get().model(), "upstream-model remap stamps the outbound model");
        assertEquals("clef", response.model(), "the client response carries the alias, never the upstream remap");
    }

    @Test
    void literalSecretInApiKeyEnvIsNotTreatedAsAnEnvironmentName() {
        DecisionModelListEntry entry =
                new DecisionModelListEntry("m", "p", "sk-live-SECRET-123", null, null, null, null, null, null);
        Map<String, List<DecisionBackend>> routes = DecisionModelListFactory.buildDecisionRoutes(
                List.of(entry),
                Map.of("p", new DecisionProviderEntry("systemone", "https://x.example", null)),
                env -> "should-not-be-used",
                defaults(),
                recording());

        assertEquals("", built.get().auth().secret(), "a literal secret must never be treated as an env-var name");
        assertEquals(1, routes.get("m").size());
    }

    @Test
    void invalidProviderWireFormatFailsBootWithTheBlockName() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> DecisionModelListFactory.buildDecisionRoutes(
                        List.of(new DecisionModelListEntry("m", "my-provider")),
                        Map.of("my-provider", new DecisionProviderEntry("anthropic", "https://x.example", null)),
                        env -> null,
                        defaults(),
                        recording()));
        assertTrue(e.getMessage().contains("my-provider"), e.getMessage());
        assertTrue(e.getMessage().contains("anthropic"), e.getMessage());
    }

    @Test
    void nonPositiveDecisionTimeoutFailsBootInsteadOfBeingDroppedAtBinding() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> DecisionModelListFactory.buildDecisionRoutes(
                        List.of(new DecisionModelListEntry("m", "p", null, null, null, null, 0, null, null)),
                        Map.of(),
                        env -> null,
                        defaults(),
                        recording()));
        assertTrue(e.getMessage().contains("connect-timeout-seconds"), e.getMessage());
    }

    @Test
    void blankDecisionAliasFailsBoot() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> DecisionModelListFactory.buildDecisionRoutes(
                        List.of(new DecisionModelListEntry(" ", "p")), Map.of(), env -> null, defaults(), recording()));
        assertTrue(e.getMessage().contains("name"), e.getMessage());
    }

    @Test
    void unknownProviderWithoutBlockFailsFastListingKnownAdapters() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> DecisionModelListFactory.buildDecisionRoutes(
                        List.of(new DecisionModelListEntry("m", "mystery")),
                        Map.of(),
                        env -> null,
                        defaults(),
                        recording()));
        assertTrue(e.getMessage().contains("systemone"), e.getMessage());
        assertTrue(e.getMessage().contains("openai-decisions"), e.getMessage());
    }

    @Test
    void openAiDecisionsFamilyKeepsItsFixedBackendNameAndVisionGateTrips() {
        DecisionModelListEntry entry = new DecisionModelListEntry("gpt-6-luna", "my-openai");
        Map<String, List<DecisionBackend>> routes = DecisionModelListFactory.buildDecisionRoutes(
                List.of(entry),
                Map.of("my-openai", new DecisionProviderEntry("openai-decisions", "https://api.openai.com", null)),
                env -> null,
                defaults(),
                recording());
        DecisionBackend backend = routes.get("gpt-6-luna").get(0);
        assertEquals(OpenAiDecisionsAdapter.NAME, backend.name());
        assertFalse(backend.supportsImages(), "no vision flag on the entry ⇒ not image-capable");

        DecisionRequest withImage = new DecisionRequest(
                "gpt-6-luna",
                "s",
                List.of("aGVsbG8="),
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of());
        DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> backend.decide(withImage));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type());
        assertFalse(
                e.getMessage().contains("my-openai"),
                "the client-facing gate must not name the operator's backend: " + e.getMessage());
        assertNull(built.get().lastRequest.get(), "the gate fails closed before any dispatch");
    }

    @Test
    void visionFlagCarriesThroughToTheRouterCapabilitySeam() {
        Map<String, List<DecisionBackend>> routes = DecisionModelListFactory.buildDecisionRoutes(
                List.of(new DecisionModelListEntry("clef", "cloudflare", null, null, null, true, null, null, null)),
                Map.of("cloudflare", new DecisionProviderEntry("systemone", "https://x.example", null)),
                env -> null,
                defaults(),
                recording());
        assertTrue(routes.get("clef").get(0).supportsImages(), "vision = true ⇒ image-capable");
    }

    @Test
    void blankEnvSecretBootsWithAnEmptySecretNotAnError() {
        DecisionModelListFactory.buildDecisionRoutes(
                List.of(new DecisionModelListEntry("clef", "cloudflare")),
                Map.of("cloudflare", new DecisionProviderEntry("systemone", "https://x.example", "UNSET_VAR")),
                env -> null, // the warning path: the env var is unset
                defaults(),
                recording());
        assertEquals(
                "", built.get().auth().secret(), "an unset env var boots with an empty secret (warned, not fatal)");
    }

    @Test
    void entryBaseUrlBeatsTheBlockAndAllThreeTimeoutsMerge() {
        Map<String, List<DecisionBackend>> routes = DecisionModelListFactory.buildDecisionRoutes(
                List.of(new DecisionModelListEntry(
                        "clef", "cloudflare", null, "https://entry.example", null, null, 3, null, 9)),
                Map.of(
                        "cloudflare",
                        new DecisionProviderEntry("systemone", "https://block.example", "CF_TOKEN", null, 7, null)),
                env -> "sekret",
                defaults(),
                recording());

        assertEquals("https://entry.example", routes.get("clef").get(0).baseUrl(), "entry base-url wins over block");
        // entry > block > global across all three deadlines.
        assertEquals(3, builtTimeouts.get().connectTimeoutSeconds());
        assertEquals(7, builtTimeouts.get().headerTimeoutSeconds());
        assertEquals(9, builtTimeouts.get().bodyReadTimeoutSeconds());
    }

    @Test
    void duplicateBackendNamesWithinOneAliasAreRejected() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> DecisionModelListFactory.buildDecisionRoutes(
                        List.of(
                                new DecisionModelListEntry("m", "systemone"),
                                new DecisionModelListEntry("m", "systemone")),
                        Map.of(),
                        env -> null,
                        defaults(),
                        recording()));
        assertTrue(e.getMessage().contains("\"m\"") && e.getMessage().contains("systemone"), e.getMessage());
    }
}
