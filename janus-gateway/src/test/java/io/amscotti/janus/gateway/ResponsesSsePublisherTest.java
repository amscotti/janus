package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.OpenAiResponsesCodec;
import io.amscotti.janus.core.codec.OpenAiResponsesStreamEvent;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.UserMessage;
import io.micronaut.http.sse.Event;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link ResponsesSsePublisher} under the shared
 * {@link SseStreamPublisher} contract, third implementation: demand-exact named-event
 * emission (one event per demand unit), the stall path emitting a snapshot-built
 * {@code response.failed} frame (504 terminal status), and cancel → upstream release +
 * 499. Uses the {@link TestStreams}/{@link TestSubscriber} harnesses, no network.
 */
class ResponsesSsePublisherTest {

    private static final OpenAiResponsesCodec CODEC = OpenAiResponsesCodec.create();
    private static final ErrorMapper ERROR_MAPPER = new ErrorMapper();

    private static StreamChunk chunk(String text) {
        return new StreamChunk(
                "c1",
                "chat.completion.chunk",
                1L,
                "m",
                List.of(new ChunkChoice(0, new Delta(null, text, null), null)),
                null,
                Map.of());
    }

    private static io.amscotti.janus.core.model.ChatRequest request() {
        return new io.amscotti.janus.core.model.ChatRequest(
                "m",
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
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void oneDemandUnitYieldsExactlyOneNamedEvent() throws Exception {
        AtomicInteger nextCalls = new AtomicInteger();
        var upstream = TestStreams.blockingAfterFirst(List.of(chunk("Hello"), chunk("!")), null, nextCalls);
        var publisher = new ResponsesSsePublisher(
                upstream, CODEC.newStreamEncoder(request()), ERROR_MAPPER, new AtomicReference<>(200));
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        assertEquals("response.created", subscriber.awaitEvent(0, 2_000).getName());
        subscriber.assertNotTerminated();

        subscriber.subscription().request(3);
        assertEquals("response.in_progress", subscriber.awaitEvent(1, 2_000).getName());
        assertEquals(
                "response.output_item.added", subscriber.awaitEvent(2, 2_000).getName());
        assertEquals(
                "response.content_part.added", subscriber.awaitEvent(3, 2_000).getName());
        subscriber.assertNotTerminated(); // no more events than demand units
    }

    @Test
    void exhaustionEmitsTerminalCompletedAndClosesUpstream() throws Exception {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var upstream = TestStreams.of(chunk("Hi")).onClose(() -> closed.set(true));
        var terminalStatus = new AtomicReference<Integer>(io.micronaut.http.HttpStatus.OK.getCode());
        var publisher =
                new ResponsesSsePublisher(upstream, CODEC.newStreamEncoder(request()), ERROR_MAPPER, terminalStatus);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(Long.MAX_VALUE);
        assertTrue(subscriber.awaitTerminal(2_000), "clean exhaustion completes");
        var names = subscriber.events().stream().map(Event::getName).toList();
        assertEquals("response.created", names.getFirst());
        assertEquals("response.completed", names.getLast());
        assertTrue(names.contains("response.output_text.delta"), String.valueOf(names));
        awaitTrue(closed, 2_000);
        assertTrue(closed.get(), "close releases the upstream (close contract)");
        assertEquals(200, terminalStatus.get(), "clean exhaustion keeps the 2xx status");
    }

    @Test
    void stallEmitsSnapshotBuiltFailedFrameAndRecords504() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger nextCalls = new AtomicInteger();
        var upstream = TestStreams.blockingAfterFirst(List.of(chunk("part"), chunk("stalled")), release, nextCalls)
                .onClose(() -> {
                    closed.set(true);
                    release.countDown(); // emulate socket-close unblocking the read
                });
        var terminalStatus = new AtomicReference<Integer>(io.micronaut.http.HttpStatus.OK.getCode());
        var publisher = new ResponsesSsePublisher(
                upstream, CODEC.newStreamEncoder(request()), ERROR_MAPPER, Duration.ofMillis(200), terminalStatus);
        TestSubscriber subscriber = subscribe(publisher);

        // Demand larger than the first chunk's frames: after consuming them the worker
        // blocks INSIDE next on the stalled second fetch — the true upstream stall
        // (waitingForDemand=false), which the watchdog kills.
        subscriber.subscription().request(10);
        assertTrue(subscriber.awaitTerminal(3_000), "watchdog terminates the stalled stream");
        var last = subscriber.events().getLast();
        assertEquals("response.failed", last.getName(), String.valueOf(subscriber.events()));
        assertTrue(last.getData().contains("\"status\":\"failed\""), last.getData());
        assertTrue(
                last.getData().contains("\"text\":\"part\""), "snapshot carries the partial text: " + last.getData());
        assertEquals(504, terminalStatus.get(), "the mapped timeout status threads through");
        awaitTrue(closed, 2_000);
        assertTrue(closed.get(), "stall close releases the upstream");
    }

    @Test
    void encoderCrashOnTheErrorPathDegradesToAFixed500FailedFrameNeverAHang() throws Exception {
        // encoder.failed serializes the snapshot through Jackson (it can throw
        // OpenAiCodecException) — the throw is inside the guard now, so it degrades to
        // a fixed 500 response.failed frame and the stream still completes, exactly
        // like a mapper crash on the other two faces (an escape from the worker with
        // neither onError nor onComplete is the hang this must never be).
        var upstream = TestStreams.failingAfter(chunk("hi"), new IllegalStateException("upstream exploded"));
        var terminalStatus = new AtomicReference<Integer>(io.micronaut.http.HttpStatus.OK.getCode());
        var publisher = new ResponsesSsePublisher(upstream, new ThrowingFailedEncoder(), ERROR_MAPPER, terminalStatus);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(10);
        assertTrue(subscriber.awaitTerminal(2_000), "an encoder crash on the error path must still terminate");
        var last = subscriber.events().getLast();
        assertEquals("response.failed", last.getName(), String.valueOf(subscriber.events()));
        assertTrue(last.getData().contains("\"status\":\"failed\""), last.getData());
        assertTrue(last.getData().contains("internal server error"), last.getData());
        assertEquals(500, terminalStatus.get(), "the degraded outcome records the fixed 500");
        assertNull(subscriber.error(), "delivered as a frame, never onError");
    }

    @Test
    void clientCancelReleasesUpstreamAndRecordsClientClosed() throws Exception {
        // The cancel-path close runs off the caller thread — await it, never assume it ran.
        var closed = new CountDownLatch(1);
        AtomicInteger nextCalls = new AtomicInteger();
        var upstream = TestStreams.blockingAfterFirst(List.of(chunk("a"), chunk("b")), null, nextCalls)
                .onClose(closed::countDown);
        var terminalStatus = new AtomicReference<Integer>(io.micronaut.http.HttpStatus.OK.getCode());
        var publisher =
                new ResponsesSsePublisher(upstream, CODEC.newStreamEncoder(request()), ERROR_MAPPER, terminalStatus);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        subscriber.awaitEvent(0, 2_000);
        subscriber.subscription().cancel();
        assertTrue(closed.await(2_000, TimeUnit.MILLISECONDS), "cancel must close the upstream");
        assertEquals(SseStreamPublisher.STATUS_CLIENT_CLOSED, terminalStatus.get());
    }

    private static TestSubscriber subscribe(ResponsesSsePublisher publisher) {
        TestSubscriber subscriber = new TestSubscriber();
        publisher.subscribe(subscriber);
        return subscriber;
    }

    /**
     * An encoder whose {@code failed} throws (the serialization failure the real
     * {@code StreamEncoderImpl} wraps in {@code OpenAiCodecException}) — the
     * error-path degrade test: the failure frame itself must degrade, never hang.
     */
    private static final class ThrowingFailedEncoder
            implements io.amscotti.janus.core.codec.OpenAiResponsesCodec.OpenAiResponsesStreamEncoder {

        @Override
        public List<OpenAiResponsesStreamEvent> initialEvents() {
            return List.of();
        }

        @Override
        public List<OpenAiResponsesStreamEvent> feed(StreamChunk chunk) {
            return List.of();
        }

        @Override
        public List<OpenAiResponsesStreamEvent> finish() {
            return List.of();
        }

        @Override
        public OpenAiResponsesStreamEvent failed(Throwable failure) {
            throw new io.amscotti.janus.core.codec.OpenAiCodecException(
                    io.amscotti.janus.core.codec.OpenAiCodecException.TYPE_API_ERROR,
                    "failed to encode Responses stream event: boom");
        }
    }

    /** Bounded wait for an async flag (the upstream close hook can trail the
     * subscriber's terminal signal on a slow runner — poll, then assert). */
    private static void awaitTrue(java.util.concurrent.atomic.AtomicBoolean flag, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!flag.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
