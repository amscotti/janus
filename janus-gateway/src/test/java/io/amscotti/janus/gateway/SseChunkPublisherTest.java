package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.OpenAiMessageCodec;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.provider.ProviderException;
import io.micronaut.http.sse.Event;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * C1 — {@link SseChunkPublisher} unit suite (plan step 5). Uses the
 * {@link TestStreams}/{@link TestSubscriber} harnesses: backpressure (one event per
 * demand unit, batching), cancel → upstream close, exhaustion → {@code [DONE]} +
 * onComplete + close, mid-stream failure → SSE error frame + complete, and the idle
 * watchdog stall-close. No network.
 */
class SseChunkPublisherTest {

    private static final OpenAiMessageCodec CODEC = OpenAiMessageCodec.create();
    private static final ErrorMapper ERROR_MAPPER = new ErrorMapper();

    private static final StreamChunk C1 = chunk("Hello");
    private static final StreamChunk C2 = chunk(" world");

    @Test
    void oneDemandUnitYieldsExactlyOneEventThenWaits() throws Exception {
        AtomicInteger nextCalls = new AtomicInteger();
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(List.of(C1, C2), null, nextCalls);
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        assertEquals(CODEC.encodeChunk(C1), subscriber.awaitEvent(0, 2_000).getData());
        subscriber.assertNotTerminated();
        assertEquals(1, nextCalls.get(), "exactly one fetch per demand unit");

        subscriber.subscription().request(1);
        assertEquals(CODEC.encodeChunk(C2), subscriber.awaitEvent(1, 2_000).getData());
    }

    @Test
    void batchedDemandDeliversAllThenDoneOnNextDemand() throws Exception {
        AtomicInteger nextCalls = new AtomicInteger();
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(List.of(C1, C2), null, nextCalls);
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(2);
        assertEquals(CODEC.encodeChunk(C1), subscriber.awaitEvent(0, 2_000).getData());
        assertEquals(CODEC.encodeChunk(C2), subscriber.awaitEvent(1, 2_000).getData());
        subscriber.assertNotTerminated();

        subscriber.subscription().request(1);
        assertEquals("[DONE]", subscriber.awaitEvent(2, 2_000).getData());
        assertTrue(subscriber.awaitTerminal(2_000), "clean exhaustion must onComplete");
    }

    @Test
    void exhaustionEmitsDoneThenCompletesAndClosesUpstream() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        Stream<StreamChunk> upstream = TestStreams.of(C1).onClose(() -> closed.set(true));
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(2);
        assertTrue(subscriber.awaitTerminal(2_000), "clean exhaustion must terminate");

