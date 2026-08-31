package io.amscotti.janus.router;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sequential round-robin per model alias (LiteLLM {@code simple-shuffle} unweighted —
 * the deterministic cycle is the unweighted form of weighted-random). Config key:
 * {@code "round-robin"}.
 *
 * <p>State: one {@link AtomicLong} per model alias in a {@link ConcurrentHashMap} — two
 * aliases with different candidate lists do <b>not</b> share a position, and the cycle is
 * exact (no randomness, no reseeding). Thread-safe.
 */
public final class RoundRobinLoadBalancer implements LoadBalancer {

    // Entries are never removed — bounded by the configured model-alias count, so no
    // unbounded growth for a gateway (a model alias maps to exactly one counter).
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "round-robin";
    }

    @Override
    public ChatBackend pick(String model, List<ChatBackend> candidates) {
        // Review L2: the Router guarantees non-empty candidate lists, but pick is a
        // public method — fail with a clear contract message instead of an
        // ArithmeticException ("/ by zero") from floorMod.
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "round-robin pick requires a non-empty candidate list (model " + model + ")");
        }
        long position = counters.computeIfAbsent(model, m -> new AtomicLong()).getAndIncrement();
        return candidates.get(Math.floorMod(position, candidates.size()));
    }
}
