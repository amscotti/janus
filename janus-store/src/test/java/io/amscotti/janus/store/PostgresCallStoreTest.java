package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The parity milestone: {@link PostgresCallStore} runs the
 * <b>entire</b> {@link AbstractCallStoreContractTest} suite unchanged against a real
 * Postgres (Testcontainers, {@code postgres:16-alpine}) — the "JDBC store passes the
 * same unit suite as in-memory (interface parity test)" harness, no re-authored
 * assertions. {@code ringRetention = 2} pins eviction exactly like
 * {@link InMemoryCallStoreTest}; both concurrency smokes (exact {@code dropped};
 * exactly-3-of-8 concurrent reserves) run against the database. Docker-less machines
 * skip (not fail) via {@code @Testcontainers(disabledWithoutDocker = true)}; CI
 * (Docker preinstalled) exercises it for real.
 *
 * <p><b>Fresh data per test.</b> The contract's {@code setUp} builds a fresh store
 * over the shared database; the subclass truncates all tables afterwards (the
 * schema itself survives — the store's boot migration is idempotent and
 * version-tracked). The {@code @AfterEach} closes the store-under-test's pool and
 * the truncation pool so connections never leak (the container's default
 * {@code max_connections} is 100).
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresCallStoreTest extends AbstractCallStoreContractTest {

    private DataSource truncateDataSource;
    private PostgresCallStore lastStore;

    @BeforeAll
    static void startDatabase() {
        PgTestDb.ensureStarted();
        PgTestDb.migrate();
    }

    @Override
    protected CallStore newStore(MutableClock clock) {
        lastStore = new PostgresCallStore(PgTestDb.config(), clock, ringRetention());
        return lastStore;
    }

    @Override
    protected int ringRetention() {
        return 2;
    }

    @Test
    void ledgerRetentionIsIndependentOfTheCallRingRetention() {
        // Parity with InMemoryCallStore's full constructor: [janus.store] retention
        // sizes the calls ring, [janus.limits] ledger-retention the spend_entries
        // ring (PgSpendLedger's prune). Pin that each knob drives only its own ring.
        MutableClock clock = new MutableClock(START);
        try (PostgresCallStore store = new PostgresCallStore(PgTestDb.config(), clock, 5, 1)) {
            store.recordSpend("k", 10);
            store.recordSpend("k", 20);
            store.recordSpend("k", 30);
            assertEquals(1, store.recent("k", 10).size(), "the spend ring is pruned to the LEDGER retention (1)");
            assertEquals(30, store.recent("k", 10).get(0).microUsd(), "newest first");

            for (int i = 1; i <= 3; i++) {
                store.recordCall(record("r" + i, "k", clock.millis() + i));
            }
            assertEquals(
                    3,
                    store.recentCalls("k", 10).size(),
                    "the calls ring is bounded by the CALL retention (5), unaffected by the ledger knob");
            assertEquals(0, store.dropped(), "no calls-ring overflow at 3 of 5");
        }
    }

    @Test
    void v2MigrationSurvivesTheTruncateReMigrateCycleAndReshapesTheSpendPk() throws Exception {
        // The V2-idempotency pin: PgTestDb.truncateAll wipes schema_migrations, so
        // EVERY store construction re-applies V1+V2 against an already-migrated
        // schema — the idempotent DDL (the spend PK swap's DROP CONSTRAINT IF EXISTS →
        // ADD pair included) must re-run harmlessly (this test's own setUp + newStore
        // already did one such cycle; one more explicit boot here pins it). The
        // schema shapes are asserted straight from information_schema: spend's PK is
        // (key_id, window_start) and keys carries budget_duration.
        try (PostgresCallStore rebooted = new PostgresCallStore(PgTestDb.config(), new MutableClock(START), 2);
                var connection = truncateDataSource.getConnection()) {
            assertEquals(
                    "[key_id, window_start]",
                    primaryKeyColumns(connection, "spend"),
                    "V2 reshaped the spend primary key to (key_id, window_start)");
            assertEquals("[id]", primaryKeyColumns(connection, "keys"), "keys keeps its single-column PK");
            try (var ps = connection.prepareStatement("SELECT count(*) FROM information_schema.columns"
                    + " WHERE table_name = 'keys' AND column_name = 'budget_duration'")) {
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(1, rs.getLong(1), "V2 added keys.budget_duration");
                }
            }
            // And the rebooted store works end-to-end on the migrated schema.
            assertInstanceOf(
                    SpendLedger.ReserveResult.Allowed.class,
                    rebooted.reserve("v2-pin", 100, 1_000, 0.8, 0),
                    "the rebooted store reserves on the migrated schema");
            rebooted.settle("v2-pin", 100, 90, 0);
            assertEquals(90, rebooted.spendByKey("v2-pin", 0));
        }
    }

    private static String primaryKeyColumns(java.sql.Connection connection, String table) throws Exception {
        try (var ps = connection.prepareStatement("SELECT kcu.column_name FROM information_schema.table_constraints tc"
                + " JOIN information_schema.key_column_usage kcu"
                + " ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema"
                + " WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = ?"
                + " ORDER BY kcu.ordinal_position")) {
            ps.setString(1, table);
            var columns = new java.util.ArrayList<String>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
            return columns.toString();
        }
    }

    @BeforeEach
    void truncateDatabase() {
        truncateDataSource = PgTestDb.newDataSource();
        PgTestDb.truncateAll(truncateDataSource);
    }

    @AfterEach
    void closeStores() {
        if (lastStore != null) {
            lastStore.close();
            lastStore = null;
        }
        if (truncateDataSource != null) {
            PgTestDb.close(truncateDataSource);
            truncateDataSource = null;
        }
    }
}
