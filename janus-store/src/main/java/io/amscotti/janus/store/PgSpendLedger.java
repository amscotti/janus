package io.amscotti.janus.store;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/**
 * The Postgres {@link SpendLedger} half of {@link PostgresCallStore} — the
 * {@link InMemorySpendLedger} semantics re-implemented in JDBC — read its javadoc
 * and tests for the exact reserve/settle/release math and clamping: double
 * {@code release} is a harmless no-op, unknown key ⇒ zero spend / empty recent):
 * windowed settled spend (the budget view; see "Reset windows" below), the bounded
 * {@code spend_entries} recent ring (bigserial seq is the stable newest-first order,
 * pruned by retention), and the atomic reserve/settle/release flow.
 *
 * <p><b>Reset windows (V2 schema).</b> The {@code spend} primary key is
 * {@code (key_id, window_start)} — the same shape as {@code rate_limits} — where
 * {@code window_start = floorDiv(nowSeconds, windowSeconds) × windowSeconds} for a
 * windowed key and {@code 0} for a lifetime key ({@code windowSeconds == 0}; pre-V2
 * rows backfill to 0). Windowed epochs are huge positive numbers for any real
 * timestamp, so lifetime and windowed rows never collide. {@code reserve} upserts on
 * the composite key (a reserve in a newer epoch lands in that window's row with
 * {@code settled = 0} — forward-only rollover); {@code settle}/{@code release}
 * target <b>the reservation's</b> window row ({@code reservationWindowStart} from the
 * {@link SpendLedger.ReserveResult.Allowed}), so a straddled reservation settles into
 * its own window exactly like the in-memory reference (the straddle-parity pin —
 * both backends key ledger state identically). {@link #spendByKey} reads the key's
 * max-{@code window_start} row (the budget view); {@link #totalSpendByKey} sums
 * surviving rows — exact because the prune (below) only drops rows already folded
 * into older windows, never the newest.
 *
 * <p><b>Bounded retention.</b> Windowed keys accrue one row per window; a sampled
 * write-path janitor (one {@code DELETE} per {@value #PRUNE_INTERVAL} reserves)
 * drops the key's rows more than {@value #PRUNE_KEEP_WINDOWS} windows behind the
 * current one (the {@link PgRateLimiter} pattern). Lifetime keys keep their single
 * window-0 row forever (it IS the budget view and the all-time total). A prune
 * failure is housekeeping — it never denies the request whose reserve already
 * succeeded.
 *
 * <p><b>Negative-actual clamp.</b> {@code settle} clamps
 * a negative {@code actualMicroUsd} to 0 — the max(actual, 0)
 * reference semantic — it never throws on the actual's sign (the estimate stays
 * guarded); the Java-side clamp is the single rule (the SQL takes the already-clamped
 * value), and the in-memory ledger shares it (its non-negative guard was removed in
 * both implementations clamp — no divergence).
 *
 * <p><b>Settle pending clamp.</b> {@code settle} also clamps
 * {@code pending − estimate} at 0 ({@code GREATEST(pending − ?, 0)}), mirroring the
 * {@code release} clamp: a settle that overdraws pending — a racing release or a
 * double-settle — never drives pending negative (a negative pending would loosen the
 * hard cap's {@code settled + pending ≥ cap} admission check).
 *
 * <p><b>Settle saturation.</b> {@code settle} accumulates the committed
 * total with {@code settled = CASE WHEN settled > max − actual THEN max ELSE
 * settled + actual END} — saturated at bigint max, never summed raw. The in-memory
 * reference saturates too (its {@code saturatingAdd}); the raw {@code settled +
 * actual} would raise a Postgres "bigint out of range" 5xx on the billing path.
 * Unreachable at realistic spend (~$9.2e12 micro-USD), pinned for parity.
 *
 * <p><b>Atomic no-overspend reserve.</b> {@code reserve} first self-registers the
 * row ({@code INSERT... ON CONFLICT DO NOTHING} — unknown keys self-register, the
 * contract), then runs the increment-then-check as <b>one atomic upsert</b>:
 * {@code ON CONFLICT (key_id, window_start) DO UPDATE SET pending = pending +
 * EXCLUDED.pending WHERE settled < ? - pending - EXCLUDED.pending RETURNING settled,
 * pending}. The {@code WHERE} re-checks the cap against the current row after the
 * row lock is acquired, so a concurrent burst can never overspend beyond one request
 * (the contract's 8-thread smoke: cap 1000, estimate 300 ⇒ exactly 3 Allowed). The
 * in-memory {@code ≥} comparison is mirrored — the cap-exact reservation is denied
 * ({@code total >= cap} ⇒ deny). The guard is written in the in-memory reference's
 * <b>overflow-safe form</b> {@code settled >= cap - pending - estimate} (never the
 * raw {@code settled + pending + estimate} sum, which would raise a Postgres
 * "bigint out of range" 5xx at saturation; the subtraction cannot
 * underflow because the ledger never lets {@code pending} reach {@code cap}; the
 * estimate is clamped to the cap Java-side first — the in-memory reference's
 * outcome-preserving defense-in-depth clamp — so the guard's subtraction can never
 * underflow bigint on a saturated estimate).
 * {@code hardCapMicroUsd ≤ 0} ⇒ no cap (unconditional upsert, with the pending
 * accumulation <b>saturated at bigint max in SQL</b> — the in-memory reference's
 * unconditional {@code saturatingAdd}, mirrored: a saturated estimate followed by any
 * second no-cap reserve would otherwise raise a Postgres "bigint out of range" 5xx
 * where the in-memory ledger saturates). {@code soft} is
 * computed from the RETURNING row in the in-memory reference's overflow-safe form:
 * {@code settled ≥ floor(hardCap × softFraction) − pending} (never
 * {@code settled + pending}, which could wrap).
 *
 * <p><b>Reserve is one transaction.</b> The upsert, the
 * {@code RETURNING} read, and — on a hard deny — the denied-payload totals
 * {@code readTotals} all run inside one explicit transaction. The failed upsert's
 * row lock is held until commit, so a concurrent {@code settle}/{@code release}
 * cannot interleave between the denial and the totals read: the {@code Denied}
 * payload is a single snapshot exactly like the in-memory ledger's one atomic
 * {@code compute}, not a post-concurrent-settle state a second autocommit statement
 * would observe.
 *
 * <p><b>Self-registering settle/release.</b> {@code settle} is a single
 * self-registering <b>upsert</b> (a key's committed total exists from its first
 * settle; a settle whose reservation window row was pruned mid-flight re-creates
 * it with {@code settled = actual} — never a 0-row no-op that would drop the
 * actual from the all-time sum); {@code release} is a single clamped
 * {@code UPDATE} — a no-op for unknown keys (0 rows) and never drives pending
 * negative (the clamp).
 *
 * <p><b>recordSpend ring.</b> Appends to {@code spend_entries} and prunes the
 * per-key ring to retention inside one transaction under
 * {@code pg_advisory_xact_lock(hashtextextended(key_id, 0))} — per-key serialization
 * mirrors the in-memory per-key {@code compute} atomicity (racing prunes would
 * otherwise both delete the same overflow row).
 */
