package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * step 6: {@link CostBasedLoadBalancer} — cumulative cost from actual usage via the
 * {@link CostFunction} seam, config-order tie break, null-usage contributes 0, failed
 * calls not counted, per-successful-response seam invocation, and the end-to-end flow
 * through {@link Router#balanced}.
 */
class CostBasedLoadBalancerTest {

    /** Price map: backend name → {input price per 1k, output price per 1k}. */
    private static CostFunction priced(Map<String, double[]> prices) {
        return (model, backend, response) -> {
            double[] p = prices.get(backend.name());
            Usage usage = response.usage();
            return (usage.promptTokens() / 1000.0) * p[0] + (usage.completionTokens() / 1000.0) * p[1];
        };
    }

    private static final CostFunction PRICED =
            priced(Map.of("A", new double[] {1.0, 3.0}, "B", new double[] {2.0, 6.0}));

    @Test
    void emptyCandidateListFailsWithAClearContractMessage() {
        // Same contract shape as RoundRobinLoadBalancer (Review L2): pick is a public
        // method — an empty list must fail with the contract message, not a bare
        // NoSuchElementException from getFirst.
        assertThrows(IllegalArgumentException.class, () -> new CostBasedLoadBalancer(PRICED).pick("m", List.of()));
    }

    @Test
    void picksLowerCumulativeCost() {
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer(PRICED);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        List<ChatBackend> candidates = List.of(a, b);
        // A: 1000 prompt + 2000 completion → 1 + 6 = 7
        lb.onRequestEnd("m", a, true, TestData.response("m", new Usage(1000, 2000, 3000)));
        // B: 1000 prompt + 1000 completion → 2 + 6 = 8
        lb.onRequestEnd("m", b, true, TestData.response("m", new Usage(1000, 1000, 2000)));
        assertEquals("A", lb.pick("m", candidates).name());
        // A: another 2000 prompt + 4000 completion → +14 → A total 21 > B's 8 → B wins
        lb.onRequestEnd("m", a, true, TestData.response("m", new Usage(2000, 4000, 6000)));
        assertEquals("B", lb.pick("m", candidates).name());
        assertEquals(21.0, lb.cumulativeCostOf(a), 1e-9);
        assertEquals(8.0, lb.cumulativeCostOf(b), 1e-9);
    }

    @Test
    void tiesGoToFirstInConfigOrder() {
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer(PRICED);
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"));
        assertEquals("A", lb.pick("m", candidates).name()); // both at 0 → A
    }

    @Test
    void nullUsageContributesZero() {
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer(PRICED);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        List<ChatBackend> candidates = List.of(a, b);
        lb.onRequestEnd("m", a, true, TestData.response("m")); // usage null → 0
        lb.onRequestEnd("m", b, true, TestData.response("m", new Usage(1000, 1000, 2000))); // → 8
        assertEquals(0.0, lb.cumulativeCostOf(a), 1e-9);
        assertEquals("A", lb.pick("m", candidates).name());
    }

    @Test
    void failedCallsDoNotAccumulate() {
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer(PRICED);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        List<ChatBackend> candidates = List.of(a, b);
        lb.onRequestEnd("m", a, false, null); // failure → not counted
        lb.onRequestEnd("m", b, true, TestData.response("m", new Usage(1000, 1000, 2000))); // → 8
        assertEquals(0.0, lb.cumulativeCostOf(a), 1e-9);
        assertEquals("A", lb.pick("m", candidates).name());
    }

    @Test
    void costFunctionSeamInvokedPerSuccessfulResponse() {
        List<String> invocations = new ArrayList<>();
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer((model, backend, response) -> {
            invocations.add(backend.name() + ":" + response.id());
            return 1.0;
        });
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        lb.onRequestEnd("m", a, true, TestData.response("ra", "m", new Usage(10, 10, 20)));
        lb.onRequestEnd("m", a, false, null);
        lb.onRequestEnd("m", b, true, TestData.response("rb", "m", new Usage(10, 10, 20)));
        assertEquals(List.of("A:ra", "B:rb"), invocations);
        assertEquals(1.0, lb.cumulativeCostOf(a), 1e-9);
        assertEquals(1.0, lb.cumulativeCostOf(b), 1e-9);
    }

    @Test
    void integratesWithBalancedRouter() {
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer((model, backend, response) -> {
            Usage usage = response.usage();
            double in = backend.name().equals("expensive") ? 1.0 : 0.1;
            double out = backend.name().equals("expensive") ? 3.0 : 0.3;
            return (usage.promptTokens() / 1000.0) * in + (usage.completionTokens() / 1000.0) * out;
        });
        FakeBackend expensive = TestData.fake("expensive", TestData.response("m", new Usage(2000, 4000, 6000)));
        FakeBackend cheap = TestData.fake("cheap", TestData.response("m", new Usage(100, 100, 200)));
        Router router = Router.balanced(Map.of("m", List.of(expensive, cheap)), lb);
        router.complete(TestData.request("m")); // 0-0 tie → expensive → cost 14
        assertEquals(1, expensive.completeCalls.size());
        router.complete(TestData.request("m")); // cheap (0.04 < 14)
        assertEquals(1, cheap.completeCalls.size());
    }

    @Test
    void nameIsCostBased() {
        assertEquals("cost-based", new CostBasedLoadBalancer(PRICED).name());
    }

    @Test
    void successfulCallWithNullResponseDoesNotAccumulate() {
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer(PRICED);
        FakeBackend a = TestData.fake("A");
        // Clean stream close without observed usage (or success with null response): not billed.
        lb.onRequestEnd("m", a, true, null);
        assertEquals(0.0, lb.cumulativeCostOf(a), 1e-9);
        assertEquals("A", lb.pick("m", List.of(a, TestData.fake("B"))).name());
    }

    @Test
    void streamingTerminalUsageFeedsCostBasedSelection() {
        // Router wrapStream captures the last usage-bearing chunk and feeds a synthetic
        // ChatResponse into onRequestEnd on clean stream close.
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer(PRICED);
        Usage terminalUsage = new Usage(1000, 1000, 2000); // A: 1+3 = 4
        StreamChunk mid = new StreamChunk("c1", "chat.completion.chunk", 0L, "m", List.of(), null, Map.of());
        StreamChunk terminal =
                new StreamChunk("c1", "chat.completion.chunk", 0L, "m", List.of(), terminalUsage, Map.of());
        FakeBackend a = TestData.fake("A", TestData.response("m"), Stream.of(mid, terminal));
        FakeBackend b = TestData.fake("B");
        List<ChatBackend> candidates = List.of(a, b);
        Router router = Router.balanced(Map.of("m", candidates), lb);
        try (Stream<StreamChunk> stream = router.stream(TestData.request("m"))) {
            // Force full traversal — Stream.count may short-circuit on SIZED streams
            // without calling tryAdvance, which would skip usage capture in wrapStream.
            List<StreamChunk> chunks = stream.toList();
            assertEquals(2, chunks.size());
        }
        assertEquals(4.0, lb.cumulativeCostOf(a), 1e-9);
        assertEquals(0.0, lb.cumulativeCostOf(b), 1e-9);
        assertEquals("B", lb.pick("m", candidates).name());
    }

    @Test
    void streamingMidStreamFailureAfterUsageChunkBillsTheObservedUsage() {
        // A usage chunk that already passed through the router's tryAdvance was
        // delivered to the client (and governance settled it) — a mid-stream delegate
        // failure AFTER it must still bill the cost-based LB, so the failed backend does
        // not get a quiet spend advantage over healthy ones.
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer(PRICED);
        Usage terminalUsage = new Usage(1000, 1000, 2000); // A: 1+3 = 4
        StreamChunk mid = new StreamChunk("c1", "chat.completion.chunk", 0L, "m", List.of(), null, Map.of());
        StreamChunk terminal =
                new StreamChunk("c1", "chat.completion.chunk", 0L, "m", List.of(), terminalUsage, Map.of());
        FakeBackend a = TestData.fake(
                "A",
                TestData.response("m"),
                streamThenFail(List.of(mid, terminal), new RuntimeException("upstream died after the usage chunk")));
        FakeBackend b = TestData.fake("B");
        List<ChatBackend> candidates = List.of(a, b);
        Router router = Router.balanced(Map.of("m", candidates), lb);
        try (Stream<StreamChunk> stream = router.stream(TestData.request("m"))) {
            assertThrows(RuntimeException.class, stream::toList, "the delegate failure propagates to the consumer");
        }
        assertEquals(4.0, lb.cumulativeCostOf(a), 1e-9, "usage observed before the failure is still billed");
        assertEquals(0.0, lb.cumulativeCostOf(b), 1e-9);
        assertEquals("B", lb.pick("m", candidates).name());
    }

    @Test
    void streamingFailureBeforeAnyUsageChunkBillsNothing() {
        // Connect-then-die (no usage delivered): end(false, null) — nothing billed, the
        // conservative boundary the mid-stream billing fix must not disturb.
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer(PRICED);
        StreamChunk content = new StreamChunk("c1", "chat.completion.chunk", 0L, "m", List.of(), null, Map.of());
        FakeBackend a = TestData.fake(
                "A",
                TestData.response("m"),
                streamThenFail(List.of(content), new RuntimeException("upstream died before any usage chunk")));
        FakeBackend b = TestData.fake("B");
        List<ChatBackend> candidates = List.of(a, b);
        Router router = Router.balanced(Map.of("m", candidates), lb);
        try (Stream<StreamChunk> stream = router.stream(TestData.request("m"))) {
            assertThrows(RuntimeException.class, stream::toList);
        }
        assertEquals(0.0, lb.cumulativeCostOf(a), 1e-9, "no usage observed → $0, never billed");
        assertEquals("A", lb.pick("m", candidates).name());
    }

    @Test
    void resilientRetryBillsOnlyTheSuccessfulAttempt() {
        // Coverage: in resilient mode a retry where attempt 0 fails retryably
        // and attempt 1 succeeds must bill exactly once — for the successful backend.
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer(PRICED);
        FailingBackend a = new FailingBackend(
                "A",
                TestData.response("m", new Usage(1000, 2000, 3000)), // A rate: 1 + 6 = 7 (never reached)
                null,
                new ArrayList<>(List.of(new BackendException(BackendException.TYPE_NETWORK, "connection reset"))));
        FakeBackend b = TestData.fake("B", TestData.response("m", new Usage(1000, 1000, 2000))); // B rate: 2 + 6 = 8
        ResilienceConfig config = new ResilienceConfig(
                new RetryPolicy(1, 10, 100, 0.0), UpstreamHealth.disabled(), DefaultRetryClassifier.INSTANCE);
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, config);
        router.complete(TestData.request("m"));
        assertEquals(0.0, lb.cumulativeCostOf(a), 1e-9, "the failed attempt is never billed");
        assertEquals(8.0, lb.cumulativeCostOf(b), 1e-9, "the successful attempt is billed exactly once");
    }

    @Test
    void concurrentOnRequestEndAccumulatesExactly() throws InterruptedException {
        // Coverage: costs.compute accumulation must lose no updates under
        // concurrent end-hook deliveries (the router drives hooks from multiple requests).
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer((model, backend, response) -> 1.0);
        FakeBackend a = TestData.fake("A");
        int threads = 8;
        int perThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
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
                        lb.onRequestEnd("m", a, true, TestData.response("m", new Usage(10, 10, 20)));
                    }
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "the concurrent deliveries must finish");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                (double) (threads * perThread),
                lb.cumulativeCostOf(a),
                1e-9,
                "no lost update under concurrent end-hook deliveries");
    }

    @Test
    void emptyPriceTablePinsTheFirstCandidate() {
        // Behavior pin: with no pricing rows every response costs 0, so pick's
        // strict `<` comparison never flips away from index 0 — the first candidate wins
        // every pick. Deliberate until the operator adds rows (the gateway warns at boot).
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer((model, backend, response) -> 0.0);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        List<ChatBackend> candidates = List.of(a, b);
        for (int i = 0; i < 25; i++) {
            lb.onRequestEnd("m", a, true, TestData.response("m", new Usage(1000, 1000, 2000)));
            assertEquals("A", lb.pick("m", candidates).name(), "0-vs-0 tie → config order (backend 2 never served)");
        }
    }

    /** A stream whose spliterator yields {@code chunks} then throws {@code failure} on the next advance. */
    private static Stream<StreamChunk> streamThenFail(List<StreamChunk> chunks, RuntimeException failure) {
        Iterator<StreamChunk> iterator = chunks.iterator();
        Spliterator<StreamChunk> spliterator = new Spliterator<>() {
            @Override
            public boolean tryAdvance(Consumer<? super StreamChunk> action) {
                if (!iterator.hasNext()) {
                    throw failure;
                }
                action.accept(iterator.next());
                return true;
            }

            @Override
            public Spliterator<StreamChunk> trySplit() {
                return null;
            }

            @Override
            public long estimateSize() {
                return Long.MAX_VALUE;
            }

            @Override
            public int characteristics() {
                return Spliterator.ORDERED;
            }
        };
        return StreamSupport.stream(spliterator, false);
    }
}
