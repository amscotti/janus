package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.OpenAiCodecException;
import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.LoadBalancer;
import io.amscotti.janus.router.Router;
import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.CallStatus;
import io.amscotti.janus.store.FixedWindowRateLimiter;
import io.amscotti.janus.store.InMemoryCallStore;
import io.amscotti.janus.store.InMemorySpendLedger;
import io.amscotti.janus.store.PriceTable;
import io.amscotti.janus.store.PricingRate;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The call ledger's {@code provider} column is fed from the <b>actually-dispatched
 * backend</b> — the router's dispatch observer captured at the dispatch seam — never a
 * re-resolve through {@code Router.route} (which is deliberately the config-first
 * candidate in balanced mode and would attribute every call to the wrong provider in
 * exactly the multi-provider-alias configs the balancer exists for). Direct controller
 * construction over a balanced router whose strategy always picks the SECOND candidate,
 * so {@code route} and the pick disagree by construction.
 */
class GovernanceDispatchedProviderTest {

    private static final Clock CLOCK = TestKeyAuthFactory.CLOCK;
    private static final String ALIAS = "deepseek-v4-flash";

    /** Global-only resolver (the no-override shape — deadlines are not this test's subject). */
    private static final StreamIdleTimeoutResolver IDLE =
            new StreamIdleTimeoutResolver(Duration.ofSeconds(60), Map.of());

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

    private record Wiring(Router router, FakeBackend first, FakeBackend second, InMemoryCallStore calls) {}

    private static Wiring wiring() {
        FakeBackend first = new FakeBackend("deepseek");
        FakeBackend second = new FakeBackend("kimi");
        Router router = Router.balanced(Map.of(ALIAS, List.of(first, second)), new LastPickLoadBalancer());
        InMemoryCallStore calls = new InMemoryCallStore(CLOCK, 1000);
        return new Wiring(router, first, second, calls);
    }

    private static Governance governance(InMemoryCallStore calls) {
        return new Governance(
                new FixedWindowRateLimiter(CLOCK),
                PriceTable.of(Map.of(ALIAS, new PricingRate(0.14, 0.28, 0.0, 0.0, 4096))),
                new InMemorySpendLedger(CLOCK, 1000),
                new RecordingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                calls);
    }

    @Test
    void nonStreamingOkRowCarriesThePickedBackendNotRoute() {
        Wiring w = wiring();
        w.second.completeReturns(chatResponse());
        ChatCompletionsController controller =
                new ChatCompletionsController(w.router(), governance(w.calls()), MetricsRecorder.noop(), IDLE);

        HttpResponse<?> response = controller.chat(body(false), request());

        assertEquals(200, response.getStatus().getCode());
        assertEquals("deepseek", w.router().route(ALIAS).name(), "route() is the config-first candidate");
        assertEquals(1, w.second.completeCalls.size(), "the strategy's pick served the request");
        assertTrue(w.first.completeCalls.isEmpty(), "the config-first candidate was never dispatched");

        List<CallRecord> records = w.calls().recentCalls(10);
        assertEquals(1, records.size());
        assertEquals("kimi", records.get(0).provider(), "the OK row names the backend that actually served");
        assertEquals(CallStatus.OK, records.get(0).status());
    }

    @Test
    void streamOkRowCarriesThePickedBackend() throws InterruptedException {
        Wiring w = wiring();
        w.second.streamReturns(Stream.of(textChunk("Hello"), usageChunk(new Usage(10, 5, 15))));
        ChatCompletionsController controller =
                new ChatCompletionsController(w.router(), governance(w.calls()), MetricsRecorder.noop(), IDLE);

        HttpResponse<?> response = controller.chat(body(true), request());
        @SuppressWarnings("unchecked")
        org.reactivestreams.Publisher<io.micronaut.http.sse.Event<String>> publisher =
                (org.reactivestreams.Publisher<io.micronaut.http.sse.Event<String>>) response.body();
        TestSubscriber subscriber = new TestSubscriber();
        publisher.subscribe(subscriber);
        subscriber.subscription().request(Long.MAX_VALUE);
        assertTrue(subscriber.awaitTerminal(5_000), "the stream terminates cleanly");

        List<CallRecord> records = w.calls().recentCalls(10);
        assertEquals(1, records.size());
        assertEquals("kimi", records.get(0).provider(), "the stream settle names the backend that actually served");
        assertEquals(CallStatus.OK, records.get(0).status());
        assertTrue(records.get(0).stream());
    }

    @Test
    void upstreamFailureRowCarriesTheDispatchedBackend() {
        Wiring w = wiring();
        w.second.completeFails(new ProviderException(ProviderException.TYPE_UPSTREAM_5XX, "upstream 500"));
        ChatCompletionsController controller =
                new ChatCompletionsController(w.router(), governance(w.calls()), MetricsRecorder.noop(), IDLE);

        ProviderException e = assertThrows(ProviderException.class, () -> controller.chat(body(false), request()));

        List<CallRecord> records = w.calls().recentCalls(10);
        assertEquals(1, records.size());
        assertEquals(
                "kimi", records.get(0).provider(), "the failure row names the backend the request was dispatched to");
        assertEquals(CallStatus.ERROR_UPSTREAM, records.get(0).status());
    }

    @Test
    void decodeFailureRowCarriesNoProvider() {
        // Nothing was dispatched (the codec rejected the body before routing), so the
        // row carries a null provider — never a guessed config-first candidate.
        Wiring w = wiring();
        ChatCompletionsController controller =
                new ChatCompletionsController(w.router(), governance(w.calls()), MetricsRecorder.noop(), IDLE);

        assertThrows(OpenAiCodecException.class, () -> controller.chat("not valid json", request()));

        List<CallRecord> records = w.calls().recentCalls(10);
        assertEquals(1, records.size());
        assertNull(records.get(0).provider(), "no dispatch happened — the provider stays null");
        assertEquals(CallStatus.ERROR_CLIENT, records.get(0).status());
        assertTrue(w.first.completeCalls.isEmpty() && w.second.completeCalls.isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static String body(boolean stream) {
        return "{\"model\":\""
                + ALIAS
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":"
                + stream
                + "}";
    }

    private static HttpRequest<?> request() {
        return HttpRequest.POST("/v1/chat/completions", "").contentType(MediaType.APPLICATION_JSON);
    }

    private static ChatResponse chatResponse() {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                ALIAS,
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(14, 12, 26),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static StreamChunk textChunk(String content) {
        return new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                ALIAS,
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, content, null), null)),
                null,
                Map.of());
    }

    private static StreamChunk usageChunk(Usage usage) {
        return new StreamChunk(
                "chatcmpl-1", "chat.completion.chunk", 1_700_000_000L, ALIAS, List.of(), usage, Map.of());
    }
}
