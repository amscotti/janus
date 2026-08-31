package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.OpenAiCodecException;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.Router;
import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.FixedWindowRateLimiter;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The chat controllers' {@code recordRequest} call sites are best-effort: a recorder
 * whose {@code recordRequest} throws must not (a) replace the client's true envelope
 * (the original exception still propagates), (b) skip the call-ledger
 * {@code recordFailure}, or (c) on the stream path, escape the SSE worker's close
 * chain uncaught — the same log-and-drop guard {@code Governance.writeCallRecord}
 * already applies to every other collaborator on the path. Unit-level direct
 * construction (no Micronaut context).
 */
class ThrowingRecorderAccountingTest {

    private static final Clock CLOCK = TestKeyAuthFactory.CLOCK;

    /** Global-only resolver (the no-override shape — deadlines are not this test's subject). */
    private static final StreamIdleTimeoutResolver IDLE =
            new StreamIdleTimeoutResolver(Duration.ofSeconds(60), Map.of());

    @Test
    void throwingRecorderOnOpenAiFaceKeepsTrueEnvelopeAndFiresTheCallLedger() {
        List<CallRecord> before = TestGovernanceFactory.CALLS.recentCalls(1000);
        ThrowingRecorder recorder = new ThrowingRecorder();
        Router router = new Router(Map.of("deepseek-v4-flash", new NeverReachedBackend()));
        Governance governance = new Governance(
                new FixedWindowRateLimiter(CLOCK),
                TestGovernanceFactory.PRICES,
                TestGovernanceFactory.LEDGER,
                TestGovernanceFactory.NOTIFIER,
                0.8,
                CLOCK,
                recorder,
                TestGovernanceFactory.CALLS);
        ChatCompletionsController controller = new ChatCompletionsController(router, governance, recorder, IDLE);

        // A malformed body fails decode → the catch path records (guarded) + writes the
        // call ledger + rethrows the client 400 unchanged.
        OpenAiCodecException e =
                assertThrows(OpenAiCodecException.class, () -> controller.chat("not valid json", request()));
        assertEquals(
                OpenAiCodecException.TYPE_INVALID_REQUEST,
                e.type(),
                "the codec exception is the true envelope, not the recorder's failure");
        assertEquals(
                HttpStatus.BAD_REQUEST,
                new ErrorMapper().map(e).status(),
                "the handler maps the rethrown exception to the client 400");

        assertEquals(
                1,
                recorder.recordRequests.get(),
                "the catch path attempted the recorder exactly once and contained its failure — "
                        + "the recorder's exception never replaced the client envelope");

        List<CallRecord> added = TestGovernanceFactory.CALLS.recentCalls(1000).stream()
                .filter(record -> !before.contains(record))
                .toList();
        assertEquals(1, added.size(), "a throwing recorder must not skip the call-ledger recordFailure: " + added);
    }

    @Test
    void throwingRecorderOnAnthropicFaceKeepsTrueEnvelopeAndFiresTheCallLedger() {
        List<CallRecord> before = TestGovernanceFactory.CALLS.recentCalls(1000);
        ThrowingRecorder recorder = new ThrowingRecorder();
        Router router = new Router(Map.of("claude-3", new NeverReachedBackend()));
        Governance governance = new Governance(
                new FixedWindowRateLimiter(CLOCK),
                TestGovernanceFactory.PRICES,
                TestGovernanceFactory.LEDGER,
                TestGovernanceFactory.NOTIFIER,
                0.8,
                CLOCK,
                recorder,
                TestGovernanceFactory.CALLS);
        MessagesController controller = new MessagesController(router, governance, recorder, IDLE);

        io.amscotti.janus.core.codec.AnthropicCodecException e = assertThrows(
                io.amscotti.janus.core.codec.AnthropicCodecException.class,
                () -> controller.messages("not valid json", request()));

        assertEquals(
                io.amscotti.janus.core.codec.AnthropicCodecException.TYPE_INVALID_REQUEST,
                e.type(),
                "the codec exception is the true envelope, not the recorder's failure");
        assertEquals(
                HttpStatus.BAD_REQUEST,
                new AnthropicErrorMapper().map(e).status(),
                "the handler maps the rethrown exception to the client 400");
        assertEquals(
                1,
                recorder.recordRequests.get(),
                "the catch path attempted the recorder exactly once and contained its failure — "
                        + "the recorder's exception never replaced the client envelope");

        List<CallRecord> added = TestGovernanceFactory.CALLS.recentCalls(1000).stream()
                .filter(record -> !before.contains(record))
                .toList();
        assertEquals(1, added.size(), "a throwing recorder must not skip the call-ledger recordFailure: " + added);
    }

