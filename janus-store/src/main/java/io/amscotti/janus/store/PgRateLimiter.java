package io.amscotti.janus.store;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/**
 * The Postgres {@link RateLimiter} half of {@link PostgresCallStore}: the
 * {@link FixedWindowRateLimiter} semantics re-implemented as atomic SQL upserts —
 * {@link FixedWindowRateLimiter} is the parity reference (the contract pins
 * {@code accumulate}'s return as the fixed-window <b>window total</b>).
 *
 * <p><b>Fixed-window only (documented divergence).</b> The parity harness pins
 * {@code accumulate}'s return as the fixed-window <b>window total</b>; the token
 * bucket's {@code accumulate} now returns the same window-total quantity for the same
 * sequence (its net-tokens-consumed view, floored), but a full token-bucket
 * <em>admission</em> (refill-by-rate, debt) has no Postgres counterpart. Postgres
 * therefore implements fixed-window semantics only; {@code [janus.limits] window =
 * "sliding"} combined with a postgres store is rejected at config binding.
 *
 * <p><b>Windows.</b> Aligned to the epoch: {@code window_start =
 * floorDiv(now_seconds, 60) * 60}, 60s — read the javadoc, do not re-derive.
 * The counter PK carries {@code window_start}, so a stale window's row is simply
 * ignored (the upsert lands in the current window and the insert wins) — the
 * in-memory "rollover resets" semantic preserved by the window-in-PK.
 *
 * <p><b>Atomicity.</b> {@code tryAcquire}/{@code accumulate} are single
 * {@code INSERT... ON CONFLICT DO UPDATE... WHERE... RETURNING} statements:
 * the {@code WHERE} re-checks the cap against the current row (PostgreSQL
 * re-evaluates it after acquiring the conflicting row's lock), so the cap is exact
 * under concurrency and a denied request <b>never consumes</b> (no row returned ⇒
 * the denied count is not inserted). {@code wouldExceed} is a non-consuming
 * {@code SELECT} — a TOCTOU vs a concurrent {@code accumulate} is inherent and
 * matches the in-memory non-atomic pre-check (documented semantics).
 *
 * <p><b>Bounded retention.</b> The window-in-PK design means a stale window's
 * row is dead by construction — only the current window is ever read. Rows are
 * pruned by a <b>sampled write-path janitor</b>: every {@value #PRUNE_INTERVAL}th
 * write access runs {@code DELETE FROM rate_limits WHERE window_start < now −
 * {@value #PRUNE_KEEP_WINDOWS} × 60}, keeping the current window plus the last
 * {@value #PRUNE_KEEP_WINDOWS} (a ~3-minute horizon). No scheduler, no thread: the
 * janitor rides the request path, so an active key's 2 rows per 60s window never
 * accrete forever. A prune failure is housekeeping — it never denies the request
 * (the write already succeeded; see {@link #pruneStaleWindows}).
 */
final class PgRateLimiter {

    private static final Logger LOG = System.getLogger(PgRateLimiter.class.getName());

    /** Window length in seconds (the {@code FixedWindowRateLimiter} constant). */
    static final long WINDOW_SECONDS = 60;

    /**
     * Retention horizon: windows older than this many windows past the current one
     * are pruned (the current window + the last 2 survive).
     */
    static final int PRUNE_KEEP_WINDOWS = 2;

    /** Sampled janitor: one {@code DELETE} per this many write accesses (best-effort). */
    static final int PRUNE_INTERVAL = 1024;

    private static final String DIMENSION_REQUESTS = "requests";
    private static final String DIMENSION_TOKENS = "tokens";

    private final DataSource dataSource;
    private final Clock clock;
    private final AtomicLong accesses = new AtomicLong();

