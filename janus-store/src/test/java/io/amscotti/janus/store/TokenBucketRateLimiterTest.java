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
 * {@link TokenBucketRateLimiter}, the sliding-window variant: capacity =
 * limit, refill = limit/60 tokens per second, atomic CAS refill on access (no
 * scheduler — {@code compute} per shard, so concurrent requests cannot double-refill or
 * over-admit past capacity), {@code Retry-After} = ceil(deficit ÷ rate) seconds until
 * the next token, and lazy idle eviction (an entry untouched for ≥ {@value
 * TokenBucketRateLimiter#IDLE_TTL_MILLIS} ms is replaced by a fresh bucket on next
 * access — the map stays bounded without a janitor thread). {@code wouldExceed}/
 * {@code accumulate} mirror the fixed-window TPM semantics (pre-check non-consuming,
 * consume at finalize with debt allowed so the cap trips on the request after the one
 * that crossed).
 */
class TokenBucketRateLimiterTest {

    private static final Instant START = Instant.parse("2026-08-03T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(clock);

    @Test
    void capacityBurstThenRefillsAtLimitPer60Seconds() {
        int limit = 60; // refill rate = 1 token/second
        for (int i = 0; i < limit; i++) {
            assertInstanceOf(
                    RateLimitResult.Allowed.class, limiter.tryAcquire("k1", limit, 1), "burst within capacity");
        }
        RateLimitResult denied = limiter.tryAcquire("k1", limit, 1);
        assertInstanceOf(RateLimitResult.Denied.class, denied);
        assertEquals(1, ((RateLimitResult.Denied) denied).retryAfterSeconds(), "1 token deficit at 1 token/s");

        clock.advanceSeconds(1);
        assertInstanceOf(
                RateLimitResult.Allowed.class, limiter.tryAcquire("k1", limit, 1), "refill restores one token");
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", limit, 1), "only one token refilled");
    }

    @Test
    void burstBeyondCapacityDeniedWithDeficitDividedByRate() {
        int limit = 30; // refill rate = 0.5 tokens/second
        for (int i = 0; i < limit; i++) {
            limiter.tryAcquire("k1", limit, 1);
        }
        RateLimitResult denied = limiter.tryAcquire("k1", limit, 1);
        assertInstanceOf(RateLimitResult.Denied.class, denied);
        assertEquals(2, ((RateLimitResult.Denied) denied).retryAfterSeconds(), "ceil(1 / 0.5) = 2 seconds");
    }

    @Test
    void idleRefillAccumulatesToCapacityButNeverOver() {
        int limit = 10; // refill rate = 10/60 per second
        for (int i = 0; i < limit; i++) {
            limiter.tryAcquire("k1", limit, 1);
        }
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", limit, 1), "bucket is empty");

        clock.advanceSeconds(10_000); // long idle: refill clamps at capacity
        for (int i = 0; i < limit; i++) {
            assertInstanceOf(
                    RateLimitResult.Allowed.class,
                    limiter.tryAcquire("k1", limit, 1),
                    "idle refill restored the full capacity");
        }
        assertInstanceOf(
                RateLimitResult.Denied.class, limiter.tryAcquire("k1", limit, 1), "no over-accumulation past capacity");
    }

    @Test
    void wouldExceedAndAccumulateMirrorFixedWindowSemantics() {
        assertFalse(limiter.wouldExceed("k1", 100, 100), "full bucket: the estimate fits exactly");
        assertTrue(limiter.wouldExceed("k1", 100, 101), "an estimate past capacity would exceed");
        // wouldExceed is non-consuming: a tryAcquire still sees the full bucket.
        assertInstanceOf(RateLimitResult.Allowed.class, limiter.tryAcquire("k1", 100, 1));

        assertEquals(30, limiter.accumulate("k1", 100, 30), "accumulate returns the window total: 30 consumed");
        // The pre-check mirrors the fixed-window boundary: 30 consumed ⇒ 70 tokens
        // available; an estimate over the *available* tokens crosses (70 fits exactly,
        // 71 crosses) — the inverted tokens+estimate>capacity check is the C1 bug.
        assertFalse(limiter.wouldExceed("k1", 100, 70), "70 tokens remain: 70 + 70 = 100 fits");
        assertTrue(limiter.wouldExceed("k1", 100, 71), "70 tokens remain: 70 + 71 = 101 crosses");
    }

    @Test
    void lazyIdleEvictionDropsLongUntouchedEntries() {
        int limit = 5;
        for (int i = 0; i < limit; i++) {
            limiter.tryAcquire("k1", limit, 1);
        }
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", limit, 1));

        // Idle longer than the eviction TTL: the next access replaces the stale bucket
        // with a fresh one (the map entry "disappears" — bounded memory without a janitor).
        clock.advanceMillis(TokenBucketRateLimiter.IDLE_TTL_MILLIS + 1);
        RateLimitResult result = limiter.tryAcquire("k1", limit, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, result, "evicted entry starts fresh at full capacity");
        for (int i = 0; i < limit - 1; i++) {
            assertInstanceOf(RateLimitResult.Allowed.class, limiter.tryAcquire("k1", limit, 1));
        }
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", limit, 1), "fresh bucket has capacity");
    }

    @Test
    void concurrentBurstAdmitsExactlyCapacityWithSingleRefill() throws Exception {
        int cap = 100;
        int threads = 16;
        int perThread = 20;
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
        assertEquals(cap, allowed.get(), "no over-admission past capacity under concurrency");
    }

    @Test
    void validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("k1", 0, 1), "capacity must be positive");
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("k1", -3, 1));
        assertThrows(
                IllegalArgumentException.class, () -> limiter.tryAcquire("k1", 10, -1), "cost must be non-negative");
        assertThrows(IllegalArgumentException.class, () -> limiter.wouldExceed("k1", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> limiter.accumulate("k1", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> limiter.accumulate("k1", 10, -1));
        assertThrows(NullPointerException.class, () -> limiter.tryAcquire(null, 10, 1));
    }

    @Test
    void costExceedingCapacityDeniesFreshWithoutConsuming() {
        RateLimitResult denied = limiter.tryAcquire("k1", 3, 5);
        assertInstanceOf(RateLimitResult.Denied.class, denied, "cost > capacity must deny even on a full bucket");
        RateLimitResult next = limiter.tryAcquire("k1", 3, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, next, "the denied cost-5 request consumed nothing");
        assertEquals(2, ((RateLimitResult.Allowed) next).count(), "3 − 1 = 2 whole tokens remain");
    }

    @Test
    void costExceedingCapacityDeniesPermanently() {
        // A cost > capacity denial can never reopen (tokens cap at capacity),
        // so the deficit-derived retry-after would advertise a false reopen — it must
        // saturate to the never-reopening ceiling (review L2: 24 h, not the wire-absurd
        // Long.MAX_VALUE), never a bounded reopen time.
        RateLimitResult denied = limiter.tryAcquire("k1", 3, 5);
        assertInstanceOf(RateLimitResult.Denied.class, denied);
        assertEquals(
                TokenBucketRateLimiter.MAX_RETRY_AFTER_SECONDS,
                ((RateLimitResult.Denied) denied).retryAfterSeconds(),
                "cost > capacity saturates Retry-After — the gate never reopens");

        // A long refill cannot change the verdict: tokens refill toward capacity (3),
        // which is still < cost (5), so the cost-5 request is denied forever.
        clock.advanceMillis(60 * 60 * 1000); // an hour: the bucket is full again
        assertInstanceOf(
                RateLimitResult.Denied.class,
                limiter.tryAcquire("k1", 3, 5),
                "a later refill never admits a cost above capacity");
        // …while a cost within capacity is admitted normally.
        assertInstanceOf(RateLimitResult.Allowed.class, limiter.tryAcquire("k1", 3, 1));
    }

    @Test
    void fractionalRemainderIsRetainedInTheStoredBucketNotFloored() {
        // The stored bucket keeps the EXACT fractional remainder after an
        // allowed request (only the reported Allowed.count is floored). Flooring the
        // stored state would lose the half-token and delay the next admission by a
        // refill step — the documented drift below limit/60.
        int limit = 10; // refill = 10/60 per second = 1 token per 6s
        for (int i = 0; i < limit; i++) {
            limiter.tryAcquire("k1", limit, 1);
        }
        clock.advanceSeconds(9); // +1.5 tokens → bucket holds 1.5
        RateLimitResult first = limiter.tryAcquire("k1", limit, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, first, "1.5 ≥ 1 admits");
        assertEquals(0, ((RateLimitResult.Allowed) first).count(), "0 whole tokens remain (0.5 remainder)");

        clock.advanceSeconds(3); // +0.5 from the retained exact 0.5 remainder → 1.0
        assertInstanceOf(
                RateLimitResult.Allowed.class,
                limiter.tryAcquire("k1", limit, 1),
                "the exact remainder makes the next refill admit on time");
    }

    @Test
    void longRunAdmissionDoesNotDriftBelowConfiguredRate() {
        // With exact-double storage the long-run admission rate holds
        // limit/60. A floored stored bucket loses up to one token per consumed request
        // at fractional refill steps and drifts below the configured rate (over 600s
        // the exact bucket admits 109, the floored one 94). Assert the full
        // configured rate is reached: 10 windows × 10 tokens = 100 (plus the initial
        // full bucket, which the exact implementation also spends).
        int limit = 10; // refill = 1 token per 6s (fractional per second — drift-prone)
        int admitted = 0;
        for (int s = 0; s < 600; s++) { // ten 60s windows
            clock.advanceSeconds(1);
            if (limiter.tryAcquire("k1", limit, 1) instanceof RateLimitResult.Allowed) {
                admitted++;
            }
        }
        assertTrue(admitted >= 100, "admitted " + admitted + " ≥ the 10-window rate of 100 (no fractional drift)");
    }

    @Test
    void accumulateReturnsWindowTotalLikeFixedWindow() {
        // The token bucket returns the SAME quantity the fixed-window
        // contract pins — the post-accumulation window total (net tokens consumed).
        // For the sequence actual 30 then 40 of 100: fixed returns 30 then 70, and
        // the token bucket now returns 30 then 70 too (the old divergence — 70 then
        // 30 "tokens remaining" — is resolved so a future consumer of the return gets
        // identical values regardless of [janus.limits] window).
        assertEquals(30, limiter.accumulate("k1", 100, 30), "window total = 30 consumed");
        assertEquals(70, limiter.accumulate("k1", 100, 40), "window total = 30 + 40 consumed");
    }

    @Test
    void negativeDebtFromAccumulateDeniesWouldExceedUntilRefill() {
        assertEquals(130, limiter.accumulate("k1", 100, 130), "window total counts the 130-token real spend");
        assertTrue(limiter.wouldExceed("k1", 100, 1), "in debt, even a 1-token estimate crosses");
        assertTrue(limiter.wouldExceed("k1", 100, 0), "in debt, the boundary estimate also crosses");

        clock.advanceSeconds(18); // refills 18 × 100/60 = 30 → debt repaid exactly
        assertTrue(limiter.wouldExceed("k1", 100, 1), "at 0 tokens a cost-1 estimate still crosses");
        assertFalse(limiter.wouldExceed("k1", 100, 0), "at 0 tokens a 0 estimate fits exactly");

        clock.advanceSeconds(1); // 100/60 more tokens accrete
        assertFalse(limiter.wouldExceed("k1", 100, 1), "refilled past 1 token: a cost-1 estimate fits");
        assertTrue(limiter.wouldExceed("k1", 100, 2), "…but a cost-2 estimate crosses");
    }

    @Test
    void clockStepBackDoesNotDrainTheBucket() {
        int limit = 10; // refill = 10/60 per second = 1 token per 6s
        for (int i = 0; i < limit; i++) {
            limiter.tryAcquire("k1", limit, 1);
        }
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", limit, 1), "bucket empty");

        clock.advanceSeconds(6); // exactly one token refilled
        assertInstanceOf(RateLimitResult.Allowed.class, limiter.tryAcquire("k1", limit, 1));
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", limit, 1), "spent again");

        // Clock steps back 30s (NTP slew): the negative elapsed would drain the bucket
        // (tokens → −5) without the clamp — the request is denied either way, but the
        // bucket must NOT have been drained…
        clock.advanceSeconds(-30);
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", limit, 1));

        // …so 6s of forward refill restores a full token. (Without the clamp the −5
        // drain would still be in the bucket and this would deny.)
        clock.advanceSeconds(6);
        assertInstanceOf(
                RateLimitResult.Allowed.class,
                limiter.tryAcquire("k1", limit, 1),
                "a stepped-back clock must not drain the refill");
    }

    @Test
    void fractionalBoundaryReportedCountMatchesFollowingRequest() {
        int limit = 10; // refill = 10/60 per second
        for (int i = 0; i < 9; i++) {
            limiter.tryAcquire("k1", limit, 1);
        }
        clock.advanceSeconds(3); // +0.5 tokens → 1.5 available
        RateLimitResult allowed = limiter.tryAcquire("k1", limit, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, allowed);
        // Allowed.count = floor(1.5 − 1) = 0 whole tokens — the REPORTED count is
        // floored, so it is exactly what the next request's cost-1 check sees (0.5
        // stored ≥ 1 is false) even though the stored bucket keeps the exact 0.5.
        assertEquals(
                0, ((RateLimitResult.Allowed) allowed).count(), "0 whole tokens remain after consuming 0.5's worth");
        assertInstanceOf(
                RateLimitResult.Denied.class,
                limiter.tryAcquire("k1", limit, 1),
                "the reported 0 whole tokens match the immediately-following request");
    }

    @Test
    void wouldExceedOnIdleExpiredEntryReplacesWithFreshBucket() {
        // Coverage: the stale-entry replacement branch of wouldExceed —
        // an EXISTING entry idle past the TTL is replaced by a fresh bucket (a brand-
        // new key, by contrast, creates no entry: `entry == null ? null : fresh`).
        int limit = 10;
        limiter.accumulate("k1", limit, 8); // 8 consumed ⇒ 2 available
        assertTrue(limiter.wouldExceed("k1", limit, 3), "2 available: a 3-token estimate crosses");

        clock.advanceMillis(TokenBucketRateLimiter.IDLE_TTL_MILLIS + 1);
        assertFalse(limiter.wouldExceed("k1", limit, 9), "the stale bucket is replaced: 9 ≤ 10 fits on a fresh one");
        assertTrue(limiter.wouldExceed("k1", limit, 11), "…an over-capacity estimate still crosses");
        // The replaced entry is a fresh full bucket: a settle in the same access sees it
        // (from full capacity ⇒ window total 8; the stale 2-available bucket would
        // have returned a 16-token window total for the same actual).
        assertEquals(
                8, limiter.accumulate("k1", limit, 8), "a fresh bucket settles from full capacity, not the stale 2");
    }

    @Test
    void accumulateOnIdleExpiredEntryStartsFromFreshBucket() {
        // Coverage: accumulate's stale-entry replacement — an entry idle
        // past the TTL is replaced by a fresh full bucket before the settle, so a
        // long-idle key's debt does not carry across an idle gap.
        int limit = 10;
        limiter.accumulate("k1", limit, 30); // 30 consumed ⇒ −20 debt
        assertTrue(limiter.wouldExceed("k1", limit, 1), "in debt");

        clock.advanceMillis(TokenBucketRateLimiter.IDLE_TTL_MILLIS + 1);
        assertEquals(7, limiter.accumulate("k1", limit, 7), "the stale debt is forgotten: a fresh bucket settles 7");
        assertFalse(limiter.wouldExceed("k1", limit, 3), "7 consumed ⇒ 3 available: a 3-token estimate fits exactly");
    }

    @Test
    void wouldExceedClockStepBackDoesNotDrainTheBucket() {
        // Coverage: the non-consuming pre-check refills from the stored
        // bucket on every access; a stepped-back clock must clamp the refill elapsed
        // to ≥ 0 there too (only tryAcquire's refill was previously pinned).
        int limit = 10; // refill = 1 token per 6s
        limiter.accumulate("k1", limit, 5); // 5 consumed ⇒ 5 available
        assertFalse(limiter.wouldExceed("k1", limit, 4), "5 available: a 4-token estimate fits");
        assertTrue(limiter.wouldExceed("k1", limit, 6), "…a 6-token estimate crosses");

        // Step the clock back 30s: the negative elapsed would drain 5 tokens (→ 0)
        // and spuriously deny the exact-fit estimate, without the clamp.
        clock.advanceSeconds(-30);
        assertFalse(
                limiter.wouldExceed("k1", limit, 4),
                "a stepped-back clock must not drain the pre-check's bucket (clamped elapsed ≥ 0)");
    }

    @Test
    void fractionalDebtAccumulateReturnsWindowTotalAndWouldExceedReadsTheExactDebt() {
        // Coverage: accumulate against a fractional bucket. The
        // stored bucket keeps the EXACT debt (−0.5) and the return is the floored
        // WINDOW TOTAL (10), not floor(−0.5) = −1 — so a caller can never confuse the
        // return with the stored bucket, and wouldExceed reads the true debt (−0.5):
        // even a 0-token estimate crosses, which it would NOT against a floored −1.
        int limit = 10; // refill = 1 token per 6s
        limiter.accumulate("k1", limit, 5); // 5 consumed ⇒ 5 available
        clock.advanceSeconds(3); // +0.5 → bucket holds 5.5
        assertEquals(
                10,
                limiter.accumulate("k1", limit, 6),
                "a 6-token settle against 5.5 available = 0.5 debt ⇒ window total floor(10.5) = 10");
        assertTrue(limiter.wouldExceed("k1", limit, 0), "the exact −0.5 debt crosses even a 0-token estimate");
        assertTrue(limiter.wouldExceed("k1", limit, 1), "…and a cost-1 estimate certainly crosses");

        clock.advanceSeconds(3); // +0.5 repays the debt exactly → 0.0 available
        assertFalse(limiter.wouldExceed("k1", limit, 0), "at exactly 0 available a 0-token estimate fits");
        assertTrue(limiter.wouldExceed("k1", limit, 1), "…but a cost-1 estimate still crosses");
    }

    @Test
    void idleEntriesAreEvictedByTheJanitorAndTheMapStaysBounded() {
        // Lazy eviction never fires for a key that stops being
        // accessed (revoked/abandoned) — the janitor keeps the map bounded.
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("k" + i, 5, 1);
        }
        assertEquals(10, limiter.bucketCount(), "ten distinct keys created ten buckets");

        clock.advanceMillis(TokenBucketRateLimiter.IDLE_TTL_MILLIS + 1);
        // a returning key gets a fresh full bucket…
        RateLimitResult result = limiter.tryAcquire("k0", 5, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, result);

        // …and the janitor drops the idle-stale keys, leaving only the just-touched one.
        limiter.pruneStale();
        assertEquals(1, limiter.bucketCount(), "idle entries are evicted, not just replaced");
    }
}
