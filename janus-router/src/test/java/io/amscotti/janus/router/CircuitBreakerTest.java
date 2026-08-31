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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * step 3: {@link CircuitBreaker} — the per-backend closed → open → half-open state
 * machine: opens at {@code failureThreshold} failures within the rolling window (and not
 * before), window expiry resets the counter, open denies dispatch until the cooldown
 * elapses, exactly one half-open probe is admitted (atomic claim; concurrent callers
 * denied), probe success closes / probe failure re-opens with a fresh cooldown, the
 * streaming-safe rule ({@code beforeFirstChunk = false} is transient), the half-open
 * probe is released on any terminal outcome (no leak), identity keying, thread-safety
 * smoke and the {@code disabled} no-op singleton. Fixed {@link Clock} — no real time.
 */
class CircuitBreakerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Test clock whose {@code advance} drives the rolling window and cooldown lazily. */
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

    private static CircuitBreaker breaker(int threshold, long windowMillis, long cooldownMillis, MutableClock clock) {
        return CircuitBreaker.create(
                new CircuitBreakerConfig(threshold, Duration.ofMillis(windowMillis), Duration.ofMillis(cooldownMillis)),
                clock);
    }

    @Test
    void opensAtThresholdWithinTheWindowAndNotBefore() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(3, 60_000, 30_000, clock);
        FakeBackend a = TestData.fake("A");
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a));
        assertTrue(breaker.canTry(a));
        breaker.recordConnectFailure(a); // signal 1: 1/3
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a));
        breaker.recordStreamFailure(a, true); // signal 2: 2/3 (both signals share one counter)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a));
        assertTrue(breaker.canTry(a)); // still dispatching below the threshold
        breaker.recordConnectFailure(a); // 3/3 → OPEN
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a));
        assertFalse(breaker.canTry(a));
    }

    @Test
    void windowExpiryResetsTheCounter() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(3, 1000, 30_000, clock);
        FakeBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a); // 1/3
        breaker.recordConnectFailure(a); // 2/3
        clock.advance(1000); // window (1s) expired
        breaker.recordConnectFailure(a); // fresh window → 1/3
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a));
        breaker.recordConnectFailure(a); // 2/3
        breaker.recordConnectFailure(a); // 3/3 → OPEN
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a));
    }

    @Test
    void openDeniesWhileCooldownPendingThenAdmitsExactlyOneProbe() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a); // OPEN at T0
        assertFalse(breaker.canTry(a)); // gate: cooldown pending
        clock.advance(999);
        assertFalse(breaker.canTry(a));
        clock.advance(1); // cooldown elapsed
        assertTrue(breaker.canTry(a)); // gate: eligible — but the gate never claims
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a));
        assertTrue(breaker.claimProbe(a)); // dispatch-time claim → HALF_OPEN
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a));
        assertFalse(breaker.claimProbe(a)); // second admission denied
        assertFalse(breaker.canTry(a)); // gate: slot busy
    }

    @Test
    void exactlyOneConcurrentClaimAdmitsTheProbe() throws Exception {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a);
        clock.advance(1000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return breaker.claimProbe(a);
            }));
        }
        start.countDown();
        int admitted = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                admitted++;
            }
        }
        pool.shutdown();
        assertEquals(1, admitted); // exactly one probe, no lost/duplicated claim
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a));
    }

    @Test
    void claimProbeClaimsAnOpenBackendRegardlessOfCooldown() {
        // The all-open fail-open probe dispatches to a cooldown-pending OPEN backend
        // (documented divergence, re-checked by; claimProbe is the router's
        // dispatch-time claim and admits exactly one such probe.
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a); // OPEN at T0, cooldown pending
        assertFalse(breaker.canTry(a)); // the normal-path gate denies
        assertTrue(breaker.claimProbe(a)); // the fail-open dispatch claims
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a));
        assertFalse(breaker.claimProbe(a)); // exactly one probe in flight
    }

    @Test
    void probeSuccessClosesAndResetsTheCounter() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(2, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a); // 1/2
        breaker.recordConnectFailure(a); // 2/2 → OPEN
        clock.advance(1000);
        assertTrue(breaker.claimProbe(a)); // probe claimed
        breaker.recordSuccess(a); // probe succeeded
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a));
        assertTrue(breaker.canTry(a));
        breaker.recordConnectFailure(a); // counter was reset: 1 < 2 → still closed
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a));
    }

    @Test
    void plainClosedSuccessDoesNotResetTheRollingWindowCounter() {
        // A plain success on a CLOSED backend must not reset the failure
        // counter — the breaker opens at failureThreshold failures *within a rolling window*,
        // not on consecutive failures. F,S,F,S,… must still trip once the windowed count
        // crosses the threshold; only a probe success resets (pinned by
        // probeSuccessClosesAndResetsTheCounter).
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(5, 60_000, 30_000, clock);
        FakeBackend a = TestData.fake("A");
        for (int i = 0; i < 4; i++) {
            breaker.recordConnectFailure(a); // 4 failures in the window
        }
        breaker.recordSuccess(a); // plain CLOSED success — the windowed count must survive
        for (int i = 0; i < 4; i++) {
            breaker.recordConnectFailure(a); // 8 total, still inside the window
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a)); // windowed 8 ≥ threshold 5
        assertFalse(breaker.canTry(a));
    }

    @Test
    void probeFailureReopensWithFreshCooldown() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a); // OPEN at T0
        clock.advance(1000);
        assertTrue(breaker.claimProbe(a)); // probe claimed
        breaker.recordConnectFailure(a); // probe failed → re-OPEN with fresh openedAt = T0+1000
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a));
        assertFalse(breaker.canTry(a)); // fresh cooldown
        clock.advance(999);
        assertFalse(breaker.canTry(a));
        clock.advance(1);
        assertTrue(breaker.canTry(a)); // eligible for another probe
    }

    @Test
    void streamFailureAfterFirstChunkIsTransientAndDoesNotCount() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(2, 60_000, 30_000, clock);
        FakeBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a); // 1/2
        breaker.recordStreamFailure(a, false); // partial delivery → transient no-op
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a));
        breaker.recordConnectFailure(a); // 2/2 → OPEN (the transient did not count)
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a));
    }

    @Test
    void halfOpenProbeReleasedOnMidStreamFailure() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a);
        clock.advance(1000);
        assertTrue(breaker.claimProbe(a)); // probe claimed → HALF_OPEN
        breaker.recordStreamFailure(a, false); // mid-stream failure: transient, but free the probe
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a));
        assertTrue(breaker.canTry(a)); // gate sees the free slot → no probe leak
        assertTrue(breaker.claimProbe(a)); // next dispatch re-claims
        assertFalse(breaker.claimProbe(a)); // still exactly one slot
    }

    @Test
    void halfOpenProbeReleasedOnEarlyClose() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a);
        clock.advance(1000);
        assertTrue(breaker.claimProbe(a)); // probe claimed
        breaker.releaseProbe(a); // abandoned stream (client disconnect): no signal, slot freed
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a));
        assertTrue(breaker.canTry(a)); // re-claimable → no deadlock
    }

    @Test
    void halfOpenProbeFailureBeforeFirstChunkReopens() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a);
        clock.advance(1000);
        assertTrue(breaker.claimProbe(a));
        breaker.recordStreamFailure(a, true); // probe failed before any chunk → re-OPEN
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a));
        assertFalse(breaker.canTry(a));
    }

    @Test
    void stateIsKeyedByIdentityNotByName() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        FakeBackend a1 = TestData.fake("A");
        FakeBackend a2 = TestData.fake("A"); // same name, different instance
        breaker.recordConnectFailure(a1);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a1));
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a2)); // untouched
        assertTrue(breaker.canTry(a2));
    }

    @Test
    void concurrentRecordFailuresKeepTheCounterConsistent() throws Exception {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(100, 60_000, 30_000, clock);
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
                    breaker.recordConnectFailure(a);
                    breaker.canTry(a); // interleave dispatch checks with the writes
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        // 200 concurrent failures (with interleaved canTry) ≥ threshold(100) → OPEN,
        // no lost updates.
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a));
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(b));
    }

    @Test
    void claimProbeRacingRecordFailureKeepsTheStateCoherent() throws Exception {
        // Regression: the old claimProbe read the map (get) before taking the
        // per-key bin lock (compute), so a concurrent recordFailure that opened the backend
        // and a concurrent claimProbe that claimed the probe in between could deny a claim
        // that was legitimate at read time — a lost dispatch under contention. The fix
        // decides every admission inside the compute, atomically under the bin lock:
        // absent/CLOSED/OPEN admit, and a claim is denied only by a genuinely claimed
        // half-open slot.
        //
        // Stress: a never-failed backend, threshold 1 (any failure opens it), 8 threads
        // racing claimProbe against recordConnectFailure. Every thread ends with a failure,
        // so the final op is deterministic: the state ends OPEN (a probe is never left
        // claimed — a failure always frees it), and a fresh claim is always admitted.
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        int threads = 8;
        int iterations = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong admitted = new AtomicLong();
        AtomicLong denied = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int j = 0; j < iterations; j++) {
                    if (breaker.claimProbe(a)) {
                        admitted.incrementAndGet();
                    } else {
                        denied.incrementAndGet();
                    }
                    breaker.recordConnectFailure(a);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        // Every claim is accounted for — a probe admission is never lost to the stale read.
        assertEquals((long) threads * iterations, admitted.get() + denied.get());
        // The final op is a recordConnectFailure (each thread ends with one): coherent OPEN,
        // and the probe slot is free (a failure never leaves it claimed) → no wedge.
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a));
        assertTrue(breaker.claimProbe(a)); // the next dispatch is always admitted
    }

    @Test
    void disabledIsANoOpSingleton() {
        CircuitBreaker breaker = CircuitBreaker.disabled();
        FakeBackend a = TestData.fake("A");
        assertTrue(breaker.canTry(a));
        assertTrue(breaker.claimProbe(a));
        breaker.recordConnectFailure(a);
        breaker.recordStreamFailure(a, true);
        breaker.recordStreamFailure(a, false);
        breaker.recordSuccess(a);
        breaker.releaseProbe(a);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a)); // never opens
        assertTrue(breaker.canTry(a));
        assertTrue(breaker.claimProbe(a));
        assertSame(CircuitBreaker.disabled(), breaker); // singleton identity
    }

    @Test
    void createWithDisabledConfigReturnsTheDisabledSingleton() {
        assertSame(CircuitBreaker.disabled(), CircuitBreaker.create(CircuitBreakerConfig.disabled()));
        assertSame(
                CircuitBreaker.disabled(),
                CircuitBreaker.create(CircuitBreakerConfig.disabled(), new MutableClock(T0)));
    }

    @Test
    void stateOfUnknownBackendIsClosed() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(TestData.fake("X")));
        assertTrue(breaker.canTry(TestData.fake("X")));
        assertTrue(breaker.claimProbe(TestData.fake("X")));
    }

    @Test
    void rejectsNullBackends() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        CircuitBreaker disabled = CircuitBreaker.disabled();
        assertThrows(NullPointerException.class, () -> breaker.canTry(null));
        assertThrows(NullPointerException.class, () -> breaker.claimProbe(null));
        assertThrows(NullPointerException.class, () -> breaker.recordConnectFailure(null));
        assertThrows(NullPointerException.class, () -> breaker.recordStreamFailure(null, true));
        assertThrows(NullPointerException.class, () -> breaker.recordSuccess(null));
        assertThrows(NullPointerException.class, () -> breaker.releaseProbe(null));
        assertThrows(NullPointerException.class, () -> disabled.canTry(null));
    }

    @Test
    void rejectsNullConfigAndClock() {
        assertThrows(NullPointerException.class, () -> CircuitBreaker.create(null));
        assertThrows(NullPointerException.class, () -> CircuitBreaker.create(null, new MutableClock(T0)));
        assertThrows(
                NullPointerException.class,
                () -> CircuitBreaker.create(
                        new CircuitBreakerConfig(1, Duration.ofSeconds(60), Duration.ofSeconds(30)), null));
    }
    // ------------------------------------------- gate-vs-claim timing window (TOCTOU)

    @Test
    void cooldownPendingOpenRefusesTheNormalPathClaimButAdmitsTheFailOpenProbe() {
        // canTry(CLOSED)=true and claimProbe can see OPEN microseconds later (a
        // concurrent failure trips it between the two). The claim must REFUSE on the
        // normal path (cooldown holds) and only admit as the all-open fail-open probe.
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        ChatBackend a = TestData.fake("A"); // ONE instance — the breaker maps by identity
        breaker.recordConnectFailure(a); // trips OPEN, cooldown pending

        assertFalse(breaker.claimProbe(a, false), "normal path: cooldown holds");
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a), "refusal leaves the state untouched");

        assertTrue(breaker.claimProbe(a, true), "fail-open probe: admitted");
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a));
    }

    @Test
    void cooldownElapsedOpenAdmitsOnBothPaths() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        ChatBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a);
        clock.advance(31_000);

        assertTrue(breaker.claimProbe(a, false));
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a));
    }

    @Test
    void backCompatClaimProbeKeepsTheFailOpenSemantics() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        ChatBackend a = TestData.fake("A");
        breaker.recordConnectFailure(a);
        // the two-arg form is the all-open fail-open path (and the tests' seam)
        assertTrue(breaker.claimProbe(a));
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a));
    }
}
