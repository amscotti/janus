package io.amscotti.janus.store;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory token-bucket {@link RateLimiter} — the sliding-window variant the
 * {@code [janus.limits] window = "sliding"} config selects. Capacity = the limit,
 * refill = {@code limit / 60} tokens per second (a per-minute window smoothed into a
 * continuous stream — a burst of {@code limit} is admitted, then tokens accrete at the
 * steady rate).
 *
 * <p><b>No scheduler.</b> Refill is computed lazily on access from the last-access
 * timestamp ({@code tokens = min(capacity, tokens + elapsed × rate)}), inside the same
 * atomic {@code compute} that consumes — so concurrent requests cannot double-refill or
 * over-admit past capacity (the concurrent-burst test pins exactly-cap admission).
 * {@code Retry-After} for a denial = {@code ceil(deficit ÷ rate)} seconds until enough
 * tokens exist, which reaches 0 exactly as the bucket refills.
 *
 * <p><b>Bounded memory without a janitor.</b> An entry untouched for ≥
 * {@value #IDLE_TTL_MILLIS} ms (10 windows) is replaced by a fresh full bucket on its
 * next access — the stale entry "disappears", so long-idle keys never pin heap
 * ({@code FixedWindowRateLimiter} replaces on rollover instead; both are per-key
 * in-memory state — the Postgres store does not implement this variant).
 *
 * <p><b>Clock step-back is clamped.</b> The wall clock is read on every access
 * (production wires {@code Clock.systemUTC} via the gateway factory); if it steps
 * <em>backward</em> (NTP slew), the refill elapsed {@code now − lastRefillMillis}
 * would go negative and drain the bucket — it is clamped to ≥ 0, so a backward
 * clock never reduces the available tokens.
 *
 * <p>{@link #wouldExceed}/{@link #accumulate} mirror the fixed-window TPM semantics:
 * the pre-check is non-consuming, and accumulate subtracts real tokens at finalize —
 * the count may go negative (the request that crossed pays the debt; the cap gates the
 * <em>next</em> request, the documented behavior). <b>One return meaning.</b>
 * {@link #accumulate} returns the post-accumulation TPM counter value — <em>the window
 * total</em> (net tokens consumed in the current window, floored to whole tokens),
 * exactly the fixed-window meaning the {@link RateLimiter} contract pins: the sliding
 * variant and the fixed default now return the same quantity for the same sequence, so
 * the return is safe for any consumer (a future {@code X-RateLimit-*} header) regardless
 * of {@code [janus.limits] window}.
 *
 * <p>Thread-safe. The {@link Clock} is injected  and read on every
 * access; validation rejects non-positive capacities/rates and negative costs, so a
 * misconfigured cap fails at boot, not on the request path.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    /** Window length in seconds — the refill denominator (rate = limit/60 per second). */
    public static final long WINDOW_SECONDS = 60;

    /**
     * The Retry-After ceiling for a never-reopening denial (cost > capacity): 24 h —
     * "denied forever" on the wire without the absurd {@code Retry-After:
     * 9223372036854775807} (review L2).
     */
    public static final long MAX_RETRY_AFTER_SECONDS = 24 * 60 * 60;

    /**
     * Lazy-eviction idle TTL: an entry untouched for this long is replaced by a fresh
     * bucket on next access ({@code 10 × WINDOW_SECONDS × 1000}).
     */
    public static final long IDLE_TTL_MILLIS = 10 * WINDOW_SECONDS * 1000;

    private static final String DIMENSION_REQUESTS = "requests";
    private static final String DIMENSION_TOKENS = "tokens";

    private final Clock clock;
    private final ConcurrentMap<Shard, Entry> buckets = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong accesses = new java.util.concurrent.atomic.AtomicLong();

    public TokenBucketRateLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RateLimitResult tryAcquire(String keyId, int limit, long cost) {
        requireKey(keyId);
        requirePositive(limit, "limit");
        requireNonNegative(cost, "cost");
        long now = clock.millis();
        double capacity = limit;
        double ratePerSecond = limit / (double) WINDOW_SECONDS;
        AtomicReference<RateLimitResult> result = new AtomicReference<>();
        buckets.compute(new Shard(keyId, DIMENSION_REQUESTS), (shard, entry) -> {
            Entry fresh = entry == null || now - entry.lastAccessMillis() >= IDLE_TTL_MILLIS
                    ? new Entry(capacity, now, now)
                    : entry;
            // Clamp the refill elapsed to ≥ 0: a stepped-back clock must not drain the bucket.
            double tokens = Math.min(
                    capacity, fresh.tokens() + Math.max(0, now - fresh.lastRefillMillis()) * ratePerSecond / 1000.0);
            if (tokens >= cost) {
                double remaining = tokens - cost;
                // Allowed.count = whole tokens remaining (floored from the exact bucket);
                // the stored bucket keeps the EXACT remainder, so refill never loses
                // the fraction and the steady-state rate holds limit/60 — floor(x) ≤ x is
                // only applied to the reported count, never the stored state.
                result.set(new RateLimitResult.Allowed((long) Math.floor(remaining)));
                return new Entry(remaining, now, now);
            }
            // Denied. When cost > capacity the gate can NEVER reopen (tokens cap at
            // capacity), so the deficit-derived retry-after would advertise a false
            // reopen — it saturates to "denied forever" instead, clamped to a sane
            // ceiling (review L2: an unclamped Long.MAX_VALUE would render as
            // `Retry-After: 9223372036854775807` on the wire). Unreachable today (RPM
            // cost is always 1 ≤ capacity); the clamp guards a future cost > 1.
            double deficit = cost - tokens;
            long retryAfter = cost > capacity
                    ? MAX_RETRY_AFTER_SECONDS
                    : Math.min((long) Math.ceil(deficit / ratePerSecond), MAX_RETRY_AFTER_SECONDS);
            result.set(new RateLimitResult.Denied(Math.max(0, retryAfter)));
            return new Entry(tokens, now, now);
        });
        maybePrune();
        return result.get();
    }

    @Override
    public boolean wouldExceed(String keyId, int limit, long estimate) {
        requireKey(keyId);
        requirePositive(limit, "limit");
        requireNonNegative(estimate, "estimate");
        long now = clock.millis();
        double capacity = limit;
        double ratePerSecond = limit / (double) WINDOW_SECONDS;
        AtomicReference<Boolean> result = new AtomicReference<>(Boolean.FALSE);
        buckets.compute(new Shard(keyId, DIMENSION_TOKENS), (shard, entry) -> {
            if (entry == null || now - entry.lastAccessMillis() >= IDLE_TTL_MILLIS) {
                result.set(estimate > capacity); // fresh bucket: only an over-capacity estimate exceeds
                return entry == null ? null : new Entry(capacity, now, now);
            }
            double tokens = Math.min(
                    capacity, entry.tokens() + Math.max(0, now - entry.lastRefillMillis()) * ratePerSecond / 1000.0);
            // Mirror of the fixed-window check (count + estimate > limit where count =
            // tokens *consumed* in the window): consumed = capacity − tokens, so
            // consumed + estimate > capacity ⟺ estimate > tokens (tokens *available*).
            result.set(estimate > tokens);
            return new Entry(tokens, now, now); // non-consuming: refill/access recorded, no tokens taken
        });
        maybePrune();
        return result.get();
    }

    @Override
    public long accumulate(String keyId, int limit, long actual) {
        requireKey(keyId);
        requirePositive(limit, "limit");
        requireNonNegative(actual, "actual");
        long now = clock.millis();
        double capacity = limit;
        double ratePerSecond = limit / (double) WINDOW_SECONDS;
        AtomicReference<Long> result = new AtomicReference<>(0L);
        buckets.compute(new Shard(keyId, DIMENSION_TOKENS), (shard, entry) -> {
            Entry fresh = entry == null || now - entry.lastAccessMillis() >= IDLE_TTL_MILLIS
                    ? new Entry(capacity, now, now)
                    : entry;
            // Clamp the refill elapsed to ≥ 0 (clock step-back).
            double tokens = Math.min(
                    capacity, fresh.tokens() + Math.max(0, now - fresh.lastRefillMillis()) * ratePerSecond / 1000.0);
            // Returns the WINDOW TOTAL (net tokens consumed, floored) — the one meaning
            // the RateLimiter contract pins, matching the fixed-window variant and the
            // (no Postgres variant). The stored bucket keeps the EXACT consumed value
            // (may be negative — real-spend debt), so wouldExceed always reads the true
            // debt; the returned window total is derived from it, never stored instead.
            double consumed = tokens - actual; // may go negative: real spend is a debt the cap repays
            result.set((long) Math.floor(capacity - consumed));
            return new Entry(consumed, now, now);
        });
        maybePrune();
        return result.get();
    }

    private static void requireKey(String keyId) {
        Objects.requireNonNull(keyId, "keyId");
    }

    private static void requirePositive(int limit, String name) {
        if (limit <= 0) {
            throw new IllegalArgumentException(name + " must be positive (got " + limit + ")");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative (got " + value + ")");
        }
    }

    /**
     * One token bucket: {@code tokens} (may go negative after {@code accumulate} —
     * real-spend debt), the last refill timestamp and the last access timestamp (the
     * lazy-eviction clock).
     */
    record Entry(double tokens, long lastRefillMillis, long lastAccessMillis) {}

    /** The per-key/per-dimension map key (RPM and TPM buckets on one key are independent). */
    record Shard(String keyId, String dimension) {}
    /**
     * Lazy eviction only fires on the NEXT access of a
     * key — a revoked or abandoned key's bucket entry is never accessed again and
     * previously lived forever (the class javadoc's "bounded memory" claim was only
     * true for returning keys). The sampled write-path janitor mirrors
     * {@link FixedWindowRateLimiter}: every {@value #PRUNE_INTERVAL}th access sweeps
     * entries untouched for ≥ {@value #IDLE_TTL_MILLIS} ms. Conditional atomic removal
     * (CHM {@code removeIf}): a concurrent access that just refreshed an entry is
     * never evicted.
     */
    static final int PRUNE_INTERVAL = 1024;

    /** Remove entries untouched for ≥ {@link #IDLE_TTL_MILLIS} (package-private test seam). */
    void pruneStale() {
        long now = clock.millis();
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccessMillis() >= IDLE_TTL_MILLIS);
    }

    private void maybePrune() {
        if (accesses.incrementAndGet() % PRUNE_INTERVAL == 0) {
            pruneStale();
        }
    }

    /** Live bucket count (package-private test accessor). */
    int bucketCount() {
        return buckets.size();
    }
}
