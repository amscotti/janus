package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.OpenAiMessageCodec;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.UserMessage;
import io.amscotti.janus.store.FixedWindowRateLimiter;
import io.amscotti.janus.store.InMemorySpendLedger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@link Governance#wrapStream} close propagation through the real (non
 * {@code noop}) governance into the SSE publisher. The / suites mask the wrap by
 * injecting {@code Governance.noop}, which returns the upstream unchanged; here the
 * production chain is exercised: the router-style {@link FakeBackend} stream (whose
 * {@code onClose} sets {@code streamClosed} — the adapter close contract) is wrapped by
 * real governance and driven by {@link SseChunkPublisher}. A client cancel mid-block and
 * the stall watchdog must both propagate the close to the adapter stream (the
 * close-releases-the-connection contract) — without the fix the close never reaches the
 * backend and the connection leaks. No network, no Micronaut context.
 */
class GovernanceSseCloseTest {

    private static final OpenAiMessageCodec CODEC = OpenAiMessageCodec.create();
    private static final ErrorMapper ERROR_MAPPER = new ErrorMapper();

    private static final StreamChunk C1 = chunk("Hello");
    private static final StreamChunk C2 = chunk(" world");

    @Test
    void cancelMidBlockClosesTheAdapterStreamThroughTheGovernanceWrap() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger nextCalls = new AtomicInteger();
        FakeBackend backend = new FakeBackend("deepseek");
        // The second latch observes the close reaching the adapter stream: the
        // cancel-path close runs off the caller thread (the Netty event loop on a real
        // disconnect — its onClose chain runs governance settle), so the test awaits
        // it instead of assuming it ran.
        CountDownLatch closed = new CountDownLatch(1);
        backend.streamReturns(TestStreams.blockingAfterFirst(List.of(C1, C2), release, nextCalls)
                .onClose(release::countDown)
                .onClose(closed::countDown));
        Stream<StreamChunk> wrapped =
                governance().wrapStream(Governance.Preflight.NONE, request(), backend.stream(null), null);
        SseChunkPublisher publisher = new SseChunkPublisher(wrapped, CODEC, ERROR_MAPPER, Duration.ofSeconds(5));
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        assertEquals(CODEC.encodeChunk(C1), subscriber.awaitEvent(0, 2_000).getData());
        subscriber.subscription().request(1);
        Thread.sleep(50); // the worker is now blocked in the upstream read
        subscriber.subscription().cancel();

        assertTrue(
                closed.await(3_000, TimeUnit.MILLISECONDS),
                "cancel must release the adapter stream through the governance wrap (close contract)");
    }

    @Test
    void stallClosesTheAdapterStreamThroughTheGovernanceWrapAndEmitsTheTimeoutFrame() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger nextCalls = new AtomicInteger();
        FakeBackend backend = new FakeBackend("deepseek");
        backend.streamReturns(TestStreams.blockingAfterFirst(List.of(C1, C2), release, nextCalls)
                .onClose(release::countDown));
        Stream<StreamChunk> wrapped =
                governance().wrapStream(Governance.Preflight.NONE, request(), backend.stream(null), null);
        SseChunkPublisher publisher = new SseChunkPublisher(wrapped, CODEC, ERROR_MAPPER, Duration.ofMillis(250));
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(2);
        assertEquals(CODEC.encodeChunk(C1), subscriber.awaitEvent(0, 2_000).getData());
        assertTrue(subscriber.awaitTerminal(3_000), "the stall must terminate the stream");
        assertEquals(2, subscriber.eventCount(), "chunk then timeout error frame");
        assertTrue(subscriber.events().get(1).getData().contains("upstream stream stalled"));
        assertTrue(
                backend.streamClosed(), "the stall close must release the adapter stream through the governance wrap");
    }

    // ---------------------------------------------------------------- helpers

    private static Governance governance() {
        return new Governance(
                new FixedWindowRateLimiter(TestKeyAuthFactory.CLOCK),
                TestGovernanceFactory.PRICES,
                new InMemorySpendLedger(TestKeyAuthFactory.CLOCK, 1000),
                new RecordingNotifier(),
                0.8,
                TestKeyAuthFactory.CLOCK,
                MetricsRecorder.noop());
    }

    private static TestSubscriber subscribe(SseChunkPublisher publisher) {
        TestSubscriber subscriber = new TestSubscriber();
        publisher.subscribe(subscriber);
        return subscriber;
    }

    private static ChatRequest request() {
        return new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                true,
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                Map.of());
    }

    private static StreamChunk chunk(String content) {
        return new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, content, null), null)),
                null,
                Map.of());
    }
}
