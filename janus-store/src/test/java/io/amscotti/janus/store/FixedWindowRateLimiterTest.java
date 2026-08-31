package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.store.RateLimiter.RateLimitResult;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link FixedWindowRateLimiter}: epoch-aligned 60s windows, atomic
 * per-key/type counters (exact within a window under concurrency — the single
 * {@code compute} on the shard is the source of truth, Core.RateLimiter
 * semantics), {@code Retry-After} = seconds until the window end, and the documented
 * boundary-rollover imprecision (≤ a constant number of free requests per rollover per
 * shard — a request landing exactly on the boundary resets the window). Everything is
 * pinned against the {@link MutableClock}; {@code wouldExceed} never consumes and
 * {@code accumulate} consumes the TPM dimension, so the cap trips on the request
 * <em>after</em> the one that crossed for real tokens (the documented semantics).
 */
class FixedWindowRateLimiterTest {

    /** Epoch-aligned instant (epoch seconds divisible by 60): window starts at 0 mod 60. */
    private static final Instant START = Instant.parse("2026-08-03T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock);

    @Test
    void allowsUpToCapPerAlignedWindowThenDeniesWithRetryAfter() {
        for (int i = 1; i <= 3; i++) {
            RateLimitResult result = limiter.tryAcquire("k1", 3, 1);
            assertInstanceOf(RateLimitResult.Allowed.class, result, "request " + i + " must be allowed");
            assertEquals(i, ((RateLimitResult.Allowed) result).count());
        }
        RateLimitResult denied = limiter.tryAcquire("k1", 3, 1);
        assertInstanceOf(RateLimitResult.Denied.class, denied);
        // START is epoch-aligned: window end is exactly 60s away at a pinned clock.
        assertEquals(60, ((RateLimitResult.Denied) denied).retryAfterSeconds());
    }

    @Test
    void windowRolloverResetsTheCounter() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k1", 3, 1);
        }
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", 3, 1));

