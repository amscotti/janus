package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * step 5: {@link LatencyBasedLoadBalancer} — EMA math (alpha 0.3 default, ctor
 * configurable), exploration of unsampled candidates in config order, min-EMA selection
 * once everyone has data, success-only sampling, and the end-to-end flow through
 * {@link Router#balanced}.
 */
class LatencyBasedLoadBalancerTest {

    @Test
    void emptyCandidateListFailsWithAClearContractMessage() {
        // Same contract shape as RoundRobinLoadBalancer (Review L2): pick is a public
        // method — an empty list must fail with the contract message, not a bare
        // NoSuchElementException from getFirst.
        assertThrows(IllegalArgumentException.class, () -> new LatencyBasedLoadBalancer().pick("m", List.of()));
    }

    @Test
    void emaMathWithDefaultAlpha() {
        LatencyBasedLoadBalancer lb = new LatencyBasedLoadBalancer();
        FakeBackend a = TestData.fake("A");
        lb.onLatencySample("m", a, 100);
        assertEquals(100.0, lb.emaOf(a), 1e-9); // first sample seeds the EMA
        lb.onLatencySample("m", a, 1000);
        // 0.3 * 1000 + 0.7 * 100 = 370
        assertEquals(370.0, lb.emaOf(a), 1e-9);
    }

    @Test
    void customAlphaIsConfigurable() {
        LatencyBasedLoadBalancer lb = new LatencyBasedLoadBalancer(0.5);
        FakeBackend a = TestData.fake("A");
        lb.onLatencySample("m", a, 100);
        lb.onLatencySample("m", a, 300);
        // 0.5 * 300 + 0.5 * 100 = 200
        assertEquals(200.0, lb.emaOf(a), 1e-9);
    }

    @Test
    void rejectsOutOfRangeAlpha() {
        // alpha = 0 freezes the EMA, alpha > 1 overshoots, alpha < 0 degrades the strategy
        // — a bad config value must fail fast, not silently mis-route traffic.
        assertThrows(IllegalArgumentException.class, () -> new LatencyBasedLoadBalancer(0.0));
        assertThrows(IllegalArgumentException.class, () -> new LatencyBasedLoadBalancer(-0.5));
        assertThrows(IllegalArgumentException.class, () -> new LatencyBasedLoadBalancer(1.5));
        assertThrows(IllegalArgumentException.class, () -> new LatencyBasedLoadBalancer(Double.NaN));
        // Boundary values are valid.
        new LatencyBasedLoadBalancer(0.0001);
        new LatencyBasedLoadBalancer(1.0);
    }

    @Test
    void unsampledCandidatesPreferredInConfigOrder() {
        LatencyBasedLoadBalancer lb = new LatencyBasedLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        assertEquals("A", lb.pick("m", candidates).name()); // nothing sampled → first
        lb.onLatencySample("m", candidates.get(1), 1); // B sampled (tiny latency)
        assertEquals("A", lb.pick("m", candidates).name()); // A and C still unsampled → A (config order)
        lb.onLatencySample("m", candidates.get(0), 1); // A sampled
        assertEquals("C", lb.pick("m", candidates).name()); // only C unsampled → C
    }

    @Test
    void minEmaWinsOnceAllSampled() {
        LatencyBasedLoadBalancer lb = new LatencyBasedLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        lb.onLatencySample("m", candidates.get(0), 100);
        lb.onLatencySample("m", candidates.get(1), 50);
        lb.onLatencySample("m", candidates.get(2), 80);
        assertEquals("B", lb.pick("m", candidates).name());
        lb.onLatencySample("m", candidates.get(1), 1000); // B's EMA jumps to 335
        assertEquals("C", lb.pick("m", candidates).name()); // C (80) now wins
    }

    @Test
    void tiesGoToFirstInConfigOrder() {
        LatencyBasedLoadBalancer lb = new LatencyBasedLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"));
        lb.onLatencySample("m", candidates.get(0), 100);
        lb.onLatencySample("m", candidates.get(1), 100);
        assertEquals("A", lb.pick("m", candidates).name());
    }

    @Test
    void failuresDoNotUpdateEma() {
        LatencyBasedLoadBalancer lb = new LatencyBasedLoadBalancer();
        FakeBackend a = TestData.fake("A");
        lb.onLatencySample("m", a, 100);
        lb.onRequestEnd("m", a, false, null); // success-only sampling
        assertEquals(100.0, lb.emaOf(a), 1e-9);
    }

    @Test
    void integratesWithBalancedRouter() {
        LatencyBasedLoadBalancer lb = new LatencyBasedLoadBalancer();
        FakeBackend a = TestData.fake("A", TestData.response("m"));
        FakeBackend b = TestData.fake("B", TestData.response("m"));
        Router router = Router.balanced(Map.of("m", List.of(a, b)), lb);
        router.complete(TestData.request("m")); // both unsampled → A (exploration)
        assertEquals(1, a.completeCalls.size());
        router.complete(TestData.request("m")); // A sampled via the router, B unsampled → B
        assertEquals(1, b.completeCalls.size());
    }

    @Test
    void staleSampleIsReExploredBeforeCompetingOnItsStaleEma() {
        // A backend whose last sample is older than the staleness window is
        // treated as needing a fresh sample — otherwise a cooldown-recovered backend with
        // a stale (slow) EMA would be starved by its history forever. Here C was sampled
        // long ago with a poor 100ms EMA; A and B are fresh with tiny 5ms EMAs. Staleness
        // forces C to be re-explored (config-order scan hits C after A and B, both fresh).
        MutableClock clock = new MutableClock(0);
        LatencyBasedLoadBalancer lb = new LatencyBasedLoadBalancer(0.3, 10_000, clock);
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        lb.onLatencySample("m", candidates.get(2), 100); // C sampled at T0
        clock.advance(10_000); // C's sample is exactly staleness-window old
        lb.onLatencySample("m", candidates.get(0), 5); // A fresh
        lb.onLatencySample("m", candidates.get(1), 5); // B fresh
        assertEquals("C", lb.pick("m", candidates).name()); // stale → re-explored before min-EMA
        lb.onLatencySample("m", candidates.get(2), 5); // C re-sampled fresh and fast
        assertEquals("A", lb.pick("m", candidates).name()); // all fresh now → min-EMA tie → A
    }

    @Test
    void freshSamplesAreNeverReExplored() {
        // The staleness rule must not disturb the steady-state min-EMA behavior while all
        // samples are fresh (the plain-constructor byte-parity path).
        MutableClock clock = new MutableClock(0);
        LatencyBasedLoadBalancer lb = new LatencyBasedLoadBalancer(0.3, 10_000, clock);
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"));
        lb.onLatencySample("m", candidates.get(0), 100);
        lb.onLatencySample("m", candidates.get(1), 50);
        clock.advance(9_999); // under the window → nothing stale
        assertEquals("B", lb.pick("m", candidates).name()); // min-EMA, no re-exploration
    }

    @Test
    void rejectsNegativeStalenessWindow() {
        assertThrows(IllegalArgumentException.class, () -> new LatencyBasedLoadBalancer(0.3, -1));
    }

    /** Test clock whose {@code advance} drives sample staleness. */
    private static final class MutableClock extends java.time.Clock {

        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long deltaMillis) {
            millis += deltaMillis;
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(millis);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
