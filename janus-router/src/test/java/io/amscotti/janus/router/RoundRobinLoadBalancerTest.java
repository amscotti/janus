package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * step 3: {@link RoundRobinLoadBalancer} — exact per-model cycle, independent per-
 * alias counters, balanced distribution under concurrency, and the end-to-end pick +
 * observation flow through {@link Router#balanced}.
 */
class RoundRobinLoadBalancerTest {

    @Test
    void emptyCandidateListFailsWithAClearContractMessage() {
        // Review L2: pick is a public method — an empty list must fail with the
        // contract message, not an ArithmeticException ("/ by zero") from floorMod.
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new RoundRobinLoadBalancer().pick("m", java.util.List.of()));
    }

    @Test
    void cyclesExactlyAcrossCandidates() {
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            picked.add(lb.pick("m", candidates).name());
        }
        assertEquals(List.of("A", "B", "C", "A", "B", "C"), picked);
    }

    @Test
    void perModelCountersAreIndependent() {
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        List<ChatBackend> m1 = List.of(TestData.fake("A"), TestData.fake("B"));
        List<ChatBackend> m2 = List.of(TestData.fake("X"), TestData.fake("Y"), TestData.fake("Z"));
        assertEquals("A", lb.pick("m1", m1).name());
        // A shared counter would have advanced to index 1 here (→ Y): independence → X.
        assertEquals("X", lb.pick("m2", m2).name());
        assertEquals("B", lb.pick("m1", m1).name());
        assertEquals("Y", lb.pick("m2", m2).name());
        assertEquals("A", lb.pick("m1", m1).name());
    }

    @Test
    void concurrentPicksAreBalanced() throws Exception {
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        List<ChatBackend> candidates =
                List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"), TestData.fake("D"));
        int threads = 4;
        int picksPerThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Map<String, Integer>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    Map<String, Integer> counts = new HashMap<>();
                    for (int i = 0; i < picksPerThread; i++) {
                        counts.merge(lb.pick("m", candidates).name(), 1, Integer::sum);
                    }
                    return counts;
                }));
            }
            Map<String, Integer> total = new HashMap<>();
            for (Future<Map<String, Integer>> future : futures) {
                for (Map.Entry<String, Integer> entry :
                        future.get(10, TimeUnit.SECONDS).entrySet()) {
                    total.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
            assertEquals(Map.of("A", 250, "B", 250, "C", 250, "D", 250), total);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void integratesWithBalancedRouter() {
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        FakeBackend a = TestData.fake("A", TestData.response("m"));
        FakeBackend b = TestData.fake("B", TestData.response("m"));
        Router router = Router.balanced(Map.of("m", List.of(a, b)), lb);
        router.complete(TestData.request("m"));
        router.complete(TestData.request("m"));
        assertEquals(1, a.completeCalls.size()); // A then B — exact cycle through the router
        assertEquals(1, b.completeCalls.size());
    }
}
