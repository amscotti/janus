package io.amscotti.janus.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Postgres {@link CallStore}: the whole 19-method union (6 {@code KeyStore}
 * + 3 {@code RateLimiter} + 6 {@code SpendLedger} + 4 call-ledger) implemented over
 * <b>one</b> {@link javax.sql.DataSource} (HikariCP pool) + the injected {@link Clock}
 * + the call-ring and spend-ledger-ring retentions (two independent knobs — see the
 * constructors) — the "JDBC store passes the same
 * unit suite as in-memory" parity target ({@code PostgresCallStoreTest extends
 * AbstractCallStoreContractTest}, no re-authored assertions).
 *
 * <p><b>One public seam, decomposed implementation</b> (the risk "flat extends
 * seam size" mitigation): internally four package-private JDBC pieces share the pool
 * and a single boot-time migration — {@link PgKeyStore}, {@link PgRateLimiter},
 * {@link PgSpendLedger}, {@link PgCallLedger} — so the gateway factory never imports
 * HikariCP and janus-store stays Micronaut-free ({@code javax.sql}, {@code java.sql},
 * {@code java.time}, {@code java.util.concurrent} — the module boundary rule is about
 * module imports; HikariCP/Postgres are third-party).
 *
 * <p><b>Boot = migrate.</b> The constructor builds the pool (HikariCP
 * {@code initializationFailTimeout = 1} — an unreachable database refuses the node to
 * start at construction, never on first request; the fail-fast decision the gate's
 * drill verifies) and runs {@link SchemaMigration} (no Flyway): the
 * {@code db/migration/V1__init.sql} DDL is applied in one transaction and version-
 * tracked, so a fresh database gets the full schema and an existing one is a no-op.
 *
 * <p><b>Fail fast on unreachable config.</b> A {@link PostgresStoreConfig} with a
 * blank URL or non-positive pool size is rejected at construction; a pool that cannot
 * connect throws {@code PoolInitializationException} from HikariCP at construction.
 * The gateway factory wraps that failure with an error naming the <b>env var</b>,
 * never the URL (credentials may be embedded — see the risk section).
 *
 * <p>Thread-safe (the pool is; the per-key advisory locks serialize the ring/prune
 * writers exactly like the in-memory per-key computes). Implements
 * {@link AutoCloseable} so tests and lifecycle owners can release the pool.
 */
public final class PostgresCallStore implements CallStore, AutoCloseable {

    private final PgKeyStore keys;
    private final PgRateLimiter rateLimiter;
    private final PgSpendLedger ledger;
    private final PgCallLedger callLedger;
    private final HikariDataSource dataSource;

    /**
     * Convenience: one retention sizes both rings — the spend-ledger ring shares the
     * call ring's retention.
     *
     * @param config the JDBC URL + pool knobs (see {@link PostgresStoreConfig})
     * @param clock the single store clock (clock discipline — the gateway factory passes
     * the same {@link Clock} bean the in-memory branch uses)
     * @param retention the per-key call-ring <b>and</b> spend-ledger ring retention
     * (must be &gt; 0)
     */
    public PostgresCallStore(PostgresStoreConfig config, Clock clock, int retention) {
        this(config, clock, retention, retention);
    }

    /**
     * Full constructor: the call-ring retention and the spend-ledger ring retention are
     * independent knobs — {@code [janus.store] retention} sizes the {@code calls} ring
     * while {@code [janus.limits] ledger-retention} sizes the {@code spend_entries}
     * ring, so the factory wires each from its own config key.
     *
     * @param config the JDBC URL + pool knobs (see {@link PostgresStoreConfig})
     * @param clock the single store clock (clock discipline — the gateway factory passes
     * the same {@link Clock} bean the in-memory branch uses)
     * @param retention the per-key call-ring retention (must be &gt; 0)
     * @param ledgerRetention the per-key spend-ledger ring retention (must be &gt; 0;
     * the {@code recent} spend ring, distinct from the call ring)
     */
    public PostgresCallStore(PostgresStoreConfig config, Clock clock, int retention, int ledgerRetention) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        if (retention <= 0) {
            throw new IllegalArgumentException("retention must be positive (got " + retention + ")");
        }
        // Before the pool is built: PgSpendLedger would reject it later, but only
        // after the Hikari pool exists — this boot-failure path must not leak the pool.
        if (ledgerRetention <= 0) {
            throw new IllegalArgumentException("ledgerRetention must be positive (got " + ledgerRetention + ")");
        }
        this.dataSource = new HikariDataSource(poolConfig(config));
        try {
            SchemaMigration.migrate(dataSource, clock);
        } catch (RuntimeException e) {
            // A failed migration must not leak the pool (constructor failure path).
            dataSource.close();
            throw e;
        }
        this.keys = new PgKeyStore(dataSource, clock);
        this.rateLimiter = new PgRateLimiter(dataSource, clock);
        this.ledger = new PgSpendLedger(dataSource, clock, ledgerRetention);
        this.callLedger = new PgCallLedger(dataSource, retention);
    }

    /** The Hikari pool: URL + optional credentials + pool size, fail-fast at construction. */
    private static HikariConfig poolConfig(PostgresStoreConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        if (config.username() != null) {
            hikari.setUsername(config.username());
        }
        if (config.password() != null) {
            hikari.setPassword(config.password());
        }
        hikari.setMaximumPoolSize(config.maxPoolSize());
        // boot decision (recorded for the gate's drill): fail fast — a node silently
        // falling back to memory in a multi-node deployment would violate
        // read-your-writes. 1 = attempt one connection at construction, fail on error.
        hikari.setInitializationFailTimeout(1);
        // Review 2: the pool's default connectionTimeout (30s) exceeds the
        // drill's no-hang bound — a mid-run DB death must surface as a clean 5xx in
        // ~2s, not a 30s stall. Idle pools never wait (connections are reused), so
        // the shorter bound only affects the failure path.
        hikari.setConnectionTimeout(2000);
        return hikari;
    }

    /** Release the pool (tests and lifecycle owners; idempotent). */
    @Override
    public void close() {
        dataSource.close();
    }

    // --- KeyStore (the semantics, re-implemented in SQL — see PgKeyStore) -------

    @Override
    public CreatedKey create(KeyCreateRequest request) {
        return keys.create(request);
    }

    @Override
    public Optional<KeyRecord> findByPrefix(String prefix) {
        return keys.findByPrefix(prefix);
    }

    @Override
    public boolean revoke(String id) {
        return keys.revoke(id);
    }

    @Override
    public AuthResult authenticate(String prefix, String secret) {
        return keys.authenticate(prefix, secret);
    }

    @Override
    public List<KeyRecordView> list() {
        return keys.list();
    }

    @Override
    public void touch(String prefix) {
        keys.touch(prefix);
    }

    // --- RateLimiter (fixed-window only — see PgRateLimiter) ------------------------

    @Override
    public RateLimitResult tryAcquire(String keyId, int limit, long cost) {
        return rateLimiter.tryAcquire(keyId, limit, cost);
    }

    @Override
    public boolean wouldExceed(String keyId, int limit, long estimate) {
        return rateLimiter.wouldExceed(keyId, limit, estimate);
    }

    @Override
    public long accumulate(String keyId, int limit, long actual) {
        return rateLimiter.accumulate(keyId, limit, actual);
    }

    // --- SpendLedger (the semantics, re-implemented in SQL — see PgSpendLedger) -

    @Override
    public long spendByKey(String keyId, long windowSeconds) {
        return ledger.spendByKey(keyId, windowSeconds);
    }

    @Override
    public long totalSpendByKey(String keyId) {
        return ledger.totalSpendByKey(keyId);
    }

    @Override
    public List<LedgerEntry> recent(String keyId, int n) {
        return ledger.recent(keyId, n);
    }

    @Override
    public ReserveResult reserve(
            String keyId, long estimateMicroUsd, long hardCapMicroUsd, double softFraction, long windowSeconds) {
        return ledger.reserve(keyId, estimateMicroUsd, hardCapMicroUsd, softFraction, windowSeconds);
    }

    @Override
    public void settle(String keyId, long estimateMicroUsd, long actualMicroUsd, long reservationWindowStart) {
        ledger.settle(keyId, estimateMicroUsd, actualMicroUsd, reservationWindowStart);
    }

    @Override
    public void release(String keyId, long estimateMicroUsd, long reservationWindowStart) {
        ledger.release(keyId, estimateMicroUsd, reservationWindowStart);
    }

    @Override
    public void recordSpend(String keyId, long amountMicroUsd) {
        ledger.recordSpend(keyId, amountMicroUsd);
    }

    // --- Call ledger (see PgCallLedger) ---------------------------------------

    @Override
    public void recordCall(CallRecord record) {
        callLedger.recordCall(record);
    }

    @Override
    public List<CallRecord> recentCalls(String keyId, int n) {
        return callLedger.recentCalls(keyId, n);
    }

    @Override
    public List<CallRecord> recentCalls(int n) {
        return callLedger.recentCalls(n);
    }

    @Override
    public long dropped() {
        return callLedger.dropped();
    }
}
