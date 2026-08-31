package io.amscotti.janus.store;

/**
 * The Postgres connection + pool knobs for {@link PostgresCallStore} — the
 * gateway's {@code CallStoreFactory} builds this record from the {@code [janus.store]}
 * TOML section — {@code jdbc-url-env} resolved from the environment, env-var-name
 * pattern, never the URL in TOML). The pool itself is built <b>inside janus-store</b> (this
 * record → {@code HikariDataSource}), so the gateway factory never imports HikariCP
 * and janus-store stays Micronaut-free.
 *
 * <p><b>Fail fast at boot (boot decision, recorded for the gate's drill).</b> The pool is
 * constructed with HikariCP's {@code initializationFailTimeout = 1}: an unreachable
 * database refuses the node to start at construction, never on first request — a node
 * silently falling back to memory in a multi-node deployment would violate
 * read-your-writes. The factory's error messages name the <b>env var</b>, never the
 * URL (credentials may be embedded in it — see the risk section).
 *
 * <p><b>Credentials.</b> {@code username}/{@code password} are nullable — when null
 * the pool uses whatever the JDBC URL embeds (the optional {@code user-env} /
 * {@code password-env} refs override URL-embedded credentials).
 *
 * @param jdbcUrl the full {@code jdbc:postgresql://...} URL (non-blank; resolved from
 * the env by the factory, never from TOML)
 * @param username optional pool username (overrides URL-embedded credentials); null =
 * URL-embedded
 * @param password optional pool password; null = URL-embedded
 * @param maxPoolSize HikariCP maximum pool size (positive; default 10)
 */
public record PostgresStoreConfig(String jdbcUrl, String username, String password, int maxPoolSize) {

    public PostgresStoreConfig {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must be non-blank");
        }
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("maxPoolSize must be positive (got " + maxPoolSize + ")");
        }
    }
}