        clock.advanceSeconds(60); // exactly one aligned window later
        RateLimitResult result = limiter.tryAcquire("k1", 3, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, result, "rollover must reset the counter");
        assertEquals(1, ((RateLimitResult.Allowed) result).count(), "the new window starts at count 1");
    }

    @Test
    void wouldExceedNeverConsumes() {
        assertFalse(limiter.wouldExceed("k1", 100, 50), "nothing consumed yet");
        assertFalse(limiter.wouldExceed("k1", 100, 50), "a second check sees the same state");
        // A tryAcquire confirms the counter was untouched by the two checks.
        RateLimitResult result = limiter.tryAcquire("k1", 100, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, result);
        assertEquals(1, ((RateLimitResult.Allowed) result).count());
    }

    @Test
    void accumulateConsumesTokensAndLaterWouldExceedSeesIt() {
        assertEquals(30, limiter.accumulate("k1", 100, 30));
        assertFalse(limiter.wouldExceed("k1", 100, 70), "30 + 70 = 100 is exactly at the cap, not over");
        assertTrue(limiter.wouldExceed("k1", 100, 71), "30 + 71 = 101 crosses the cap");
        // accumulate is consume-at-finalize: repeated calls add up (real spend).
        assertEquals(80, limiter.accumulate("k1", 100, 50));
    }

    @Test
    void perKeyCountersAreIndependent() {
        limiter.tryAcquire("a", 1, 1);
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("a", 1, 1));
        RateLimitResult other = limiter.tryAcquire("b", 1, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, other, "key b shares no counter with key a");
        // The RPM and TPM dimensions on the same key are independent shards too.
        RateLimitResult tpm = limiter.tryAcquire("a", 100, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, tpm, "RPM denial does not touch the TPM shard");
    }

    @Test
    void retryAfterIsNonNegativeAndApproachesZeroAtRollover() throws Exception {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k1", 5, 1);
        }
        RateLimitResult denied = limiter.tryAcquire("k1", 5, 1);
        assertInstanceOf(RateLimitResult.Denied.class, denied);
        long retryAfter = ((RateLimitResult.Denied) denied).retryAfterSeconds();
        assertTrue(retryAfter >= 0, "Retry-After must never be negative");
        assertEquals(60, retryAfter);

        // One second before the window end the Retry-After is 1; at the rollover the
        // window resets and the same request is allowed again.
        clock.advanceSeconds(59);
        RateLimitResult nearEnd = limiter.tryAcquire("k1", 5, 1);
        assertInstanceOf(RateLimitResult.Denied.class, nearEnd);
        assertEquals(1, ((RateLimitResult.Denied) nearEnd).retryAfterSeconds());

        clock.advanceSeconds(1); // exactly at the rollover boundary
        RateLimitResult afterRollover = limiter.tryAcquire("k1", 5, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, afterRollover, "rollover resets the counter");
        assertEquals(1, ((RateLimitResult.Allowed) afterRollover).count());
    }

    @Test
    void concurrentBurstAdmitsExactlyTheCap() throws Exception {
        int cap = 100;
        int threads = 16;
        int perThread = 20; // 320 attempts against a cap of 100
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.tryAcquire("burst", cap, 1) instanceof RateLimitResult.Allowed) {
                            allowed.incrementAndGet();
                        }
                    }
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "burst threads must finish");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(cap, allowed.get(), "exactly the cap is admitted; the atomic counter is the source of truth");
    }

    @Test
    void validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("k1", 0, 1), "limit must be positive");
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("k1", -3, 1));
        assertThrows(
                IllegalArgumentException.class, () -> limiter.tryAcquire("k1", 10, -1), "cost must be non-negative");
        assertThrows(IllegalArgumentException.class, () -> limiter.wouldExceed("k1", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> limiter.wouldExceed("k1", 10, -1));
        assertThrows(IllegalArgumentException.class, () -> limiter.accumulate("k1", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> limiter.accumulate("k1", 10, -1));
        assertThrows(NullPointerException.class, () -> limiter.tryAcquire(null, 10, 1));
    }

    @Test
    void costExceedingLimitDeniesFreshWithoutConsuming() {
        // The interface contract "Allowed iff existing + cost ≤ limit": a cost above
        // the cap on a fresh shard is denied and the counter stays untouched.
        RateLimitResult denied = limiter.tryAcquire("k1", 3, 5);
        assertInstanceOf(RateLimitResult.Denied.class, denied, "cost > limit must deny even on a fresh shard");
        RateLimitResult next = limiter.tryAcquire("k1", 3, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, next, "the denied cost-5 request consumed nothing");
        assertEquals(1, ((RateLimitResult.Allowed) next).count());
    }

    @Test
    void accumulateRollsOverToAFreshWindowAndWouldExceedRollsWithoutConsuming() {
        assertEquals(30, limiter.accumulate("k1", 100, 30));
        assertTrue(limiter.wouldExceed("k1", 100, 71), "30 consumed + 71 estimate > 100");

        clock.advanceSeconds(60); // window W+1: the accumulated 30 belongs to the stale window
        assertFalse(limiter.wouldExceed("k1", 100, 1), "the stale window's 30 no longer counts");
        // wouldExceed is a pure read — it rolls nothing; the settle's own
        // rollover starts the new window from zero, not from the stale 30.
        assertEquals(10, limiter.accumulate("k1", 100, 10), "the new window starts fresh for accumulate too");
        assertTrue(limiter.wouldExceed("k1", 100, 91), "10 consumed + 91 estimate crosses; 10 + 90 fits");
        assertFalse(limiter.wouldExceed("k1", 100, 90));
    }

    @Test
    void wouldExceedNeverMutatesStateNotEvenOnARolledWindow() {
        // Purity pin: a wouldExceed landing after a window rollover must
        // leave the entry untouched — observable by stepping the clock BACK (NTP slew)
        // and settling in the old window. The mutating implementation would have
        // replaced the entry with a rolled count-0 window; the forward-rollover-only
        // rule then keeps that phantom window and the settle sums from zero (40).
        // The pure implementation leaves the original 30-count entry in place and the
        // settle sums onto it (70).
        assertEquals(30, limiter.accumulate("k1", 100, 30));
        clock.advanceSeconds(60); // window W+1
        assertFalse(limiter.wouldExceed("k1", 100, 1));
        clock.advanceSeconds(-60); // step back into W (NTP slew)
        assertEquals(70, limiter.accumulate("k1", 100, 40), "the pre-check must not have rolled the entry");
    }

    @Test
    void clockStepBackDoesNotResetTheCounter() {
        for (int i = 0; i < 2; i++) {
            assertInstanceOf(RateLimitResult.Allowed.class, limiter.tryAcquire("k1", 2, 1));
        }
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", 2, 1), "cap reached in window 0");

        clock.advanceSeconds(60); // window 60
        assertInstanceOf(RateLimitResult.Allowed.class, limiter.tryAcquire("k1", 2, 1));
        assertInstanceOf(RateLimitResult.Allowed.class, limiter.tryAcquire("k1", 2, 1));
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", 2, 1));

        clock.advanceSeconds(60); // window 120
        assertInstanceOf(RateLimitResult.Allowed.class, limiter.tryAcquire("k1", 2, 1));
        assertInstanceOf(RateLimitResult.Allowed.class, limiter.tryAcquire("k1", 2, 1));
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", 2, 1));

        // Clock steps back 90s: the recomputed window (0) is *behind* the stored one
        // (120). A naive !=-rollover would reset the counter mid-window and re-admit.
        clock.advanceSeconds(-90);
        assertInstanceOf(
                RateLimitResult.Denied.class,
                limiter.tryAcquire("k1", 2, 1),
                "a stepped-back clock keeps the stored window — no spurious re-admission");
    }

    @Test
    void capCheckSurvivesHugeCostsWithoutOverflowWrapping() {
        // cost near Long.MAX: a wrapped count + cost would go negative and *pass* the
        // cap check — the overflow-safe comparison must still deny.
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", 5, Long.MAX_VALUE));
        assertTrue(limiter.wouldExceed("k1", 5, Long.MAX_VALUE), "a huge estimate always exceeds");
        assertTrue(limiter.wouldExceed("k1", 5, 6));
        assertFalse(limiter.wouldExceed("k1", 5, 5));
        // A count pushed toward the cap by accumulate must not wrap the compare either.
        limiter.accumulate("k1", 5, 4);
        assertTrue(limiter.wouldExceed("k1", 5, Long.MAX_VALUE));
        // accumulate's unbounded window total uses addExact: a settle past the wrap
        // point throws instead of silently wrapping (Postgres bigint parity).
        limiter.accumulate("k2", 5, Long.MAX_VALUE); // 0 + MAX is exact
        assertThrows(ArithmeticException.class, () -> limiter.accumulate("k2", 5, 1), "count + actual must not wrap");
    }

    @Test
    void idleEntriesAreEvictedByTheJanitorAndTheMapStaysBounded() {
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("k" + i, 5, 1);
        }
        assertEquals(10, limiter.shardCount(), "ten distinct keys created ten shards");

        // Idle longer than the eviction TTL (which spans 10 windows): the window rolls
        // and a fresh access still resets correctly…
        clock.advanceMillis(FixedWindowRateLimiter.IDLE_TTL_MILLIS + 1);
        RateLimitResult result = limiter.tryAcquire("k0", 5, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, result);
        assertEquals(1, ((RateLimitResult.Allowed) result).count(), "rollover still resets the counter");

        // …and the janitor drops the idle-stale keys, leaving only the just-touched one.
        limiter.pruneStale();
        assertEquals(1, limiter.shardCount(), "idle entries are evicted, not just replaced");
        assertInstanceOf(
                RateLimitResult.Allowed.class,
                limiter.tryAcquire("k1", 5, 1),
                "an evicted key restarts on a fresh shard");
    }

    @Test
    void debtFromAccumulatePastTheCapMakesAnyEstimateExceed() {
        // Coverage: after accumulate pushes the TPM count past the cap, the
        // fixed-window debt case — ANY estimate (including 0) crosses, because the
        // overflow-safe check reads `estimate > limit − count` with a negative
        // `limit − count`.
        assertEquals(130, limiter.accumulate("k1", 100, 130), "the settle pushes the window total past the cap");
        assertTrue(limiter.wouldExceed("k1", 100, 0), "in debt, even the boundary 0 estimate crosses");
        assertTrue(limiter.wouldExceed("k1", 100, 1), "…and a cost-1 estimate certainly crosses");
        // The debt is window-scoped: the next aligned window starts fresh.
        clock.advanceSeconds(60);
        assertFalse(limiter.wouldExceed("k1", 100, 1), "the stale window's debt no longer counts");
    }

    @Test
    void wouldExceedClockStepBackKeepsTheStoredAheadWindow() {
        // Coverage: the stored-ahead-window conservative branch — under a
        // stepped-back clock the recomputed window trails the stored one, so wouldExceed
        // applies the stored (future) window's counter instead of resetting to zero.
        limiter.accumulate("k1", 100, 40); // window 0, count 40
        clock.advanceSeconds(60); // window 60
        limiter.wouldExceed("k1", 100, 1); // rolls (non-consuming) into window 60, count 0
        limiter.accumulate("k1", 100, 40); // window 60, count 40
        assertFalse(limiter.wouldExceed("k1", 100, 60), "40 consumed: a 60-token estimate fits exactly");
        assertTrue(limiter.wouldExceed("k1", 100, 61), "…a 61-token estimate crosses");

        // Clock steps back 60s: the recomputed window (0) trails the stored one (60).
        // A naive !=-rollover would reset the counter to zero and under-deny; the
        // forward-only guard keeps the stored window's count (the conservative branch).
        clock.advanceSeconds(-60);
        assertTrue(
                limiter.wouldExceed("k1", 100, 61),
                "a stepped-back clock keeps the stored ahead-window counter (no spurious reset)");
        assertFalse(limiter.wouldExceed("k1", 100, 60), "the stored count still applies exactly");
    }
}
