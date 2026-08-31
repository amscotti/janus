package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.core.model.UserMessage;
import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.CallStatus;
import io.amscotti.janus.store.CallStore;
import io.amscotti.janus.store.FixedWindowRateLimiter;
import io.amscotti.janus.store.InMemoryCallStore;
import io.amscotti.janus.store.InMemoryKeyStore;
import io.amscotti.janus.store.InMemorySpendLedger;
import io.amscotti.janus.store.KeyRecord;
import io.amscotti.janus.store.KeyRecordView;
import io.amscotti.janus.store.KeyStore;
import io.amscotti.janus.store.PriceTable;
import io.amscotti.janus.store.PricingRate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * stage 0 — the {@code janus_ledger_write_seconds} seam at the
 * {@link Governance} writer: {@code MetricsRecorder#recordLedgerWrite} fires
 * exactly once per {@code writeCallRecord} store write on <b>every</b> path —
 * finalize (OK), {@code recordFailure} (error rows), and the store-failure
 * contained path (a failed write's duration is precisely the pool-exhaustion
 * tail the timer exists to see) — and the timing itself never throws: a
 * recorder failure is dropped like a store failure, never observed by the
 * request path. The recorder override is an anonymous {@link MetricsRecorder}
 * (the interface's {@code recordLedgerWrite} default no-op keeps the four test
 * fakes untouched — only this seam test and {@link MicrometerMetricsRecorder}
 * mention the method).
 */
class LedgerWriteTimerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    private final InMemoryKeyStore keyStore = new InMemoryKeyStore(CLOCK);
    private final PriceTable priceTable = PriceTable.of(Map.of("deepseek-v4-flash", new PricingRate(0.14, 0.28, 4096)));

    /** {@link MetricsRecorder} double that counts {@code recordLedgerWrite} calls. */
    private static final class CountingLedgerWriteRecorder implements MetricsRecorder {

        private final AtomicInteger ledgerWrites = new AtomicInteger();

        @Override
        public void recordRequest(String face, int status, long durationMillis, String keyId) {}

        @Override
        public void recordUsage(String keyId, Usage usage, long costMicroUsd) {}

        @Override
        public void forgetKey(String keyId) {}

        @Override
        public void recordLedgerWrite(long durationNanos) {
            ledgerWrites.incrementAndGet();
        }
    }

    /** {@link MetricsRecorder} whose {@code recordLedgerWrite} always throws — the containment pin. */
    private static final class ThrowingLedgerWriteRecorder implements MetricsRecorder {

        @Override
        public void recordRequest(String face, int status, long durationMillis, String keyId) {}

        @Override
        public void recordUsage(String keyId, Usage usage, long costMicroUsd) {}

        @Override
        public void forgetKey(String keyId) {}

        @Override
        public void recordLedgerWrite(long durationNanos) {
            throw new IllegalStateException("registry unregistered");
        }
    }

    /**
     * A {@link CallStore} whose {@code recordCall} always throws; every other
     * operation delegates to a real in-memory store ({@code GovernanceWriterTest}'s
     * {@code ThrowingRecordCallStore} posture — the writer's containment pin).
     */
    private static final class ThrowingRecordCallStore implements CallStore {

        private final CallStore delegate = new InMemoryCallStore(CLOCK, 1000);

        @Override
        public void recordCall(CallRecord record) {
            throw new IllegalStateException("postgres is down");
        }

        @Override
        public long dropped() {
            return delegate.dropped();
        }

        @Override
        public List<CallRecord> recentCalls(String keyId, int n) {
            return delegate.recentCalls(keyId, n);
        }

        @Override
        public List<CallRecord> recentCalls(int n) {
            return delegate.recentCalls(n);
        }

        @Override
        public CreatedKey create(KeyCreateRequest request) {
            return delegate.create(request);
        }

        @Override
        public Optional<KeyRecord> findByPrefix(String prefix) {
            return delegate.findByPrefix(prefix);
        }

        @Override
        public boolean revoke(String id) {
            return delegate.revoke(id);
        }

        @Override
        public AuthResult authenticate(String prefix, String secret) {
            return delegate.authenticate(prefix, secret);
        }

        @Override
        public List<KeyRecordView> list() {
            return delegate.list();
        }

        @Override
        public void touch(String prefix) {
            delegate.touch(prefix);
        }

        @Override
        public RateLimitResult tryAcquire(String keyId, int limit, long cost) {
            return delegate.tryAcquire(keyId, limit, cost);
        }

        @Override
        public boolean wouldExceed(String keyId, int limit, long estimate) {
            return delegate.wouldExceed(keyId, limit, estimate);
        }

        @Override
        public long accumulate(String keyId, int limit, long actual) {
            return delegate.accumulate(keyId, limit, actual);
        }

        @Override
        public long spendByKey(String keyId, long windowSeconds) {
            return delegate.spendByKey(keyId, windowSeconds);
        }

        @Override
        public long totalSpendByKey(String keyId) {
            return delegate.totalSpendByKey(keyId);
        }

        @Override
        public List<LedgerEntry> recent(String keyId, int n) {
            return delegate.recent(keyId, n);
        }

        @Override
        public ReserveResult reserve(
                String keyId, long estimateMicroUsd, long hardCapMicroUsd, double softFraction, long windowSeconds) {
            return delegate.reserve(keyId, estimateMicroUsd, hardCapMicroUsd, softFraction, windowSeconds);
        }

        @Override
        public void settle(String keyId, long estimateMicroUsd, long actualMicroUsd, long reservationWindowStart) {
            delegate.settle(keyId, estimateMicroUsd, actualMicroUsd, reservationWindowStart);
        }

        @Override
        public void release(String keyId, long estimateMicroUsd, long reservationWindowStart) {
            delegate.release(keyId, estimateMicroUsd, reservationWindowStart);
        }

        @Override
        public void recordSpend(String keyId, long amountMicroUsd) {
            delegate.recordSpend(keyId, amountMicroUsd);
        }
    }

    @Test
    void finalizeFiresTheLedgerWriteTimerOncePerCallRecord() {
        CountingLedgerWriteRecorder recorder = new CountingLedgerWriteRecorder();
        InMemoryCallStore callStore = new InMemoryCallStore(CLOCK, 1000);
        Governance governance = governance(recorder, callStore);

        governance.finalize(null, request(), response(), preflight(), 12, "deepseek");

        assertEquals(1, callStore.recentCalls(10).size(), "the OK row landed");
        assertEquals(1, recorder.ledgerWrites.get(), "exactly one timer record per call-record write");
    }

    @Test
    void recordFailureFiresTheLedgerWriteTimerOncePerCallRecord() {
        CountingLedgerWriteRecorder recorder = new CountingLedgerWriteRecorder();
        InMemoryCallStore callStore = new InMemoryCallStore(CLOCK, 1000);
        Governance governance = governance(recorder, callStore);

        governance.recordFailure(null, Governance.Preflight.NONE, request(), 429, 10, null);

        assertEquals(1, callStore.recentCalls(10).size(), "the ERROR_LIMIT row landed");
        assertEquals(CallStatus.ERROR_LIMIT, callStore.recentCalls(10).get(0).status());
        assertEquals(1, recorder.ledgerWrites.get(), "error rows time their store write too");
    }

    @Test
    void aFailingStoreWriteStillFiresTheTimerOnceAndNeverThrows() {
        // The store-failure contained path: the write is dropped (log-and-drop,
        // the writer's containment contract) but its DURATION still fires the
        // timer exactly once — a Hikari pool-exhaustion timeout or an advisory-lock
        // stall is precisely the tail this timer exists to measure.
        CountingLedgerWriteRecorder recorder = new CountingLedgerWriteRecorder();
        Governance failingWriter = governance(recorder, new ThrowingRecordCallStore());

        assertDoesNotThrow(
                () -> failingWriter.recordFailure(null, Governance.Preflight.NONE, request(), 429, 10, null),
                "a throwing store must stay contained (the existing writer pin)");
        assertDoesNotThrow(
                () -> failingWriter.finalize(null, request(), response(), preflight(), 12, "deepseek"),
                "a throwing store must stay contained on finalize too");
        assertEquals(2, recorder.ledgerWrites.get(), "one timer record per write ATTEMPT — failures included");
    }

    @Test
    void aThrowingTimerRecordingIsContainedLikeAStoreFailure() {
        // The timing itself must never throw: recordLedgerWrite is guarded by
        // the same best-effort posture as the store write, so a recorder failure
        // (e.g. a registry unregistered mid-flight) is logged and dropped — the
        // request path and the ledger row are unaffected.
        InMemoryCallStore callStore = new InMemoryCallStore(CLOCK, 1000);
        Governance governance = governance(new ThrowingLedgerWriteRecorder(), callStore);

        Governance.Finalized finalized =
                assertDoesNotThrow(() -> governance.finalize(null, request(), response(), preflight(), 12, "deepseek"));
        assertEquals(1, callStore.recentCalls(10).size(), "the OK row lands regardless of the timer's failure");
        assertEquals(5320, callStore.recentCalls(10).get(0).costMicroUsd());
        assertEquals(false, finalized.softExceeded());
    }

    // ------------------------------------------------------------------ helpers

    private Governance governance(MetricsRecorder recorder, CallStore callStore) {
        return new Governance(
                new FixedWindowRateLimiter(CLOCK),
                priceTable,
                new InMemorySpendLedger(CLOCK, 1000),
                new LoggingNotifier(),
                0.8,
                CLOCK,
                recorder,
                callStore);
    }

    private Governance.Preflight preflight() {
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("timer", List.of("deepseek-v4-flash"), null, null, null, null, null));
        return new Governance.Preflight(
                created.record(), priceTable.rateFor("deepseek-v4-flash"), 1000, 5320, false, false, 0);
    }

    private static ChatRequest request() {
        return new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                false,
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                Map.of());
    }

    private static ChatResponse response() {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(14, 12, 26),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }
}
