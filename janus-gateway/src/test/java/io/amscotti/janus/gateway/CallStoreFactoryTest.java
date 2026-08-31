package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.CallStatus;
import io.amscotti.janus.store.CallStore;
import io.amscotti.janus.store.PostgresCallStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * the {@link CallStoreFactory} Testcontainers leg: {@code [janus.store]
 * type = "postgres"} resolves the env references and builds the real {@link
 * PostgresCallStore} against a live container — the boot migration ran, the call ring
 * honors {@code [janus.store] retention}, the spend ring honors {@code [janus.limits]
 * ledger-retention}, and the pool is closed (test leak discipline). Docker-less
 * machines skip the class (CI exercises it for real); every Docker-free pin — the
 * memory branch (retention defaults/knobs, window-variant selection, ledger-retention
 * independence, derived-bean identity) and the postgres fail-fast paths — lives in
 * the sibling {@code CallStoreFactoryBootTest} so it runs everywhere.
 */
@Testcontainers(disabledWithoutDocker = true)
class CallStoreFactoryTest {

    /** A fixed clock ( discipline) — the postgres branch needs no real time. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void postgresWithResolvedEnvBuildsThePgStore() {
        CallStoreFactory factory = new CallStoreFactory(name -> switch (name) {
            case "JANUS_DB_URL" -> POSTGRES.getJdbcUrl();
            case "JANUS_DB_USER" -> POSTGRES.getUsername();
            case "JANUS_DB_PASS" -> POSTGRES.getPassword();
            default -> null;
        });
        JanusConfig config = new JanusConfig(
                "janus",
                "0.1.0-SNAPSHOT",
                null,
                null,
                null,
                null,
                null,
                new JanusConfig.LimitsConfig("fixed", null, null, 1),
                new JanusConfig.StoreConfig("postgres", "JANUS_DB_URL", "JANUS_DB_USER", "JANUS_DB_PASS", 5, 3));
        CallStore store = factory.callStore(config, CLOCK);
        try {
            assertInstanceOf(PostgresCallStore.class, store, "type = \"postgres\" builds the JDBC store");
            // The boot migration ran: a record lands and the retention=3 ring prunes.
            for (int i = 0; i < 4; i++) {
                store.recordCall(record("r" + i, "k"));
            }
            assertEquals(3, store.recentCalls("k", 10).size(), "the Postgres ring honors [janus.store] retention");
            assertEquals(1, store.dropped());
            // [janus.limits] ledger-retention reaches the Postgres spend ring too
            // (PgSpendLedger's prune) — independent of the call-ring retention.
            store.recordSpend("k", 10);
            store.recordSpend("k", 20);
            assertEquals(1, store.recent("k", 10).size(), "the spend ring honors [janus.limits] ledger-retention=1");
        } finally {
            ((PostgresCallStore) store).close(); // test leak discipline (pool)
        }
    }

    private static JanusConfig config(JanusConfig.StoreConfig store) {
        return new JanusConfig("janus", "0.1.0-SNAPSHOT", null, null, null, null, null, null, store);
    }

    /** A Tier-1 record with fixed non-content fields (no bodies, no secrets). */
    private static CallRecord record(String requestId, String keyId) {
        return new CallRecord(
                requestId,
                keyId,
                "gpt-4o",
                "deepseek",
                10,
                20,
                30,
                null,
                null,
                250,
                100,
                false,
                CallStatus.OK,
                CLOCK.millis());
    }
}
