package io.amscotti.janus.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * The tiny no-Flyway schema migration runner (Flyway is allowed only
 * "if it proves native-image-clean" — YAGNI says don't take the dependency). On
 * {@link PostgresCallStore} construction it reads {@code db/migration/V*.sql} classpath
 * resources in version order, executes each file's statements inside <b>one
 * transaction</b>, then records the version in {@code schema_migrations} (already
 * applied versions are skipped). A failed file rolls its transaction back — the
 * version table never advances past a half-applied migration.
 *
 * <p><b>Idempotency.</b> The shipped DDL is idempotent ({@code CREATE TABLE IF NOT
 * EXISTS}, {@code CREATE INDEX IF NOT EXISTS}), so even without the version table a
 * re-run is harmless; the version table pins "applied exactly once" (the
 * {@code SchemaMigrationTest} applies twice and asserts a single application). On a
 * fresh database the version table does not exist yet — the runner treats the
 * {@code 42P01 undefined_table} error as "not applied" and lets V1 create it.
 *
 * <p><b>Native-image.</b> The SQL files are classpath resources — GraalVM does not
 * embed them by default, so the gateway's {@code resource-config.json} registers the
 * {@code db/migration/*.sql} pattern (the classic native "schema not found" trap).
 * Resource enumeration: on the JVM the {@code db/migration} classpath directory is
 * listed (any {@code V<number>__<name>.sql} file); in a native image directories do
 * not exist, so the runner falls back to probing {@code V<n>__init.sql} for
 * {@code n = 1, 2,...} until one is missing (the shipped naming convention, drift-
 * guarded by {@code SchemaMigrationTest}'s convention test — a {@code V2__add_thing.sql}
 * would be invisible to the probe and silently never applied in the native build).
 *
 * <p><b>Concurrent fresh-boot.</b> The store's raison d'être is multi-node
 * boot: two nodes racing a fresh database both read "not applied" before the version
 * table exists. Each migration's transaction therefore takes
 * {@code pg_advisory_xact_lock(hashtextextended(?, 0))} on a fixed key before applying —
 * the second node waits for the first's commit — and the version {@code INSERT}'s
 * {@code 23505} unique violation is scoped to that INSERT only (the residual race where
 * the second node re-applies the already-won file) and treated as "someone else applied
 * it", so the loser is a no-op, never a failed boot. Once the lock is held the winner
 * cannot be mid-apply, so the runner re-checks {@code isApplied} on the transaction
 * connection and <b>never re-runs</b> a version the winner already applied (the shipped
 * DDL's idempotency must not be relied on by a future non-idempotent V2). A
 * {@code 23505} raised by a migration's own statements is a real schema bug and fails
 * the boot.
 *
 * <p>Thread-safety: the runner is invoked once per {@code PostgresCallStore}
 * construction, before the store serves any request; {@code migrate} itself is not
 * synchronized (construction is single-threaded, discipline), but the advisory
 * lock makes concurrent migrators safe regardless.
 */
final class SchemaMigration {

    /** The classpath directory the versioned SQL files live in. */
    static final String MIGRATION_PATH = "db/migration";

    /** {@code V<number>__<name>.sql} — version files only, no down migrations. */
    private static final Pattern VERSION_FILE = Pattern.compile("V(\\d+)__.*\\.sql");

    private static final String SQLSTATE_UNDEFINED_TABLE = "42P01";

    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** Fixed advisory-lock key serializing concurrent migrators (any version, any node). */
    private static final String ADVISORY_LOCK_KEY = "janus_schema_migrations";

    private SchemaMigration() {}

    /**
     * Apply all not-yet-applied {@code db/migration/V*.sql} files, in version order,
     * each in its own transaction. Idempotent: already-applied versions are skipped.
     * An empty migration list is refused with {@link IllegalStateException} — zero
     * migrations means the resources were never embedded (the native-image
     * registration failure), and a "successful" no-op boot would leave the store
     * schema-less (the fail-fast boot = migrate contract).
     *
     * @param dataSource the store's pool
     * @param clock the store's clock ({@code applied_at} derives from it, never
     * the wall clock)
     */
    static void migrate(DataSource dataSource, Clock clock) {
        migrate(dataSource, clock, loadMigrations());
    }

    /**
     * Apply the given migrations in ascending version order (the test seam — the
     * classpath loader uses this with {@link #loadMigrations}; tests drive it with
     * synthetic files to pin ordering and rollback). Already-applied versions are
     * skipped; each file's statements run inside one transaction and the version is
     * recorded in the same transaction (a failed file rolls back everything). See the
     * class javadoc for the concurrent-fresh-boot serialization (advisory lock + the
     * {@code 23505} version-INSERT catch).
     */
    static void migrate(DataSource dataSource, Clock clock, List<Migration> migrations) {
        Objects.requireNonNull(clock, "clock");
        if (migrations.isEmpty()) {
            // The exact native-image resource-registration failure the class javadoc
            // names: db/migration/V*.sql not embedded ⇒ the classpath listing finds
            // nothing and the version probe stops at V1 ⇒ zero migrations. Applying
            // zero migrations "successfully" boots a schema-less store whose every call
            // 5xxes at runtime — the silent opposite of the documented boot = migrate
            // fail-fast contract. Refuse the boot instead (the empty list is a broken
            // build/resource registration, never a valid no-op state).
            throw new IllegalStateException(
                    "no schema migrations found under " + MIGRATION_PATH + "/ — the build is missing its"
                            + " db/migration resources (native-image: check the resource-config.json"
                            + " db/migration/*.sql registration); refusing to boot a schema-less store");
        }
        List<Migration> ordered = new ArrayList<>(migrations);
        ordered.sort(Comparator.comparingInt(Migration::version));
        for (Migration migration : ordered) {
            if (isApplied(dataSource, migration.version())) {
                continue;
            }
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    // Serialize concurrent migrators at the database level (the
                    // MEDIUM): without the lock, two fresh-boot nodes both apply V1 and
                    // the second one's version INSERT fails with a unique violation,
                    // refusing that node's boot with a confusing error. The lock makes
                    // the loser wait for the winner's commit.
                    try (PreparedStatement lock =
                            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                        lock.setString(1, ADVISORY_LOCK_KEY);
                        lock.executeQuery();
                    }
                    // The winner's statements are never re-run. The
                    // pre-lock isApplied check runs on a separate connection before the
                    // lock is taken, so under a concurrent fresh boot the lock-loser
                    // used to re-execute the winner's (idempotent) DDL — harmless only
                    // because the shipped V1 is idempotent, but a future non-idempotent
                    // V2 would fail the loser's boot with "relation already exists".
                    // Now that the lock is held nobody else can be mid-apply, so a fresh
                    // check on the transaction connection means a completed winner: skip
                    // without re-running its statements.
                    if (isApplied(connection, migration.version())) {
                        connection.rollback();
                        continue;
                    }
                    for (String statement : migration.statements()) {
                        try (Statement st = connection.createStatement()) {
                            st.execute(statement);
                        }
                    }
                    // The version INSERT's 23505 is the only "already applied" case the
                    // runner treats as success (a racing winner that committed between
                    // the post-lock re-check and this INSERT — unreachable in practice
                    // since the lock is held, kept as the belt-and-suspenders of the
                    // concurrent-boot race). A 23505 raised by the migration's own
                    // statements is a real schema bug and must refuse the boot
                    // MEDIUM): the catch is scoped to this statement only, never the
                    // per-version transaction.
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)")) {
                        ps.setInt(1, migration.version());
                        ps.setLong(2, clock.millis());
                        try {
                            ps.executeUpdate();
                        } catch (SQLException e) {
                            if (SQLSTATE_UNIQUE_VIOLATION.equals(e.getSQLState())) {
                                connection.rollback();
                                continue; // a racing node applied this version while we waited — success
                            }
                            throw e;
                        }
                    }
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "failed to apply schema migration V" + migration.version() + " (" + migration.resource() + ")",
                        e);
            }
        }
    }

    /**
     * Is {@code version} recorded in {@code schema_migrations}? Runs on its <b>own</b>
     * connection (autocommit): a failed lookup on a fresh database (absent version
     * table — {@code 42P01 undefined_table}) must not poison the migration
     * transaction (PostgreSQL aborts a transaction after any statement error).
     */
    private static boolean isApplied(DataSource dataSource, int version) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("SELECT version FROM schema_migrations WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            if (SQLSTATE_UNDEFINED_TABLE.equals(e.getSQLState())) {
                return false; // fresh database: the version table does not exist yet (V1 creates it)
            }
            throw new IllegalStateException("failed to check schema_migrations for version " + version, e);
        }
    }

    /**
     * Is {@code version} recorded in {@code schema_migrations}, read on the given
     * <b>transactional</b> connection? Runs under a savepoint: on a fresh database the
     * version table does not exist, and its {@code 42P01 undefined_table} error would
     * abort the PostgreSQL transaction — the rollback-to-savepoint releases that abort
     * so the migration transaction stays usable. The runner uses this on the
     * transaction connection <b>after</b> acquiring the advisory lock (see
     * {@link #migrate}): the lock serializes migrators, so "applied now" means a
     * completed winner whose statements must not be re-run.
     */
    private static boolean isApplied(Connection connection, int version) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            try (PreparedStatement ps =
                    connection.prepareStatement("SELECT version FROM schema_migrations WHERE version = ?")) {
                ps.setInt(1, version);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            if (SQLSTATE_UNDEFINED_TABLE.equals(e.getSQLState())) {
                return false; // fresh database: the version table does not exist yet (V1 creates it)
            }
            throw e;
        } finally {
            connection.rollback(savepoint);
        }
    }

    /** List the classpath versioned resources in ascending version order (missing dir ⇒ empty). */
    static List<Migration> loadMigrations() {
        return loadMigrations(SchemaMigration.class.getClassLoader());
    }

    /**
     * List the classpath versioned resources in ascending version order (the test
     * seam — tests point this at a synthetic loader to pin the native-image probe
     * path). Missing dir ⇒ empty; the native-image fallback probes
     * {@code V<n>__init.sql} for {@code n = 1, 2,...} until one is missing.
     */
    static List<Migration> loadMigrations(ClassLoader loader) {
        List<Migration> migrations = new ArrayList<>();
        try {
            Enumeration<URL> directories = loader.getResources(MIGRATION_PATH);
            while (directories.hasMoreElements()) {
                URL directory = directories.nextElement();
                if ("file".equals(directory.getProtocol())) {
                    try (var stream = Files.list(Path.of(directory.toURI()))) {
                        stream.filter(path -> VERSION_FILE
                                        .matcher(path.getFileName().toString())
                                        .matches())
                                .forEach(path -> migrations.add(
                                        readMigration(loader, path.getFileName().toString())));
                    }
                }
                // Non-file protocols (jar, native-image): fall through to the version probe.
            }
        } catch (Exception e) {
            throw new UncheckedIOException("failed to enumerate " + MIGRATION_PATH + " migrations", new IOException(e));
        }
        if (migrations.isEmpty()) {
            // Native-image / jar fallback: probe V<n>__init.sql until one is missing.
            for (int version = 1; ; version++) {
                String name = "V" + version + "__init.sql";
                try (InputStream in = loader.getResourceAsStream(MIGRATION_PATH + "/" + name)) {
                    if (in == null) {
                        break;
                    }
                    migrations.add(readMigration(loader, name));
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to read migration " + name, e);
                }
            }
        }
        migrations.sort(Comparator.comparingInt(Migration::version));
        // A skipped version (a deleted V1, a renamed file) would silently never apply —
        // in the native build the probe just stops. Fail loudly instead of shipping
        // schema drift; the naming-convention guard test keeps the probe complete.
        ensureContiguousFromV1(migrations);
        return List.copyOf(migrations);
    }

    /** The versions must be {@code 1, 2, ..., N} with no gaps (a gap = silently never-applied migrations). */
    private static void ensureContiguousFromV1(List<Migration> migrations) {
        int expected = 1;
        for (Migration migration : migrations) {
            if (migration.version() != expected) {
                throw new IllegalStateException("schema migrations are not contiguous from V1 — missing V" + expected
                        + " (found " + migration.resource() + ")");
            }
            expected++;
        }
    }

    private static Migration readMigration(ClassLoader loader, String name) {
        try (InputStream in = loader.getResourceAsStream(MIGRATION_PATH + "/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing migration resource " + MIGRATION_PATH + "/" + name);
            }
            Matcher m = VERSION_FILE.matcher(name);
            if (!m.matches()) {
                throw new IllegalStateException("migration file must match V<number>__<name>.sql: " + name);
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Migration(Integer.parseInt(m.group(1)), MIGRATION_PATH + "/" + name, splitStatements(sql));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read migration " + name, e);
        }
    }

    /**
     * Split a migration file into executable statements on semicolons at line ends.
     * The janus DDL is plain {@code CREATE TABLE}/{@code CREATE INDEX} statements —
     * no functions/triggers with embedded semicolons — so this lightweight splitter
     * (no SQL parser, no Flyway) is exact for the shipped migrations.
     */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] lines = sql.split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue; // comments and blank lines are not part of a statement
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty() && !current.toString().isBlank()) {
            throw new IllegalStateException("migration contains a statement without a trailing ';'");
        }
        return List.copyOf(statements);
    }

    /** One versioned migration: version number, resource name, executable statements. */
    record Migration(int version, String resource, List<String> statements) {}
}
