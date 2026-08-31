package io.amscotti.janus.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers Postgres for the janus-store JDBC suites (the parity
 * harness + the per-piece mirrors). One shared {@code postgres:16-alpine} container,
 * started once per JVM ({@link #ensureStarted} from each test class's
 * {@code @BeforeAll}) and stopped by a shutdown hook — a class-scoped
 * {@code @Container} lifecycle would restart the container between classes and race
 * the next class's pool construction. Test classes pair this with
 * {@code @Testcontainers(disabledWithoutDocker = true)} so a Docker-less machine
 * skips (not fails) while CI (Docker preinstalled) exercises the real database.
 * Test classes truncate between tests ({@link #truncateAll}) — the schema is shared
 * and idempotently migrated, the <b>data</b> is per-test fresh. Test classes must
 * close the pools they create (test leak discipline — the container's default
 * {@code max_connections} is 100).
 */
final class PgTestDb {

    /** The shared container (started once per JVM, see {@link #ensureStarted()}). */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop, "pg-testdb-shutdown"));
    }

    private PgTestDb() {}

    /** Start the shared container exactly once (idempotent; call from each class's @BeforeAll). */
    static void ensureStarted() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    /** The store config for the shared container (pool size 10 — the documented default). */
    static PostgresStoreConfig config() {
        return new PostgresStoreConfig(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 10);
    }

    /** A fresh Hikari pool over the shared container (fail-fast init, like production). */
    static DataSource newDataSource() {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(POSTGRES.getJdbcUrl());
        hikari.setUsername(POSTGRES.getUsername());
        hikari.setPassword(POSTGRES.getPassword());
        hikari.setMaximumPoolSize(10);
        hikari.setInitializationFailTimeout(1);
        return new HikariDataSource(hikari);
    }

    /** A fresh Hikari pool over a named database in the shared container (the fresh-boot migration test). */
    static DataSource newDataSourceFor(String databaseName) {
        String url =
                "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + databaseName;
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url);
        hikari.setUsername(POSTGRES.getUsername());
        hikari.setPassword(POSTGRES.getPassword());
        hikari.setMaximumPoolSize(10);
        hikari.setInitializationFailTimeout(1);
        return new HikariDataSource(hikari);
    }

    /** Close a pool created by {@link #newDataSource()} (test leak discipline). */
    static void close(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    /** Apply the classpath migrations (idempotent — version-tracked; safe on a shared DB). */
    static void migrate() {
        DataSource dataSource = newDataSource();
        try {
            SchemaMigration.migrate(dataSource, Clock.systemUTC());
        } finally {
            close(dataSource);
        }
    }

    /** Wipe all data (per-test freshness); the schema survives and RESTART IDENTITY resets the seqs. */
    static void truncateAll(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE keys, rate_limits, spend, spend_entries, calls, store_meta,"
                    + " schema_migrations RESTART IDENTITY");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to truncate the shared test database", e);
        }
    }
}
