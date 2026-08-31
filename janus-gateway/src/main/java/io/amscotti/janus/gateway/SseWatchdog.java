package io.amscotti.janus.gateway;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared SSE idle watchdog : one daemon
 * {@code ScheduledThreadPoolExecutor} (a single watchdog thread for all streams — idle
 * timeouts are per-subscription) holding the stall-check schedule used by both SSE
 * publishers. Extracted verbatim from {@link SseChunkPublisher}'s private static
 * executor; behavioral no-op, pinned by {@code SseChunkPublisherTest} staying green
 * unchanged.
 */
final class SseWatchdog {

    private static final Logger LOG = LoggerFactory.getLogger(SseWatchdog.class);

    private static final ScheduledThreadPoolExecutor EXECUTOR = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "janus-sse-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    /** Every live stall-check (test seam — a {@link WatchdogTask} removes itself on cancel). */
    private static final Set<ScheduledFuture<?>> ACTIVE = ConcurrentHashMap.newKeySet();

    private SseWatchdog() {}

    /**
     * Fixed-delay stall-check schedule (delay = the idle timeout). The command is
     * wrapped in a try/catch: {@code ScheduledThreadPoolExecutor} permanently
     * suppresses a periodic task whose execution throws (the pool has a single
     * thread shared by every stream, so a misbehaving check must never cascade into
     * silently disabling stall protection). A throwing check is logged and its
     * schedule continues.
     */
    static ScheduledFuture<?> schedule(Runnable command, long delayNanos) {
        ScheduledFuture<?> future = new WatchdogTask(EXECUTOR.scheduleWithFixedDelay(
                () -> {
                    try {
                        command.run();
                    } catch (RuntimeException e) {
                        LOG.warn("SSE stall-check threw; continuing the watchdog schedule: {}", e.toString());
                    }
                },
                delayNanos,
                delayNanos,
                TimeUnit.NANOSECONDS));
        ACTIVE.add(future);
        return future;
    }

    /**
     * Test seam: the number of live stall-check tasks. A cancelled {@link WatchdogTask}
     * removes itself from {@link #ACTIVE} on {@link Future#cancel}, so this is a count
     * of live entries — never a purge point (the set must not retain every
     * ever-scheduled future for the process lifetime).
     */
    static int scheduledTaskCount() {
        return ACTIVE.size();
    }

    /**
     * {@link ScheduledFuture} wrapper that removes itself from {@link #ACTIVE} when it
     * is cancelled — otherwise every completed/cancelled stream would pin its
     * {@code ScheduledFuture} — and through it the whole subscription graph: worker
     * thread, subscriber, and the closed-but-referenced upstream — in the static set
     * for the process lifetime (unbounded slow memory growth on a
     * long-lived gateway). The executor's delay queue self-purges cancelled periodic
     * tasks lazily on poll; the set does not — hence the explicit removal here. The
     * wrapper is the only value ever stored in {@link #ACTIVE}, and a periodic
     * {@code scheduleWithFixedDelay} future never completes on its own, so cancel is
     * the single exit from the live set.
     */
    private static final class WatchdogTask implements ScheduledFuture<Void> {

        private final ScheduledFuture<?> delegate;

        WatchdogTask(ScheduledFuture<?> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean result = delegate.cancel(mayInterruptIfRunning);
            ACTIVE.remove(this);
            return result;
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public Void get() throws InterruptedException, ExecutionException {
            delegate.get();
            return null;
        }

        @Override
        public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            delegate.get(timeout, unit);
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return delegate.getDelay(unit);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) {
                return 0;
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
