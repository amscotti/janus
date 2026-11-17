package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link SseWatchdog} resilience: the shared single-thread stall-check schedule
 * must survive a throwing check (a throwing {@code scheduleWithFixedDelay} task would
 * otherwise be permanently suppressed, silently disabling stall protection). No
 * network.
 */
class SseWatchdogTest {

    @Test
    void throwingStallCheckIsLoggedAndKeepsItsSchedule() throws Exception {
        AtomicInteger throwingRuns = new AtomicInteger();
        AtomicInteger healthyRuns = new AtomicInteger();
        long delayNanos = TimeUnit.MILLISECONDS.toNanos(10);
        ScheduledFuture<?> bad = SseWatchdog.schedule(
                () -> {
                    throwingRuns.incrementAndGet();
                    throw new IllegalStateException("boom");
                },
                delayNanos);
        ScheduledFuture<?> good = SseWatchdog.schedule(healthyRuns::incrementAndGet, delayNanos);

        Thread.sleep(300);
        bad.cancel(false);
        good.cancel(false);

        assertTrue(
                throwingRuns.get() >= 3,
                "a throwing stall-check must be swallowed and keep firing, not be suppressed: " + throwingRuns.get());
        assertTrue(
                healthyRuns.get() >= 3,
                "a sibling throwing check must never disable another stream's schedule: " + healthyRuns.get());
    }

    @Test
    void cancelledStallChecksRemoveThemselvesFromTheLiveSet() throws Exception {
        // The ACTIVE set must not retain every ever-scheduled future — a
        // cancelled watchdog removes itself on cancel, so a long-lived gateway cannot
        // accumulate an unbounded set of dead futures (each pinning its subscription
        // graph) in the static set. The purge happens at cancel time, not on the next
        // scheduledTaskCount probe (which is now a pure live count, not a purge point).
        long baseline = stableCount();
        List<ScheduledFuture<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // Long delays: these tasks never fire during the test, so only the
            // cancellations below can remove them from the live set.
            tasks.add(SseWatchdog.schedule(() -> {}, TimeUnit.MINUTES.toNanos(30)));
        }
        assertEquals(baseline + 20, SseWatchdog.scheduledTaskCount(), "scheduling must register each watchdog task");

        for (ScheduledFuture<?> task : tasks) {
            assertTrue(task.cancel(false), "a scheduled watchdog must be cancellable");
        }
        assertEquals(
                baseline,
                SseWatchdog.scheduledTaskCount(),
                "cancelling must purge the live set immediately (self-removal on cancel)");
    }

    @Test
    void watchdogTaskDelegatesFutureQueriesAndOrdersByDelay() throws Exception {
        ScheduledFuture<?> near = SseWatchdog.schedule(() -> {}, TimeUnit.SECONDS.toNanos(5));
        ScheduledFuture<?> far = SseWatchdog.schedule(() -> {}, TimeUnit.MINUTES.toNanos(5));
        try {
            assertTrue(near.getDelay(TimeUnit.NANOSECONDS) > 0);
            assertTrue(far.getDelay(TimeUnit.NANOSECONDS) > near.getDelay(TimeUnit.NANOSECONDS));
            assertEquals(0, near.compareTo(near));
            assertTrue(near.compareTo(far) < 0);
            assertTrue(far.compareTo(near) > 0);
            assertThrows(TimeoutException.class, () -> near.get(1, TimeUnit.MILLISECONDS));
            assertFalse(near.isCancelled());
            assertFalse(near.isDone());
            Delayed externalDelayed = new Delayed() {
                @Override
                public long getDelay(TimeUnit unit) {
                    return unit.convert(10, TimeUnit.SECONDS);
                }

                @Override
                public int compareTo(Delayed o) {
                    return 0;
                }
            };
            assertTrue(near.compareTo(externalDelayed) < 0);
            assertTrue(far.compareTo(externalDelayed) > 0);
            assertTrue(near.cancel(false));
            assertTrue(near.isCancelled());
            assertTrue(near.isDone());
            assertThrows(CancellationException.class, near::get);
            assertThrows(CancellationException.class, () -> near.get(1, TimeUnit.MILLISECONDS));
        } finally {
            near.cancel(false);
            far.cancel(false);
        }
    }

    @Test
    void cancelledWatchdogStopsFiringAndRuntimeExceptionCallbackDoesNotBreakPool() throws Exception {
        long baseline = stableCount();
        AtomicInteger callsAfterCancel = new AtomicInteger();
        CountDownLatch firstFired = new CountDownLatch(1);
        ScheduledFuture<?> task = SseWatchdog.schedule(
                () -> {
                    callsAfterCancel.incrementAndGet();
                    firstFired.countDown();
                },
                TimeUnit.MILLISECONDS.toNanos(10));
        try {
            assertTrue(firstFired.await(2, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            int countAtCancel = callsAfterCancel.get();
            Thread.sleep(100);
            assertTrue(
                    callsAfterCancel.get() <= countAtCancel + 1,
                    "cancelled task must stop firing (saw " + callsAfterCancel.get() + " vs " + countAtCancel + ")");
            assertEquals(baseline, SseWatchdog.scheduledTaskCount());
        } finally {
            task.cancel(true);
        }

        // RuntimeException thrown inside callback is caught and logged while cancel(true) removes it cleanly
        CountDownLatch boomFired = new CountDownLatch(1);
        ScheduledFuture<?> throwing = SseWatchdog.schedule(
                () -> {
                    boomFired.countDown();
                    throw new RuntimeException("simulated onStall boom");
                },
                TimeUnit.MILLISECONDS.toNanos(10));
        try {
            assertTrue(boomFired.await(2, TimeUnit.SECONDS));
            assertTrue(throwing.cancel(true));
            assertTrue(throwing.isCancelled());
            assertEquals(baseline, SseWatchdog.scheduledTaskCount());
        } finally {
            throwing.cancel(true);
        }
    }

    /** A phase-stable watchdog-task count (two consecutive equal reads 20ms apart). */
    private static long stableCount() throws InterruptedException {
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
}
