package io.amscotti.janus.store;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * The Postgres {@link KeyStore} half of {@link PostgresCallStore} — the
 * {@link InMemoryKeyStore} semantics re-implemented in JDBC — the {@code
 * InMemoryKeyStoreTest} suite is the spec, mirrored by {@code PgKeyStoreTest}):
 * prefix-indexed rows, salt + salted SHA-256 hash only (the secret is never
 * stored), timing-safe verify via the shipped {@link KeyHash}, idempotent revoke
 * (PostgreSQL {@code UPDATE} returns <b>matched</b> rows, so re-revoking an
 * existing id stays {@code true}), no-regress {@code touch}, secret-free
 * {@code list} (the view row omits salt/hash — structural redaction) and a
 * prefix-collision retry on {@code create} (unique index; fresh material per
 * attempt, matching {@code InMemoryKeyStore}'s putIfAbsent+retry loop).
 *
 * <p><b>Revoke wins over a racing auth.</b> {@link #authenticate} reads the record,
 * verifies the secret, then bumps {@code lastUsedAt} with
 * {@code UPDATE... WHERE prefix = ? AND status = 'ACTIVE'} — the status re-check
 * inside the UPDATE means a revoke that lands between the read and the bump wins
 * (the bump is a no-op), while a request that already passed the check completes
 * as {@code OK} (the last one through — no torn state, the contract). The read
 * and the bump are <em>not</em> a single atomic step in Postgres, so when the bump
 * matches 0 rows the auth <em>re-reads</em> the row and re-classifies: a revoke
 * that committed before the auth's own write is reported {@code REVOKED}, never
 * {@code OK} with the stale {@code ACTIVE} snapshot (the in-memory store's
 * single-{@code compute} transition is the atomic reference for this).
 *
 * <p>Every method maps a {@link SQLException} to {@link IllegalStateException}
 * (the seam methods declare no checked exceptions; a DB failure surfaces as a 5xx
 * via the gateway's exception handler — HikariCP throws on checkout for a lost
 * database, the documented mid-run behavior).
 */
final class PgKeyStore {

    private static final HexFormat HEX = HexFormat.of();

    /** All key columns except salt/hash — the secret-free {@code list()} projection. */
    private static final String VIEW_COLUMNS = "id, prefix, owner, models, status, created_at, expires_at,"
            + " last_used_at, budget_usd, budget_duration, rpm, tpm";

    private static final String FULL_COLUMNS = "id, prefix, salt, secret_hash, owner, models, status, created_at,"
            + " expires_at, last_used_at, budget_usd, budget_duration, rpm, tpm";

    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;
    private final Clock clock;
    private final Supplier<KeyGenerator.Generated> material;

    PgKeyStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, KeyGenerator::generate);
    }

    /**
     * Test seam (package-private): a {@code material} supplier replaces
     * {@link KeyGenerator#generate} so the prefix-collision retry loop can be forced
     * deterministically (a real 62^8 collision is statistically impossible to hit).
     */
    PgKeyStore(DataSource dataSource, Clock clock, Supplier<KeyGenerator.Generated> material) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.material = Objects.requireNonNull(material, "material");
    }

    KeyStore.CreatedKey create(KeyStore.KeyCreateRequest request) {
        Objects.requireNonNull(request, "request");
        KeyStore.validateCaps(request);
        long now = clock.millis();
        // Prefix-collision retry (unique index): fresh material regenerated per
        // attempt — a collision retry also gets a fresh id (InMemoryKeyStore loop).
        for (; ; ) {
            KeyGenerator.Generated generated = material.get();
            KeyRecord record = new KeyRecord(
                    generateId(),
                    generated.prefix(),
                    generated.salt(),
                    generated.secretHash(),
                    request.owner(),
                    request.models(),
                    KeyStatus.ACTIVE,
                    now,
                    request.expiresAt(),
                    null,
                    request.budgetUsd(),
                    request.budgetDuration(),
                    request.rpm(),
                    request.tpm());
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO keys (id, prefix, salt, secret_hash, owner, models, status, created_at,"
                                    + " expires_at, last_used_at, budget_usd, budget_duration, rpm, tpm)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, NULL, ?, ?, ?, ?)")) {
                ps.setString(1, record.id());
                ps.setString(2, record.prefix());
                ps.setBytes(3, record.salt());
                ps.setBytes(4, record.secretHash());
                ps.setString(5, record.owner());
                ps.setArray(6, connection.createArrayOf("text", record.models().toArray(String[]::new)));
                ps.setLong(7, record.createdAt());
                if (record.expiresAt() == null) {
                    ps.setNull(8, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(8, record.expiresAt());
                }
                if (record.budgetUsd() == null) {
                    ps.setNull(9, java.sql.Types.DOUBLE);
                } else {
                    ps.setDouble(9, record.budgetUsd());
                }
                if (record.budgetDuration() == null) {
                    ps.setNull(10, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(10, record.budgetDuration());
                }
                if (record.rpm() == null) {
                    ps.setNull(11, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(11, record.rpm());
                }
                if (record.tpm() == null) {
                    ps.setNull(12, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(12, record.tpm());
                }
                ps.executeUpdate();
                return new KeyStore.CreatedKey(record, generated.fullKey());
            } catch (SQLException e) {
                if (SQLSTATE_UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    continue; // prefix collision: regenerate (statistically impossible to repeat)
                }
                throw new IllegalStateException("Postgres key store create failed", e);
            }
        }
    }

    Optional<KeyRecord> findByPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT " + FULL_COLUMNS + " FROM keys WHERE prefix = ?")) {
            ps.setString(1, prefix);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRecord(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres key store findByPrefix failed", e);
        }
    }

    boolean revoke(String id) {
        Objects.requireNonNull(id, "id");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("UPDATE keys SET status = 'REVOKED' WHERE id = ?")) {
            ps.setString(1, id);
            // PostgreSQL UPDATE returns MATCHED rows: true for an existing id on every
            // call (idempotent-true), false only for unknown ids (the contract).
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres key store revoke failed", e);
        }
    }

    KeyStore.AuthResult authenticate(String prefix, String secret) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(secret, "secret");
        // The store clock governs expiry (the discipline; never a caller-supplied
        // "now", so a mis-wired caller cannot silently disable the expiresAt check).
        long now = clock.millis();
        Optional<KeyRecord> found = findByPrefix(prefix);
        if (found.isEmpty()) {
            return new KeyStore.AuthResult(KeyStore.AuthOutcome.INVALID, null);
        }
        KeyRecord record = found.get();
        if (!KeyHash.verify(record.salt(), record.secretHash(), secret)) {
            // A wrong secret must not return the credential-bearing record (salt/
            // secretHash) to a caller that is not authenticated — same shape as INVALID
            // for an unknown prefix (which also carries no record).
            return new KeyStore.AuthResult(KeyStore.AuthOutcome.INVALID, null);
        }
        if (record.status() == KeyStatus.REVOKED) {
            return new KeyStore.AuthResult(KeyStore.AuthOutcome.REVOKED, record);
        }
        if (!record.isActive(now)) {
            return new KeyStore.AuthResult(KeyStore.AuthOutcome.EXPIRED, record);
        }
        // Atomic lastUsedAt bump with the status re-check inside the UPDATE's WHERE:
        // a racing revoke either lands before (⇒ REVOKED above) or its UPDATE flips
        // the row so this bump matches 0 rows — the request that already passed the
        // check is the last one through (the "no torn state" contract). The
        // `last_used_at < now` guard keeps the bump monotonic (never regresses).
        // RETURNING last_used_at gives the value actually written (the bump
        // sets it to `now`), so the returned record can never lag the row even under a
        // concurrent clock-advanced bump.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE keys SET last_used_at = ? WHERE prefix = ? AND status = 'ACTIVE'"
                                + " AND (last_used_at IS NULL OR last_used_at < ?) RETURNING last_used_at")) {
            ps.setLong(1, now);
            ps.setString(2, prefix);
            ps.setLong(3, now);
            try (ResultSet rs = ps.executeQuery()) {
                // RETURNING row ⇒ the bump matched: the DB's last_used_at is now exactly
                // the returned value. Deriving the returned record from the row (not the
                // pre-bump snapshot) keeps it exact under every interleaving.
                if (rs.next()) {
                    long written = rs.getLong(1);
                    KeyRecord updated = (record.lastUsedAt() != null && record.lastUsedAt() >= written)
                            ? record
                            : record.withLastUsedAt(written);
                    return new KeyStore.AuthResult(KeyStore.AuthOutcome.OK, updated);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres key store authenticate failed", e);
        }
        // The read and the write are two separate statements, so a revoke
        // can commit *between* them — the bump matches 0 rows while the read snapshot
        // was still ACTIVE. Re-classify on a fresh read: a revoke that landed before the
        // auth's own write must surface as REVOKED (revoke-first ⇒ REVOKED), never as OK
        // with the stale ACTIVE snapshot. The only remaining OK-with-0-rows cases are a
        // same-instant concurrent touch or the last_used_at < now guard on an ACTIVE row.
        Optional<KeyRecord> current = findByPrefix(prefix);
        if (current.isEmpty()) {
            return new KeyStore.AuthResult(KeyStore.AuthOutcome.INVALID, null);
        }
        KeyRecord fresh = current.get();
        if (fresh.status() == KeyStatus.REVOKED) {
            return new KeyStore.AuthResult(KeyStore.AuthOutcome.REVOKED, fresh);
        }
        if (!fresh.isActive(now)) {
            return new KeyStore.AuthResult(KeyStore.AuthOutcome.EXPIRED, fresh);
        }
        KeyRecord updated =
                (fresh.lastUsedAt() != null && fresh.lastUsedAt() >= now) ? fresh : fresh.withLastUsedAt(now);
        return new KeyStore.AuthResult(KeyStore.AuthOutcome.OK, updated);
    }

    List<KeyRecordView> list() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT " + VIEW_COLUMNS + " FROM keys ORDER BY created_at, id")) {
            List<KeyRecordView> views = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    views.add(new KeyRecordView(
                            rs.getString("id"),
                            rs.getString("prefix"),
                            rs.getString("owner"),
                            modelsOf(rs),
                            keyStatusOf(rs.getString("status")),
                            rs.getLong("created_at"),
                            rs.getObject("expires_at", Long.class),
                            rs.getObject("last_used_at", Long.class),
                            rs.getObject("budget_usd", Double.class),
                            rs.getObject("budget_duration", Long.class),
                            rs.getObject("rpm", Integer.class),
                            rs.getObject("tpm", Integer.class)));
                }
            }
            return List.copyOf(views);
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres key store list failed", e);
        }
    }

    void touch(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        // Capture `now` once — the value written and the `last_used_at < ?`
        // guard must be the same instant (authenticate does the same), so a clock that
        // returns a different value per call cannot admit a write the guard would not
        // (a last_used_at regression).
        long now = clock.millis();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE keys SET last_used_at = ? WHERE prefix = ? AND status = 'ACTIVE'"
                                + " AND (last_used_at IS NULL OR last_used_at < ?)")) {
            ps.setLong(1, now);
            ps.setString(2, prefix);
            ps.setLong(3, now);
            // No-op for unknown prefixes and non-ACTIVE records (0 rows matched): a
            // revoked record's lastUsedAt must never be bumped (m1 — status guard).
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres key store touch failed", e);
        }
    }

    private static KeyRecord mapRecord(ResultSet rs) throws SQLException {
        return new KeyRecord(
                rs.getString("id"),
                rs.getString("prefix"),
                rs.getBytes("salt"),
                rs.getBytes("secret_hash"),
                rs.getString("owner"),
                modelsOf(rs),
                keyStatusOf(rs.getString("status")),
                rs.getLong("created_at"),
                rs.getObject("expires_at", Long.class),
                rs.getObject("last_used_at", Long.class),
                rs.getObject("budget_usd", Double.class),
                rs.getObject("budget_duration", Long.class),
                rs.getObject("rpm", Integer.class),
                rs.getObject("tpm", Integer.class));
    }

    /** The {@code text[]} models column → {@code List<String>} (never null; empty = allow all). */
    private static List<String> modelsOf(ResultSet rs) throws SQLException {
        Array array = rs.getArray("models");
        if (array == null) {
            return List.of();
        }
        String[] models = (String[]) array.getArray();
        return List.of(models);
    }

    /**
     * {@code status} cell → {@link KeyStatus}, mapping any unexpected stored value to the
     * store's {@link IllegalStateException} seam: a manual DB edit or a downgrade
     * after a future enum value was written must surface like every other store failure,
     * not as a bare {@link IllegalArgumentException} the gateway's error mapper does not
     * expect. The offending value is not echoed (secrets discipline — it never needs to be).
     */
    private static KeyStatus keyStatusOf(String value) {
        for (KeyStatus status : KeyStatus.values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalStateException("keys row has an unknown status value");
    }

    private static String generateId() {
        byte[] bytes = new byte[16];
        KeyGenerator.RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }
}
