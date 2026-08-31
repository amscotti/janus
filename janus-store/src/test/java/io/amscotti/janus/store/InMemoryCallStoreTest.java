package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link InMemoryCallStore}. Runs the full {@link
 * AbstractCallStoreContractTest} parity suite (the
 * {@code PostgresCallStoreTest} will extend unchanged) with a small retention of 2
 * to pin eviction, plus the class-specific pins: ring eviction order (oldest evicted
 * first, newest-first views), retention = 1 boundary, {@code dropped} increments
 * on overflow (drop-only semantics), per-key isolation, the {@code keyId = ""}
 * sentinel view for auth-off records, clock determinism, the global view bounded by
 * retention, and <b>delegation identity</b> — the embedded {@link InMemoryKeyStore}/
 * {@link RateLimiter}/{@link InMemorySpendLedger} behave identically standalone vs
 * through the composite (same results, same thread-safety).
 */
class InMemoryCallStoreTest extends AbstractCallStoreContractTest {

    @Override
    protected CallStore newStore(MutableClock clock) {
        return new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));
    }

    @Override
    protected int ringRetention() {
        return 2;
    }

    @Test
    void ringEvictsOldestFirstWithNewestFirstViews() {
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore store = new InMemoryCallStore(clock, 3, new FixedWindowRateLimiter(clock));
        for (int i = 1; i <= 4; i++) {
            store.recordCall(AbstractCallStoreContractTest.record("r" + i, "k", START.toEpochMilli() + i));
        }
        List<CallRecord> recent = store.recentCalls("k", 10);
        assertEquals(
                List.of("r4", "r3", "r2"),
                recent.stream().map(CallRecord::requestId).toList(),
                "newest first, oldest (r1) evicted");
        assertEquals(1, store.dropped(), "one overflow, one drop");
    }

    @Test
    void retentionOneBoundaryKeepsOnlyTheNewest() {
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore tiny = new InMemoryCallStore(clock, 1, new FixedWindowRateLimiter(clock));
        for (int i = 1; i <= 3; i++) {
            tiny.recordCall(AbstractCallStoreContractTest.record("r" + i, "k", START.toEpochMilli() + i));
        }
        assertEquals(
                List.of("r3"),
                tiny.recentCalls("k", 10).stream().map(CallRecord::requestId).toList(),
                "retention 1 keeps exactly the newest record");
        assertEquals(2, tiny.dropped(), "every overflow is counted");
        assertEquals(1, tiny.recentCalls(10).size(), "the global view is bounded by retention too");
    }

    @Test
    void droppedIsGlobalAndMonotonicAcrossKeys() {
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore store = new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));
        for (int i = 0; i < 3; i++) {
            store.recordCall(AbstractCallStoreContractTest.record("a" + i, "a", START.toEpochMilli() + i));
            store.recordCall(AbstractCallStoreContractTest.record("b" + i, "b", START.toEpochMilli() + i));
        }
        assertEquals(2, store.dropped(), "one drop per key ring overflow (3 recorded, 2 retained each)");
        assertTrue(store.dropped() >= 0, "monotonic non-negative");
    }

    @Test
    void perKeyRingsAreIsolated() {
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore store = new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));
        store.recordCall(AbstractCallStoreContractTest.record("a1", "a", 1));
        store.recordCall(AbstractCallStoreContractTest.record("b1", "b", 2));
        assertEquals(
                List.of("a1"),
                store.recentCalls("a", 10).stream().map(CallRecord::requestId).toList());
        assertEquals(
                List.of("b1"),
                store.recentCalls("b", 10).stream().map(CallRecord::requestId).toList());
        assertTrue(store.recentCalls("c", 10).isEmpty(), "a never-touched key has no ring");
    }

    @Test
    void authOffRecordsUseTheSentinelView() {
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore store = new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));
        store.recordCall(AbstractCallStoreContractTest.record("anon-1", null, 1));
        assertEquals(1, store.recentCalls("", 10).size(), "the sentinel ring holds auth-off records");
        assertEquals(1, store.recentCalls((String) null, 10).size(), "null keyId addresses the sentinel ring");
        assertTrue(store.recentCalls("k", 10).isEmpty(), "real keys never share the sentinel ring");
    }

    @Test
    void clockDeterminism() {
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore store = new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));
        clock.advanceMillis(7);
        store.recordCall(AbstractCallStoreContractTest.record("r1", "k", clock.millis()));
        clock.advanceMillis(3);
        store.recordCall(AbstractCallStoreContractTest.record("r2", "k", clock.millis()));

        List<CallRecord> recent = store.recentCalls("k", 10);
        assertEquals(clock.millis(), recent.get(0).atEpochMillis(), "records carry the store clock's timestamps");
        assertEquals(START.toEpochMilli() + 7, recent.get(1).atEpochMillis(), "older record keeps its timestamp");
        assertEquals("r2", recent.get(0).requestId(), "newest first follows the clock");
    }

    @Test
    void globalRecentCallsBoundedByRetention() {
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore store = new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));
        for (int i = 0; i < 5; i++) {
            store.recordCall(AbstractCallStoreContractTest.record("r" + i, "k", START.toEpochMilli() + i));
        }
        assertEquals(2, store.recentCalls(100).size(), "the global view never exceeds the ring retention for one key");
        assertEquals(1, store.recentCalls(1).size(), "n still clamps the global view");
    }

    @Test
    void globalViewTieOrderIsStableByInsertionAcrossSameMillisecondKeys() {
        // The in-memory global view must pin the Postgres (at_epoch_millis DESC,
        // seq DESC) tie-break — same-millisecond records across keys order by insertion,
        // never by HashMap iteration order (which is unstable across mutations).
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore store = new InMemoryCallStore(clock, 10, new FixedWindowRateLimiter(clock));
        long sameMillis = START.toEpochMilli();
        // Interleave two keys at the same millisecond: a1, b1, a2, b2, a3.
        store.recordCall(AbstractCallStoreContractTest.record("a1", "a", sameMillis));
        store.recordCall(AbstractCallStoreContractTest.record("b1", "b", sameMillis));
        store.recordCall(AbstractCallStoreContractTest.record("a2", "a", sameMillis));
        store.recordCall(AbstractCallStoreContractTest.record("b2", "b", sameMillis));
        store.recordCall(AbstractCallStoreContractTest.record("a3", "a", sameMillis));

        assertEquals(
                List.of("a3", "b2", "a2", "b1", "a1"),
                store.recentCalls(10).stream().map(CallRecord::requestId).toList(),
                "same-millisecond global ties order by insertion (seq), newest last-inserted first");
    }

    @Test
    void constructorRejectsNonPositiveRetention() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryCallStore(new MutableClock(START), 0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryCallStore(new MutableClock(START), -1));
    }

    @Test
    void constructorRejectsNonPositiveLedgerRetention() {
        // The ledger ring is its own knob — it must fail at boot exactly like the call
        // ring's retention (a misconfigured ring is never silently accepted).
        MutableClock clock = new MutableClock(START);
        assertThrows(
                IllegalArgumentException.class,
                () -> new InMemoryCallStore(clock, 5, 0, new FixedWindowRateLimiter(clock)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InMemoryCallStore(clock, 5, -1, new FixedWindowRateLimiter(clock)));
    }

    @Test
    void ledgerRetentionIsIndependentOfTheCallRingRetention() {
        // The full constructor takes the two ring retentions separately: [janus.store]
        // retention sizes the call ring, [janus.limits] ledger-retention the
        // spend-ledger ring. Pin that each knob drives only its own ring.
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore store = new InMemoryCallStore(clock, 5, 1, new FixedWindowRateLimiter(clock));

        store.recordSpend("k", 10);
        store.recordSpend("k", 20);
        store.recordSpend("k", 30);
        assertEquals(1, store.recent("k", 10).size(), "the spend ring is bounded by the LEDGER retention (1)");
        assertEquals(30, store.recent("k", 10).get(0).microUsd(), "newest first");

        for (int i = 1; i <= 3; i++) {
            store.recordCall(AbstractCallStoreContractTest.record("r" + i, "k", START.toEpochMilli() + i));
        }
        assertEquals(
                3,
                store.recentCalls("k", 10).size(),
                "the call ring is bounded by the CALL retention (5), unaffected by the ledger knob");
        assertEquals(0, store.dropped(), "no call-ring overflow at 3 of 5");
    }

    @Test
    void singleRetentionConvenienceConstructorSharesTheValueAcrossBothRings() {
        // Backwards-compatible shape: the 3-arg constructor keeps one retention for
        // both rings (the pre-separation behavior every existing call site relies on).
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore store = new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));

        store.recordSpend("k", 10);
        store.recordSpend("k", 20);
        store.recordSpend("k", 30);
        assertEquals(2, store.recent("k", 10).size(), "the spend ring shares the call ring's retention");
    }

    @Test
    void delegationIdentityKeyStoreMatchesStandalone() {
        MutableClock clock = new MutableClock(START);
        InMemoryKeyStore standalone = new InMemoryKeyStore(clock);
        InMemoryCallStore composite = new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));

        KeyStore.CreatedKey standaloneKey =
                standalone.create(new KeyStore.KeyCreateRequest("o", null, null, null, null, null, null));
        KeyStore.CreatedKey compositeKey =
                composite.create(new KeyStore.KeyCreateRequest("o", null, null, null, null, null, null));
        KeyGenerator.Parsed standaloneParsed =
                KeyGenerator.parse(standaloneKey.fullKey()).orElseThrow();
        KeyGenerator.Parsed compositeParsed =
                KeyGenerator.parse(compositeKey.fullKey()).orElseThrow();

        assertEquals(
                standalone
                        .authenticate(standaloneParsed.prefix(), standaloneParsed.secret())
                        .outcome(),
                composite
                        .authenticate(compositeParsed.prefix(), compositeParsed.secret())
                        .outcome(),
                "authenticate behaves identically standalone vs through the composite");
        assertEquals(
                standalone.revoke(standaloneKey.record().id()),
                composite.revoke(compositeKey.record().id()),
                "revoke behaves identically");
        assertEquals(1, composite.list().size(), "the composite's key CRUD is the embedded store's");
    }

    @Test
    void delegationIdentityRateLimiterMatchesStandalone() {
        MutableClock clock = new MutableClock(START);
        FixedWindowRateLimiter standalone = new FixedWindowRateLimiter(clock);
        InMemoryCallStore composite = new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));

        for (int i = 0; i < 3; i++) {
            assertEquals(
                    standalone.tryAcquire("k", 3, 1),
                    composite.tryAcquire("k", 3, 1),
                    "tryAcquire results match step for step");
        }
        RateLimiter.RateLimitResult standaloneDenied = standalone.tryAcquire("k", 3, 1);
        RateLimiter.RateLimitResult compositeDenied = composite.tryAcquire("k", 3, 1);
        assertEquals(standaloneDenied, compositeDenied, "denials match too");
        assertEquals(standalone.wouldExceed("k", 10, 100), composite.wouldExceed("k", 10, 100), "wouldExceed matches");
        assertEquals(standalone.accumulate("t", 10, 7), composite.accumulate("t", 10, 7), "accumulate matches");
    }

    @Test
    void delegationIdentityTokenBucketRateLimiterMatchesStandalone() {
        // The second supported variant must be exercised through the composite too —
        // same step-for-step sequence standalone vs through InMemoryCallStore, as done
        // for FixedWindow above (each instance owns its own bucket state; equality pins
        // the forwarding wiring, not the variant's own semantics — those stay the
        // suite's spec).
        MutableClock clock = new MutableClock(START);
        TokenBucketRateLimiter standalone = new TokenBucketRateLimiter(clock);
        InMemoryCallStore composite = new InMemoryCallStore(clock, 2, new TokenBucketRateLimiter(clock));

        for (int i = 0; i < 3; i++) {
            assertEquals(
                    standalone.tryAcquire("k", 3, 1),
                    composite.tryAcquire("k", 3, 1),
                    "token-bucket tryAcquire results match step for step");
        }
        RateLimiter.RateLimitResult standaloneDenied = standalone.tryAcquire("k", 3, 1);
        RateLimiter.RateLimitResult compositeDenied = composite.tryAcquire("k", 3, 1);
        assertEquals(standaloneDenied, compositeDenied, "token-bucket denials match too");
        assertEquals(
                standalone.wouldExceed("k", 10, 100),
                composite.wouldExceed("k", 10, 100),
                "token-bucket wouldExceed matches");
        assertEquals(
                standalone.accumulate("t", 10, 7), composite.accumulate("t", 10, 7), "token-bucket accumulate matches");
        assertEquals(
                standalone.accumulate("t", 10, 5),
                composite.accumulate("t", 10, 5),
                "token-bucket accumulate keeps matching after repeated settles");
    }

    @Test
    void slidingVariantAccumulateMatchesTheWindowTotalThroughTheSeam() {
        // The accumulate-return divergence is RESOLVED — both variants
        // return the window total (net tokens consumed), so the same sequence through
        // the SAME CallStore seam yields the same quantity under either
        // [janus.limits] window selection (previously sliding returned tokens-remaining
        // — 70 for actual 30 of 100 — silently diverging from the pinned contract).
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore sliding = new InMemoryCallStore(clock, 2, new TokenBucketRateLimiter(clock));
        InMemoryCallStore fixed = new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));
        assertEquals(30, sliding.accumulate("k", 100, 30), "sliding seam: the window total (30 consumed)");
        assertEquals(30, fixed.accumulate("k", 100, 30), "fixed seam: the same window total");
        assertEquals(70, sliding.accumulate("k", 100, 40), "a second settle: 30 + 40 = 70 consumed");
        assertEquals(70, fixed.accumulate("k", 100, 40), "fixed adds up to the same 70");
    }

    @Test
    void accumulateReturnsTheSameQuantityAcrossWindowSelectionForASequence() {
        // The contract-level pin: a shared sequence through
        // the CallStore seam returns IDENTICAL values on both the "fixed" and "sliding"
        // composite stores — the one-meaning contract, enforced not just for single
        // settles but for an interleaved real-spend sequence (no refill on the fixed
        // clock, so the bucket's window total tracks the fixed window's count).
        MutableClock clock = new MutableClock(START);
        InMemoryCallStore fixed = new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));
        InMemoryCallStore sliding = new InMemoryCallStore(clock, 2, new TokenBucketRateLimiter(clock));
        long[] actuals = {30, 50, 80, 5};
        for (long actual : actuals) {
            assertEquals(
                    fixed.accumulate("k", 100, actual),
                    sliding.accumulate("k", 100, actual),
                    "accumulate(actual=" + actual + ") returns the same window total on both window modes");
        }
    }

    @Test
    void delegationIdentitySpendLedgerMatchesStandalone() {
        MutableClock clock = new MutableClock(START);
        InMemorySpendLedger standalone = new InMemorySpendLedger(clock, 2);
        InMemoryCallStore composite = new InMemoryCallStore(clock, 2, new FixedWindowRateLimiter(clock));

        assertEquals(
                standalone.reserve("k", 300, 1_000, 0.8, 0),
                composite.reserve("k", 300, 1_000, 0.8, 0),
                "reserve matches");
        standalone.settle("k", 300, 250, 0);
        composite.settle("k", 300, 250, 0);
        assertEquals(standalone.spendByKey("k", 0), composite.spendByKey("k", 0), "settled spend matches");
        assertEquals(standalone.totalSpendByKey("k"), composite.totalSpendByKey("k"), "all-time spend matches");
        assertEquals(standalone.recent("k", 10), composite.recent("k", 10), "ledger recent views match");
        standalone.recordSpend("k", 42);
        composite.recordSpend("k", 42);
        assertEquals(standalone.recent("k", 10), composite.recent("k", 10), "recordSpend rings match");
        standalone.release("k", 300, 0);
        composite.release("k", 300, 0);
        assertEquals(
                standalone.spendByKey("k", 0), composite.spendByKey("k", 0), "release leaves spend unchanged in both");
        assertTrue(
                composite.recentCalls("k", 10).isEmpty(),
                "ledger operations never write the call ring (distinct contracts)");
    }
}
