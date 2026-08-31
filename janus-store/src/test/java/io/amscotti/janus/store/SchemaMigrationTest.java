package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link SchemaMigration}, the no-Flyway runner: applies the classpath
 * {@code db/migration/V*.sql} files in version order, each inside one transaction,
 * version-tracked in {@code schema_migrations} (already-applied versions skipped —
 * applying V1 twice applies the DDL once); a failed file rolls its transaction back
 * (the version table never advances past a half-applied migration); the splitter
 * rejects a statement without a trailing semicolon. Concurrent migrators on a
 * fresh database (advisory lock + the unique-violation catch), the native-probe
 * naming-convention drift guard, and the probe's non-{@code __init} trap.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaMigrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    private DataSource dataSource;

    @BeforeAll
    static void startDatabase() {
        PgTestDb.ensureStarted();
        PgTestDb.migrate();
    }

    @BeforeEach
    void cleanDatabase() {
        dataSource = PgTestDb.newDataSource();
        PgTestDb.truncateAll(dataSource);
    }

    @AfterEach
    void closePool() {
        PgTestDb.close(dataSource);
    }

    @Test
    void appliesEveryVersionExactlyOnceAndSkipsTheSecondApplication() {
        // The truncateAll/re-migrate cycle the test harness runs on every boot is the
        // V2 idempotency pin: schema_migrations is wiped (so every file re-applies
        // against an already-migrated schema) and the idempotent DDL — the PK swap's
        // DROP CONSTRAINT IF EXISTS → ADD pair included — must re-run harmlessly.
        SchemaMigration.migrate(dataSource, CLOCK);
        SchemaMigration.migrate(dataSource, CLOCK); // idempotent re-run must not re-apply

        assertEquals(
                List.of(1, 2), appliedVersions(), "the version table pins exactly one application of each version");
        assertTrue(tableExists("keys"), "V1 created the keys table");
        assertTrue(tableExists("calls"), "V1 created the calls table");
        assertTrue(tableExists("rate_limits"), "V1 created the rate_limits table");
        assertTrue(tableExists("store_meta"), "V1 created the store_meta table");
    }

    @Test
    void appliesFilesInVersionOrderDespiteScrambledInput() {
        // The runner sorts by version number: scrambled input must land 1, then 2 —
        // observable in the version table (a wrong order would record [2, 1]).
        SchemaMigration.migrate(
                dataSource,
                CLOCK,
                List.of(
                        new SchemaMigration.Migration(
                                2, "V2__probe.sql", List.of("CREATE TABLE IF NOT EXISTS probe_b (id int)")),
                        new SchemaMigration.Migration(
                                1, "V1__probe.sql", List.of("CREATE TABLE IF NOT EXISTS probe_a (id int)"))));

        assertEquals(List.of(1, 2), appliedVersions(), "versions are applied in ascending order");
        assertTrue(tableExists("probe_a"));
        assertTrue(tableExists("probe_b"));
    }

    @Test
    void failedMigrationRollsBackItsWholeTransaction() {
        SchemaMigration.Migration bad = new SchemaMigration.Migration(
                99, "V99__bad.sql", List.of("CREATE TABLE IF NOT EXISTS probe_bad (id int)", "THIS IS NOT SQL"));

        assertThrows(IllegalStateException.class, () -> SchemaMigration.migrate(dataSource, CLOCK, List.of(bad)));

        assertFalse(tableExists("probe_bad"), "the first statement's DDL rolled back with the failed file");
        assertEquals(List.of(), appliedVersions(), "a failed migration never records its version");
    }

    @Test
    void anEmptyMigrationListRefusesTheBoot() {
        // loadMigrations returning empty is the exact native-image
        // resource-registration failure the runner's javadoc names: db/migration/V*.sql
        // not embedded ⇒ the classpath listing finds nothing and the version probe
        // stops at V1 ⇒ zero migrations. Applying zero migrations "successfully" boots
        // a schema-less store whose every call 5xxes at runtime — the silent opposite
        // of the documented boot = migrate fail-fast contract — so migrate must refuse
        // the boot instead of treating the broken build as a valid no-op.
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> SchemaMigration.migrate(dataSource, CLOCK, List.of()),
                "an empty migration list is a broken build, never a valid no-op boot");
        assertTrue(
                e.getMessage().contains("no schema migrations"),
                "the error names the failure (missing db/migration resources): " + e.getMessage());
        assertTrue(
                e.getMessage().contains(SchemaMigration.MIGRATION_PATH),
                "the error names the resource path: " + e.getMessage());
    }

    @Test
    void concurrentMigrateOnAFreshDatabaseAppliesExactlyOnce() throws Exception {
        // The fresh multi-node boot race — two PostgresCallStore
        // constructions against a database with NO schema both read "not applied"
        // (isApplied treats the missing version table as not-applied). Without the
        // advisory lock + unique-violation catch, both apply V1 and the loser fails
        // its boot on the version INSERT's 23505. Here: a throwaway schema-less
        // database, two threads migrating it, both must return clean and the version
        // table must hold exactly one row.
        String databaseName = "janus_fresh_" + System.nanoTime();
        createDatabase(databaseName);
        DataSource fresh = PgTestDb.newDataSourceFor(databaseName);
        try {
            int threads = 2;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            try {
                CountDownLatch done = new CountDownLatch(threads);
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        SchemaMigration.migrate(fresh, CLOCK);
                        done.countDown();
                    });
                }
                start.countDown();
                assertTrue(done.await(60, TimeUnit.SECONDS), "both racing migrators must return without exception");
            } finally {
                pool.shutdownNow();
            }
            assertEquals(
                    List.of(1, 2),
                    appliedVersions(fresh),
                    "the racing migrators record exactly one application of each version");
            assertTrue(tableExists(fresh, "keys"), "the fresh database got the full schema");
        } finally {
            PgTestDb.close(fresh);
        }
    }

    @Test
    void uniqueViolationFromTheMigrationsOwnStatementsFailsTheBoot() {
        // The 23505 catch used to cover the whole per-version
        // transaction, so a future V2 whose statements raise a unique violation (e.g. a
        // data-seed INSERT into a UNIQUE column) was silently skipped forever — the
        // version never recorded, every boot retries-and-re-skips, permanent silent
        // schema drift with a green boot. The catch is now scoped to the version INSERT
        // only: a 23505 from the migration's own statements is a real bug and must
        // refuse the boot, with the version table never advancing past the file.
        SchemaMigration.Migration dataSeed = new SchemaMigration.Migration(
                99,
                "V99__seed.sql",
                List.of(
                        "CREATE TABLE probe_seed (name text PRIMARY KEY)",
                        "INSERT INTO probe_seed (name) VALUES ('dup')",
                        "INSERT INTO probe_seed (name) VALUES ('dup')"));

        assertThrows(
                IllegalStateException.class,
                () -> SchemaMigration.migrate(dataSource, CLOCK, List.of(dataSeed)),
                "a migration whose own statements violate a unique constraint must fail the boot, not be skipped");
        assertFalse(tableExists("probe_seed"), "the failed migration's DDL rolled back with the file");
        assertEquals(List.of(), appliedVersions(), "a statement-23505 migration is never recorded as applied");
    }

    @Test
    void concurrentMigrateWithNonIdempotentStatementsAppliesExactlyOnce() throws Exception {
        // The fresh-boot lock-loser used to re-run the winner's statements
        // after acquiring the lock, relying on the shipped DDL being idempotent
        // (CREATE... IF NOT EXISTS) to stay harmless. A future non-idempotent V2 would
        // fail the loser's boot with "relation already exists". The post-lock re-check
        // (on the transaction connection) makes the loser skip the winner's
        // already-applied version instead. Drive it with a non-idempotent V1 on a fresh
        // schema-less database; the gated DataSource rendezvous the two threads at their
        // migration-transaction connections — AFTER both pre-lock isApplied reads
        // complete (both see the fresh DB) — so the loser is guaranteed to reach the
        // advisory lock and re-run the winner's statements, deterministically.
        String databaseName = "janus_nonidem_" + System.nanoTime();
        createDatabase(databaseName);
        DataSource fresh = PgTestDb.newDataSourceFor(databaseName);
        try {
            DataSource gated = new RendezvousDataSource(fresh);
            SchemaMigration.Migration v1 = new SchemaMigration.Migration(
                    1,
                    "V1__probe.sql",
                    // Mirror the shipped V1 shape: the migration itself creates the
                    // version table, and the DDL is deliberately NON-idempotent (no
                    // IF NOT EXISTS) so a loser that re-runs the winner's statements
                    // would fail with "relation already exists".
                    List.of(
                            "CREATE TABLE schema_migrations (version int PRIMARY KEY, applied_at bigint NOT NULL)",
                            "CREATE TABLE probe (id int)"));
            SchemaMigration.Migration v2 = new SchemaMigration.Migration(
                    2, "V2__probe.sql", List.of("CREATE TABLE IF NOT EXISTS probe2 (id int)"));
            int threads = 2;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            try {
                CountDownLatch done = new CountDownLatch(threads);
                java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                        new java.util.concurrent.atomic.AtomicReference<>();
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        try {
                            SchemaMigration.migrate(gated, CLOCK, List.of(v1, v2));
                        } catch (Throwable e) {
                            failure.set(e);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(
                        done.await(60, TimeUnit.SECONDS),
                        "both racing migrators must return without a relation-already-exists failure");
                assertTrue(
                        failure.get() == null,
                        "neither migrator may fail, got: " + (failure.get() == null ? "none" : failure.get()));
            } finally {
                pool.shutdownNow();
            }
            assertEquals(
                    List.of(1, 2),
                    appliedVersions(fresh),
                    "the racing migrators record exactly one application of each version");
            assertTrue(tableExists(fresh, "probe"), "the non-idempotent table exists exactly once");
            assertTrue(tableExists(fresh, "probe2"), "the second version applied exactly once too");
        } finally {
            PgTestDb.close(fresh);
        }
    }

    /**
     * A {@link DataSource} that rendezvous the two migrator threads at their
     * migration-transaction {@code getConnection} calls (calls #2 and #3 — the pre-lock
     * {@code isApplied} checks are calls #0 and #1, which complete on the fresh database
     * before either thread reaches its migration connection). The migration runner opens
     * exactly two connections per migrate call (pre-lock check, then the transaction), so
     * gating calls #2/#3 guarantees BOTH pre-lock reads happen before either migration
     * applies — the fresh-boot interleaving, made deterministic for the test.
     */
    private static final class RendezvousDataSource implements DataSource {
        private final DataSource delegate;
        private final java.util.concurrent.CyclicBarrier migrationRendezvous =
                new java.util.concurrent.CyclicBarrier(2);
        private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        RendezvousDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            int call = calls.getAndIncrement();
            if (call >= 2 && call < 4) {
                try {
                    migrationRendezvous.await(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new SQLException("migration rendezvous interrupted", e);
                }
            }
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return delegate.getConnection(username, password);
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    @Test
    void everyShippedMigrationUsesTheNativeProbeNamingConvention() {
        // The native-image probe only finds V<n>__init.sql — a
        // V2__add_thing.sql would be silently skipped in the native build (no error,
        // just schema drift). Drift-guard: every committed migration file must match
        // the probe's convention, and versions must be contiguous from V1.
        List<SchemaMigration.Migration> migrations = SchemaMigration.loadMigrations();
        assertFalse(migrations.isEmpty(), "at least one migration is shipped");
        int expected = 1;
        for (SchemaMigration.Migration migration : migrations) {
            assertEquals(
                    "V" + expected + "__init.sql",
                    fileNameOf(migration.resource()),
                    "migration files must be V<n>__init.sql — the native-image probe stops at the first"
                            + " missing V<n>__init.sql, so any other name would silently never apply");
            expected++;
        }
    }

    @Test
    void nativeProbePathOnlyFindsInitNamedMigrations() throws Exception {
        // Documented trap: a jar/native classpath is probed for
        // V<n>__init.sql until one is missing — V2__add_thing.sql is invisible to it.
        // The convention guard test above prevents such a file from shipping; this
        // pins the trap so the probe's contract is not silently redefined.
        Path jar = Files.createTempFile("janus-migration-probe", ".jar");
        jar.toFile().deleteOnExit();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("db/migration/V1__init.sql"));
            out.write("CREATE TABLE IF NOT EXISTS probe_one (id int);".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("db/migration/V2__add_thing.sql"));
            out.write("CREATE TABLE IF NOT EXISTS probe_two (id int);".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            List<SchemaMigration.Migration> found = SchemaMigration.loadMigrations(loader);
            assertEquals(
                    List.of(1),
                    found.stream().map(SchemaMigration.Migration::version).toList(),
                    "the probe stops at the missing V2__init.sql and never sees V2__add_thing.sql");
        }
    }

    @Test
    void loadMigrationsFindsEveryVersionInOrder() {
        List<SchemaMigration.Migration> migrations = SchemaMigration.loadMigrations();
        assertEquals(
                List.of(1, 2),
                migrations.stream().map(SchemaMigration.Migration::version).toList());
        assertEquals(2, migrations.size());
    }

    @Test
    void splitterRejectsAStatementWithoutTerminatingSemicolon() {
        assertThrows(
                IllegalStateException.class,
                () -> SchemaMigration.splitStatements("CREATE TABLE t (id int)"),
                "a file must not end mid-statement");
    }

    @Test
    void splitterHandlesLineLeadingCommentsOnly() {
        // Review L2 pin on the naive splitter's documented constraint: LINE-LEADING
        // {@code --} comments are skipped, but a trailing inline comment after a
        // statement makes the remainder look unterminated (a loud boot failure, never a
        // silent mis-split) — migration files must keep comments on their own lines
        // (the shipped DDL does; a semicolon inside a string literal would mis-split
        // the same way). If this ever needs lifting, rewrite the splitter — do not
        // patch the regex.
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> SchemaMigration.splitStatements("CREATE TABLE t (id int); -- inline trailing comment\n"),
                "an inline trailing comment swallows the newline-terminated statement");
        // Statements carry their terminating ';' + newline (the runner's contract —
        // JDBC tolerates the trailing terminator). Found once Docker came up: this
        // class is disabledWithoutDocker, so the pin silently skipped while lima was
        // down and the wrong expectation surfaced only on the first container-backed
        // run.
        assertEquals(
                java.util.List.of("CREATE TABLE t (id int);\n"),
                SchemaMigration.splitStatements("-- leading comment\nCREATE TABLE t (id int);\n"));
    }

    private List<Integer> appliedVersions() {
        return appliedVersions(dataSource);
    }

    private static List<Integer> appliedVersions(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT version FROM schema_migrations ORDER BY version")) {
            List<Integer> versions = new ArrayList<>();
            while (rs.next()) {
                versions.add(rs.getInt(1));
            }
            return versions;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read schema_migrations", e);
        }
    }

    private boolean tableExists(String name) {
        return tableExists(dataSource, name);
    }

    private static boolean tableExists(DataSource dataSource, String name) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to check table existence", e);
        }
    }

    private static void createDatabase(String name) throws SQLException {
        String base = "jdbc:postgresql://" + PgTestDb.POSTGRES.getHost() + ":" + PgTestDb.POSTGRES.getMappedPort(5432)
                + "/postgres";
        try (Connection connection = DriverManager.getConnection(
                        base, PgTestDb.POSTGRES.getUsername(), PgTestDb.POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
    }

    private static String fileNameOf(String resource) {
        return resource.substring(resource.lastIndexOf('/') + 1);
    }
}
