package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.CallStatus;
import io.amscotti.janus.store.CallStore;
import io.amscotti.janus.store.InMemoryCallStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The {@link CallStoreFactory} pins that must run <b>without</b> Docker (the sibling
 * {@code CallStoreFactoryTest} is Testcontainers-gated, so anything left there skips
 * whole on Docker-less CI). These are pure-JVM boot behaviors:
 *
 * <ul>
 * <li>the memory branch defaults: absent {@code [janus.store]} ⇒ the in-memory
 * backend with the documented default retention, and a custom {@code [janus.store]
 * retention} bounds the call ring;
 * <li>the {@code [janus.limits] window} selection (fixed default, explicit
 * {@code "fixed"}, and the {@code "sliding"} token-bucket variant — memory-only);
 * <li>{@code [janus.limits] ledger-retention} wiring: the spend ring honors its own
 * knob (independently of the call ring) and falls back to the documented 1000
 * default;
 * <li>the derived {@code KeyStore}/{@code RateLimiter}/{@code SpendLedger} beans are
 * the same {@code CallStore} instance;
 * <li>the postgres fail-fast paths: an unresolvable env var names the env var (never
 * a URL), and the pool-init failure must never leak the JDBC URL or any credentials
 * embedded in it through the exception <b>chain</b> (the factory's message names
 * only the env var; the wrapped cause carries only the failure type — the boot-time
 * stack trace Micronaut emits is therefore clean).
 * </ul>
 *
 * <p>The window-variant pins assert the resolved contract: {@code accumulate}
 * returns the same <b>window total</b> on both the fixed-window default (7, then 14)
 * and the sliding/token-bucket branch (7, then 14) — one meaning across window
 * selections, asserted through the composite, no reflection.
 */
class CallStoreFactoryBootTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    /** The production env resolution (System.getenv) — the memory branch never touches it. */
    private static final CallStoreFactory FACTORY = new CallStoreFactory(System::getenv);

    @Test
    void postgresPoolFailureNeverLeaksTheUrlOrEmbeddedCredentials() {
        // A URL with credentials embedded in its userinfo part: psql fails fast (before
        // any TCP) by resolving the mangled host "admin:SUPER-SECRET-PASSWORD@127.0.0.1",
        // and the RAW Hikari cause chain reproduces that string verbatim — the exact
        // leak the redaction must stop.
        CallStoreFactory factory =
                new CallStoreFactory(name -> "jdbc:postgresql://admin:SUPER-SECRET-PASSWORD@127.0.0.1:1/nodb");
        JanusConfig config = config(new JanusConfig.StoreConfig("postgres", "JANUS_DB_URL", null, null, 10, 1000));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> factory.callStore(config, CLOCK));
        assertFalse(
                e.getMessage().contains("SUPER-SECRET-PASSWORD"),
                "the factory message must never carry the credential: " + e.getMessage());
        assertFalse(e.getMessage().contains("jdbc:postgresql://"), "the factory message must never echo the URL");

        String chain = renderChain(e);
        assertFalse(
                chain.contains("SUPER-SECRET-PASSWORD"),
                "the exception chain must never carry the credential:\n" + chain);
        assertFalse(chain.contains("jdbc:postgresql://"), "the exception chain must never echo the URL:\n" + chain);
    }

    @Test
    void postgresWithoutEnvVarFailsFastNamingTheEnvVar() {
        CallStoreFactory factory = new CallStoreFactory(name -> null);
        JanusConfig config = config(new JanusConfig.StoreConfig("postgres", "JANUS_DB_URL", null, null, 10, 1000));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> factory.callStore(config, CLOCK));
        assertTrue(
                e.getMessage().contains("JANUS_DB_URL"), "the error names the env var, never a URL: " + e.getMessage());
    }

    @Test
    void absentStoreSectionDefaultsToMemoryWithDefaultRetention() {
        CallStore store = FACTORY.callStore(config(null), CLOCK);
        assertInstanceOf(InMemoryCallStore.class, store, "absent [janus.store] ⇒ the in-memory default");

        // Default retention 1000 wired from InMemoryCallStore.DEFAULT_RETENTION: the
        // 1001st record evicts exactly one ('s deferred TOML binding, default path).
        for (int i = 0; i <= InMemoryCallStore.DEFAULT_RETENTION; i++) {
            store.recordCall(record("r" + i, "k"));
        }
        assertEquals(
                InMemoryCallStore.DEFAULT_RETENTION,
                store.recentCalls("k", Integer.MAX_VALUE).size(),
                "the ring holds exactly the default retention");
        assertEquals(1, store.dropped(), "the overflow was counted, not lost");
    }

    @Test
    void memoryRetentionFromStoreConfig() {
        CallStore store =
                FACTORY.callStore(config(new JanusConfig.StoreConfig("memory", null, null, null, null, 3)), CLOCK);
        for (int i = 0; i < 4; i++) {
            store.recordCall(record("r" + i, "k"));
        }
        assertEquals(3, store.recentCalls("k", 10).size(), "[janus.store] retention=3 bounds the ring");
        assertEquals(1, store.dropped());
        assertEquals("r3", store.recentCalls("k", 10).get(0).requestId(), "newest first");
    }

    @Test
    void fixedWindowIsTheMemoryDefault() {
        CallStore store = FACTORY.callStore(config(null), CLOCK);
        assertEquals(7, store.accumulate("k", 10, 7), "fixed-window accumulate = the window total");
        assertEquals(14, store.accumulate("k", 10, 7), "repeated accumulate adds up (window total)");
    }

    @Test
    void explicitFixedWindowSelectsFixedWindowRateLimiter() {
        JanusConfig config = new JanusConfig(
                "janus",
                "0.1.0-SNAPSHOT",
                null,
                null,
                null,
                null,
                null,
                new JanusConfig.LimitsConfig("fixed", null, null, null),
                null);
        CallStore store = new CallStoreFactory(System::getenv).callStore(config, CLOCK);
        assertInstanceOf(InMemoryCallStore.class, store, "window=\"fixed\" stays memory-only (no Postgres variant)");
        // Fixed-window accumulate returns the window total (7, then 14) — the
        // documented divergence vs the token-bucket "tokens remaining".
        assertEquals(7, store.accumulate("k", 10, 7), "fixed-window accumulate = the window total");
        assertEquals(14, store.accumulate("k", 10, 7), "repeated accumulate adds up (window total)");
    }

    @Test
    void slidingWindowKeepsTheMemoryBackendWithTokenBucket() {
        JanusConfig config = new JanusConfig(
                "janus",
                "0.1.0-SNAPSHOT",
                null,
                null,
                null,
                null,
                null,
                new JanusConfig.LimitsConfig("sliding", null, null, null),
                null);
        CallStore store = FACTORY.callStore(config, CLOCK);
        assertInstanceOf(InMemoryCallStore.class, store, "window=\"sliding\" stays memory-only (no Postgres variant)");
        // The token-bucket accumulate returns the SAME window total as the fixed
        // default (7, then 14) — the one-meaning contract across window selections.
        assertEquals(7, store.accumulate("k", 10, 7), "sliding accumulate = the window total");
        assertEquals(14, store.accumulate("k", 10, 7), "repeated settle adds up, same as fixed");
    }

    @Test
    void ledgerRetentionFromLimitsConfigFlowsIntoTheStoreIndependently() {
        // [janus.limits] ledger-retention is the SPEND-ledger ring's knob — distinct
        // from [janus.store] retention (the call ring) — and must reach the store:
        // store retention 5, ledger retention 1 ⇒ each ring bounded by its own knob.
        JanusConfig config = new JanusConfig(
                "janus",
                "0.1.0-SNAPSHOT",
                null,
                null,
                null,
                null,
                null,
                new JanusConfig.LimitsConfig("fixed", null, null, 1),
                new JanusConfig.StoreConfig("memory", null, null, null, null, 5));
        CallStore store = FACTORY.callStore(config, CLOCK);

        store.recordSpend("k", 10);
        store.recordSpend("k", 20);
        assertEquals(1, store.recent("k", 10).size(), "[janus.limits] ledger-retention=1 bounds the spend ring");
        assertEquals(20, store.recent("k", 10).get(0).microUsd(), "newest first");

        for (int i = 0; i < 3; i++) {
            store.recordCall(record("r" + i, "k"));
        }
        assertEquals(3, store.recentCalls("k", 10).size(), "the call ring still honors [janus.store] retention=5");
        assertEquals(0, store.dropped());
    }

    @Test
    void absentLedgerRetentionFallsBackToTheDocumentedDefault() {
        // No [janus.limits] section ⇒ the LimitsConfig.DEFAULTS ledger retention
        // (1000) applies — matching the documented default, not the call ring's knob.
        CallStore store =
                FACTORY.callStore(config(new JanusConfig.StoreConfig("memory", null, null, null, null, 3)), CLOCK);
        for (int i = 0; i < 4; i++) {
            store.recordSpend("k", i);
        }
        assertEquals(
                4,
                store.recent("k", 10).size(),
                "the spend ring keeps the 1000 default while the call ring is 3 — the knobs are independent");
    }

    @Test
    void derivedBeansAreTheSameCallStoreInstance() {
        CallStore store = FACTORY.callStore(config(null), CLOCK);
        assertSame(store, FACTORY.keyStore(store), "the KeyStore view is the CallStore instance");
        assertSame(store, FACTORY.rateLimiter(store), "the RateLimiter view is the CallStore instance");
        assertSame(store, FACTORY.spendLedger(store), "the SpendLedger view is the CallStore instance");
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

    /** The full exception chain (message + cause toString), as boot logs would render it. */
    private static String renderChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        while (current != null) {
            sb.append(current).append('\n');
            current = current.getCause();
        }
        return sb.toString();
    }
}
