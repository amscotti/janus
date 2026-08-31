package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * step 2: {@link Router} behavior — construction validation + defensive copy, exact
 * route lookup, typed unknown-model failure, delegate-through complete/stream, stream
 * pass-through by identity (close propagates), models listing.
 */
class RouterTest {

    private static ChatRequest request(String model) {
        return new ChatRequest(
                model, List.of(), null, // system
                null, // tools
                null, // toolChoice
                null, // temperature
                null, // topP
                null, // topK
                null, // maxTokens
                null, // stop
                null, // seed
                null, // n
                null, // frequencyPenalty
                null, // presencePenalty
                null, // logitBias
                null, // responseFormat
                false, // stream
                null, // streamOptions
                null, // reasoning
                null, // cacheControl
                null, // extras
                null); // meta
    }

    private static ChatResponse response(String model) {
        return new ChatResponse(
                "resp-1",
                "chat.completion",
                0L,
                model,
                List.of(),
                null,
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static StreamChunk chunk() {
        return new StreamChunk("chunk-1", "chat.completion.chunk", 0L, "deepseek-v4-flash", List.of(), null, Map.of());
    }

    private static FakeBackend fake(String name) {
        return new FakeBackend(name, response("deepseek-v4-flash"), null);
    }

    private static FakeBackend fake(String name, ChatResponse response) {
        return new FakeBackend(name, response, null);
    }

    private static FakeBackend fake(String name, ChatResponse response, Stream<StreamChunk> stream) {
        return new FakeBackend(name, response, stream);
    }

    // --- construction validation + defensive copy -----------------------------------

    @Test
    void rejectsNullRoutesMap() {
        assertThrows(NullPointerException.class, () -> new Router(null));
    }

    @Test
    void rejectsNullAliasKey() {
        Map<String, ChatBackend> routes = new HashMap<>();
        routes.put(null, fake("deepseek"));
        assertThrows(NullPointerException.class, () -> new Router(routes));
    }

    @Test
    void rejectsNullBackendValue() {
        Map<String, ChatBackend> routes = new HashMap<>();
        routes.put("deepseek-v4-flash", null);
        assertThrows(NullPointerException.class, () -> new Router(routes));
    }

    @Test
    void rejectsBlankAliases() {
        for (String blank : List.of("", " ", "  \t ")) {
            assertThrows(IllegalArgumentException.class, () -> new Router(Map.of(blank, fake("deepseek"))), blank);
        }
    }

    @Test
    void copiesRoutesDefensively() {
        Map<String, ChatBackend> input = new HashMap<>();
        FakeBackend deepseek = fake("deepseek");
        input.put("deepseek-v4-flash", deepseek);
        Router router = new Router(input);
        // Mutate the caller's map after construction — routing must not change.
        input.remove("deepseek-v4-flash");
        input.put("deepseek-v4-flash", fake("other"));
        input.put("deepseek-v4-pro", fake("deepseek"));
        assertSame(deepseek, router.route("deepseek-v4-flash"));
        assertThrows(UnknownModelException.class, () -> router.route("deepseek-v4-pro"));
        assertEquals(Set.of("deepseek-v4-flash"), router.models());
    }

    // --- route ---------------------------------------------------------------------

    @Test
    void routesExactAliasToBackend() {
        FakeBackend deepseek = fake("deepseek");
        FakeBackend anthropic = fake("anthropic");
        Router router =
                new Router(Map.of("deepseek-v4-flash", deepseek, "deepseek-v4-pro", deepseek, "claude-3", anthropic));
        assertSame(deepseek, router.route("deepseek-v4-flash"));
        assertSame(deepseek, router.route("deepseek-v4-pro"));
        assertSame(anthropic, router.route("claude-3"));
    }

    @Test
    void routeIsCaseSensitive() {
        Router router = new Router(Map.of("deepseek-v4-flash", fake("deepseek")));
        assertThrows(UnknownModelException.class, () -> router.route("DeepSeek-Chat"));
    }

    @Test
    void routeUnknownAliasCarriesModelName() {
        Router router = new Router(Map.of("deepseek-v4-flash", fake("deepseek")));
        UnknownModelException e = assertThrows(UnknownModelException.class, () -> router.route("gpt-4"));
        assertEquals("gpt-4", e.model());
        assertTrue(e.getMessage().contains("gpt-4"), e.getMessage());
    }

    @Test
    void routeRejectsNullAndBlankModels() {
        Router router = new Router(Map.of("deepseek-v4-flash", fake("deepseek")));
        assertThrows(IllegalArgumentException.class, () -> router.route(null));
        assertThrows(IllegalArgumentException.class, () -> router.route(""));
        assertThrows(IllegalArgumentException.class, () -> router.route("   "));
    }

    // --- models --------------------------------------------------------------------

    @Test
    void modelsListsAllAliasesUnmodifiable() {
        Router router = new Router(Map.of("deepseek-v4-flash", fake("deepseek"), "deepseek-v4-pro", fake("deepseek")));
        Set<String> models = router.models();
        assertEquals(Set.of("deepseek-v4-flash", "deepseek-v4-pro"), models);
        assertThrows(UnsupportedOperationException.class, () -> models.add("x"));
    }

    // --- complete ------------------------------------------------------------------

    @Test
    void completeDelegatesToResolvedBackend() {
        ChatResponse expected = response("deepseek-v4-flash");
        FakeBackend deepseek = fake("deepseek", expected);
        Router router = new Router(Map.of("deepseek-v4-flash", deepseek));
        ChatRequest request = request("deepseek-v4-flash");
        ChatResponse actual = router.complete(request);
        assertSame(expected, actual);
        assertEquals(1, deepseek.completeCalls.size());
        assertSame(request, deepseek.completeCalls.get(0)); // meta passthrough by identity
    }

    @Test
    void completeRoutesToTheModelInTheRequest() {
        ChatResponse expected = response("deepseek-v4-pro");
        FakeBackend deepseek = fake("deepseek", expected);
        Router router = new Router(Map.of("deepseek-v4-flash", deepseek, "deepseek-v4-pro", deepseek));
        assertSame(expected, router.complete(request("deepseek-v4-pro")));
    }

    @Test
    void completeRejectsNullRequest() {
        Router router = new Router(Map.of("deepseek-v4-flash", fake("deepseek")));
        assertThrows(NullPointerException.class, () -> router.complete(null));
    }

    @Test
    void completeRejectsNullAndBlankRequestModels() {
        Router router = new Router(Map.of("deepseek-v4-flash", fake("deepseek")));
        assertThrows(IllegalArgumentException.class, () -> router.complete(request(null)));
        assertThrows(IllegalArgumentException.class, () -> router.complete(request(" ")));
    }

    @Test
    void completeUnknownModelThrows() {
        Router router = new Router(Map.of("deepseek-v4-flash", fake("deepseek")));
        UnknownModelException e = assertThrows(UnknownModelException.class, () -> router.complete(request("gpt-4")));
        assertEquals("gpt-4", e.model());
    }

    // --- stream --------------------------------------------------------------------

    @Test
    void streamReturnsBackendStreamByIdentityAndClosePropagates() {
        FakeBackend deepseek = fake("deepseek", response("deepseek-v4-flash"), Stream.of(chunk()));
        Router router = new Router(Map.of("deepseek-v4-flash", deepseek));
        ChatRequest request = request("deepseek-v4-flash");
        Stream<StreamChunk> routed = router.stream(request);
        assertSame(deepseek.stream, routed); // no wrap layer: pass-through by identity
        assertEquals(1, deepseek.streamCalls.size());
        assertSame(request, deepseek.streamCalls.get(0));
        assertFalse(deepseek.streamClosed.get());
        routed.close();
        assertTrue(deepseek.streamClosed.get()); // close-releases-connection contract preserved
    }

    @Test
    void streamResolvesRouteEagerly() {
        FakeBackend deepseek = fake("deepseek", response("deepseek-v4-flash"), Stream.of(chunk()));
        Router router = new Router(Map.of("deepseek-v4-flash", deepseek));
        assertThrows(UnknownModelException.class, () -> router.stream(request("gpt-4")));
        assertTrue(deepseek.streamCalls.isEmpty()); // resolved before any backend call
    }

    @Test
    void streamRejectsNullAndBlankRequestModels() {
        Router router = new Router(Map.of("deepseek-v4-flash", fake("deepseek")));
        assertThrows(IllegalArgumentException.class, () -> router.stream(request(null)));
        assertThrows(IllegalArgumentException.class, () -> router.stream(request("")));
    }
}
