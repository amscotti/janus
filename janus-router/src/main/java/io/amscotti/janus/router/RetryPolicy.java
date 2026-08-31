package io.amscotti.janus.router;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Retry policy: bounded retry count, exponential backoff
 * capped at {@code maxDelayMillis}, plus optional fractional jitter. Config keys the
 * gateway's {@code [router]} TOML binds: {@code max_retries}, {@code backoff_base_ms},
 * {@code backoff_max_ms}, {@code jitter}.
 *
 * <p>{@link #delayMillis(int)} follows {@code min(base * 2^attempt, max)} — attempt 0 is
 * the delay <i>before</i> the first retry — plus jitter: with jitter {@code j ∈ [0,1]},
 * the returned delay lies in {@code [capped, capped + j * capped]} (deterministic when
 * {@code j = 0}). The {@link Sleeper} seam (default {@code Thread.sleep}, the only real
 * sleep in the codebase) lets tests record delays instead of sleeping.
 *
 * <p>Fail-fast construction : {@code maxRetries < 0}, {@code base <= 0},
 * {@code max < base} and out-of-range jitter are rejected up front. Native-image safe:
 * no reflection, JDK-only types, nothing serialized.
 */
public final class RetryPolicy {

    private final int maxRetries;
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final double jitter;
    private final RandomGenerator random;
    private final Sleeper sleeper;

    /** Defaults: {@link ThreadLocalRandom} jitter source, real {@link Thread#sleep}. */
    public RetryPolicy(int maxRetries, long baseDelayMillis, long maxDelayMillis, double jitter) {
        this(maxRetries, baseDelayMillis, maxDelayMillis, jitter, ThreadLocalRandom.current(), Sleeper.THREAD_SLEEPER);
    }

    /**
     * Test/advanced seam: inject the jitter source and the sleeping seam so tests pin
     * delay <i>values</i> and never sleep real time.
     */
    public RetryPolicy(
            int maxRetries,
            long baseDelayMillis,
            long maxDelayMillis,
            double jitter,
            RandomGenerator random,
            Sleeper sleeper) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0: " + maxRetries);
        }
        if (baseDelayMillis <= 0) {
            throw new IllegalArgumentException("baseDelayMillis must be > 0: " + baseDelayMillis);
        }
        if (maxDelayMillis < baseDelayMillis) {
            throw new IllegalArgumentException(
                    "maxDelayMillis must be >= baseDelayMillis: " + maxDelayMillis + " < " + baseDelayMillis);
        }
        if (!(jitter >= 0.0 && jitter <= 1.0)) {
            throw new IllegalArgumentException("jitter must be in [0.0, 1.0]: " + jitter);
        }
        this.maxRetries = maxRetries;
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.jitter = jitter;
        this.random = Objects.requireNonNull(random, "random");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** Maximum retries <i>after</i> the first attempt (at most {@code maxRetries + 1} tries). */
    public int maxRetries() {
        return maxRetries;
    }

    /** Exponential base delay in millis. */
    public long baseDelayMillis() {
        return baseDelayMillis;
    }

    /** Delay cap in millis. */
    public long maxDelayMillis() {
        return maxDelayMillis;
    }

    /** Jitter fraction {@code ∈ [0, 1]} of the capped delay. */
    public double jitter() {
        return jitter;
    }

    /**
     * Backoff delay for {@code attempt}: {@code min(base * 2^attempt, max)} plus jitter
     * (bounds above). Attempt 0 is the delay before the first retry; the router calls
     * {@link #sleepBackoff(int)} with the attempt that just failed.
     */
    public long delayMillis(int attempt) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must be >= 0: " + attempt);
        }
        long delay = baseDelayMillis;
        for (int i = 0; i < attempt && delay < maxDelayMillis; i++) {
            long doubled = delay > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : delay * 2;
            delay = Math.min(doubled, maxDelayMillis);
        }
        if (jitter == 0.0) {
            return delay;
        }
        long jitterMillis = (long) (random.nextDouble() * jitter * delay);
        return delay > Long.MAX_VALUE - jitterMillis ? Long.MAX_VALUE : delay + jitterMillis;
    }

    /** Sleep the {@link #delayMillis(int)} backoff via the {@link Sleeper} seam. */
    public void sleepBackoff(int attempt) {
        sleeper.sleep(delayMillis(attempt));
    }

    /**
     * Sleeping seam: the only place a retry loop blocks on time. Default is real
     * {@link Thread#sleep} (safe on virtual threads, JEP 444/491 — no platform-thread
     * pinning); tests inject a recording no-op and assert delay values instead.
     */
    @FunctionalInterface
    public interface Sleeper {

        void sleep(long millis);

        /** Production default: {@code Thread.sleep}; interruption restores the flag and
         * surfaces as a {@link RuntimeException} (the request fails without a further
         * attempt — interruption is a shutdown signal, not a retry condition). */
        Sleeper THREAD_SLEEPER = millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted during retry backoff", e);
            }
        };
    }
}
