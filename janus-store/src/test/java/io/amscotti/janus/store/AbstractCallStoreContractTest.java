package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The interface-parity harness for the {@link CallStore} seam ("JDBC
 * store passes the same unit suite as in-memory"; Review &amp; Fix: "every store
 * method's semantics documented and tested against both impls"). Abstract: each
 * concrete store's test extends this base and supplies a fresh instance via
 * {@link #newStore}; {@link InMemoryCallStoreTest} does so now, 's
 * {@code PostgresCallStoreTest} does the same with a JDBC-backed construction —
 * parity is inherited, not re-authored.
 *
 * <p>The suite pins the composite contract: the delegation invariants (each of the
 * three embedded concerns behaves per its / javadoc <em>through</em> the
 * {@code CallStore} view), the call-ledger semantics (ring retention/eviction,
 * drop-only overflow, auth-off sentinel, two-rings separation), the composite
 * end-to-end scenario, and concurrency smokes (no lost {@code recordCall}s; the
 * atomic reserve no-overspend guarantee re-asserted through the composite). JVM-only,
 * no network, deterministic: every test gets a fresh store over a fresh
 * {@link MutableClock}.
 */
abstract class AbstractCallStoreContractTest {

    /** A fixed epoch start (the / store tests' convention). */
    protected static final Instant START = Instant.parse("2026-08-03T00:00:00Z");

    private MutableClock clock;
    private CallStore store;

    /** Subclasses supply a fresh store (fresh state) for each test. */
    protected abstract CallStore newStore(MutableClock clock);

    /**
     * The store under test (fresh per test). Subclasses' class-specific tests may use
     * this instead of their own instances.
     */
    protected final CallStore store() {
        return store;
    }

    /** The store's clock (advance it to pin time-dependent behavior deterministically). */
    protected final MutableClock clock() {
        return clock;
    }

    /**
     * The store's per-key call-ring retention — the contract's ring/eviction pins are
     * written against it so a Postgres implementation with a different configured
     * retention still satisfies them.
     */
    protected abstract int ringRetention();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        store = newStore(clock);
    }

    @Test
    void compositeEndToEndScenario() {
        // create key → authenticate → scope/limit ops → reserve/settle → recordCall
        // → recentCalls (per-key + global) → revoke: the whole store surface on one seam.
        KeyStore.CreatedKey created = store.create(
                new KeyStore.KeyCreateRequest("owner", List.of("gpt-4o"), null, 1_000.0, null, 60, 100_000));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();
        String keyId = created.record().id();

        KeyStore.AuthResult auth = store.authenticate(parsed.prefix(), parsed.secret());
        assertEquals(KeyStore.AuthOutcome.OK, auth.outcome(), "the created key authenticates through the seam");
        assertTrue(store.findByPrefix(parsed.prefix()).isPresent());
        assertEquals(1, store.list().size(), "key CRUD lists through the seam");

        // rate-limit counters: exactly the cap is admitted, then denied (Retry-After)
        for (int i = 0; i < 3; i++) {
            assertInstanceOf(
                    RateLimiter.RateLimitResult.Allowed.class,
                    store.tryAcquire(keyId, 3, 1),
                    "the first 3 requests fit the rpm cap of 3");
        }
        RateLimiter.RateLimitResult fourth = store.tryAcquire(keyId, 3, 1);
        assertInstanceOf(RateLimiter.RateLimitResult.Denied.class, fourth, "the 4th request is denied");
        assertTrue(
                ((RateLimiter.RateLimitResult.Denied) fourth).retryAfterSeconds() >= 0,
                "Retry-After is a whole number of seconds");

        // spendByKey: reserve → settle (the atomic flow through the seam; lifetime
        // window — windowSeconds = 0)
        SpendLedger.ReserveResult reserve = store.reserve(keyId, 300, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, reserve);
        store.settle(keyId, 300, 250, 0);
        assertEquals(250, store.spendByKey(keyId, 0), "settled spend is visible through the seam");
        assertEquals(250, store.totalSpendByKey(keyId), "the all-time view matches the lifetime window");
        store.recordSpend(keyId, 250);

        // call ledger: one record, per-key + global views
        CallRecord rec = record("req-1", keyId, clock().millis());
        store.recordCall(rec);
        assertEquals(List.of(rec), store.recentCalls(keyId, 10), "per-key recent view");
        assertEquals(List.of(rec), store.recentCalls(10), "global recent view");

        // revoke: the idempotent transition through the seam
        assertTrue(store.revoke(keyId));
        assertTrue(store.revoke(keyId), "revoke is idempotent for an existing id");
        assertFalse(store.revoke("unknown-id"), "unknown id ⇒ false");
        assertEquals(
                KeyStore.AuthOutcome.REVOKED,
                store.authenticate(parsed.prefix(), parsed.secret()).outcome(),
                "a revoked key authenticates to REVOKED (403), not OK");
    }

    @Test
    void keyCrudDelegationInvariants() {
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("o", null, null, null, null, null, null));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();

        assertEquals(
                KeyStore.AuthOutcome.INVALID,
                store.authenticate(parsed.prefix(), "wrong-secret").outcome(),
                "wrong secret ⇒ INVALID");
        assertEquals(
                KeyStore.AuthOutcome.INVALID,
                store.authenticate("unknown-prefix", "whatever").outcome(),
                "unknown prefix ⇒ INVALID");

        // list exposes redacted views only — the KeyRecordView type is structural
        List<KeyRecordView> views = store.list();
        assertEquals(1, views.size());
        assertNotNull(views.get(0).id());
        assertTrue(views.stream().allMatch(v -> v instanceof KeyRecordView));

        // touch is a no-op for unknown prefixes and never regresses lastUsedAt
        store.touch("unknown-prefix"); // must not throw
        store.touch(parsed.prefix());
        assertTrue(store.findByPrefix(parsed.prefix()).orElseThrow().lastUsedAt() != null);
        long lastUsed = store.findByPrefix(parsed.prefix()).orElseThrow().lastUsedAt();
        store.touch(parsed.prefix());
        assertEquals(lastUsed, store.findByPrefix(parsed.prefix()).orElseThrow().lastUsedAt(), "never regresses");
    }

    @Test
    void rateLimitDelegationInvariants() {
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("o", null, null, null, null, null, null));
        String keyId = created.record().id();

        // wouldExceed is non-consuming: the pre-check denies an over-cap estimate…
        assertFalse(store.wouldExceed(keyId, 10, 5), "5 ≤ 10 fits");
        assertTrue(store.wouldExceed(keyId, 10, 100), "5 + 100 > 10 would exceed");
        // …and the rpm counter still admits the full cap afterwards
        assertInstanceOf(
                RateLimiter.RateLimitResult.Allowed.class,
                store.tryAcquire(keyId, 10, 10),
                "the pre-checks consumed nothing");
        assertInstanceOf(
                RateLimiter.RateLimitResult.Denied.class, store.tryAcquire(keyId, 10, 1), "the cap is now exhausted");

        // accumulate consumes real tokens at finalize; the cap gates the next request
        KeyStore.CreatedKey tpmKey =
                store.create(new KeyStore.KeyCreateRequest("t", null, null, null, null, null, null));
        String tpmKeyId = tpmKey.record().id();
        store.accumulate(tpmKeyId, 10, 7);
        assertTrue(store.wouldExceed(tpmKeyId, 10, 4), "7 consumed + 4 estimate > 10 cap");
        assertFalse(store.wouldExceed(tpmKeyId, 10, 3), "7 consumed + 3 estimate = 10 cap fits");
    }

    @Test
    void accumulateReturnValueIsPinnedThroughTheSeam() {
        // pins ONE meaning for accumulate's return (the javadoc deferred the
        // choice to: the post-accumulation TPM counter value — the window total
        // (cumulative tokens consumed in the current window), matching the shipped
        // default FixedWindowRateLimiter. PostgresCallStore inherits this
        // assertion via the parity harness and must return the same quantity
        // (changing the meaning ⇒ change the seam javadoc and this assertion together).
        String keyId = "tpm-pin";
        assertEquals(30, store.accumulate(keyId, 100, 30), "fresh window: the window total equals actual");
        assertEquals(80, store.accumulate(keyId, 100, 50), "consume-at-finalize adds up (real spend)");
        assertTrue(store.wouldExceed(keyId, 100, 21), "80 consumed + 21 estimate > 100 cap");
        assertFalse(store.wouldExceed(keyId, 100, 20), "80 consumed + 20 estimate = 100 cap fits");
    }

    @Test
    void spendLedgerDelegationInvariants() {
        // settle corrects the reservation; release rolls it back
        SpendLedger.ReserveResult reserve = store.reserve("k1", 300, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, reserve);
        store.settle("k1", 300, 250, 0);
        assertEquals(250, store.spendByKey("k1", 0), "pending −= estimate, settled += actual");

        store.reserve("k2", 300, 1_000, 0.8, 0);
        store.release("k2", 300, 0);
        assertEquals(0, store.spendByKey("k2", 0), "released reservations never settle");
        store.release("k2", 300, 0); // double release is a harmless no-op

        assertEquals(0, store.spendByKey("unknown", 0), "unknown key ⇒ zero spend");
        assertEquals(0, store.totalSpendByKey("unknown"), "unknown key ⇒ zero all-time spend");
        assertTrue(store.recent("unknown", 10).isEmpty(), "unknown key ⇒ empty recent view");
    }

    @Test
    void callLedgerAndLedgerRingAreDistinctContracts() {
        // keeps two rings with distinct contracts (no semantic change to /):
        // recordCall feeds the CallRecord ring only…
        store.recordCall(record("req-1", "k1", clock().millis()));
        assertEquals(1, store.recentCalls("k1", 10).size(), "the call ring sees the record");
        assertEquals(0, store.spendByKey("k1", 0), "recordCall never touches settled spend");
        assertTrue(store.recent("k1", 10).isEmpty(), "recordCall never writes SpendLedger entries");
        // …and recordSpend feeds the LedgerEntry ring only
        store.recordSpend("k2", 42);
        assertEquals(1, store.recent("k2", 10).size(), "the ledger ring sees the spend");
        assertTrue(store.recentCalls("k2", 10).isEmpty(), "recordSpend never writes CallRecord entries");
        assertEquals(1, store.recentCalls(10).size(), "the global call view holds only the recordCall record");
        assertEquals("req-1", store.recentCalls(10).get(0).requestId(), "recordSpend added no CallRecord");
    }

    @Test
    void ringEvictsOldestAndBumpsDropped() {
        String key = "k";
        int r = ringRetention();
        CallRecord[] records = new CallRecord[r + 1];
        for (int i = 0; i <= r; i++) {
            clock().advanceMillis(1); // strictly increasing timestamps pin the order
            records[i] = record("r" + i, key, clock().millis());
            store.recordCall(records[i]);
        }
        List<CallRecord> recent = store.recentCalls(key, 100);
        assertEquals(r, recent.size(), "the ring holds exactly retention entries");
        assertEquals(records[r], recent.get(0), "newest first");
        assertEquals(records[1], recent.get(r - 1), "the oldest surviving entry is r1");
        assertEquals(1, store.dropped(), "the overflow (r0) was dropped, counted, not lost");
        assertEquals(recent, store.recentCalls(100), "the global view mirrors the per-key view for one key");
        assertTrue(
                recent.stream().noneMatch(c -> c.requestId().equals("r0")),
                "the evicted oldest record is no longer observable");
    }

    @Test
    void perKeyRingOrdersAndEvictsByTimestampThenSeqUnderOutOfOrderWriters() {
        // Out-of-order writer timestamps (concurrent finalization, clock skew across
        // nodes): the per-key ring orders and evicts by (atEpochMillis, seq) — the
        // same comparator as the global view and the Postgres
        // (at_epoch_millis DESC, seq DESC) view/prune — never by ring insertion
        // order. An insertion-order ring would keep the last-inserted record no
        // matter how old its timestamp; the parity contract drops the
        // timestamp-oldest record and counts it in dropped.
        String key = "k";
        int r = ringRetention();
        long t = START.toEpochMilli();
        for (int i = 1; i <= r; i++) {
            store.recordCall(record("r" + i, key, t + i)); // fill the ring, increasing timestamps
        }
        // The next record is inserted LAST but carries the OLDEST timestamp: it is
        // the (at, seq)-minimum, so it is evicted immediately (the Postgres prune
        // parity) — an insertion-order ring would have kept it and dropped r1.
        store.recordCall(record("late-old", key, t));
        List<CallRecord> recent = store.recentCalls(key, 100);
        assertEquals(r, recent.size(), "the ring still holds exactly retention entries");
        assertEquals("r" + r, recent.get(0).requestId(), "the timestamp-newest record leads the per-key view");
        assertEquals(
                "r1", recent.get(r - 1).requestId(), "r1 survives: the late-arriving older record dropped, not it");
        assertTrue(
                recent.stream().noneMatch(c -> c.requestId().equals("late-old")),
                "the timestamp-oldest record is evicted even though it was inserted last");
        assertEquals(1, store.dropped(), "the immediate eviction is counted, not lost");

        // A later record with a NEWER timestamp slots in at the head — ordered by
        // timestamp — and evicts the now-oldest survivor (r1).
        store.recordCall(record("late-new", key, t + 1_000));
        List<CallRecord> after = store.recentCalls(key, 100);
        assertEquals(r, after.size(), "the ring stays bounded");
        assertEquals("late-new", after.get(0).requestId(), "ordered by timestamp, newest first");
        assertEquals("r2", after.get(r - 1).requestId(), "r1 (now the timestamp-oldest) is the one evicted");
        assertEquals(2, store.dropped(), "every overflow is counted exactly once");
        assertEquals(after, store.recentCalls(100), "the global view mirrors the per-key view for one key");
    }

    @Test
    void recentCallsClampN() {
        store.recordCall(record("r1", "k1", clock().millis()));
        store.recordCall(record("r2", "k1", clock().millis()));
        assertEquals(1, store.recentCalls("k1", 1).size(), "n clamps to the ring");
        assertEquals("r2", store.recentCalls("k1", 1).get(0).requestId(), "newest first");
        assertTrue(store.recentCalls("k1", 0).isEmpty(), "n ≤ 0 ⇒ empty");
        assertTrue(store.recentCalls(0).isEmpty(), "global n ≤ 0 ⇒ empty");
        assertTrue(store.recentCalls("unknown", 10).isEmpty(), "unknown key ⇒ empty");
    }

    @Test
    void globalRecentCallsNewestFirstAcrossKeys() {
        clock().advanceMillis(1);
        store.recordCall(record("a1", "a", clock().millis()));
        clock().advanceMillis(1);
        store.recordCall(record("b1", "b", clock().millis()));
        clock().advanceMillis(1);
        store.recordCall(record("a2", "a", clock().millis()));

        List<CallRecord> global = store.recentCalls(10);
        assertEquals(
                List.of("a2", "b1", "a1"),
                global.stream().map(CallRecord::requestId).toList(),
                "global view is newest-first across keys");

        // per-key views stay isolated
        List<CallRecord> a = store.recentCalls("a", 10);
        assertEquals(List.of("a2", "a1"), a.stream().map(CallRecord::requestId).toList());
        assertEquals(
                List.of("b1"),
                store.recentCalls("b", 10).stream().map(CallRecord::requestId).toList());
    }

    @Test
    void authOffRecordsRingUnderSentinelView() {
        clock().advanceMillis(1);
        CallRecord authOff = record("anon-1", null, clock().millis());
        store.recordCall(authOff);
        clock().advanceMillis(1);
        CallRecord keyed = record("keyed-1", "k1", clock().millis());
        store.recordCall(keyed);

        assertEquals(List.of(authOff), store.recentCalls((String) null, 10), "null keyId → the sentinel ring");
        assertEquals(List.of(authOff), store.recentCalls("", 10), "empty string is the same sentinel ring");
        assertEquals(List.of(keyed, authOff), store.recentCalls(10), "the global view sees both, newest first");
        assertTrue(
                store.recentCalls("k1", 10).stream().noneMatch(c -> c.keyId() == null),
                "a real key's ring never mixes in auth-off records");
    }

    @Test
    void callRecordsCarryNoSecretMaterial() {
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("o", null, null, null, null, null, null));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();
        String keyId = created.record().id();
        store.recordCall(record("req-1", keyId, clock().millis()));

        CallRecord call = store.recentCalls(keyId, 10).get(0);
        assertEquals(keyId, call.keyId(), "records reference the non-secret key id");
        assertFalse(call.toString().contains(parsed.secret()), "no secret material in the call view");
        assertFalse(call.toString().contains(created.fullKey()), "no full key material in the call view");
        assertNotEquals(parsed.prefix(), call.keyId(), "the ring is keyed by id, never the auth prefix");
    }

    @Test
    void concurrentRecordCallsNeverLoseRecords() throws Exception {
        int threads = 8;
        int perThread = 25;
        int total = threads * perThread;
        AtomicInteger sequence = new AtomicInteger();
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
                    for (int i = 0; i < perThread; i++) {
                        store.recordCall(record("req-" + sequence.incrementAndGet(), "k", clock().millis()));
                    }
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "all recordCalls must finish");
        } finally {
            pool.shutdownNow();
        }
        // retention bounds the ring; the overflow is counted, never lost
        assertEquals(ringRetention(), store.recentCalls("k", Integer.MAX_VALUE).size(), "the ring stays bounded");
        assertEquals(total - ringRetention(), store.dropped(), "every overflow is counted exactly once");
    }

    @Test
    void concurrentReserveNoOverspendThroughComposite() throws Exception {
        long cap = 1_000;
        long estimate = 300; // exactly 3 reservations fit; every other one must roll back
        int threads = 8;
        AtomicInteger allowed = new AtomicInteger();
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
                    if (store.reserve("k1", estimate, cap, 0.8, 0) instanceof SpendLedger.ReserveResult.Allowed) {
                        allowed.incrementAndGet();
                    }
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "reservations must finish");
        } finally {
            pool.shutdownNow();
        }
        // The atomic increment-then-check guarantee, re-asserted through the
        // composite seam: a concurrent burst can never overspend beyond one request.
        assertEquals(3, allowed.get(), "the 4th concurrent reservation must roll back");
    }

    @Test
    void windowedBudgetFlowsThroughTheSeam() {
        // The composite leg, inherited by both store suites (parity free): a key
        // created with a budget + budget_duration enforces per-window through the same
        // CallStore seam — rollover resets the cap, a straddled settle credits the
        // reservation's window, the budget view is window-scoped and the all-time view
        // accumulates.
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("w", null, null, 1_000.0, 3600L, null, null));
        String keyId = created.record().id();
        assertEquals(3600L, created.record().budgetDuration(), "budget_duration persists through the seam");

        SpendLedger.ReserveResult first = store.reserve(keyId, 300, 1_000, 0.8, 3600);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, first);
        long w1 = ((SpendLedger.ReserveResult.Allowed) first).windowStartEpochSeconds();
        store.settle(keyId, 300, 250, w1);
        assertEquals(250, store.spendByKey(keyId, 3600), "the windowed budget view reads the current window");
        assertEquals(250, store.totalSpendByKey(keyId), "the all-time view accumulates");

        // A deny inside the window, then the rollover resets: the same reservation is
        // allowed again in W+1 and the straddled settle credits W, not W+1.
        store.settle(keyId, 0, 550, w1); // W now holds 800 of the 1000 cap
        assertInstanceOf(
                SpendLedger.ReserveResult.Denied.class,
                store.reserve(keyId, 300, 1_000, 0.8, 3600),
                "800 + 300 ≥ 1000 inside the window → deny");
        clock().advanceSeconds(3601); // roll into W+1
        SpendLedger.ReserveResult second = store.reserve(keyId, 300, 1_000, 0.8, 3600);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, second, "the rollover reset the cap");
        assertEquals(0, ((SpendLedger.ReserveResult.Allowed) second).settledMicroUsd(), "W+1 starts settled = 0");
        store.settle(keyId, 300, 250, w1); // a straddled settle still credits W1
        assertEquals(0, store.spendByKey(keyId, 3600), "the budget view holds W+1 only (the straddle is invisible)");
        assertEquals(1_050, store.totalSpendByKey(keyId), "the all-time view holds every window's spend");
    }

    /** A Tier-1 record with fixed non-content fields and the given request id/key/timestamp. */
    protected static CallRecord record(String requestId, String keyId, long atEpochMillis) {
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
                atEpochMillis);
    }
}
