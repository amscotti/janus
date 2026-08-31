package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.StreamChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * step 4: {@link LeastInflightLoadBalancer} — min-inflight selection, config-order
 * tie break, release-on-failure, identity-keyed state shared across aliases, and the
 * end-to-end flow through {@link Router#balanced}.
 */
class LeastInflightLoadBalancerTest {

    @Test
    void emptyCandidateListFailsWithAClearContractMessage() {
        // Same contract shape as RoundRobinLoadBalancer (Review L2): pick is a public
        // method — an empty list must fail with the contract message, not a bare
        // NoSuchElementException from getFirst.
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new LeastInflightLoadBalancer().pick("m", List.of()));
    }

    @Test
    void picksLeastInflightBackend() {
        LeastInflightLoadBalancer lb = new LeastInflightLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        // B gets 2 in-flight; A and C sit at 0 → tie between A and C → A (config order).
        lb.onRequestStart("m", candidates.get(1));
        lb.onRequestStart("m", candidates.get(1));
        assertEquals("A", lb.pick("m", candidates).name());
        // A gets 1 in-flight → C (0) wins outright.
        lb.onRequestStart("m", candidates.get(0));
        assertEquals("C", lb.pick("m", candidates).name());
    }

    @Test
    void tiesGoToFirstInConfigOrder() {
        LeastInflightLoadBalancer lb = new LeastInflightLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        assertEquals("A", lb.pick("m", candidates).name()); // all 0 → A
        lb.onRequestStart("m", candidates.get(0));
        assertEquals("B", lb.pick("m", candidates).name()); // A=1, B=C=0 → B
    }

    @Test
    void backendWithHighInflightIsSkippedWhileAnotherIsFree() {
        LeastInflightLoadBalancer lb = new LeastInflightLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"));
        for (int i = 0; i < 3; i++) {
            lb.onRequestStart("m", candidates.get(0));
        }
        assertEquals("B", lb.pick("m", candidates).name());
    }

    @Test
    void onRequestEndDecrementsEvenOnFailure() {
        LeastInflightLoadBalancer lb = new LeastInflightLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"));
        lb.onRequestStart("m", candidates.get(0));
        lb.onRequestStart("m", candidates.get(0));
        assertEquals(2, lb.inflightOf(candidates.get(0)));
        lb.onRequestEnd("m", candidates.get(0), false, null); // end-in-finally: failure releases its slot
        assertEquals(1, lb.inflightOf(candidates.get(0)));
        assertEquals("B", lb.pick("m", candidates).name());
    }

    @Test
    void sharedBackendSharesCounterAcrossAliases() {
        LeastInflightLoadBalancer lb = new LeastInflightLoadBalancer();
        FakeBackend shared = TestData.fake("shared");
        List<ChatBackend> m1 = List.of(shared, TestData.fake("A"));
        List<ChatBackend> m2 = List.of(shared, TestData.fake("B"));
        lb.onRequestStart("m1", shared);
        assertEquals(1, lb.inflightOf(shared)); // identity-keyed: one counter for the instance
        assertEquals("B", lb.pick("m2", m2).name()); // shared has 1 in-flight → B wins
    }

    @Test
    void concurrentPickStartEndCyclesKeepCountersConsistent() throws Exception {
        LeastInflightLoadBalancer lb = new LeastInflightLoadBalancer();
        List<ChatBackend> candidates =
                List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"), TestData.fake("D"));
        int threads = 4;
        int cyclesPerThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    int picked = 0;
                    for (int i = 0; i < cyclesPerThread; i++) {
                        ChatBackend backend = lb.pick("m", candidates); // always a real candidate
                        assertNotNull(backend);
                        assertTrue(candidates.contains(backend));
                        lb.onRequestStart("m", backend);
                        lb.onRequestEnd("m", backend, true, null);
                        picked++;
                    }
                    return picked;
                }));
            }
            int totalPicks = 0;
            for (Future<Integer> future : futures) {
                totalPicks += future.get(10, TimeUnit.SECONDS).intValue();
            }
            assertEquals(threads * cyclesPerThread, totalPicks);
        } finally {
            pool.shutdownNow();
        }
        // Every start was matched by an end: no slot leaked under concurrency (the
        // min-inflight pick property itself is structural — the scan always returns a
        // member of the current min set, ties → config order).
        for (ChatBackend candidate : candidates) {
            assertEquals(0, lb.inflightOf(candidate));
        }
    }

    @Test
    void integratesWithBalancedRouter() {
        LeastInflightLoadBalancer lb = new LeastInflightLoadBalancer();
        FakeBackend a = TestData.fake("A", TestData.response("m"), Stream.of(TestData.chunk()));
        FakeBackend b = TestData.fake("B", TestData.response("m"), Stream.of(TestData.chunk()));
        Router router = Router.balanced(Map.of("m", List.of(a, b)), lb);
        Stream<StreamChunk> first = router.stream(TestData.request("m")); // 0-0 tie → A
        assertEquals(1, a.streamCalls.size());
        Stream<StreamChunk> second = router.stream(TestData.request("m")); // A holds 1 → B
        assertEquals(1, b.streamCalls.size());
        first.close();
        second.close();
        assertEquals(0, lb.inflightOf(a)); // close released both slots
        assertEquals(0, lb.inflightOf(b));
    }
}
