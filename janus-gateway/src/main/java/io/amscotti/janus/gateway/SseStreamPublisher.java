package io.amscotti.janus.gateway;

import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.provider.ProviderException;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.sse.Event;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared demand-driven SSE publisher core (review L1 dedup): the entire
 * concurrency machinery — backpressure-exact iteration on one virtual worker thread,
 * the idle-stall watchdog, client-cancel connection release, mid-stream error frames,
 * terminal-status threading, and the Reactive-Streams violation path — lives here
 * <b>once</b>. {@link SseChunkPublisher} (OpenAI face) and {@link AnthropicSsePublisher}
 * (Anthropic face) are thin subclasses that supply only what actually differs: how one
 * canonical {@link StreamChunk} becomes wire frames ({@link #feedFrames}), what clean
 * exhaustion appends ({@link #finishFrames} — the {@code [DONE]} sentinel vs the
 * {@code message_stop} family), and how a failure maps to a status + error frame
 * ({@link #errorOutcome}). The two hand-rolled copies this replaces had already drifted
 * once; the
 * next concurrency fix must land in exactly one place.
 *
 * <p><b>Demand unit = one SSE frame</b> (the RS contract — one {@code onNext} per
 * requested item, which the Micronaut SSE writer relies on): {@code request(n)} emits
 * exactly {@code n} frames. Internally the worker pulls one upstream chunk per refill
 * and fans the fed frames (1 chunk → N frames, or zero) through a pending queue, so a
 * role-only first chunk may produce one frame and a content-less chunk zero frames
 * (refilled without consuming demand). The worker blocks on the upstream iterator
 * between refills; iteration runs on one JDK virtual thread per stream
 * ({@code Thread.ofVirtual}, native-image clean on JDK 25); the eager upstream send
 * already happened in the controller's blocking pool.
 *
 * <p><b>Idle watchdog</b> (via the shared {@link SseWatchdog}): any stream whose
 * <em>upstream read</em> is idle &gt; the timeout is stall-closed — the upstream stream
 * is closed (unblocking a socket read) and a timeout error frame is emitted before
 * completion. "Idle" is activity-based: demand arrival, a completed upstream fetch, or
 * an emission all reset the timer, and the timer is armed only while the worker is
 * blocked on the upstream read (a subscriber parked at zero demand is client-paced and
 * never killed; a subscriber write in flight pauses the timer). The stall's timeout
 * error frame is deliberately emitted <em>outside demand accounting</em> — the only way
 * to surface a stall to a subscriber that requested frames and then went silent while
 * the upstream stopped producing. <b>Stall handling runs off the watchdog thread</b>
 *: the shared single-thread watchdog performs only the cheap liveness
 * checks and hands the state transition + the upstream close (whose onClose hooks run
 * governance settle — a possible DB write) to a fresh virtual thread, so one wedged
 * settle can never stall stall-detection for every other stream.
 *
 * <p><b>Terminal-outcome threading.</b> The {@link AtomicReference}
 * {@code terminalStatus} is set to the mapped error status (the controller's
 * {@code recordRequest} reads it on stream close) whenever a mid-stream error frame is
 * emitted or the watchdog stall fires — 504 for a stall, the failure's mapped status
 * otherwise — so a stream that failed mid-flight is <em>not</em> recorded in the 2xx
 * bucket. <b>Client cancel:</b> a cancel before clean exhaustion flips the
 * status 200 → {@value #STATUS_CLIENT_CLOSED} (nginx's client-closed-request
 * convention; the coarse metrics bucket folds it into 4xx) — a client abort is not a
 * successful 2xx response and must not inflate the success rate. The flip is a CAS from
 * the 200 default, so a mapped error status already recorded always wins. Clean
 * exhaustion leaves the 200 untouched. <b>The cancel-path close runs off the caller's
 * thread</b> (a fresh virtual thread, like the stall path): a client disconnect arrives
 * on the Netty event loop, and the close's onClose chain — the metrics hook, governance
 * settle/release (a synchronous DB write under the Postgres store), the adapter socket
 * close — must never block that loop; the status flip happens synchronously in
 * {@code cancel} so the close hook still observes it.
 *
 * <p>The worker is the only thread that calls {@code onNext/onError/onComplete}, so
 * subscriber signals are serialized. A non-positive {@code request(n)} is a Reactive
 * Streams protocol violation: the upstream is closed and the violation is delivered as
 * {@code onError} from the worker.
 */
abstract class SseStreamPublisher implements Publisher<Event<String>> {

    private static final Logger LOG = LoggerFactory.getLogger(SseStreamPublisher.class);

    /** nginx convention: the client closed the connection before the response ended. */
    static final int STATUS_CLIENT_CLOSED = 499;

    private static final String STALL_MESSAGE = "upstream stream stalled";

    /** Unique worker names (review L2: the old per-call builder restarted its counter at 0). */
    private static final AtomicLong WORKER_SEQ = new AtomicLong();

    protected final Stream<StreamChunk> upstream;
    protected final long idleTimeoutNanos;
    protected final AtomicReference<Integer> terminalStatus;

    /** One wire frame: the SSE data payload plus an optional {@code event:} name. */
    protected record SseFrame(String name, String dataJson) {}

    /** A failure's terminal outcome: the recorded status and the error frame to emit. */
    protected record ErrorOutcome(int status, SseFrame frame) {}

    /**
     * Feed one upstream chunk, appending zero-or-more wire frames to {@code pending}.
     * May throw — the worker converts a throwing feed into the error-frame path
     * (never a hang).
     */
    protected abstract void feedFrames(StreamChunk chunk, Deque<SseFrame> pending) throws Throwable;

    /**
     * Append the clean-exhaustion frames ({@code [DONE]} on the OpenAI face, the
     * {@code message_stop} family on the Anthropic face). May throw — handled like a
     * feed failure. Appending nothing (a zero-frame encoder) ends the stream with no
     * further emission.
     */
    protected abstract void finishFrames(Deque<SseFrame> pending) throws Throwable;

    /**
     * Map a failure to the recorded terminal status and the SSE error frame. Must not
     * throw (each subclass degrades a mapper or frame-encode crash to a fixed 500
     * frame — never a hang).
     */
    protected abstract ErrorOutcome errorOutcome(Throwable failure);

    /** The face's wire name for the stall/close bookkeeping logs. */
    protected abstract String faceName();

    SseStreamPublisher(Stream<StreamChunk> upstream, Duration idleTimeout, AtomicReference<Integer> terminalStatus) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.idleTimeoutNanos =
                Objects.requireNonNull(idleTimeout, "idleTimeout").toNanos();
        this.terminalStatus = Objects.requireNonNull(terminalStatus, "terminalStatus");
    }

    @Override
    public void subscribe(Subscriber<? super Event<String>> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        SubscriptionImpl subscription = new SubscriptionImpl(subscriber);
        subscriber.onSubscribe(subscription);
    }

    /** Worker-loop outcomes of {@link SubscriptionImpl#waitForDemand()}. */
    private enum Outcome {
        /** Demand available — fetch and emit frames for one demand unit. */
        PROCEED,
        /** Subscription was cancelled — exit without emitting. */
        CANCELLED,
        /** Watchdog stall fired — emit the timeout error frame, then complete. */
        STALLED,
        /** Non-positive request — emit onError, then exit. */
        VIOLATION
    }

    private final class SubscriptionImpl implements Subscription {

        private final Subscriber<? super Event<String>> subscriber;
        private final Thread worker;
        private final ScheduledFuture<?> watchdog;

        private long demand;
        private boolean cancelled;
        private boolean violated;
        private volatile boolean stalled;
        private volatile boolean done;
        private volatile boolean inWrite;
        /** True while the worker is parked in {@link #waitForDemand()} (client-paced). */
        private volatile boolean waitingForDemand;

        private volatile long lastActivityNanos;

        private SubscriptionImpl(Subscriber<? super Event<String>> subscriber) {
            this.subscriber = subscriber;
            this.lastActivityNanos = System.nanoTime();
            this.worker = Thread.ofVirtual()
                    .name("janus-sse-" + faceName() + "-", WORKER_SEQ.incrementAndGet())
                    .unstarted(this::run);
            this.watchdog = SseWatchdog.schedule(this::stallCheck, idleTimeoutNanos);
        }

        @Override
        public void request(long n) {
            boolean wasStarted = false;
            synchronized (SubscriptionImpl.this) {
                if (n <= 0) {
                    // Reactive Streams 3.9: a non-positive request is a protocol violation.
                    violated = true;
                    // Capture pre-start state under the lock: a worker started by THIS
                    // call must not be start-closed (its try-with-resources closes the
                    // stream after onError); one already running may be mid-fetch and
                    // needs the close to unblock it — closing a stream the worker has
                    // not yet reached iterator on would make iterator throw with
                    // no terminal signal (a hang).
                    wasStarted = worker.getState() != Thread.State.NEW;
                    if (!wasStarted) {
                        worker.start();
                    }
                    SubscriptionImpl.this.notifyAll();
                } else if (!cancelled && !done) {
                    demand = saturatingAdd(demand, n);
                    // Demand arrival is activity — a slow-but-alive client must not be
                    // stall-killed while it keeps requesting.
                    lastActivityNanos = System.nanoTime();
                    if (worker.getState() == Thread.State.NEW) {
                        worker.start();
                    }
                    SubscriptionImpl.this.notifyAll();
                }
            }
            if (n <= 0) {
                if (wasStarted) {
                    closeUpstreamOffCallerThread();
                }
                watchdog.cancel(false);
            }
        }

        @Override
        public void cancel() {
            synchronized (SubscriptionImpl.this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                // A client cancel before clean exhaustion is not a
                // successful 200 — flip the default to 499 (client-closed-request) so
                // the controller's close hook does not count an aborted stream in the
                // success bucket. Value-compared against the 200 default only (an
                // AtomicReference CAS would be reference-equality on boxed Integers):
                // an error status already recorded, or the clean-exhaustion 200 with
                // done=true, always wins.
                Integer current = terminalStatus.get();
                if (!done && current != null && current.intValue() == HttpStatus.OK.getCode()) {
                    terminalStatus.set(STATUS_CLIENT_CLOSED);
                }
                SubscriptionImpl.this.notifyAll();
            }
            closeUpstreamOffCallerThread();
            watchdog.cancel(false);
        }

        /**
         * Watchdog: stall-close streams idle longer than the timeout. The cheap
         * liveness checks run on the shared watchdog thread; the state transition and
         * the upstream close (whose onClose hooks run governance settle — possibly a
         * slow DB write) are handed to a fresh virtual thread: the single
         * watchdog thread must never block, or one wedged settle stalls stall-detection
         * for every other live stream.
         */
        private void stallCheck() {
            if (done) {
                watchdog.cancel(false);
                return;
            }
            if (inWrite) {
                // The worker is inside a subscriber write; a slow-to-complete write is
                // a live stream, not an upstream stall — pause stall protection while
                // the write is in flight.
                return;
            }
            if (System.nanoTime() - lastActivityNanos < idleTimeoutNanos) {
                return;
            }
            if (waitingForDemand) {
                // The worker is parked in waitForDemand — the stream is
                // client-paced, not upstream-stalled. A compliant subscriber that
                // requested frames, consumed them, and has not requested again is a
                // slow-but-alive client: never stall-close (the "never killed" contract
                // holds in the parked case too).
                return;
            }
            Thread.ofVirtual().start(this::handleStall);
        }

        /** The stall's state transition + unblocking close — off the watchdog thread. */
        private void handleStall() {
            synchronized (SubscriptionImpl.this) {
                if (cancelled || done) {
                    return;
                }
                stalled = true;
                // The stall close runs the controller's onClose hook
                // synchronously inside closeUpstream — the terminal outcome must be
                // recorded before that close. A mapper crash here must not
                // skip the unblock below — degrade to a fixed 500 (mirrors the
                // error-frame posture) and record it, then notifyAll.
                int status;
                try {
                    status = errorOutcome(stallError()).status();
                } catch (Throwable mappingFailure) {
                    status = HttpStatus.INTERNAL_SERVER_ERROR.getCode();
                }
                terminalStatus.set(status);
                SubscriptionImpl.this.notifyAll();
            }
            // Unblock a socket read on the worker (closing the stream releases the
            // upstream connection — close contract).
            closeUpstream();
            if (worker.getState() == Thread.State.NEW) {
                // Subscribed-but-never-requested: the worker will never consume this
                // stream, so nothing else cancels the repeating schedule — stop the
                // watchdog task now that the upstream has been released.
                watchdog.cancel(false);
            }
        }

        private void run() {
            try (Stream<StreamChunk> owned = upstream) {
                Iterator<StreamChunk> iterator;
                try {
                    iterator = owned.iterator();
                } catch (Throwable t) {
                    // A close landing between the worker's start and iterator — a
                    // request(n<=0) violation, or a cancel — makes iterator throw
                    // with no terminal signal. This must never escape the worker: an
                    // uncaught escape leaves the subscriber with neither onError nor
                    // onComplete (a hang). Deliver the terminal outcome instead.
                    if (cancelled) {
                        return;
                    }
                    if (violated) {
                        deliverViolation();
                        return;
                    }
                    emitErrorFrame(stalled ? stallError() : t);
                    finish();
                    return;
                }
                ArrayDeque<SseFrame> pending = new ArrayDeque<>();
                boolean exhausted = false;
                boolean finishQueued = false;
                while (true) {
                    Outcome outcome = waitForDemand();
                    switch (outcome) {
                        case CANCELLED -> {
                            return;
                        }
                        case STALLED -> {
                            emitErrorFrame(stallError());
                            finish();
                            return;
                        }
                        case VIOLATION -> {
                            deliverViolation();
                            return;
                        }
                        case PROCEED -> {
                            // one frame consumed below
                        }
                    }
                    synchronized (SubscriptionImpl.this) {
                        demand--;
                    }
                    if (cancelled) {
                        return;
                    }
                    // Fetch under demand: pull chunks until a frame is queued or the
                    // upstream is exhausted. A feed may produce zero frames
                    // (role/usage-only chunks); refilling keeps demand accounting
                    // frame-exact (exactly one onNext per demand unit — the RS
                    // contract the Micronaut SSE writer relies on).
                    while (pending.isEmpty() && !exhausted) {
                        try {
                            if (!iterator.hasNext()) {
                                exhausted = true;
                                break;
                            }
                            StreamChunk chunk = iterator.next();
                            // A completed fetch is activity — the upstream is alive.
                            lastActivityNanos = System.nanoTime();
                            synchronized (SubscriptionImpl.this) {
                                if (cancelled) {
                                    return; // no onNext after cancel
                                }
                            }
                            // The watchdog may have stall-closed the stream while we
                            // were blocked in next; deliver the timeout frame instead.
                            if (stalled) {
                                emitErrorFrame(stallError());
                                finish();
                                return;
                            }
                            feedFrames(chunk, pending);
                        } catch (Throwable t) {
                            if (cancelled) {
                                return;
                            }
                            // A close-induced unblock after a non-positive request is
                            // the violation signal, not a failure frame.
                            if (violated) {
                                deliverViolation();
                                return;
                            }
                            emitErrorFrame(stalled ? stallError() : t);
                            finish();
                            return;
                        }
                    }
                    if (pending.isEmpty()) {
                        // Exhausted: queue the terminal frames (the OpenAI face's
                        // [DONE] sentinel; the Anthropic face's message_stop family —
                        // no sentinel on that face, the last finish frame ends it).
                        if (!finishQueued) {
                            finishQueued = true;
                            try {
                                finishFrames(pending);
                            } catch (Throwable t) {
                                if (cancelled) {
                                    return;
                                }
                                emitErrorFrame(t);
                                finish();
                                return;
                            }
                        }
                        if (pending.isEmpty()) {
                            // Zero-frame finish (empty upstream + zero-feed encoder):
                            // nothing to emit.
                            finish();
                            return;
                        }
                    }
                    SseFrame frame = pending.removeFirst();
                    try {
                        emit(eventOf(frame));
                    } catch (Throwable t) {
                        // A subscriber violating RS 2.13 (throwing onNext) must not
                        // escape the worker without a terminal signal (a hang) —
                        // error frame then complete.
                        if (cancelled) {
                            return;
                        }
                        emitErrorFrame(t);
                        finish();
                        return;
                    }
                    if (exhausted && pending.isEmpty()) {
                        finish();
                        return;
                    }
                }
            } finally {
                watchdog.cancel(false);
            }
        }

        /**
         * Blocks until demand is available or a terminal state is reached. Returns
         * {@link Outcome#PROCEED} when a demand unit may be consumed, otherwise the
         * terminal outcome to act on. On interrupt (never expected) the interrupt flag
         * is restored and the loop exits as cancelled.
         */
        private Outcome waitForDemand() {
            synchronized (SubscriptionImpl.this) {
                waitingForDemand = true;
                while (demand == 0 && !cancelled && !stalled && !violated) {
                    try {
                        SubscriptionImpl.this.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Outcome.CANCELLED;
                    }
                }
                if (cancelled) {
                    return Outcome.CANCELLED;
                }
                if (stalled) {
                    stalled = false;
                    return Outcome.STALLED;
                }
                if (violated) {
                    return Outcome.VIOLATION;
                }
                waitingForDemand = false;
                return Outcome.PROCEED;
            }
        }

        /** A frame's wire event: named ({@code event:} header) or plain data. */
        private static Event<String> eventOf(SseFrame frame) {
            Event<String> event = Event.of(frame.dataJson());
            return frame.name() == null ? event : event.name(frame.name());
        }

        private void emit(Event<String> event) {
            synchronized (SubscriptionImpl.this) {
                if (cancelled) {
                    return; // no onNext after cancel
                }
                // Stall-vs-emit micro-race: the watchdog may have declared
                // the stall after the worker's `if (stalled)` check but before this
                // emit — re-check under the same lock the stall handler sets `stalled`
                // with, so a content frame is never delivered once the stall's 504 was
                // recorded.
                if (stalled) {
                    return;
                }
            }
            // A subscriber write is activity while it progresses: touch before AND
            // after so a slow (blocked) write is not misread as idle, and flag the
            // in-write window so the stall watchdog pauses — a write that exceeds the
            // idle timeout is still a live stream, never an upstream stall.
            inWrite = true;
            try {
                lastActivityNanos = System.nanoTime();
                subscriber.onNext(event);
                lastActivityNanos = System.nanoTime();
            } finally {
                inWrite = false;
            }
        }

        private void emitErrorFrame(Throwable failure) {
            synchronized (SubscriptionImpl.this) {
                if (cancelled) {
                    return; // no onNext after cancel
                }
            }
            // A mapper failure inside the worker would escape run with no
            // terminal signal (a hang) — the subclass's errorOutcome degrades to a
            // fixed 500 frame instead, and records the fixed status so the
            // controller's close hook still sees a 5xx.
            ErrorOutcome outcome = errorOutcome(failure);
            // A mid-stream error frame means the client received an error, not a
            // clean 200 — record the mapped status for the controller's close hook.
            terminalStatus.set(outcome.status());
            inWrite = true;
            try {
                lastActivityNanos = System.nanoTime();
                try {
                    subscriber.onNext(eventOf(outcome.frame()));
                } catch (Throwable t) {
                    // A subscriber that throws on the *error-frame* onNext has
                    // nowhere to go — swallow it so finish still delivers a terminal
                    // signal (a consistently-throwing subscriber must never leave the
                    // worker with neither onError nor onComplete — a hang).
                    LOG.warn("SSE subscriber rejected the error frame: {}", t.toString());
                }
                lastActivityNanos = System.nanoTime();
            } finally {
                inWrite = false;
            }
        }

        private void finish() {
            synchronized (SubscriptionImpl.this) {
                if (done || cancelled) {
                    return;
                }
                done = true;
            }
            try {
                subscriber.onComplete();
            } catch (Throwable t) {
                // A throwing subscriber on the terminal signal has nowhere to go.
                LOG.warn("SSE subscriber threw from onComplete: {}", t.toString());
            }
        }

        /** Deliver the protocol-violation onError, recording the mapped status (
         * LOW: a violation-terminated stream must not be recorded in the 2xx bucket by
         * the controller's close hook — consistent with every other failure path). */
        private void deliverViolation() {
            int status;
            try {
                status = errorOutcome(new IllegalArgumentException("request must be positive"))
                        .status();
            } catch (Throwable mappingFailure) {
                status = HttpStatus.INTERNAL_SERVER_ERROR.getCode();
            }
            terminalStatus.set(status);
            subscriber.onError(new IllegalArgumentException("request must be positive"));
        }

        /**
         * Run the upstream close off the calling thread. {@code cancel} (a client
         * disconnect — delivered by the framework's SSE writer on the Netty event
         * loop) and the non-positive-request path must not run {@link #closeUpstream}
         * inline: its onClose chain runs the metrics hook, governance settle/release
         * (a synchronous JDBC write under the Postgres store) and the adapter socket
         * close, and blocking the event loop on that round trip stalls every other
         * connection it serves. The same offload {@link #handleStall} already uses
         * for the identical close. Ordering is safe: settle/release is CAS-guarded
         * (idempotent) and the worker's try-with-resources close tolerates a close
         * that already ran.
         */
        private void closeUpstreamOffCallerThread() {
            Thread.ofVirtual().start(this::closeUpstream);
        }

        private void closeUpstream() {
            try {
                upstream.close();
            } catch (RuntimeException ignored) {
                // Best-effort release; the connection is gone either way.
            }
        }

        private static ProviderException stallError() {
            return new ProviderException(ProviderException.TYPE_TIMEOUT, STALL_MESSAGE);
        }

        private static long saturatingAdd(long left, long right) {
            long sum = left + right;
            return sum < 0 ? Long.MAX_VALUE : sum;
        }
    }
}
