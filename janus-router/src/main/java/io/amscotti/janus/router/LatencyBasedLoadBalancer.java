package io.amscotti.janus.router;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Latency-based selection (like LiteLLM {@code lowest-latency}): pick the backend with
 * the lowest EMA of success latencies — time-to-first-chunk for streaming, total duration
 * for non-streaming (see {@link LoadBalancer} hook wiring). Config key:
 * {@code "latency-based"}.
 *
 * <p>State: one EMA double per backend, keyed by <b>identity</b>, updated only on
 * {@link #onLatencySample} (success samples only — failures never touch the EMA).
 * {@code ema = alpha * sample + (1 - alpha) * ema}; the first sample seeds the EMA.
 * {@code alpha} defaults to {@code 0.3} and is configurable for the gateway's TOML binding.
 *
 * <p><b>Exploration rule.</b> A candidate with no sample yet is preferred in config order
 * — a never-tried upstream must get traffic before it can be scored. With a positive
 * {@code stalenessMillis}, the rule additionally re-explores a candidate whose last sample
 * is older than the window: a backend that spent a health/breaker cooldown
 * out of rotation competes on a stale EMA on recovery — re-exploring it forces a fresh
 * sample before it competes again, so a slow-but-recovered backend is not starved by its
 * stale history. Once every candidate has fresh data, the minimum EMA wins; ties go to the
 * lowest index. The gateway wires {@code stalenessMillis} from the health cooldown (a
 * cooldown-recovered backend is exactly the stale-EMA case); the plain constructors leave
 * it disabled (0) so the strategy is byte-identical to the pre-refactor behavior.
 *
 * <p>Thread-safe: the EMA and last-sample maps are {@link ConcurrentHashMap}s; {@link
 * Clock#millis} is called on {@link #pick} only to age samples, never to mutate.
 */
public final class LatencyBasedLoadBalancer implements LoadBalancer {

    private final double alpha;
    private final long stalenessMillis;
    private final Clock clock;
    private final ConcurrentHashMap<ChatBackend, Double> emas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChatBackend, Long> lastSampleAt = new ConcurrentHashMap<>();

    /** Default EMA smoothing factor {@code alpha = 0.3}; staleness re-exploration disabled. */
    public LatencyBasedLoadBalancer() {
        this(0.3, 0, Clock.systemUTC());
    }

    /**
     * @param alpha EMA smoothing factor in {@code (0, 1]} — binds this from TOML
     * @throws IllegalArgumentException if {@code alpha} is not in {@code (0, 1]} (an
     * out-of-range value would freeze the EMA at {@code alpha = 0} or overshoot at
     * {@code alpha > 1}, silently degrading the strategy)
     */
    public LatencyBasedLoadBalancer(double alpha) {
        this(alpha, 0, Clock.systemUTC());
    }

    /**
     * EMA + staleness re-exploration window. The gateway wires {@code stalenessMillis}
     * from the health cooldown so a cooldown-recovered backend is re-sampled before it
     * competes on its stale EMA.
     *
     * @param stalenessMillis a candidate whose last sample is at least this old is
     * re-explored (treated as unsampled); {@code 0} disables the freshness rule
     * @throws IllegalArgumentException if {@code alpha} is not in {@code (0, 1]} or
     * {@code stalenessMillis} is negative
     */
    public LatencyBasedLoadBalancer(double alpha, long stalenessMillis) {
        this(alpha, stalenessMillis, Clock.systemUTC());
    }

    /** Test seam: inject the clock that ages samples (same pattern as the health/breaker). */
    LatencyBasedLoadBalancer(double alpha, long stalenessMillis, Clock clock) {
        if (!(alpha > 0 && alpha <= 1)) {
            throw new IllegalArgumentException("alpha must be in (0, 1]: " + alpha);
        }
        if (stalenessMillis < 0) {
            throw new IllegalArgumentException("stalenessMillis must be >= 0: " + stalenessMillis);
        }
        this.alpha = alpha;
        this.stalenessMillis = stalenessMillis;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "latency-based";
    }

    @Override
    public ChatBackend pick(String model, List<ChatBackend> candidates) {
        // Same contract guard as RoundRobinLoadBalancer (Review L2): the Router
        // guarantees non-empty candidate lists, but pick is a public method — fail with
        // the contract message instead of a bare NoSuchElementException from getFirst.
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "latency-based pick requires a non-empty candidate list (model " + model + ")");
        }
        // Exploration: first candidate that needs a fresh sample, in config order — a
        // never-sampled candidate, or one whose last sample is older than the staleness
        // window (a cooldown-recovered backend must be re-scored before it competes).
        for (ChatBackend candidate : candidates) {
            if (needsSample(candidate)) {
                return candidate;
            }
        }
        // Everyone has fresh data: minimum EMA, ties → lowest index.
        ChatBackend best = candidates.getFirst();
        double bestEma = emas.get(best);
        for (int i = 1; i < candidates.size(); i++) {
            ChatBackend candidate = candidates.get(i);
            double ema = emas.get(candidate);
            if (ema < bestEma) {
                best = candidate;
                bestEma = ema;
            }
        }
        return best;
    }

    @Override
    public void onLatencySample(String model, ChatBackend backend, long elapsedNanos) {
        long now = clock.millis();
        emas.compute(backend, (b, ema) -> ema == null ? elapsedNanos : alpha * elapsedNanos + (1 - alpha) * ema);
        lastSampleAt.put(backend, now);
    }

    /** Whether {@code backend} should be explored: never sampled, or sample too old. */
    private boolean needsSample(ChatBackend backend) {
        if (!emas.containsKey(backend)) {
            return true;
        }
        if (stalenessMillis == 0) {
            return false;
        }
        Long last = lastSampleAt.get(backend);
        return last != null && clock.millis() - last >= stalenessMillis;
    }

    /** Package-private test seam: current EMA for {@code backend} (0.0 if never sampled). */
    double emaOf(ChatBackend backend) {
        Double ema = emas.get(backend);
        return ema == null ? 0.0 : ema;
    }
}
