package io.amscotti.janus.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The call-ledger half of {@link PostgresCallStore} (the four
 * {@code recordCall}/{@code recentCalls}/{@code dropped} operations over the
 * {@code calls}/{@code store_meta} tables): the per-key bounded ring with
 * <b>synchronous exact eviction</b> and the global newest-first view, the
 * {@code ""} auth-off sentinel, and the monotonic global {@code dropped} counter.
 *
 * <p><b>Ring retention — the parity contract's hardest part.</b> The harness pins
 * synchronous eviction with exact drop counting: after r+1 records with retention r,
 * {@code recentCalls(key, 100)} returns exactly r rows and {@code dropped} == 1;
 * the concurrency smoke pins {@code dropped == total − retention} <b>exactly</b>
 * under 8 threads × 25 records (all with the <b>same millisecond</b> timestamp —
 * ordering must not depend on {@code at_epoch_millis} alone). Design:
 *
 * <ul>
 * <li><b>Ordering by {@code (at_epoch_millis, seq)}</b> — {@code seq BIGSERIAL}
 * insert order is the stable tie-break (the contract: "same-timestamp ties in
 * an unspecified but stable order").
 * <li><b>One transaction per {@code recordCall}</b> under
 * {@code pg_advisory_xact_lock(hashtextextended(key_id, 0))}: INSERT the record →
 * prune ({@code DELETE... WHERE key_id = ? AND seq NOT IN (SELECT seq... ORDER BY
 * at_epoch_millis DESC, seq DESC LIMIT ?)}) → add the DELETE's affected-row
 * count to {@code store_meta.dropped}. Per-key serialization makes each
 * overflow evict <b>exactly once</b> under concurrency (two racing prunes
 * would otherwise both delete the same overflow row and double-count) —
 * mirrors the in-memory ring's per-key {@code compute} atomicity; hot-key
 * serialization is a documented, accepted cost. The lock key is the key id's
 * 64-bit {@code hashtextextended} (not the 32-bit {@code hashtext} cast to
 * bigint, which would let two distinct keys collide onto one lock).
 * <li><b>Sentinel string storage.</b> A null {@code keyId} (auth-off) is stored as
 * the {@code ""} sentinel, so {@code recentCalls((String) null, n)} ≡
 * {@code recentCalls("", n)} — the contract's pinned equivalence.
 * </ul>
 *
 * <p><b>Hot-key pool-exhaustion (documented cost).</b> The advisory lock is
 * held until commit, so a burst of concurrent writers on one hot key with
 * concurrency ≥ {@code max-pool-size} holds every pooled connection blocked on the
 * lock; a concurrent op on an unrelated key then waits on pool checkout and, past
 * {@code connectionTimeout} (2s), surfaces a clean 5xx. This is the availability
 * side of the accepted hot-key serialization — see {@code docs/clustering.md} for
 * pool-sizing guidance on write-heavy keys. Not a correctness bug.
 *
 * <p><b>Append-only records.</b> {@code recordCall} always appends a new {@code seq}
 * row (the writer calls once per request; whether a re-record of the same request id
 * overwrites or appends is documented here as the decision: append — the ring
 * contract is about per-key retention, not idempotency of request ids).
 */
final class PgCallLedger {

    /** The store_meta key holding the global dropped() counter. */
    private static final String META_DROPPED = "dropped";

    private static final String CALL_COLUMNS = "request_id, key_id, model, provider, prompt_tokens,"
            + " completion_tokens, total_tokens, cache_creation_tokens, cache_read_tokens, cost_micro_usd,"
            + " duration_millis, stream, status, at_epoch_millis";

    private final DataSource dataSource;
    private final int retention;

    PgCallLedger(DataSource dataSource, int retention) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (retention <= 0) {
            throw new IllegalArgumentException("retention must be positive (got " + retention + ")");
        }
        this.retention = retention;
    }

    void recordCall(CallRecord record) {
        Objects.requireNonNull(record, "record");
        String ringKey = record.keyId() == null ? "" : record.keyId();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Per-key serialization: each overflow evicts exactly once (see javadoc).
                // 64-bit lock key (hashtextextended, never the 32-bit hashtext
                // cast to bigint) so distinct keys cannot collide onto one lock.
                try (PreparedStatement lock =
                        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    lock.setString(1, ringKey);
                    lock.executeQuery();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO calls (" + CALL_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, record.requestId());
                    insert.setString(2, ringKey);
                    insert.setString(3, record.model());
                    insert.setString(4, record.provider());
                    insert.setLong(5, record.promptTokens());
                    insert.setLong(6, record.completionTokens());
                    insert.setLong(7, record.totalTokens());
                    setNullableLong(insert, 8, record.cacheCreationInputTokens());
                    setNullableLong(insert, 9, record.cacheReadInputTokens());
                    insert.setLong(10, record.costMicroUsd());
                    insert.setLong(11, record.durationMillis());
                    insert.setBoolean(12, record.stream());
                    insert.setString(13, record.status().name());
                    insert.setLong(14, record.atEpochMillis());
                    insert.executeUpdate();
                }
                int evicted;
                try (PreparedStatement prune =
                        connection.prepareStatement("DELETE FROM calls WHERE key_id = ? AND seq NOT IN"
                                + " (SELECT seq FROM calls WHERE key_id = ?"
                                + " ORDER BY at_epoch_millis DESC, seq DESC LIMIT ?)")) {
                    prune.setString(1, ringKey);
                    prune.setString(2, ringKey);
                    prune.setInt(3, retention);
                    evicted = prune.executeUpdate();
                }
                if (evicted > 0) {
                    try (PreparedStatement counter =
                            connection.prepareStatement("INSERT INTO store_meta (key, value) VALUES (?, ?)"
                                    + " ON CONFLICT (key) DO UPDATE SET value = store_meta.value + EXCLUDED.value")) {
                        counter.setString(1, META_DROPPED);
                        counter.setLong(2, evicted);
                        counter.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres call ledger recordCall failed", e);
        }
    }

    List<CallRecord> recentCalls(String keyId, int n) {
        if (n <= 0) {
            return List.of();
        }
        String ringKey = keyId == null ? "" : keyId;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT seq, " + CALL_COLUMNS
                        + " FROM calls WHERE key_id = ?" + " ORDER BY at_epoch_millis DESC, seq DESC LIMIT ?")) {
            ps.setString(1, ringKey);
            ps.setInt(2, n);
            return readRecords(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres call ledger recentCalls failed", e);
        }
    }

    List<CallRecord> recentCalls(int n) {
        if (n <= 0) {
            return List.of();
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT seq, " + CALL_COLUMNS
                        + " FROM calls ORDER BY at_epoch_millis DESC, seq DESC LIMIT ?")) {
            ps.setInt(1, n);
            return readRecords(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres call ledger global recentCalls failed", e);
        }
    }

    long dropped() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("SELECT value FROM store_meta WHERE key = ?")) {
            ps.setString(1, META_DROPPED);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres call ledger dropped failed", e);
        }
    }

    private static List<CallRecord> readRecords(PreparedStatement ps) throws SQLException {
        List<CallRecord> records = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                // Read the cell once: the sentinel maps to null; a NULL cell
                // (unreachable today — V1 declares key_id NOT NULL) must map to null too,
                // never an NPE.
                String keyId = rs.getString("key_id");
                records.add(new CallRecord(
                        rs.getString("request_id"),
                        keyId == null || keyId.isEmpty() ? null : keyId,
                        rs.getString("model"),
                        rs.getString("provider"),
                        rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"),
                        rs.getLong("total_tokens"),
                        rs.getObject("cache_creation_tokens", Long.class),
                        rs.getObject("cache_read_tokens", Long.class),
                        rs.getLong("cost_micro_usd"),
                        rs.getLong("duration_millis"),
                        rs.getBoolean("stream"),
                        callStatusOf(rs.getString("status")),
                        rs.getLong("at_epoch_millis")));
            }
        }
        return List.copyOf(records);
    }

    /**
     * {@code status} cell → {@link CallStatus}, mapping any unexpected stored value to the
     * store's {@link IllegalStateException} seam: a manual DB edit or a downgrade
     * after a future enum value was written must surface like every other store failure,
     * not as a bare {@link IllegalArgumentException} the gateway's error mapper does not
     * expect.
     */
    private static CallStatus callStatusOf(String value) {
        for (CallStatus status : CallStatus.values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalStateException("calls row has an unknown status value");
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }
}
