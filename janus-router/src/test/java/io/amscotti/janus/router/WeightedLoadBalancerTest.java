package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * step 7: {@link WeightedLoadBalancer} — seeded-Random determinism (exact pinned
 * sequence), weight proportionality, zero/missing-weight exclusion, all-zero →
 * first-available fallback (the reference {@code pick_weighted}), ThreadLocalRandom default, and
 * the end-to-end flow through {@link Router#balanced}.
 */
class WeightedLoadBalancerTest {

    @Test
    void emptyCandidateListFailsWithAClearContractMessage() {
        // Same contract shape as RoundRobinLoadBalancer (Review L2): pick is a public
        // method — an empty list must fail with the contract message (here it would
        // otherwise be a bare NoSuchElementException from the all-excluded
        // first-available fallback's getFirst).
        assertThrows(
                IllegalArgumentException.class, () -> new WeightedLoadBalancer(Map.of("A", 1)).pick("m", List.of()));
    }

    @Test
    void seededRandomProducesPinnedSequence() {
        // java.util.Random(42).nextLong(4): 3 0 3 1 2 0 0 0 0 3 3 3 → with A:[0,1), B:[1,4):
        WeightedLoadBalancer lb = new WeightedLoadBalancer(Map.of("A", 1, "B", 3), new Random(42));
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"));
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            picked.add(lb.pick("m", candidates).name());
        }
        assertEquals(List.of("B", "A", "B", "B", "B", "A", "A", "A", "A", "B", "B", "B"), picked);
    }

    @Test
    void proportionalToWeights() {
        WeightedLoadBalancer lb = new WeightedLoadBalancer(Map.of("A", 1, "B", 3), new Random(7));
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"));
        long a = 0;
        long b = 0;
        for (int i = 0; i < 10_000; i++) {
            if (lb.pick("m", candidates).name().equals("A")) {
                a++;
            } else {
                b++;
            }
        }
        assertEquals(3.0, (double) b / a, 0.15);
    }

    @Test
    void zeroAndMissingWeightsAreExcluded() {
        WeightedLoadBalancer lb = new WeightedLoadBalancer(Map.of("A", 0, "B", 2)); // C missing
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        for (int i = 0; i < 50; i++) {
            assertEquals("B", lb.pick("m", candidates).name());
        }
    }

    @Test
    void allWeightsZeroOrAbsentFallsBackToFirstAvailable() {
        WeightedLoadBalancer lb = new WeightedLoadBalancer(Map.of("A", 0, "B", -3));
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        for (int i = 0; i < 50; i++) {
            assertEquals("A", lb.pick("m", candidates).name());
        }
        WeightedLoadBalancer empty = new WeightedLoadBalancer(Map.of());
        for (int i = 0; i < 50; i++) {
            assertEquals("A", empty.pick("m", candidates).name());
        }
    }

    @Test
    void defaultConstructorPicksFromCandidates() {
        WeightedLoadBalancer lb = new WeightedLoadBalancer(Map.of("A", 1, "B", 1));
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"));
        for (int i = 0; i < 100; i++) {
            assertTrue(candidates.contains(lb.pick("m", candidates))); // ThreadLocalRandom path
        }
    }

    @Test
    void defaultConstructorDistributesPerWeights() {
        // ThreadLocalRandom path: unseedable, so assert proportionality with a generous
        // tolerance over a large sample (p(flake) ≈ 0) — the seeded path pins exactness.
        WeightedLoadBalancer lb = new WeightedLoadBalancer(Map.of("A", 1, "B", 3));
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"));
        long a = 0;
        long b = 0;
        for (int i = 0; i < 10_000; i++) {
            if (lb.pick("m", candidates).name().equals("A")) {
                a++;
            } else {
                b++;
            }
        }
        assertEquals(3.0, (double) b / a, 0.5);
    }

    @Test
    void rejectsNullWeights() {
        assertThrows(NullPointerException.class, () -> new WeightedLoadBalancer(null));
    }

    @Test
    void largeWeightsPickWithinLongBounds() {
        // Several Integer.MAX_VALUE weights must still sum in long arithmetic and
        // produce a valid pick — never the "bound must be positive" error a long overflow
        // would cause in nextLong(totalWeight).
        WeightedLoadBalancer lb = new WeightedLoadBalancer(
                Map.of("A", Integer.MAX_VALUE, "B", Integer.MAX_VALUE, "C", Integer.MAX_VALUE), new Random(42));
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        for (int i = 0; i < 100; i++) {
            assertTrue(candidates.contains(lb.pick("m", candidates)));
        }
    }

    @Test
    void rejectsNullWeightValueWithDescriptiveMessage() {
        // Map.copyOf's bare NPE would be an obscure boot crash; the validation
        // must name the offending backend with a config-error message.
        java.util.HashMap<String, Integer> map = new java.util.HashMap<>();
        map.put("A", 1);
        map.put("B", null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new WeightedLoadBalancer(map));
        assertTrue(e.getMessage().contains("B"), e.getMessage());
    }

    @Test
    void rejectsNullWeightKeyWithDescriptiveMessage() {
        java.util.HashMap<String, Integer> map = new java.util.HashMap<>();
        map.put("A", 1);
        map.put(null, 2);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new WeightedLoadBalancer(map));
        assertTrue(e.getMessage().contains("weight key"), e.getMessage());
    }

    @Test
    void integratesWithBalancedRouter() {
        WeightedLoadBalancer lb = new WeightedLoadBalancer(Map.of("A", 1, "B", 3), new Random(42));
        FakeBackend a = TestData.fake("A", TestData.response("m"));
        FakeBackend b = TestData.fake("B", TestData.response("m"));
        Router router = Router.balanced(Map.of("m", List.of(a, b)), lb);
        for (int i = 0; i < 100; i++) {
            router.complete(TestData.request("m"));
        }
        assertFalse(a.completeCalls.isEmpty());
        assertFalse(b.completeCalls.isEmpty());
        assertTrue(b.completeCalls.size() > a.completeCalls.size()); // 3:1 → B dominates
    }
}
