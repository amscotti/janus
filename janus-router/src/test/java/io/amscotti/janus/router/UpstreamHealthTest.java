package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * step 4: {@link PassiveUpstreamHealth} — threshold flip at {@code allowedFails}
 * consecutive failures (and not before), success recovery (counter reset), lazy cooldown
 * probation with an injectable (mutable) clock, the optional {@link HealthProbe} seam
 * consulted at probation, identity keying, the fail-open {@code healthy} contract,
 * thread safety and fail-fast validation.
 */
class UpstreamHealthTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Test clock whose {@code advance} drives the lazy cooldown TTL without real time. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void flipsUnhealthyAtAllowedFailsAndNotBefore() {
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(2, 60_000, new MutableClock(T0));
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        assertEquals(List.of(a, b), health.healthy(List.of(a, b)));
        health.recordFailure(a);
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // 1 < allowedFails → still healthy
        health.recordFailure(a);
        assertEquals(List.of(b), health.healthy(List.of(a, b))); // 2 = allowedFails → unhealthy
    }

    @Test
    void successResetsTheCounterAndRecovers() {
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(2, 60_000, new MutableClock(T0));
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a);
        health.recordFailure(a);
        assertEquals(List.of(b), health.healthy(List.of(a, b)));
        health.recordSuccess(a);
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // recovered
        health.recordFailure(a);
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // counter was reset: 1 < 2
    }

    @Test
    void cooldownProbationExcludesUntilDeadlineThenAllowsOneTrial() {
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a); // unhealthy, probation until T0 + 1000
        assertEquals(List.of(b), health.healthy(List.of(a, b)));
        clock.advance(999);
        assertEquals(List.of(b), health.healthy(List.of(a, b))); // still cooling down
        clock.advance(1);
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // deadline passed → trial eligible
    }

    @Test
    void trialClaimHappensAtDispatchAndAllowsExactlyOneTrial() {
        // "one trial attempt" must hold under a concurrent burst — but the claim belongs
        // to the DISPATCH, not the filter: healthy is a pure eligibility gate (calling
        // it repeatedly never burns the trial — an admitted-but-unpicked candidate must
        // keep its trial), claimTrial atomically claims the single attempt on the
        // backend actually being dispatched to (the same canTry/claimProbe decoupling
        // the CircuitBreaker uses), and a concurrent second dispatch loses the claim.
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a);
        clock.advance(1000);
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // trial-eligible...
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // ...and STILL eligible: filtering claims nothing
        assertTrue(health.claimTrial(a)); // the dispatch claims the one trial
        assertFalse(health.claimTrial(a)); // a concurrent dispatch loses the claim
        assertEquals(List.of(b), health.healthy(List.of(a, b))); // claim held: no second trial
        clock.advance(1000);
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // fresh window -> new trial
        assertTrue(health.claimTrial(a));
    }

    @Test
    void claimTrialOnAHealthyBackendIsAFreePass() {
        // Healthy and never-failed backends have no trial to claim — every dispatch is
        // admitted, and the claim never flips them unhealthy or starts a probation.
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 60_000, new MutableClock(T0));
        FakeBackend a = TestData.fake("A");
        assertTrue(health.claimTrial(a)); // never-failed: freely dispatchable
        assertTrue(health.claimTrial(a)); // ...repeatedly
        health.recordSuccess(a);
        assertTrue(health.claimTrial(a)); // recovered backend: same free pass
        assertEquals(List.of(a), health.healthy(List.of(a)));
    }

    @Test
    void releaseTrialFreesAClaimedTrialWithoutAnOutcome() {
        // A terminal outcome that is neither a trial success nor a trial failure (a
        // non-retryable client error, a stream abandoned before its first chunk) must
        // free the claimed slot — mirroring the breaker's releaseProbe — instead of
        // letting the claim's extended probation exclude the backend for a full extra
        // cooldown window.
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a);
        clock.advance(1000);
        assertTrue(health.claimTrial(a)); // the dispatch claims the one trial
        assertEquals(List.of(b), health.healthy(List.of(a, b))); // claim held: no second trial
        health.releaseTrial(a); // terminal outcome without success/failure signals
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // slot freed: eligible again
        assertTrue(health.claimTrial(a)); // ...and re-claimable immediately, no extra window
        assertFalse(health.claimTrial(a)); // single-trial discipline still holds after release
    }

    @Test
    void releaseTrialKeepsTheBackendUnhealthy() {
        // Releasing is NOT recovering: no counter reset, no healthy flip — the next
        // trial failure re-cooldowns from the same degraded state.
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a);
        clock.advance(1000);
        assertTrue(health.claimTrial(a));
        health.releaseTrial(a);
        health.recordFailure(a); // the next trial failed
        assertEquals(List.of(b), health.healthy(List.of(a, b))); // re-cooldowned, not recovered
    }

    @Test
    void releaseTrialWithoutAClaimIsANoOp() {
        // A genuine cooldown (no dispatch ever claimed a trial) must survive a stray
        // release — only a claim this layer actually holds is freed.
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a); // probation until T0 + 1000
        health.releaseTrial(a); // nothing was claimed
        clock.advance(999);
        assertEquals(List.of(b), health.healthy(List.of(a, b))); // cooldown NOT erased
        assertFalse(health.claimTrial(a)); // still cooling down
    }

    @Test
    void claimTrialDuringCooldownIsDenied() {
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock);
        FakeBackend a = TestData.fake("A");
        health.recordFailure(a); // unhealthy, probation until T0 + 1000
        clock.advance(999);
        assertFalse(health.claimTrial(a)); // still cooling down: nothing to dispatch
        clock.advance(1);
        assertTrue(health.claimTrial(a)); // deadline passed: the trial is claimable
        assertFalse(health.claimTrial(a)); // ...exactly once per cooldown window
    }

    @Test
    void trialFailureReCooldowns() {
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a);
        clock.advance(1000);
        assertEquals(List.of(a, b), health.healthy(List.of(a, b)));
        health.recordFailure(a); // the trial failed → fresh cooldown from now
        assertEquals(List.of(b), health.healthy(List.of(a, b)));
        clock.advance(999);
        assertEquals(List.of(b), health.healthy(List.of(a, b)));
        clock.advance(1);
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // eligible again
    }

    @Test
    void trialSuccessRecovers() {
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a);
        clock.advance(1000);
        assertEquals(List.of(a, b), health.healthy(List.of(a, b)));
        health.recordSuccess(a); // trial succeeded → healthy immediately
        assertEquals(List.of(a, b), health.healthy(List.of(a, b)));
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // stays healthy on later filters
    }

    @Test
    void probeIsConsultedAtProbationAndCanVetoTheTrial() {
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock, backend -> false);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        // Healthy backends are admitted without consulting the probe.
        assertEquals(List.of(a, b), health.healthy(List.of(a, b)));
        health.recordFailure(a);
        clock.advance(1000);
        // Deadline passed but the probe says the upstream is down → still excluded.
        assertEquals(List.of(b), health.healthy(List.of(a, b)));
    }

    @Test
    void probeThatPassesAdmitsTheTrial() {
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock, backend -> true);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a);
        clock.advance(1000);
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // probe approved the trial
    }

    @Test
    void passivelyHealthyConsultsPassiveStateOnlyNotTheProbe() {
        // The observability accessor must never trigger active probe I/O — a
        // /metrics scrape re-reads the gauge on every scrape, so the probe (contractually
        // able to perform network I/O) must not fire from it.
        MutableClock clock = new MutableClock(T0);
        AtomicInteger probeCalls = new AtomicInteger();
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock, backend -> {
            probeCalls.incrementAndGet();
            return false; // a vetoing probe
        });
        FakeBackend a = TestData.fake("A");

        assertEquals(true, health.passivelyHealthy(a), "healthy backend is dispatch-eligible");
        assertEquals(0, probeCalls.get(), "a healthy backend never consulted the probe anyway");

        health.recordFailure(a); // unhealthy, probation until T0 + 1000
        assertEquals(false, health.passivelyHealthy(a), "cooling down → not eligible");
        assertEquals(0, probeCalls.get());

        clock.advance(1000);
        // Cooldown elapsed → trial-eligible in passive terms; the old healthy path
        // would call the (vetoing) probe here — passivelyHealthy must not.
        assertEquals(true, health.passivelyHealthy(a), "trial-eligible without probe I/O");
        assertEquals(0, probeCalls.get(), "passivelyHealthy must never call the probe");
    }

    @Test
    void healthyFailsOpenToTheFullListWhenEverythingIsUnhealthy() {
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 60_000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a);
        health.recordFailure(b);
        List<ChatBackend> input = List.of(a, b);
        assertSame(input, health.healthy(input)); // all-unhealthy → full list (fail-open)
    }

    @Test
    void stateIsKeyedByIdentityNotByName() {
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 60_000, new MutableClock(T0));
        FakeBackend a1 = TestData.fake("A");
        FakeBackend a2 = TestData.fake("A"); // same name, different instance
        health.recordFailure(a1);
        assertEquals(List.of(a2), health.healthy(List.of(a1, a2))); // only a1 excluded
    }

    @Test
    void concurrentRecordFailuresKeepCountersConsistent() throws Exception {
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(100, 1, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        int threads = 8;
        int failuresPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int j = 0; j < failuresPerThread; j++) {
                    health.recordFailure(a);
                    health.healthy(List.of(a, b)); // interleave filter reads with the writes
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        // 200 concurrent failures (with interleaved healthy reads) ≥ allowedFails(100)
        // → a is unhealthy, no lost updates, no torn reads.
        assertEquals(List.of(b), health.healthy(List.of(a, b)));
    }

    @Test
    void rejectsInvalidAllowedFails() {
        assertThrows(IllegalArgumentException.class, () -> new PassiveUpstreamHealth(0, 1, new MutableClock(T0)));
        assertThrows(IllegalArgumentException.class, () -> new PassiveUpstreamHealth(-1, 1, new MutableClock(T0)));
    }

    @Test
    void rejectsNegativeCooldown() {
        assertThrows(IllegalArgumentException.class, () -> new PassiveUpstreamHealth(1, -1, new MutableClock(T0)));
    }

    @Test
    void rejectsNullClock() {
        assertThrows(NullPointerException.class, () -> new PassiveUpstreamHealth(1, 1, null));
    }

    @Test
    void disabledHealthIsANoOp() {
        UpstreamHealth disabled = UpstreamHealth.disabled();
        FakeBackend a = TestData.fake("A");
        disabled.recordFailure(a);
        assertEquals(List.of(a), disabled.healthy(List.of(a))); // input unchanged
        disabled.recordSuccess(a);
        assertEquals(List.of(a), disabled.healthy(List.of(a)));
        assertTrue(disabled.passivelyHealthy(a)); // no-op gauge answer, no probe path
    }

    @Test
    void fixedClockConstructorWorks() {
        // The public Clock-taking constructor is exercised with a real (fixed) Clock.
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, Clock.fixed(T0, ZoneOffset.UTC));
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a);
        assertEquals(List.of(b), health.healthy(List.of(a, b)));
    }

    @Test
    void offsetClockConstructorWorks() {
        // Clock.offset is the immutable-clock way to pin "now" for one-shot probation checks.
        PassiveUpstreamHealth health =
                new PassiveUpstreamHealth(1, 1000, Clock.offset(Clock.fixed(T0, ZoneOffset.UTC), Duration.ZERO));
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        health.recordFailure(a);
        assertEquals(List.of(b), health.healthy(List.of(a, b)));
    }
}