        assertEquals(
                List.of(CODEC.encodeChunk(C1), "[DONE]"),
                subscriber.events().stream().map(Event::getData).toList());
        assertNull(subscriber.error());
        awaitTrue(closed, 2_000);
        assertTrue(closed.get(), "clean exhaustion must close the upstream (close contract)");
    }

    @Test
    void cancelClosesUpstreamAndStopsEmissions() throws Exception {
        // The cancel-path close runs off the caller thread (see
        // cancelReturnsBeforeTheCloseChainFinishes) — await it, never assume it ran.
        CountDownLatch closed = new CountDownLatch(1);
        Stream<StreamChunk> upstream = TestStreams.of(C1, C2).onClose(closed::countDown);
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        subscriber.awaitEvent(0, 2_000);
        subscriber.subscription().cancel();
        assertTrue(
                closed.await(2_000, TimeUnit.MILLISECONDS), "cancel must close the upstream (no leaked connections)");
        Thread.sleep(100);
        assertEquals(1, subscriber.eventCount(), "no events after cancel");
    }

    @Test
    void cancelReturnsBeforeTheCloseChainFinishes() throws Exception {
        // The cancel-path close (the onClose chain: metrics hook, governance
        // settle/release — possibly a synchronous DB write — then the adapter socket
        // close) runs on a fresh virtual thread, never on the caller: a real client
        // disconnect arrives on the Netty event loop, which a wedged settle must
        // never block (the same offload the stall path uses).
        CountDownLatch closeReached = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        Stream<StreamChunk> upstream = TestStreams.of(C1, C2).onClose(() -> {
            closeThread.set(Thread.currentThread());
            closeReached.countDown();
            try {
                releaseClose.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        subscriber.awaitEvent(0, 2_000);
        Thread canceller =
                Thread.ofVirtual().start(() -> subscriber.subscription().cancel());

        assertTrue(canceller.join(Duration.ofSeconds(2)), "cancel must not block on the close chain");
        assertTrue(closeReached.await(2_000, TimeUnit.MILLISECONDS), "the close must still run");
        assertNotSame(canceller, closeThread.get(), "the close chain must run off the cancelling thread");
        releaseClose.countDown();
    }

    @Test
    void midStreamFailureEmitsSseErrorFrameThenCompletes() throws Exception {
        Stream<StreamChunk> upstream = TestStreams.failingAfter(C1, new IllegalStateException("upstream exploded"));
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(2);
        subscriber.awaitTerminal(2_000);

        assertEquals(2, subscriber.eventCount(), "chunk then error frame");
        assertEquals(CODEC.encodeChunk(C1), subscriber.events().get(0).getData());
        String frame = subscriber.events().get(1).getData();
        assertTrue(frame.contains("\"type\":\"api_error\""), frame);
        // An untyped (unexpected) mid-stream failure maps to the fixed 500 message —
        // internal exception details never reach a client, in the stream path too.
        assertTrue(frame.contains("internal server error"), frame);
        assertNull(subscriber.error(), "failure must be delivered as a frame, not onError");
    }

    // ------------------------------------------- terminal-outcome threading

    @Test
    void midStreamFailureSetsTerminalStatusToMappedErrorStatus() throws Exception {
        // A provider dying mid-stream must not be recorded as a clean 200 — the
        // shared terminal-status reference the controller's onClose hook reads is set
        // to the mapped error status (TYPE_TIMEOUT → 504) when the error frame emits.
        AtomicReference<Integer> terminal = new AtomicReference<>(200);
        Stream<StreamChunk> upstream =
                TestStreams.failingAfter(C1, new ProviderException(ProviderException.TYPE_TIMEOUT, "boom"));
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER, terminal);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(2);
        subscriber.awaitTerminal(2_000);
        assertEquals(504, terminal.get(), "a timed-out mid-stream failure must record 504");
    }

    @Test
    void nullMessageProviderExceptionMidStreamStillTerminatesWithAnErrorFrame() throws Exception {
        // A null-message ProviderException must never NPE *inside* the error
        // mapping (that would escape the worker with no terminal signal — a hang) — the
        // frame degrades to the fixed "upstream error" message and the stream still
        // onComplete.
        Stream<StreamChunk> upstream =
                TestStreams.failingAfter(C1, new ProviderException(ProviderException.TYPE_TIMEOUT, null));
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(2);
        assertTrue(subscriber.awaitTerminal(2_000), "a null-message failure must still terminate the stream");

        assertEquals(2, subscriber.eventCount(), "chunk then error frame");
        String frame = subscriber.events().get(1).getData();
        assertTrue(frame.contains("\"message\":\"upstream error\""), frame);
        assertNull(subscriber.error(), "delivered as a frame, never onError");
    }

    @Test
    void cleanExhaustionLeavesTerminalStatus200() throws Exception {
        AtomicReference<Integer> terminal = new AtomicReference<>(200);
        SseChunkPublisher publisher = new SseChunkPublisher(TestStreams.of(C1), CODEC, ERROR_MAPPER, terminal);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(2);
        subscriber.awaitTerminal(2_000);
        assertEquals(200, terminal.get(), "clean exhaustion stays a 200");
    }

    @Test
    void watchdogStallSetsTerminalStatusTo504() throws Exception {
        // The stall's close can land on the WATCHDOG thread (the onClose hook fires
        // synchronously inside closeUpstream), so the reference must be set at stall
        // detection, not only when the worker emits the timeout frame.
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger nextCalls = new AtomicInteger();
        AtomicReference<Integer> terminal = new AtomicReference<>(200);
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(List.of(C1, C2), release, nextCalls)
                .onClose(() -> {
                    closed.set(true);
                    release.countDown();
                });
        SseChunkPublisher publisher =
                new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER, Duration.ofMillis(200), terminal);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        subscriber.awaitEvent(0, 2_000);
        subscriber.subscription().request(1);
        subscriber.awaitTerminal(3_000);
        assertEquals(504, terminal.get(), "a watchdog stall must record 504, not 200");
    }

    @Test
    void midStreamEncodeFailureEmitsErrorFrameThenCompletes()
            throws Exception { // the OpenAI analogue of AnthropicSsePublisherTest's mid-stream
        // codec-failure case — a chunk the codec cannot render (here an extras value
        // Jackson cannot serialize, wrapped by encodeChunk into an OpenAiCodecException
        // api_error) must emit an error frame and complete, never onError and never a hang.
        Stream<StreamChunk> upstream = TestStreams.of(unrenderableChunk());
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        assertTrue(subscriber.awaitTerminal(2_000), "an encode failure must terminate the stream");

        assertEquals(1, subscriber.eventCount(), "the error frame only — no chunk, no [DONE]");
        String frame = subscriber.events().get(0).getData();
        assertTrue(frame.contains("\"type\":\"api_error\""), frame);
        assertNull(subscriber.error(), "encode failure must be delivered as a frame, not onError");
    }

    @Test
    void idleWatchdogStallClosesAndEmitsTimeoutFrame() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger nextCalls = new AtomicInteger();
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(List.of(C1, C2), release, nextCalls)
                .onClose(() -> {
                    closed.set(true);
                    release.countDown(); // emulate socket-close unblocking the read
                });
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER, Duration.ofMillis(200));
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        assertEquals(CODEC.encodeChunk(C1), subscriber.awaitEvent(0, 2_000).getData());
        // second demand blocks the worker in next; the watchdog stall-closes it
        subscriber.subscription().request(1);

        assertTrue(subscriber.awaitTerminal(3_000), "watchdog must terminate the stalled stream");
        assertEquals(2, subscriber.eventCount(), "chunk then timeout error frame");
        String frame = subscriber.events().get(1).getData();
        assertTrue(frame.contains("upstream stream stalled"), frame);
        awaitTrue(closed, 2_000);
        assertTrue(closed.get(), "watchdog close must release the upstream connection");
    }

    @Test
    void stallCloseRunsOffTheWatchdogThread() throws Exception {
        // The shared single-thread watchdog must never run the stall's
        // closeUpstream — its onClose hooks fire governance settle (a possible DB write
        // with a seconds-scale timeout) synchronously, and one wedged settle would
        // stall stall-detection for every other live stream. The state transition +
        // close run on a fresh virtual thread; the watchdog performs only cheap checks.
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> closedBy = new AtomicReference<>();
        AtomicInteger nextCalls = new AtomicInteger();
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(List.of(C1, C2), release, nextCalls)
                .onClose(() -> {
                    closedBy.set(Thread.currentThread().getName());
                    release.countDown(); // emulate socket-close unblocking the read
                });
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER, Duration.ofMillis(200));
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        assertEquals(CODEC.encodeChunk(C1), subscriber.awaitEvent(0, 2_000).getData());
        subscriber.subscription().request(1); // second demand blocks the worker in next()

        assertTrue(subscriber.awaitTerminal(3_000), "watchdog must terminate the stalled stream");
        assertNotNull(closedBy.get(), "the stall must close the upstream");
        assertTrue(
                !closedBy.get().contains("watchdog"),
                "the stall close must run off the watchdog thread (ran on: " + closedBy.get() + ")");
    }

    @Test
    void stallCloseOnOneStreamDoesNotBlockAnotherStreamsStallCheck() throws Exception {
        // Hard pin: a settle that blocks forever inside closeUpstream must
        // not stop the watchdog from servicing a second stalling stream.
        CountDownLatch wedge = new CountDownLatch(0); // never counted down
        CountDownLatch firstClosed = new CountDownLatch(1);
        CountDownLatch secondTerminal = new CountDownLatch(1);
        AtomicInteger nextCalls = new AtomicInteger();
        Stream<StreamChunk> wedged = TestStreams.blockingAfterFirst(List.of(C1, C2), new CountDownLatch(1), nextCalls)
                .onClose(() -> {
                    firstClosed.countDown();
                    try {
                        wedge.await(); // a settle stuck on a DB write, forever
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        Stream<StreamChunk> second = TestStreams.blockingAfterFirst(List.of(C1, C2), new CountDownLatch(1), nextCalls)
                .onClose(() -> secondTerminal.countDown());
        SseChunkPublisher first = new SseChunkPublisher(wedged, CODEC, ERROR_MAPPER, Duration.ofMillis(100));
        SseChunkPublisher other = new SseChunkPublisher(second, CODEC, ERROR_MAPPER, Duration.ofMillis(100));
        TestSubscriber firstSubscriber = subscribe(first);
        TestSubscriber secondSubscriber = subscribe(other);

        firstSubscriber.subscription().request(1);
        assertEquals(CODEC.encodeChunk(C1), firstSubscriber.awaitEvent(0, 2_000).getData());
        firstSubscriber.subscription().request(1); // wedged stream stalls; close blocks forever
        secondSubscriber.subscription().request(1);
        assertEquals(
                CODEC.encodeChunk(C1), secondSubscriber.awaitEvent(0, 2_000).getData());
        secondSubscriber.subscription().request(1); // second stream stalls too

        assertTrue(firstClosed.await(3_000, TimeUnit.MILLISECONDS), "the wedged stream's close must still be reached");
        assertTrue(
                secondTerminal.await(3_000, TimeUnit.MILLISECONDS),
                "the watchdog must service the second stall while the first close is wedged");
    }

    @Test
    void clientCancelBeforeCleanExhaustionRecordsClientClosedStatus() throws Exception {
        // A client abort is not a successful 200 — the terminal status flips
        // to 499 (nginx client-closed-request; the coarse metrics bucket folds it into
        // 4xx) so aborted streams stop inflating the success rate.
        CountDownLatch closed = new CountDownLatch(1);
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(List.of(C1, C2), null, new AtomicInteger())
                .onClose(closed::countDown);
        AtomicReference<Integer> terminalStatus = new AtomicReference<>(io.micronaut.http.HttpStatus.OK.getCode());
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER, terminalStatus);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        assertEquals(CODEC.encodeChunk(C1), subscriber.awaitEvent(0, 2_000).getData());
        subscriber.subscription().cancel();

        assertTrue(closed.await(2_000, TimeUnit.MILLISECONDS), "cancel must close the upstream");
        assertEquals(
                SseStreamPublisher.STATUS_CLIENT_CLOSED,
                terminalStatus.get(),
                "a cancelled stream must not be recorded in the 2xx bucket");
    }

    @Test
    void cleanExhaustionAfterCancelKeepTheirStatuses() throws Exception {
        // The 499 flip is a CAS from the 200 default only: an error status already
        // recorded by a mid-stream failure always wins over a late cancel, and a
        // clean-exhaustion 200 (done before the cancel) is never downgraded.
        AtomicReference<Integer> errored = new AtomicReference<>(io.micronaut.http.HttpStatus.OK.getCode());
        SseChunkPublisher failed = new SseChunkPublisher(
                TestStreams.failingAfter(C1, new ProviderException(ProviderException.TYPE_NETWORK, "drop")),
                CODEC,
                ERROR_MAPPER,
                Duration.ofSeconds(60),
                errored);
        TestSubscriber failedSubscriber = subscribe(failed);
        failedSubscriber.subscription().request(1);
        failedSubscriber.awaitEvent(0, 2_000);
        failedSubscriber.subscription().request(1); // fetch fails → error frame + 502
        assertTrue(failedSubscriber.awaitTerminal(2_000));
        failedSubscriber.subscription().cancel(); // a late cancel after a recorded error
        assertEquals(502, errored.get(), "a mapped error status must survive a late cancel");
    }

    @Test
    void nonPositiveRequestIsAProtocolViolation() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        Stream<StreamChunk> upstream = TestStreams.of(C1).onClose(closed::countDown);
        SseChunkPublisher publisher = new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(0);
        assertTrue(subscriber.awaitError(2_000), "non-positive request must deliver onError");
        assertNotNull(subscriber.error(), "violation is surfaced via onError");
        assertTrue(closed.await(2_000, TimeUnit.MILLISECONDS), "the upstream must be closed on the violation");
    }

    @Test
    void nonPositiveRequestsArrivingWhileTheWorkerStartsNeverHang() throws Exception {
        // The wasStarted guard — a request(0) racing a freshly
        // started worker must never let the close land before iterator and lose the
        // violation's terminal signal. Without the guard this hangs (the very race
        // AnthropicSsePublisher already fixed; never backported).
        CountDownLatch closed = new CountDownLatch(1);
        SseChunkPublisher publisher =
                new SseChunkPublisher(TestStreams.of(C1, C2).onClose(closed::countDown), CODEC, ERROR_MAPPER);
        TestSubscriber subscriber = subscribe(publisher);

        for (int i = 0; i < 50; i++) {
            subscriber.subscription().request(0);
        }
        assertTrue(subscriber.awaitError(2_000), "the violation must be delivered, never a hang");
        assertNotNull(subscriber.error());
        assertTrue(closed.await(2_000, TimeUnit.MILLISECONDS), "the upstream must be closed on the violation");
    }

    @Test
    void subscribedButNeverRequestedStopsItsWatchdogAfterTheIdleTimeout() throws Exception {
        // A subscriber that subscribes and never requests never starts the
        // worker, so nothing else cancels the repeating stall-check schedule — the
        // watchdog must self-cancel (and release the upstream) after the idle timeout.
        AtomicBoolean closed = new AtomicBoolean();
        // A stable read (two consecutive equal samples) lets the previous test's
        // worker-finally cancel land before the baseline is taken.
        long baseline = stableTaskCount();
        SseChunkPublisher publisher = new SseChunkPublisher(
                TestStreams.of(C1, C2).onClose(() -> closed.set(true)), CODEC, ERROR_MAPPER, Duration.ofMillis(100));
        subscribe(publisher);
        // never request — the worker never starts

        Thread.sleep(400); // well past the idle timeout
        assertEquals(
                baseline,
                SseWatchdog.scheduledTaskCount(),
                "the never-requested watchdog must stop itself (no repeating-task leak)");
        awaitTrue(closed, 2_000);
        assertTrue(closed.get(), "the upstream must be released");
    }

    /** A phase-stable watchdog-task count (two consecutive equal reads 20ms apart). */
    private static long stableTaskCount() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000);
        long last = SseWatchdog.scheduledTaskCount();
        while (System.nanoTime() < deadline) {
            Thread.sleep(20);
            long now = SseWatchdog.scheduledTaskCount();
            if (now == last) {
                return now;
            }
            last = now;
        }
        return last;
    }

    @Test
    void aSubscriberWriteInFlightIsNeverStallKilled() throws Exception {
        // A blocked subscriber write (slow TCP client) is a live stream, not an
        // upstream stall — the stall watchdog must pause while the write is in flight
        // even when it outlasts the idle timeout, and no spurious timeout frame may
        // appear for a stream that was writing all along.
        BlockingOnceSubscriber subscriber = new BlockingOnceSubscriber();
        SseChunkPublisher publisher =
                new SseChunkPublisher(TestStreams.of(C1, C2), CODEC, ERROR_MAPPER, Duration.ofMillis(150));
        publisher.subscribe(subscriber);

        subscriber.subscription().request(1);
        assertTrue(
                subscriber.firstWriteStarted.await(2_000, TimeUnit.MILLISECONDS), "the first onNext must be in flight");
        // Hold the write well past the idle timeout while the watchdog samples.
        Thread.sleep(500);
        assertEquals(1, subscriber.eventCount(), "no stall frame may appear while the write is in flight");
        subscriber.releaseFirstWrite.countDown();

        subscriber.subscription().request(100);
        assertTrue(subscriber.terminal.await(2_000, TimeUnit.MILLISECONDS), "the stream must complete cleanly");
        assertEquals(List.of(CODEC.encodeChunk(C1), CODEC.encodeChunk(C2), "[DONE]"), subscriber.data());
        assertNull(subscriber.error.get(), "no stall error may reach the subscriber");
    }

    @Test
    void aSlowButAliveClientParkedAtZeroDemandIsNeverStallKilled() throws Exception {
        // A compliant incremental-demand subscriber that requested 1,
        // consumed it, and requests nothing further parks the worker in waitForDemand —
        // the upstream is alive and client-paced, never stalled. The watchdog must not
        // close the stream (no stall frame, no terminal, no 5xx), and the stream must
        // complete normally once the subscriber resumes requesting.
        AtomicInteger nextCalls = new AtomicInteger();
        AtomicReference<Integer> terminal = new AtomicReference<>(200);
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(List.of(C1, C2), null, nextCalls);
        SseChunkPublisher publisher =
                new SseChunkPublisher(upstream, CODEC, ERROR_MAPPER, Duration.ofMillis(150), terminal);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        assertEquals(CODEC.encodeChunk(C1), subscriber.awaitEvent(0, 2_000).getData());

        Thread.sleep(500); // well past the idle timeout, demand parked at zero
        assertEquals(1, subscriber.eventCount(), "no stall frame may appear while the client paces at demand 0");
        subscriber.assertNotTerminated();

        subscriber.subscription().request(1);
        assertEquals(CODEC.encodeChunk(C2), subscriber.awaitEvent(1, 2_000).getData());
        subscriber.subscription().request(1);
        assertEquals("[DONE]", subscriber.awaitEvent(2, 2_000).getData());
        assertTrue(subscriber.awaitTerminal(2_000), "the stream completes normally when the client resumes");
        assertEquals(200, terminal.get(), "a client-paced stream is never a stall (no 5xx)");
    }

    @Test
    void aSubscriberThrowingOnEveryNextStillReceivesATerminalSignal() throws Exception {
        // A subscriber violating RS 2.13 by throwing on *every* onNext
        // (including the error frame itself) must still terminate — the error-frame
        // onNext is swallowed so finish's onComplete reaches the subscriber (a
        // consistently-throwing subscriber must never leave the worker with neither
        // onError nor onComplete — a hang).
        AtomicReference<Subscription> sub = new AtomicReference<>();
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Event<String>> events = new ArrayList<>();
        SseChunkPublisher publisher = new SseChunkPublisher(TestStreams.of(C1), CODEC, ERROR_MAPPER);
        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription s) {
                sub.set(s);
            }

            @Override
            public void onNext(Event<String> event) {
                events.add(event);
                throw new IllegalStateException("subscriber exploded on every frame");
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                terminal.countDown();
            }

            @Override
            public void onComplete() {
                terminal.countDown();
            }
        });

        sub.get().request(1);
        assertTrue(terminal.await(2_000, TimeUnit.MILLISECONDS), "a throwing onNext must not hang the stream");
        assertNull(error.get(), "the failure is contained as an error frame + complete, never onError");
        assertEquals(2, events.size(), "the chunk frame then the attempted error frame");
    }

    @Test
    void watchdogStallWithAMapperCrashStillUnblocksAndRecords500() throws Exception {
        // A mapper crash inside stallCheck must not skip the unblock (the
        // worker parked in the upstream read would stay asleep → connection hang) —
        // the stall degrades to a fixed 500, the terminal status is recorded, and the
        // stream still terminates.
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger nextCalls = new AtomicInteger();
        AtomicReference<Integer> terminal = new AtomicReference<>(200);
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(List.of(C1, C2), release, nextCalls)
                .onClose(() -> {
                    closed.set(true);
                    release.countDown(); // emulate socket-close unblocking the read
                });
        SseChunkPublisher publisher =
                new SseChunkPublisher(upstream, CODEC, new ThrowingErrorMapper(), Duration.ofMillis(150), terminal);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        assertEquals(CODEC.encodeChunk(C1), subscriber.awaitEvent(0, 2_000).getData());
        subscriber.subscription().request(1); // blocks the worker in next()

        assertTrue(
                subscriber.awaitTerminal(3_000), "a mapper crash inside the watchdog must still terminate the stream");
        assertEquals(500, terminal.get(), "the stall degrades to a fixed 500");
        awaitTrue(closed, 2_000);
        assertTrue(closed.get(), "the stall close must still release the upstream");
        String frame = subscriber.events().get(subscriber.eventCount() - 1).getData();
        assertTrue(frame.contains("internal server error"), frame);
        assertTrue(!frame.contains("upstream stream stalled"), "the degraded frame is the fixed 500, not the stall");
    }

    @Test
    void protocolViolationRecordsNon200TerminalStatus() throws Exception {
        // A stream killed by a non-positive request must not be recorded in
        // the 2xx bucket by the controller's close hook — the violation is threaded
        // through terminalStatus like every other failure path.
        AtomicReference<Integer> terminal = new AtomicReference<>(200);
        SseChunkPublisher publisher = new SseChunkPublisher(TestStreams.of(C1), CODEC, ERROR_MAPPER, terminal);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(0);
        assertTrue(subscriber.awaitError(2_000), "non-positive request must deliver onError");
        assertTrue(
                terminal.get() != 200,
                "a violation-terminated stream must not record a clean 200, got " + terminal.get());
    }

    private static TestSubscriber subscribe(SseChunkPublisher publisher) {
        TestSubscriber subscriber = new TestSubscriber();
        publisher.subscribe(subscriber);
        return subscriber;
    }

    private static StreamChunk chunk(String content) {
        return new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, content, null), null)),
                null,
                java.util.Map.of());
    }

    /**
     * A chunk whose extras Jackson cannot serialize (a {@code java.lang.reflect.Method}
     * object recurses infinitely in the bare mapper): {@code codec.encodeChunk} throws
     * {@code OpenAiCodecException(TYPE_API_ERROR)} — the mid-stream <em>encode</em>
     * failure the publisher must render as an SSE error frame.
     */
    private static StreamChunk unrenderableChunk() {
        return new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "hi", null), null)),
                null,
                java.util.Map.of("bad", String.class.getMethods()[0]));
    }

    /**
     * An {@link ErrorMapper} whose {@code map} throws — the watchdog's mapper-crash
     * stall path (the stall must still unblock the worker and degrade to a
     * fixed 500).
     */
    private static final class ThrowingErrorMapper extends ErrorMapper {
        @Override
        ErrorMapping map(Throwable throwable) {
            throw new IllegalStateException("mapper exploded");
        }
    }

    /**
     * A subscriber whose first {@code onNext} blocks on a latch (emulating a slow TCP
     * write in the Micronaut SSE writer) — the in-flight-write stall-watchdog probe.
     */
    private static final class BlockingOnceSubscriber implements Subscriber<Event<String>> {

        private final List<Event<String>> events = new ArrayList<>();
        private final AtomicReference<Subscription> subscription = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final CountDownLatch firstWriteStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        private final AtomicBoolean blocked = new AtomicBoolean();

        @Override
        public void onSubscribe(Subscription s) {
            subscription.set(s);
        }

        @Override
        public void onNext(Event<String> event) {
            synchronized (events) {
                events.add(event);
            }
            if (blocked.compareAndSet(false, true)) {
                firstWriteStarted.countDown();
                try {
                    releaseFirstWrite.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
            terminal.countDown();
        }

        @Override
        public void onComplete() {
            terminal.countDown();
        }

        Subscription subscription() {
            return subscription.get();
        }

        List<String> data() {
            synchronized (events) {
                return events.stream().map(Event::getData).toList();
            }
        }

        int eventCount() {
            synchronized (events) {
                return events.size();
            }
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
