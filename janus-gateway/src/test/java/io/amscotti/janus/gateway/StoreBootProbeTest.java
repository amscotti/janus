package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.InMemoryCallStore;
import io.amscotti.janus.store.KeyRecord;
import io.amscotti.janus.store.KeyStore;
import io.amscotti.janus.store.RateLimiter;
import io.amscotti.janus.store.SpendLedger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * {@link StoreBootProbe}: the fail-fast-at-boot listener probes the store
 * on {@code ServerStartupEvent}. A working store answers the probe (an unknown-key
 * spend read = 0, no record); a store whose {@code spendByKey} throws (Postgres
 * unreachable) propagates the failure so the node refuses to boot.
 */
class StoreBootProbeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void probeAgainstWorkingStoreIsAReadOnlyNoOp() {
        InMemoryCallStore store = new InMemoryCallStore(CLOCK, 100);
        StoreBootProbe probe = new StoreBootProbe(store);

        probe.onApplicationEvent(null); // the event object is unused by the probe

        assertEquals(0, store.recentCalls(10).size(), "the probe must not write a CallRecord");
        assertEquals(0, store.spendByKey("__janus_boot_probe__", 0), "unknown-key spend read is 0");
    }

    @Test
    void probeAgainstFailingStorePropagatesTheFailure() {
        InMemoryCallStore store = new InMemoryCallStore(CLOCK, 100);
        AtomicBoolean fail = new AtomicBoolean(true);
        io.amscotti.janus.store.CallStore failing = new io.amscotti.janus.store.CallStore() {
            @Override
            public long spendByKey(String keyId, long windowSeconds) {
                if (fail.get()) {
                    throw new IllegalStateException("Postgres spend ledger spendByKey failed");
                }
                return store.spendByKey(keyId, windowSeconds);
            }

            @Override
            public long totalSpendByKey(String keyId) {
                return store.totalSpendByKey(keyId);
            }

            @Override
            public void recordCall(CallRecord record) {
                store.recordCall(record);
            }

            @Override
            public java.util.List<CallRecord> recentCalls(String keyId, int limit) {
                return store.recentCalls(keyId, limit);
            }

            @Override
            public java.util.List<CallRecord> recentCalls(int limit) {
                return store.recentCalls(limit);
            }

            @Override
            public long dropped() {
                return store.dropped();
            }

            @Override
            public KeyStore.CreatedKey create(KeyStore.KeyCreateRequest request) {
                return store.create(request);
            }

            @Override
            public java.util.Optional<KeyRecord> findByPrefix(String prefix) {
                return store.findByPrefix(prefix);
            }

            @Override
            public KeyStore.AuthResult authenticate(String prefix, String secret) {
                return store.authenticate(prefix, secret);
            }

            @Override
            public boolean revoke(String id) {
                return store.revoke(id);
            }

            @Override
            public java.util.List<io.amscotti.janus.store.KeyRecordView> list() {
                return store.list();
            }

            @Override
            public void touch(String prefix) {
                store.touch(prefix);
            }

            @Override
            public RateLimiter.RateLimitResult tryAcquire(String keyId, int limit, long cost) {
                return store.tryAcquire(keyId, limit, cost);
            }

            @Override
            public boolean wouldExceed(String keyId, int limit, long estimate) {
                return store.wouldExceed(keyId, limit, estimate);
            }

            @Override
            public long accumulate(String keyId, int limit, long amount) {
                return store.accumulate(keyId, limit, amount);
            }

            @Override
            public SpendLedger.ReserveResult reserve(
                    String keyId,
                    long estimateMicroUsd,
                    long hardCapMicroUsd,
                    double softFraction,
                    long windowSeconds) {
                return store.reserve(keyId, estimateMicroUsd, hardCapMicroUsd, softFraction, windowSeconds);
            }

            @Override
            public void settle(String keyId, long estimateMicroUsd, long actualMicroUsd, long reservationWindowStart) {
                store.settle(keyId, estimateMicroUsd, actualMicroUsd, reservationWindowStart);
            }

            @Override
            public void release(String keyId, long estimateMicroUsd, long reservationWindowStart) {
                store.release(keyId, estimateMicroUsd, reservationWindowStart);
            }

            @Override
            public void recordSpend(String keyId, long amountMicroUsd) {
                store.recordSpend(keyId, amountMicroUsd);
            }

            @Override
            public java.util.List<SpendLedger.LedgerEntry> recent(String keyId, int n) {
                return store.recent(keyId, n);
            }
        };
        StoreBootProbe probe = new StoreBootProbe(failing);

        assertThrows(
                IllegalStateException.class,
                () -> probe.onApplicationEvent(null),
                "a store failure at boot must propagate (the node refuses to start)");
        fail.set(false);
        probe.onApplicationEvent(null); // a recovered store boots fine
    }
}
