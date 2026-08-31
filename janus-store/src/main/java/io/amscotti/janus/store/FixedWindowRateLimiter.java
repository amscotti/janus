package io.amscotti.janus.store;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory fixed-window {@link RateLimiter} (; the Core.RateLimiter
 * algorithm, ported — read for semantics, not code). One counter per
 * {@code (keyId, dimension)} shard in a {@link ConcurrentHashMap}; every access runs
 * inside a single atomic {@code compute}, so the counter is the single source of truth
 * and the cap is <b>exact within a window under concurrency</b> (the concurrent-burst
 * test admits exactly the cap).
 *
 * <p><b>Algorithm.</b> Windows are aligned to the epoch by integer division:
 * {@code window_start = floorDiv(now_seconds, 60) * 60}. A request whose shard's
 * window differs from the current one resets the counter (count = 1 in the new
 * window) — <b>forward rollover only</b>: a {@code window_start} recomputed *behind*
 * the stored one (a stepped-back clock, NTP slew) keeps the stored window, so a
 * backward clock can neither drain the bucket nor reset a counter mid-window.
 * {@code Retry-After} for a denial = seconds until the window end
 * ({@code window_start + 60 − now_seconds}), which is always in {@code [1, 60]} for
 * a monotonic clock — never 0 (a request landing on the rollover second recomputes
 * the next window instead of being denied; the ≥ 0 floor is defensive, matching the
 * max(..., 1)). The documented boundary-rollover imprecision is
 * carried over: a request hitting the exact rollover second may reset a window that
 * already admitted requests — at most a constant number of free requests per
 * rollover per shard, the standard fixed-window property, conservative for the
 * client.
 *
 * <p>The RPM dimension ({@link #tryAcquire}) and the TPM dimension ({@link
 * #wouldExceed}/{@link #accumulate}) are independent shards on the same key, so a
 * request-count denial never disturbs the token counter and vice versa.
 *
 * <p><b>Bounded memory.</b> Entries carry a {@code lastAccessMillis}; one is
 * replaced when its window rolls (the map never accumulates stale windows), and a
 * <b>sampled write-path janitor</b> — every {@value #PRUNE_INTERVAL}th access —
 * removes entries untouched for ≥ {@value #IDLE_TTL_MILLIS} ms (10 windows), the
 * fixed-window analog of {@link TokenBucketRateLimiter}'s lazy idle eviction: long-
 * idle keys stop pinning heap. Cross-node coordination is the store layer's job.
 *
 * <p>Thread-safe. The {@link Clock} is injected  and read on every
 * access — tests pin a fixed/mutable clock, production wires the shared system-clock
 * bean via the gateway factory.
 */
public final class FixedWindowRateLimiter implements RateLimiter {

    /** Window length in seconds (the @windows "requests_per_minute" row). */
    public static final long WINDOW_SECONDS = 60;

    /**
     * Lazy-eviction idle TTL: an entry untouched for this long is removed by the
     * sampled janitor ({@code 10 × WINDOW_SECONDS × 1000}), matching
     * {@link TokenBucketRateLimiter#IDLE_TTL_MILLIS}.
     */
    public static final long IDLE_TTL_MILLIS = 10 * WINDOW_SECONDS * 1000;

    /** Sampled janitor: one idle-sweep per this many accesses (best-effort). */
    static final int PRUNE_INTERVAL = 1024;

    private static final String DIMENSION_REQUESTS = "requests";
    private static final String DIMENSION_TOKENS = "tokens";

    private final Clock clock;
    private final ConcurrentMap<Shard, Entry> counters = new ConcurrentHashMap<>();
    private final AtomicLong accesses = new AtomicLong();

    public FixedWindowRateLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RateLimitResult tryAcquire(String keyId, int limit, long cost) {
        requireKey(keyId);
        requirePositive(limit, "limit");
        requireNonNegative(cost, "cost");
        long nowMillis = clock.millis();
        long windowStart = Math.floorDiv(nowMillis / 1000, WINDOW_SECONDS) * WINDOW_SECONDS;
        AtomicReference<RateLimitResult> result = new AtomicReference<>();
        counters.compute(new Shard(keyId, DIMENSION_REQUESTS), (shard, entry) -> {
            long start = entry == null ? windowStart : entry.windowStartSeconds();
            long count = entry == null ? 0 : entry.count();
            if (windowStart > start) { // forward rollover only: a stepped-back clock keeps the window
                start = windowStart;
                count = 0;
            }
            // Overflow-safe cap check (count + cost > limit ⟺ cost > limit − count; a
            // cost > limit is denied too — limit − cost is negative and count ≥ 0).
            if (cost > limit - count) {
                result.set(new RateLimitResult.Denied(Math.max(0, start + WINDOW_SECONDS - nowMillis / 1000)));
                return entry == null ? null : new Entry(start, count, nowMillis); // denied: record access, no consume
            }
            long newCount = count + cost;
            result.set(new RateLimitResult.Allowed(newCount));
            return new Entry(start, newCount, nowMillis);
        });
        maybePrune();
        return result.get();
    }

    @Override
    public boolean wouldExceed(String keyId, int limit, long estimate) {
        requireKey(keyId);
        requirePositive(limit, "limit");
        requireNonNegative(estimate, "estimate");
        long nowMillis = clock.millis();
        long windowStart = Math.floorDiv(nowMillis / 1000, WINDOW_SECONDS) * WINDOW_SECONDS;
        // A genuinely non-mutating pre-check (PgRateLimiter.wouldExceed is a
        // pure SELECT — same semantics here). Entry fields are final (record) and the
        // CHM publishes atomically, so a plain get is a consistent snapshot; a racing
        // accumulate can only make the next pre-check stricter, never looser, and the
        // hard enforcement is the settle itself. The old compute-based check rolled a
        // stale window's entry and refreshed its access time as side effects — the
        // "non-consuming" contract is now literally true (no state change at all).
        Entry entry = counters.get(new Shard(keyId, DIMENSION_TOKENS));
        if (entry == null || entry.windowStartSeconds() < windowStart) {
            return estimate > limit;
        }
        // Overflow-safe (count + estimate > limit ⟺ estimate > limit − count).
        return estimate > limit - entry.count();
    }

    @Override
    public long accumulate(String keyId, int limit, long actual) {
        requireKey(keyId);
        requirePositive(limit, "limit");
        requireNonNegative(actual, "actual");
        long nowMillis = clock.millis();
        long windowStart = Math.floorDiv(nowMillis / 1000, WINDOW_SECONDS) * WINDOW_SECONDS;
        AtomicReference<Long> result = new AtomicReference<>(0L);
        counters.compute(new Shard(keyId, DIMENSION_TOKENS), (shard, entry) -> {
            long start = entry == null ? windowStart : entry.windowStartSeconds();
            long count = entry == null ? 0 : entry.count();
            if (windowStart > start) { // forward rollover only
                start = windowStart;
                count = 0;
            }
            // addExact: the window total is unbounded by design, but a wrap (≈9.2e18
            // tokens) would corrupt the returned pin; a wrap is unreachable with real
            // usage and throws rather than silently wrapping (Postgres bigint parity).
            long newCount = Math.addExact(count, actual);
            result.set(newCount);
            return new Entry(start, newCount, nowMillis);
        });
        maybePrune();
        return result.get();
    }

    /**
     * Remove entries untouched for ≥ {@link #IDLE_TTL_MILLIS} — the fixed-window idle
     * eviction (package-private for tests; the sampled {@link #maybePrune}
     * calls it on the request path). Conditional atomic removal (CHM
     * {@code remove(key, value)}): a concurrent access that just refreshed an entry
     * is never evicted.
     */
    void pruneStale() {
        long now = clock.millis();
        counters.entrySet().removeIf(e -> now - e.getValue().lastAccessMillis() >= IDLE_TTL_MILLIS);
    }

    private void maybePrune() {
        if (accesses.incrementAndGet() % PRUNE_INTERVAL == 0) {
            pruneStale();
        }
    }

    /** Live shard count (package-private test accessor). */
    int shardCount() {
        return counters.size();
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

    /** One fixed-window counter: the aligned window start (epoch seconds) + the count + last access (idle-eviction clock). */
    record Entry(long windowStartSeconds, long count, long lastAccessMillis) {}

    /** The per-key/per-dimension map key (RPM and TPM counters on one key are independent). */
    record Shard(String keyId, String dimension) {}
}