final class PgSpendLedger {

    private static final Logger LOG = System.getLogger(PgSpendLedger.class.getName());

    /**
     * Retention horizon: windowed spend rows older than this many windows past the
     * current one are pruned (the current window + the last
     * {@value #PRUNE_KEEP_WINDOWS} survive) — mirrors {@link PgRateLimiter}.
     */
    static final int PRUNE_KEEP_WINDOWS = 2;

    /** Sampled janitor: one {@code DELETE} per this many reserves (best-effort). */
    static final int PRUNE_INTERVAL = 1024;

    private final DataSource dataSource;
    private final Clock clock;
    private final int retention;
    private final AtomicLong reserves = new AtomicLong();

    PgSpendLedger(DataSource dataSource, Clock clock, int retention) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retention <= 0) {
            throw new IllegalArgumentException("retention must be positive (got " + retention + ")");
        }
        this.retention = retention;
    }

    long spendByKey(String keyId, long windowSeconds) {
        Objects.requireNonNull(keyId, "keyId");
        // The budget view: the newest window row (the current window once it has
        // activity — a lifetime key's only row is window 0). The max-row read is the
        // in-memory reference's newest-window read, so the two backends cannot diverge
        // whatever the clock does between the caller's epoch computation and the read.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT settled FROM spend WHERE key_id = ? ORDER BY window_start DESC LIMIT 1")) {
            ps.setString(1, keyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres spend ledger spendByKey failed", e);
        }
    }

    long totalSpendByKey(String keyId) {
        Objects.requireNonNull(keyId, "keyId");
        // The all-time view: the sum over the key's surviving rows. Exact forever
        // because the prune (below) never drops settled history — it FOLDS each
        // pruned window's settled into the key's window-0 row (the all-time
        // accumulator, the Postgres analogue of the in-memory per-key scalar) inside
        // the same atomic statement that deletes the rows. A lifetime key's single
        // window-0 row is never pruned at all.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT COALESCE(sum(settled), 0) FROM spend WHERE key_id = ?")) {
            ps.setString(1, keyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres spend ledger totalSpendByKey failed", e);
        }
    }

    List<SpendLedger.LedgerEntry> recent(String keyId, int n) {
        Objects.requireNonNull(keyId, "keyId");
        if (n <= 0) {
            return List.of();
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT at_epoch_millis, micro_usd FROM spend_entries WHERE key_id = ?"
                                + " ORDER BY seq DESC LIMIT ?")) {
            ps.setString(1, keyId);
            ps.setInt(2, n);
            List<SpendLedger.LedgerEntry> entries = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new SpendLedger.LedgerEntry(rs.getLong(1), rs.getLong(2)));
                }
            }
            return List.copyOf(entries);
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres spend ledger recent failed", e);
        }
    }

    SpendLedger.ReserveResult reserve(
            String keyId, long estimateMicroUsd, long hardCapMicroUsd, double softFraction, long windowSeconds) {
        Objects.requireNonNull(keyId, "keyId");
        requireNonNegative(estimateMicroUsd, "estimateMicroUsd");
        if (softFraction < 0 || softFraction > 1) {
            throw new IllegalArgumentException("softFraction must be in [0, 1] (got " + softFraction + ")");
        }
        // Defense-in-depth clamp, mirroring the in-memory reference's reserve exactly:
        // an estimate ≥ cap can never be admitted (settled ≥ 0 means the guard below
        // denies), so clamping is outcome-preserving AND it bounds the guard's
        // subtraction — with estimate ≤ cap, `cap − pending − estimate` can never
        // underflow bigint, where the raw saturated estimate would raise a Postgres
        // "bigint out of range" on the guard evaluation (the in-memory ledger denies
        // cleanly instead). The no-cap branch (cap ≤ 0) has no clamp to lean on, so
        // that branch's pending accumulation saturates in SQL below.
        long estimate = hardCapMicroUsd > 0 && estimateMicroUsd > hardCapMicroUsd ? hardCapMicroUsd : estimateMicroUsd;
        long windowStart = windowStart(windowSeconds);
        selfRegister(keyId, windowStart);
        String sql = hardCapMicroUsd <= 0
                ? "INSERT INTO spend (key_id, window_start, settled, pending) VALUES (?, ?, 0, ?)"
                        + " ON CONFLICT (key_id, window_start) DO UPDATE"
                        // No-cap saturation — the in-memory reference's unconditional
                        // saturatingAdd, in SQL: without a cap there is no clamp to bound
                        // the estimate, so a saturated estimate followed by any second
                        // reserve would exceed bigint on the raw sum. EXCLUDED.pending
                        // is non-negative (the guard above), so `max − EXCLUDED.pending`
                        // cannot underflow. A saturated pending hard-denies any later
                        // capped reserve exactly like the in-memory ledger.
                        + " SET pending = CASE WHEN spend.pending > " + Long.MAX_VALUE + " - EXCLUDED.pending THEN "
                        + Long.MAX_VALUE
                        + " ELSE spend.pending + EXCLUDED.pending END"
                        + " RETURNING settled, pending"
                : "INSERT INTO spend (key_id, window_start, settled, pending) VALUES (?, ?, 0, ?)"
                        + " ON CONFLICT (key_id, window_start) DO UPDATE"
                        + " SET pending = spend.pending + EXCLUDED.pending"
                        // Overflow-safe guard: the in-memory reference's
                        // `settled >= cap - pending` rewrite, mirroring EXCLUDED.pending —
                        // never the raw `settled + pending + EXCLUDED.pending` sum, which
                        // would raise a Postgres "bigint out of range" 5xx at saturation.
                        // The UPDATE's own `pending + EXCLUDED.pending` sum is safe:
                        // the guard only admits while pending + estimate < cap ≤ max.
                        + " WHERE spend.settled < ? - spend.pending - EXCLUDED.pending"
                        + " RETURNING settled, pending";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                SpendLedger.ReserveResult result;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, keyId);
                    ps.setLong(2, windowStart);
                    ps.setLong(3, estimate);
                    if (hardCapMicroUsd > 0) {
                        ps.setLong(4, hardCapMicroUsd);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long settled = rs.getLong(1);
                            long pending = rs.getLong(2);
                            // Overflow-safe form: mirror the in-memory reference exactly
                            // (settled >= softCap - pending, never settled + pending — a wrap at
                            // saturation would flip the flag). The reserve cap bounds the sum, so
                            // the wrap is unreachable on the allowed path, but the two
                            // implementations must not diverge.
                            boolean soft = hardCapMicroUsd > 0
                                    && settled >= (long) Math.floor(hardCapMicroUsd * softFraction) - pending;
                            result = new SpendLedger.ReserveResult.Allowed(soft, settled, pending, windowStart);
                        } else {
                            // The WHERE failed: the hard cap would be crossed — the increment
                            // was rolled back inside the same atomic statement (no overspend).
                            // The payload carries the key's totals read inside the SAME
                            // transaction as the failed upsert: the failed
                            // upsert's row lock blocks a concurrent settle/release until this
                            // transaction ends, so settled/pending are one snapshot exactly
                            // like the in-memory ledger's single atomic compute.
                            result = readTotals(connection, keyId, windowStart);
                        }
                    }
                }
                connection.commit();
                maybePruneStaleWindows(keyId, windowStart, windowSeconds);
                return result;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres spend ledger reserve failed", e);
        }
    }

    void settle(String keyId, long estimateMicroUsd, long actualMicroUsd, long reservationWindowStart) {
        Objects.requireNonNull(keyId, "keyId");
        requireNonNegative(estimateMicroUsd, "estimateMicroUsd");
        // Clamp, never throw, on a negative actual — the
        // max(actual, 0) reference semantic, shared with the in-memory ledger
        // (both implementations now clamp, one rule). The
        // Java-side clamp is the single rule (the SQL below takes the already-clamped
        // value, so no GREATEST(?, 0) is needed on the accumulation).
        long actual = Math.max(actualMicroUsd, 0);
        // ONE self-registering upsert, not a separate self-register + UPDATE: the
        // prune's DELETE can drop the reservation's window row BETWEEN two autocommit
        // statements — the UPDATE would then match 0 rows and silently drop the actual
        // from totalSpendByKey. The single statement either updates the surviving row
        // or re-creates the pruned row with settled = actual (a later prune folds it
        // into the window-0 all-time accumulator), so the actual is committed on every
        // interleaving — mirroring the in-memory ledger, whose straddled settle onto a
        // pruned window simply re-creates the entry.
        String sql = "INSERT INTO spend (key_id, window_start, settled, pending) VALUES (?, ?, ?, 0)"
                + " ON CONFLICT (key_id, window_start) DO UPDATE"
                // Insert-arm pending is the constant 0 = GREATEST(0 − estimate, 0): a
                // row that did not exist has no pending to release (the clamp,
                // precomputed).
                + " SET pending = GREATEST(spend.pending - ?, 0)"
                // Overflow-safe accumulation: the committed total
                // is saturated at bigint max, never summed raw. The in-memory
                // reference saturates too (its `saturatingAdd` clamps at
                // Long.MAX_VALUE) — the raw `settled + actual` sum would
                // raise a Postgres "bigint out of range" 5xx on the billing
                // path, so the CASE keeps both backends saturating
                // identically. `actual` is
                // non-negative (the Java clamp above), so `max - actual`
                // cannot underflow and `settled + actual` is only evaluated
                // when it fits.
                + ", settled = CASE WHEN spend.settled > " + Long.MAX_VALUE + " - EXCLUDED.settled THEN "
                + Long.MAX_VALUE
                + " ELSE spend.settled + EXCLUDED.settled END";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, keyId);
            ps.setLong(2, reservationWindowStart);
            ps.setLong(3, actual);
            ps.setLong(4, estimateMicroUsd);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres spend ledger settle failed", e);
        }
    }

    void release(String keyId, long estimateMicroUsd, long reservationWindowStart) {
        Objects.requireNonNull(keyId, "keyId");
        requireNonNegative(estimateMicroUsd, "estimateMicroUsd");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE spend SET pending = GREATEST(pending - ?, 0) WHERE key_id = ? AND window_start = ?")) {
            ps.setLong(1, estimateMicroUsd);
            ps.setString(2, keyId);
            ps.setLong(3, reservationWindowStart);
            // Clamped at 0, no-op for unknown keys (0 rows): the release semantics.
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres spend ledger release failed", e);
        }
    }

    void recordSpend(String keyId, long amountMicroUsd) {
        Objects.requireNonNull(keyId, "keyId");
        requireNonNegative(amountMicroUsd, "amountMicroUsd");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Per-key serialization (mirrors the in-memory per-key compute): racing
                // recordSpend+prune pairs would otherwise both delete the same overflow
                // row. The lock is held until commit. A 64-bit key
                // (hashtextextended — not the 32-bit hashtext cast to bigint) so two
                // distinct keys cannot collide into one lock, and a key id can
                // practically never collide with the migration runner's fixed lock key.
                try (PreparedStatement lock =
                        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    lock.setString(1, keyId);
                    lock.executeQuery();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO spend_entries (key_id, at_epoch_millis, micro_usd) VALUES (?, ?, ?)")) {
                    insert.setString(1, keyId);
                    insert.setLong(2, clock.millis());
                    insert.setLong(3, amountMicroUsd);
                    insert.executeUpdate();
                }
                try (PreparedStatement prune =
                        connection.prepareStatement("DELETE FROM spend_entries WHERE key_id = ? AND seq NOT IN"
                                + " (SELECT seq FROM spend_entries WHERE key_id = ? ORDER BY seq DESC LIMIT ?)")) {
                    prune.setString(1, keyId);
                    prune.setString(2, keyId);
                    prune.setInt(3, retention);
                    prune.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres spend ledger recordSpend failed", e);
        }
    }

    /**
     * Drop the key's spend rows more than {@link #PRUNE_KEEP_WINDOWS} windows behind
     * the current one, <b>folding each pruned row's settled into the key's window-0
     * row</b> (the all-time accumulator) inside the same atomic statement — the sum
     * {@link #totalSpendByKey} reads never decreases across a prune (the Postgres
     * analogue of the in-memory per-key all-time scalar). Lifetime keys have a single
     * window-0 row, which the {@code window_start > 0} candidate guard never touches;
     * a no-op prune folds nothing ({@code WHERE total > 0} inserts no accumulator
     * row). Package-private for the tests (deterministic prune assertions); the
     * sampled {@link #maybePruneStaleWindows} janitor calls it on the reserve path.
     * Housekeeping only: a prune failure is logged and dropped — it never denies the
     * request whose reserve already succeeded.
     */
    void pruneStaleWindows(String keyId, long windowStart, long windowSeconds) {
        if (windowSeconds <= 0) {
            return; // lifetime key: the single window-0 row is never pruned
        }
        long cutoff = windowStart - (long) PRUNE_KEEP_WINDOWS * windowSeconds;
        // One atomic CTE: DELETE the stale windowed rows, sum what they held, fold the
        // sum into window 0. The DELETE's row locks serialize against a concurrent
        // straddled settle on those rows — and because settle is itself one atomic
        // self-registering upsert, a row the prune deleted first is re-created with
        // settled = actual, so the all-time sum stays exact on every interleaving.
        String sql = "WITH pruned AS ("
                + " DELETE FROM spend WHERE key_id = ? AND window_start > 0 AND window_start < ? RETURNING settled"
                + "), folded AS (SELECT COALESCE(sum(settled), 0) AS total FROM pruned)"
                + " INSERT INTO spend (key_id, window_start, settled, pending)"
                + " SELECT ?, 0, total, 0 FROM folded WHERE total > 0"
                + " ON CONFLICT (key_id, window_start) DO UPDATE SET settled = spend.settled + EXCLUDED.settled";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, keyId);
            ps.setLong(2, cutoff);
            ps.setString(3, keyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(
                    Level.DEBUG,
                    "spend window prune failed (rows stay until the next sampled prune): {0}",
                    e.toString());
        }
    }

    private void maybePruneStaleWindows(String keyId, long windowStart, long windowSeconds) {
        if (reserves.incrementAndGet() % PRUNE_INTERVAL == 0) {
            pruneStaleWindows(keyId, windowStart, windowSeconds);
        }
    }

    /** The window start for {@code windowSeconds} (0 ⇒ the single lifetime window, epoch 0). */
    private long windowStart(long windowSeconds) {
        if (windowSeconds <= 0) {
            return 0;
        }
        long nowSeconds = clock.millis() / 1000;
        return Math.floorDiv(nowSeconds, windowSeconds) * windowSeconds;
    }

    /**
     * The key's current-window (settled, pending) totals, one snapshot (the
     * denied-reservation payload); 0/0 when absent.
     */
    private static SpendLedger.ReserveResult.Denied readTotals(Connection connection, String keyId, long windowStart)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT settled, pending FROM spend WHERE key_id = ? AND window_start = ?")) {
            ps.setString(1, keyId);
            ps.setLong(2, windowStart);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new SpendLedger.ReserveResult.Denied(rs.getLong(1), rs.getLong(2));
                }
                return new SpendLedger.ReserveResult.Denied(0, 0);
            }
        }
    }

    /** Package-private observability seam (the ledger test suites assert pending). */
    long pending(String keyId) {
        Objects.requireNonNull(keyId, "keyId");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT pending FROM spend WHERE key_id = ? ORDER BY window_start DESC LIMIT 1")) {
            ps.setString(1, keyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres spend ledger pending failed", e);
        }
    }

    /** Self-registering keys (the contract): create the window row on first use, never clobber. */
    private void selfRegister(String keyId, long windowStart) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("INSERT INTO spend (key_id, window_start) VALUES (?, ?)"
                                + " ON CONFLICT (key_id, window_start) DO NOTHING")) {
            ps.setString(1, keyId);
            ps.setLong(2, windowStart);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres spend ledger self-register failed", e);
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative (got " + value + ")");
        }
    }
}