    @Test
    void throwingRecorderOnTheStreamCloseHookIsContainedAndNeverEscapesTheWorker() throws Exception {
        // The metered onClose hook (the stream path's only recordRequest call site) is
        // best-effort like the catch path: a throwing recorder must be log-and-dropped
        // — the throw would otherwise escape the SSE worker's stream close uncaught —
        // and must not disturb the governance close hook that runs before it (the
        // upstream close) or the pull-time settle.
        List<CallRecord> before = TestGovernanceFactory.CALLS.recentCalls(1000);
        ThrowingRecorder recorder = new ThrowingRecorder();
        FakeBackend backend = new FakeBackend("deepseek");
        backend.streamReturns(Stream.of(textChunk("Hello"), usageChunk(new Usage(10, 5, 15))));
        Router router = new Router(Map.of("deepseek-v4-flash", backend));
        Governance governance = new Governance(
                new FixedWindowRateLimiter(CLOCK),
                TestGovernanceFactory.PRICES,
                TestGovernanceFactory.LEDGER,
                TestGovernanceFactory.NOTIFIER,
                0.8,
                CLOCK,
                recorder,
                TestGovernanceFactory.CALLS);
        ChatCompletionsController controller = new ChatCompletionsController(router, governance, recorder, IDLE);

        List<Throwable> uncaught = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, thrown) -> uncaught.add(thrown));
        try {
            HttpResponse<?> response = controller.chat(streamBody(), request());
            @SuppressWarnings("unchecked")
            org.reactivestreams.Publisher<io.micronaut.http.sse.Event<String>> publisher =
                    (org.reactivestreams.Publisher<io.micronaut.http.sse.Event<String>>) response.body();
            TestSubscriber subscriber = new TestSubscriber();
            publisher.subscribe(subscriber);
            subscriber.subscription().request(Long.MAX_VALUE);
            assertTrue(
                    subscriber.awaitTerminal(5_000),
                    "the stream must terminate cleanly despite the throwing meter hook");
            // The close chain (governance hook, then meter) runs on the worker after
            // onComplete — onComplete reaching the subscriber does NOT mean the close
            // chain has run. The recording handler must therefore stay installed until
            // the worker has terminated; restoring it earlier (in a finally bound to
            // awaitTerminal) lets an escaped throw land on the prior handler and the
            // uncaught-is-empty check below pass vacuously. Joining the worker
            // (captured at the recorder call) before the restore makes the containment
            // verdict deterministic — an escaped throw reaches the default uncaught
            // handler before the thread terminates, so post-join the verdict is final.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (recorder.recorderThread.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread worker = recorder.recorderThread.get();
            assertNotNull(worker, "the metered close hook must fire at stream close");
            worker.join(2_000);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prior);
        }

        assertTrue(
                uncaught.isEmpty(),
                "the recorder failure in the metered close hook must be contained, never an uncaught escape: "
                        + uncaught);
        assertEquals(1, recorder.recordRequests.get(), "the metered close hook attempted the recorder exactly once");
        assertTrue(backend.streamClosed(), "the governance close hook still released the upstream connection");
        List<CallRecord> added = TestGovernanceFactory.CALLS.recentCalls(1000).stream()
                .filter(record -> !before.contains(record))
                .toList();
        assertEquals(1, added.size(), "the pull-time settle still wrote its call record: " + added);
    }

    private static String streamBody() {
        return "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";
    }

    private static StreamChunk textChunk(String content) {
        return new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new io.amscotti.janus.core.model.ChunkChoice(
                        0,
                        new io.amscotti.janus.core.model.Delta(
                                io.amscotti.janus.core.model.ChatRole.ASSISTANT, content, null),
                        null)),
                null,
                Map.of());
    }

    private static StreamChunk usageChunk(Usage usage) {
        return new StreamChunk(
                "chatcmpl-1", "chat.completion.chunk", 1_700_000_000L, "deepseek-v4-flash", List.of(), usage, Map.of());
    }

    private static HttpRequest<?> request() {
        return HttpRequest.POST("/v1/chat/completions", "not valid json").contentType(MediaType.APPLICATION_JSON);
    }

    /** A backend that must never be reached (the decode fails first). */
    private static final class NeverReachedBackend implements ChatBackend {
        @Override
        public String name() {
            return "deepseek";
        }

        @Override
        public String baseUrl() {
            return "http://fake/deepseek";
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            throw new AssertionError("decode must fail before dispatch");
        }

        @Override
        public Stream<StreamChunk> stream(ChatRequest request) {
            throw new AssertionError("decode must fail before dispatch");
        }
    }

    /**
     * {@link MetricsRecorder} whose {@code recordRequest} always throws. The attempt
     * count is atomic and the calling thread is captured: on the stream path the
     * metered close hook runs on the SSE worker thread, not the test thread — the
     * test joins that thread so post-close assertions (was the throw contained?) are
     * deterministic rather than raced.
     */
    private static final class ThrowingRecorder implements MetricsRecorder {
        final java.util.concurrent.atomic.AtomicInteger recordRequests =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Thread> recorderThread =
                new java.util.concurrent.atomic.AtomicReference<>();

        @Override
        public void recordRequest(String face, int status, long durationMillis, String keyId) {
            recorderThread.set(Thread.currentThread());
            recordRequests.incrementAndGet();
            throw new IllegalStateException("registry unregistered");
        }

        @Override
        public void recordUsage(String keyId, Usage usage, long costMicroUsd) {}

        @Override
        public void forgetKey(String keyId) {}
    }
}