    PgRateLimiter(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    RateLimiter.RateLimitResult tryAcquire(String keyId, int limit, long cost) {
        requireKey(keyId);
        requirePositive(limit, "limit");
        requireNonNegative(cost, "cost");
        RateLimiter.RateLimitResult result = upsertCount(keyId, DIMENSION_REQUESTS, limit, cost);
        maybePruneStaleWindows();
        return result;
    }

    /**
     * Non-consuming pre-check: {@code estimate} alone crosses the cap when the current
     * window's row is absent (a stale window's row is not the current window); the
     * counter is untouched. Overflow-safe form: {@code count + estimate > limit} ⟺
     * {@code estimate > limit − count} (a count pushed past the cap by {@code accumulate}
     * makes any estimate exceed).
     *
     * <p><b>Backward-clock divergence (documented, accepted).</b> The in-memory
     * {@link FixedWindowRateLimiter} reference keeps its stored window when the clock
     * steps back (the documented NTP-slew behavior) and applies that ahead-of-now
     * counter to the current check — the conservative direction. Postgres derives the
     * window from the current time, so a future-window row is ignored and
     * {@code wouldExceed} is marginally more permissive than its reference under clock
     * slew. Only reachable in a pathological stepped-back-clock scenario, and in the
     * permissive — never the over-zealous — direction; under a monotonic clock the two
     * agree.
     */
    boolean wouldExceed(String keyId, int limit, long estimate) {
        requireKey(keyId);
        requirePositive(limit, "limit");
        requireNonNegative(estimate, "estimate");
        long windowStart = windowStart();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT count FROM rate_limits WHERE key_id = ? AND dimension = ? AND window_start = ?")) {
            ps.setString(1, keyId);
            ps.setString(2, DIMENSION_TOKENS);
            ps.setLong(3, windowStart);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? estimate > limit - rs.getLong(1) : estimate > limit;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres rate limiter wouldExceed failed", e);
        }
    }

    long accumulate(String keyId, int limit, long actual) {
        requireKey(keyId);
        requirePositive(limit, "limit");
        requireNonNegative(actual, "actual");
        // The pinned meaning: the post-accumulation counter value — the window
        // total (cumulative tokens consumed in the current window). consume-at-
        // finalize NEVER denies (the cap gates the next request, not the one already
        // completed — the documented semantics), so the counter may push past the
        // cap exactly like the in-memory reference.
        long result = accumulateCount(keyId, actual);
        maybePruneStaleWindows();
        return result;
    }

