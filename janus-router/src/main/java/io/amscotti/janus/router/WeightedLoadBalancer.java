package io.amscotti.janus.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Weighted-random selection (the reference {@code pick_weighted} / LiteLLM
 * {@code simple-shuffle}): pick proportional to operator-configured weights. Config key:
 * {@code "weighted"}.
 *
 * <p>Weights come from the constructor as a {@code Map<String, Integer>} keyed by
 * {@link ChatBackend#name} — they are operator config per provider entry, which is why
 * this strategy keys by <i>name</i> rather than identity (see {@link LoadBalancer}
 * state-keying rule). A backend whose weight is missing or {@code <= 0} is excluded from
 * the pool; if every candidate is excluded (all-zero/absent weights) the pick falls back
 * to the first candidate in config order (the reference first-available).
 *
 * <p>The default constructor draws from {@link ThreadLocalRandom}; a package-private
 * constructor accepts a seeded {@link Random} so tests can pin exact sequences. Both
 * {@code java.util.Random} and {@code ThreadLocalRandom} are safe for concurrent use, so
 * the strategy is thread-safe.
 */
public final class WeightedLoadBalancer implements LoadBalancer {

    private final Map<String, Integer> weights;
    private final Random random;

    /** Uses {@link ThreadLocalRandom} for picks. */
    public WeightedLoadBalancer(Map<String, Integer> weights) {
        this(weights, null);
    }

    /** Test seam: seeded random for deterministic picks. */
    WeightedLoadBalancer(Map<String, Integer> weights, Random random) {
        // Validate before the defensive copy: a malformed weights table (null key or null
        // value from a broken TOML binding) must surface as a descriptive config error,
        // not Map.copyOf's bare NullPointerException. Insertion order is kept
        // for deterministic diagnostics.
        Map<String, Integer> src = Objects.requireNonNull(weights, "weights");
        LinkedHashMap<String, Integer> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : src.entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                throw new IllegalArgumentException("weight key must not be null");
            }
            Integer value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException("weight for backend \"" + name + "\" must not be null");
            }
            validated.put(name, value);
        }
        this.weights = Collections.unmodifiableMap(validated);
        this.random = random;
    }

    @Override
    public String name() {
        return "weighted";
    }

    @Override
    public ChatBackend pick(String model, List<ChatBackend> candidates) {
        // Same contract guard as RoundRobinLoadBalancer (Review L2): the Router
        // guarantees non-empty candidate lists, but pick is a public method — fail with
        // the contract message instead of a bare NoSuchElementException from getFirst
        // (here it would fire from the all-excluded first-available fallback below).
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "weighted pick requires a non-empty candidate list (model " + model + ")");
        }
        // Pool of positively-weighted candidates, config order (the reference pick_weighted);
        // the weight rides alongside so the walk below never re-reads the map.
        List<ChatBackend> pool = new ArrayList<>();
        List<Long> poolWeights = new ArrayList<>();
        long totalWeight = 0;
        for (ChatBackend candidate : candidates) {
            Integer weight = weights.get(candidate.name());
            if (weight != null && weight > 0) {
                pool.add(candidate);
                poolWeights.add(weight.longValue());
                try {
                    // Fail-fast on long overflow: the sum is operator config, so an
                    // overflowing total is a config bug — Math.addExact turns it into a
                    // descriptive error instead of a confusing negative bound in
                    // nextLong(totalWeight).
                    totalWeight = Math.addExact(totalWeight, weight);
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException(
                            "weighted strategy: cumulative weight overflow (configured weights sum beyond "
                                    + "Long.MAX_VALUE)",
                            overflow);
                }
            }
        }
        if (pool.isEmpty()) {
            return candidates.getFirst(); // all-zero/absent → first-available fallback
        }
        // Cumulative-sum walk over the pool.
        long pick = nextLong(totalWeight);
        long cumulative = 0;
        for (int i = 0; i < pool.size(); i++) {
            cumulative += poolWeights.get(i);
            if (pick < cumulative) {
                return pool.get(i);
            }
        }
        return pool.getLast(); // unreachable for totalWeight > 0; keeps the walk total
    }

    private long nextLong(long bound) {
        Random r = random;
        if (r == null) {
            return ThreadLocalRandom.current().nextLong(bound);
        }
        return r.nextLong(bound);
    }
}
