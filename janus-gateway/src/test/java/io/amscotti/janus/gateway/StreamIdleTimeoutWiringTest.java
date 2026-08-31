package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.LoadBalancer;
import io.amscotti.janus.router.Router;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The stream-idle deadline wiring, one face at a time: the controller's {@code
 * StreamIdleTimeoutResolver} constructor parameter (supplied by the {@code
 * RouterFactory} producer bean in production; global default from {@code
 * [janus.timeouts] stream-idle-timeout-seconds} + the {@code
 * [janus.providers.<name>]} stream-idle overrides) must resolve <b>per
 * dispatch</b> — after {@code router.stream(...)} returns, from the serving
 * backend the dispatch observer named — into the face's SSE publisher. The
 * response body IS the publisher, so the wiring is observable without
 * subscribing: the publisher's idle watchdog deadline is the resolved value,
 * never a compile-time constant. The config→resolver seam is pinned by {@code
 * RouterFactoryTest}; this pins the dispatch→publisher hand-off on all three
 * faces, the provider-override and global-fallback paths, and the
 * balanced-pick case (the resolver consults the <i>actually dispatched</i>
 * provider, not the alias's config-first candidate).
 */
class StreamIdleTimeoutWiringTest {

    /** Deliberately not 60 — the test must distinguish config from the constant. */
    private static final Duration GLOBAL = Duration.ofSeconds(9);

    /** Deliberately not GLOBAL — distinguishes the provider override from the global. */
    private static final Duration OLLAMA_OVERRIDE = Duration.ofSeconds(17);

    /** The shape: no overrides ⇒ every request gets the global (byte-identical). */
    private static StreamIdleTimeoutResolver globalOnly() {
        return new StreamIdleTimeoutResolver(GLOBAL, Map.of());
    }

    private static StreamIdleTimeoutResolver withOllamaOverride() {
        return new StreamIdleTimeoutResolver(GLOBAL, Map.of("ollama", OLLAMA_OVERRIDE));
    }

    @Test
    void openAiFacePublisherCarriesTheConfiguredIdleTimeout() {
        FakeBackend backend = new FakeBackend("deepseek");
        backend.streamReturns(Stream.of(textChunk()));
        ChatCompletionsController controller = new ChatCompletionsController(
                new Router(Map.of("deepseek-v4-flash", backend)),
                Governance.noop(),
                MetricsRecorder.noop(),
                globalOnly());

        HttpResponse<?> response = controller.chat(
                "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"stream\":true}",
                request());

        SseChunkPublisher publisher = (SseChunkPublisher) response.body();
        assertEquals(GLOBAL.toNanos(), publisher.idleTimeoutNanos, "the OpenAI face watchdog carries the config");
    }

    @Test
    void anthropicFacePublisherCarriesTheConfiguredIdleTimeout() {
        FakeBackend backend = new FakeBackend("anthropic");
        backend.streamReturns(Stream.of(textChunk()));
        MessagesController controller = new MessagesController(
                new Router(Map.of("claude-3", backend)), Governance.noop(), MetricsRecorder.noop(), globalOnly());

        HttpResponse<?> response = controller.messages(
                "{\"model\":\"claude-3\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"max_tokens\":64,\"stream\":true}",
                request());

        AnthropicSsePublisher publisher = (AnthropicSsePublisher) response.body();
        assertEquals(GLOBAL.toNanos(), publisher.idleTimeoutNanos, "the Anthropic face watchdog carries the config");
    }

    @Test
    void responsesFacePublisherCarriesTheConfiguredIdleTimeout() {
        FakeBackend backend = new FakeBackend("deepseek");
        backend.streamReturns(Stream.of(textChunk()));
        ResponsesController controller = new ResponsesController(
                new Router(Map.of("deepseek-v4-flash", backend)),
                Governance.noop(),
                MetricsRecorder.noop(),
                globalOnly());

        HttpResponse<?> response =
                controller.responses("{\"model\":\"deepseek-v4-flash\",\"input\":\"hi\",\"stream\":true}", request());

        ResponsesSsePublisher publisher = (ResponsesSsePublisher) response.body();
        assertEquals(GLOBAL.toNanos(), publisher.idleTimeoutNanos, "the Responses face watchdog carries the config");
    }

    @Test
    void dispatchedOverridingProviderCarriesItsOwnIdleTimeout() {
        // per-dispatch: a request served by a provider whose block overrides
        // stream-idle gets THAT provider's deadline in the publisher.
        FakeBackend backend = new FakeBackend("ollama");
        backend.streamReturns(Stream.of(textChunk()));
        ChatCompletionsController controller = new ChatCompletionsController(
                new Router(Map.of("llama3", backend)), Governance.noop(), MetricsRecorder.noop(), withOllamaOverride());

        HttpResponse<?> response = controller.chat(
                "{\"model\":\"llama3\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}",
                request());

        SseChunkPublisher publisher = (SseChunkPublisher) response.body();
        assertEquals(
                OLLAMA_OVERRIDE.toNanos(),
                publisher.idleTimeoutNanos,
                "the dispatched provider's override wins over the global");
    }

    @Test
    void dispatchedNonOverridingProviderGetsTheGlobalIdleTimeout() {
        // Same resolver, a provider with no override: the global fallback
        // (single-provider aliases and no-override configs stay byte-identical).
        FakeBackend backend = new FakeBackend("deepseek");
        backend.streamReturns(Stream.of(textChunk()));
        ChatCompletionsController controller = new ChatCompletionsController(
                new Router(Map.of("deepseek-v4-flash", backend)),
                Governance.noop(),
                MetricsRecorder.noop(),
                withOllamaOverride());

        HttpResponse<?> response = controller.chat(
                "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"stream\":true}",
                request());

        SseChunkPublisher publisher = (SseChunkPublisher) response.body();
        assertEquals(
                GLOBAL.toNanos(),
                publisher.idleTimeoutNanos,
                "a non-overriding provider falls back to the global deadline");
    }

    @Test
    void balancedPickResolvesTheActuallyDispatchedProviderNotTheFirstCandidate() {
        // Per-DISPATCH, not per-alias: a two-candidate alias whose strategy picks
        // the SECOND backend (route names the first — the dispatched-provider
        // seam pattern from GovernanceDispatchedProviderTest) must resolve the
        // picked backend's override; a per-alias or config-first resolution would
        // return the global here.
        FakeBackend first = new FakeBackend("deepseek");
        FakeBackend second = new FakeBackend("ollama");
        second.streamReturns(Stream.of(textChunk()));
        Router router = Router.balanced(Map.of("chat", List.of(first, second)), new LastPickLoadBalancer());
        ChatCompletionsController controller =
                new ChatCompletionsController(router, Governance.noop(), MetricsRecorder.noop(), withOllamaOverride());

        HttpResponse<?> response = controller.chat(
                "{\"model\":\"chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}",
                request());

        assertEquals(1, second.streamCalls.size(), "the strategy's pick served the stream");
        SseChunkPublisher publisher = (SseChunkPublisher) response.body();
        assertEquals(
                OLLAMA_OVERRIDE.toNanos(),
                publisher.idleTimeoutNanos,
                "the idle deadline follows the dispatched backend, not route()'s first candidate");
    }

    // ------------------------------------------------------------------ helpers

    private static HttpRequest<?> request() {
        return HttpRequest.POST("/v1/chat/completions", "").contentType(MediaType.APPLICATION_JSON);
    }

    private static StreamChunk textChunk() {
        return new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "Hello", null), null)),
                null,
                Map.of());
    }

    /** A strategy that always picks the LAST candidate — route() (the first) disagrees. */
    private static final class LastPickLoadBalancer implements LoadBalancer {
        @Override
        public String name() {
            return "test-last-pick";
        }

        @Override
        public ChatBackend pick(String model, List<ChatBackend> candidates) {
            return candidates.getLast();
        }
    }
}