    /**
     * The tryAcquire atomic increment-then-check: {@code INSERT... ON CONFLICT
     * (key_id, dimension, window_start) DO UPDATE SET count = count + EXCLUDED.count
     * WHERE count <= ? - EXCLUDED.count RETURNING count} (the in-memory reference's
     * overflow-safe {@code cost > limit - count} form — the raw {@code count + cost}
     * sum would raise a Postgres "bigint out of range" 5xx on a saturated row
     * LOW). A returned row ⇒ {@link RateLimiter.RateLimitResult.Allowed} with the new
     * counter value; no row (the {@code WHERE} failed — the cap would be crossed) ⇒
     * {@link RateLimiter.RateLimitResult.Denied} with
     * {@code retryAfterSeconds = max(1, window_start + 60 - now_seconds)} — always
     * in {@code [1, 60]} (never 0: a request on the rollover second or a backward
     * clock tick still reports a wait; the ≥ 1 clamp matches the in-memory
     * reference and the max(..., 1)).
     */
    private RateLimiter.RateLimitResult upsertCount(String keyId, String dimension, int limit, long cost) {
        // The ON CONFLICT DO UPDATE's WHERE applies ONLY to the update path — a fresh
        // window's INSERT would admit a cost above the cap. The in-memory reference
        // denies cost > limit unconditionally (count ≥ 0 ⇒ count + cost > limit), so
        // the guard below reproduces it before the statement (the exact-cap
        // contract: "Allowed iff existing + cost ≤ limit").
        if (cost > limit) {
            return deny(windowStart());
        }
        long windowStart = windowStart();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO rate_limits (key_id, dimension, window_start, count) VALUES (?, ?, ?, ?)"
                                + " ON CONFLICT (key_id, dimension, window_start) DO UPDATE"
                                + " SET count = rate_limits.count + EXCLUDED.count"
                                // Overflow-safe cap re-check: the in-memory
                                // reference's `cost > limit - count` rewrite — never the
                                // raw `count + cost` sum, which PostgreSQL evaluates as
                                // bigint and would raise "bigint out of range" (a 5xx)
                                // on a row whose count saturates (reachable only by a
                                // direct DB edit; the requests counter is bounded by the
                                // int limit through the public API). `limit - cost` is
                                // non-negative because the pre-check above denies
                                // cost > limit.
                                + " WHERE rate_limits.count <= ? - EXCLUDED.count"
                                + " RETURNING count")) {
            ps.setString(1, keyId);
            ps.setString(2, dimension);
            ps.setLong(3, windowStart);
            ps.setLong(4, cost);
            ps.setLong(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new RateLimiter.RateLimitResult.Allowed(rs.getLong(1));
                }
                // Denied requests never consume: the WHERE made the update a no-op —
                // no row returned, the denied count was never inserted.
                return deny(windowStart);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres rate limiter upsert failed", e);
        }
    }

    /**
     * The accumulate atomic increment (no cap check — the consume-at-finalize
     * semantics): {@code INSERT... ON CONFLICT (key_id, dimension, window_start) DO
     * UPDATE SET count = rate_limits.count + EXCLUDED.count RETURNING count}. Always
     * returns a row — accumulation may push the counter past the cap (the cap gates
     * the <em>next</em> request), matching {@link FixedWindowRateLimiter#accumulate}.
     */
    private long accumulateCount(String keyId, long actual) {
        long windowStart = windowStart();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO rate_limits (key_id, dimension, window_start, count) VALUES (?, ?, ?, ?)"
                                + " ON CONFLICT (key_id, dimension, window_start) DO UPDATE"
                                + " SET count = rate_limits.count + EXCLUDED.count"
                                + " RETURNING count")) {
            ps.setString(1, keyId);
            ps.setString(2, DIMENSION_TOKENS);
            ps.setLong(3, windowStart);
            ps.setLong(4, actual);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres rate limiter accumulate failed", e);
        }
    }

    private RateLimiter.RateLimitResult.Denied deny(long windowStart) {
        long nowSeconds = clock.millis() / 1000;
        return new RateLimiter.RateLimitResult.Denied(Math.max(1, windowStart + WINDOW_SECONDS - nowSeconds));
    }

    /**
     * Sampled write-path janitor: one {@code DELETE} per {@link #PRUNE_INTERVAL}
     * write accesses clears every row more than {@link #PRUNE_KEEP_WINDOWS} windows
     * behind the current one — the window-in-PK design makes this safe (only the
     * current window is ever read, so a stale row can never be re-admitted). Package-
     * private for the test (deterministic prune assertions).
     */
    void pruneStaleWindows() {
        long cutoff = windowStart() - (long) PRUNE_KEEP_WINDOWS * WINDOW_SECONDS;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("DELETE FROM rate_limits WHERE window_start < ?")) {
            ps.setLong(1, cutoff);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Housekeeping only: a failed prune must never deny a request whose write
            // already succeeded (the row simply waits for the next sampled prune). But a
            // PERMANENTLY failing prune (permissions revoked on the table) would let
            // rate-limit rows grow forever with zero signal — log at FINE, not WARN:
            // the sampled janitor retries on the next PRUNE_INTERVALth write, so a WARN
            // per failure would be log spam under a sustained outage (review L2).
            LOG.log(
                    Level.DEBUG,
                    "rate_limits prune failed (rows stay until the next sampled prune): {0}",
                    e.toString());
        }
    }

    private void maybePruneStaleWindows() {
        if (accesses.incrementAndGet() % PRUNE_INTERVAL == 0) {
            pruneStaleWindows();
        }
    }

    private long windowStart() {
        long nowSeconds = clock.millis() / 1000;
        return Math.floorDiv(nowSeconds, WINDOW_SECONDS) * WINDOW_SECONDS;
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
}
