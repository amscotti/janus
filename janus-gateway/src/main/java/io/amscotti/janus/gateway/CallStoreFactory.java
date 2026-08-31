package io.amscotti.janus.gateway;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.store.CallStore;
import io.amscotti.janus.store.FixedWindowRateLimiter;
import io.amscotti.janus.store.InMemoryCallStore;
import io.amscotti.janus.store.KeyStore;
import io.amscotti.janus.store.PostgresCallStore;
import io.amscotti.janus.store.PostgresStoreConfig;
import io.amscotti.janus.store.RateLimiter;
import io.amscotti.janus.store.SpendLedger;
import io.amscotti.janus.store.TokenBucketRateLimiter;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

/**
 * Composition root for the store beans (the -documented handoff, " step
 * 1"): replaces the {@code KeyStoreFactory} body and the
 * {@code GovernanceFactory} rate/ledger beans with <b>one</b> {@link CallStore}
 * bean — {@code [janus.store] type} selects the backend — plus derived
 * {@link KeyStore}/{@link RateLimiter}/{@link SpendLedger} beans returning the same
 * instance, so the consumers ({@link KeyAuthFilter}, {@link
 * AdminKeysController}, {@link GovernanceFactory}'s {@link Governance}) compile and
 * inject <b>unchanged</b> (the design note: one object, three bean types).
 *
 * <p><b>Backend selection.</b> {@code [janus.store] type = "memory"} (absent/null ⇒
 * memory — default behavior byte-identical, zero extra config) builds
 * {@link InMemoryCallStore} with the config's {@code retention} (default
 * {@link InMemoryCallStore#DEFAULT_RETENTION}) and the {@code [janus.limits] window}
 * variant (the selection moves here from {@code GovernanceFactory}; "sliding"
 * stays memory-only — token-bucket has no Postgres counterpart in documented in
 * {@code PgRateLimiter}). {@code type = "postgres"} resolves the env references
 * ( pattern: the JDBC URL comes from {@code jdbc-url-env}, never TOML) and builds
 * {@link PostgresCallStore}. Both branches wire <b>two independent retention knobs</b>:
 * {@code [janus.store] retention} (the per-key call ring) and {@code [janus.limits]
 * ledger-retention} (the per-key spend-ledger ring, default
 * {@link JanusConfig.LimitsConfig#DEFAULTS} 1000) — a custom ledger-retention is no
 * longer silently ignored.
 *
 * <p><b>Fail fast at boot.</b> An unknown
 * type is rejected at config binding; {@code postgres} with an unresolvable env var
 * fails here naming the <b>env var</b> (never the URL — credentials may be embedded);
 * a pool that cannot connect fails at {@link PostgresCallStore} construction
 * (HikariCP {@code initializationFailTimeout = 1}) and is wrapped here with an error
 * naming the env var. A node never silently falls back to memory in a multi-node
 * deployment (that would violate read-your-writes).
 *
 * <p><b>Test seam.</b> The no-arg constructor (Micronaut's) resolves env vars from
 * {@code System.getenv}; the {@link Function}-arg constructor lets tests drive the
 * env resolution without mutating the process environment (the {@code
 * CallStoreFactoryTest} postgres-branch coverage).
 */
@Factory
final class CallStoreFactory {

    static final String TYPE_MEMORY = JanusConfig.StoreConfig.TYPE_MEMORY;
    static final String TYPE_POSTGRES = JanusConfig.StoreConfig.TYPE_POSTGRES;

    private final Function<String, String> env;

    CallStoreFactory() {
        this(System::getenv);
    }

    CallStoreFactory(Function<String, String> env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }

    @Singleton
    CallStore callStore(JanusConfig config, Clock clock) {
        JanusConfig.StoreConfig store = config.store();
        String type = store == null || store.type() == null || store.type().isBlank() ? TYPE_MEMORY : store.type();
        int retention = store != null && store.retention() != null && store.retention() > 0
                ? store.retention()
                : InMemoryCallStore.DEFAULT_RETENTION;
        // The spend-ledger ring is its own knob ([janus.limits] ledger-retention) —
        // distinct from the call ring's [janus.store] retention — and applies to BOTH
        // backends (the in-memory ledger and PgSpendLedger's spend_entries prune).
        JanusConfig.LimitsConfig limits = config.limits();
        int ledgerRetention = limits != null && limits.ledgerRetention() != null && limits.ledgerRetention() > 0
                ? limits.ledgerRetention()
                : JanusConfig.LimitsConfig.DEFAULTS.ledgerRetention();
        if (TYPE_MEMORY.equals(type)) {
            // The window selection moves here from GovernanceFactory: the memory
            // branch embeds the configured variant; "sliding" stays memory-only.
            String window = limits == null ? null : limits.window();
            RateLimiter limiter =
                    "sliding".equals(window) ? new TokenBucketRateLimiter(clock) : new FixedWindowRateLimiter(clock);
            return new InMemoryCallStore(clock, retention, ledgerRetention, limiter);
        }
        if (TYPE_POSTGRES.equals(type)) {
            PostgresStoreConfig pg = resolvePostgres(store);
            try {
                return new PostgresCallStore(pg, clock, retention, ledgerRetention);
            } catch (RuntimeException e) {
                // Never echo the URL (credentials may be embedded) — name the env var,
                // and strip the original cause: Hikari's PoolInitializationException
                // chain can carry the URL / embedded credentials (e.g. a psql
                // UnknownHostException built from the URL's user:pass@host part), and
                // the boot-time stack trace Micronaut emits would reproduce it. The
                // wrapped cause carries only the failure type, so the redaction
                // guarantee holds for the whole exception chain.
                throw new IllegalStateException(
                        "[janus.store] type = \"postgres\" could not initialize its"
                                + " connection pool from env var \"" + store.jdbcUrlEnv() + "\" ("
                                + e.getClass().getSimpleName() + ")",
                        new IllegalStateException(e.getClass().getSimpleName()));
            }
        }
        // Defensive: the StoreConfig binding already rejects unknown types.
        throw new IllegalStateException("unknown [janus.store] type \"" + type + "\" (expected \"" + TYPE_MEMORY
                + "\" or \"" + TYPE_POSTGRES + "\")");
    }

    /** Derived views of the one CallStore bean — consumers compile unchanged. */
    @Bean
    KeyStore keyStore(CallStore callStore) {
        return callStore;
    }

    @Bean
    RateLimiter rateLimiter(CallStore callStore) {
        return callStore;
    }

    @Bean
    SpendLedger spendLedger(CallStore callStore) {
        return callStore;
    }

    /** Resolve the env references into the store config; an unresolvable one fails fast. */
    private PostgresStoreConfig resolvePostgres(JanusConfig.StoreConfig store) {
        String jdbcUrl = resolve(store.jdbcUrlEnv(), "jdbc-url-env");
        String username = store.userEnv() == null ? null : resolve(store.userEnv(), "user-env");
        String password = store.passwordEnv() == null ? null : resolve(store.passwordEnv(), "password-env");
        int maxPoolSize = store.maxPoolSize() != null && store.maxPoolSize() > 0
                ? store.maxPoolSize()
                : JanusConfig.StoreConfig.DEFAULTS.maxPoolSize();
        return new PostgresStoreConfig(jdbcUrl, username, password, maxPoolSize);
    }

    private String resolve(String envName, String keyName) {
        String value = env.apply(envName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("[janus.store] type = \"postgres\" requires environment variable \""
                    + envName + "\" (referenced by [janus.store] " + keyName + ")");
        }
        return value;
    }
}
