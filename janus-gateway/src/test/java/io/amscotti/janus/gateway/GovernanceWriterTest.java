package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.core.model.UserMessage;
import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.CallStatus;
import io.amscotti.janus.store.CallStore;
import io.amscotti.janus.store.InMemoryCallStore;
import io.amscotti.janus.store.InMemoryKeyStore;
import io.amscotti.janus.store.KeyRecord;
import io.amscotti.janus.store.KeyRecordView;
import io.amscotti.janus.store.KeyStore;
import io.amscotti.janus.store.PriceTable;
import io.amscotti.janus.store.PricingRate;
import io.amscotti.janus.store.RateLimiter;
import io.amscotti.janus.store.SpendLedger;
import io.micronaut.http.HttpRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link Governance} {@code recordCall} writer (the
 * tests): finalize writes exactly one Tier-1 {@link CallRecord} with the mapped
 * fields (the threaded dispatched provider rides every record — null when nothing
 * was dispatched); the stream-settle paths write one each (zero-tokens on no usage
 * chunk); failure statuses map from the coarse HTTP class; auth-off records a null
 * keyId; no record duplication across the mutually-exclusive paths; a throwing
 * usage recorder leaves no OK row behind (the OK write is the LAST side effect on
 * every settle path).
 */
class GovernanceWriterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    private InMemoryCallStore callStore;
    private InMemoryKeyStore keyStore;
    private Governance governance;
    private PriceTable priceTable;

    @BeforeEach
    void setUp() {
        callStore = new InMemoryCallStore(CLOCK, 1000);
        keyStore = new InMemoryKeyStore(CLOCK);
        priceTable = PriceTable.of(Map.of("deepseek-v4-flash", new PricingRate(0.14, 0.28, 4096)));
        governance = new Governance(
                allowAllLimiter(),
                priceTable,
                recordingLedger(),
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                callStore);
    }

    /** The allow-all limiter the writer tests use (no 429s interfere with the writer). */
    private static RateLimiter allowAllLimiter() {
        return new RateLimiter() {
            @Override
            public RateLimitResult tryAcquire(String keyId, int limit, long cost) {
                return new RateLimitResult.Allowed(0);
            }

            @Override
            public boolean wouldExceed(String keyId, int limit, long estimate) {
                return false;
            }

            @Override
            public long accumulate(String keyId, int limit, long amount) {
                return amount;
            }
        };
    }

    /** The spend-ledger stub the writer tests use (settle/recordSpend merge into a map). */
    private static SpendLedger recordingLedger() {
        return new SpendLedger() {
            private final Map<String, Long> spend = new ConcurrentHashMap<>();

            @Override
            public long spendByKey(String keyId, long windowSeconds) {
                return spend.getOrDefault(keyId, 0L);
            }

            @Override
            public long totalSpendByKey(String keyId) {
                return spend.getOrDefault(keyId, 0L);
            }

            @Override
            public List<SpendLedger.LedgerEntry> recent(String keyId, int n) {
                return List.of();
            }

            @Override
            public SpendLedger.ReserveResult reserve(
                    String keyId,
                    long estimateMicroUsd,
                    long hardCapMicroUsd,
                    double softFraction,
                    long windowSeconds) {
                return new SpendLedger.ReserveResult.Allowed(false, 0, estimateMicroUsd, 0);
            }

            @Override
            public void settle(String keyId, long estimateMicroUsd, long actualMicroUsd, long reservationWindowStart) {
                spend.merge(keyId, actualMicroUsd, Long::sum);
            }

            @Override
            public void release(String keyId, long estimateMicroUsd, long reservationWindowStart) {}

            @Override
            public void recordSpend(String keyId, long microUsd) {
                spend.merge(keyId, microUsd, Long::sum);
            }
        };
    }

    // ------------------------------------------------------------ finalize (OK)

    @Test
    void finalizeWritesExactlyOneOkRecordWithMappedFields() {
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, null, null, null, null));
        KeyRecord key = created.record();
        Governance.Preflight preflight =
                new Governance.Preflight(key, priceTable.rateFor("deepseek-v4-flash"), 1000, 5320, false, false, 0);
        ChatRequest request = request("deepseek-v4-flash", false);
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(14, 12, 26),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());

        governance.finalize(null, request, response, preflight, 12, "deepseek");

        List<CallRecord> records = callStore.recentCalls(10);
        assertEquals(1, records.size(), "exactly one record per request");
        CallRecord record = records.get(0);
        assertEquals(key.id(), record.keyId());
        assertEquals("deepseek-v4-flash", record.model());
        assertEquals("deepseek", record.provider(), "the threaded dispatched provider rides the record");
        assertEquals(14, record.promptTokens());
        assertEquals(12, record.completionTokens());
        assertEquals(26, record.totalTokens());
        assertEquals(5320, record.costMicroUsd());
        assertFalse(record.stream());
        assertEquals(CallStatus.OK, record.status());
        assertNotNull(record.requestId());
        assertTrue(record.totalTokensConsistent());
    }

    @Test
    void authOffFinalizeRecordsNullKeyId() {
        ChatRequest request = request("deepseek-v4-flash", false);
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(14, 12, 26),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());

        governance.finalize(null, request, response, Governance.Preflight.NONE, 12, "deepseek");

        List<CallRecord> records = callStore.recentCalls(10);
        assertEquals(1, records.size());
        assertNull(records.get(0).keyId(), "auth-off records carry a null keyId");
        assertEquals("deepseek", records.get(0).provider(), "auth-off rows still carry the dispatched provider");
        assertEquals(5320, records.get(0).costMicroUsd());
    }

    // ------------------------------------------------------------- stream paths

    @Test
    void streamSettleFromTerminalUsageChunkWritesOneOkRecord() {
        Governance.Preflight preflight = preflight();
        ChatRequest request = request("deepseek-v4-flash", true);
        Stream<StreamChunk> stream = Stream.of(contentChunk("Hello"), usageChunk(new Usage(14, 12, 26)));

        try (Stream<StreamChunk> wrapped = governance.wrapStream(preflight, request, stream, "deepseek")) {
            wrapped.forEach(chunk -> {});
        }

        List<CallRecord> records = callStore.recentCalls(10);
        assertEquals(1, records.size(), "exactly one record for the stream");
        assertEquals("deepseek", records.get(0).provider(), "the stream settle carries the dispatched provider");
        assertTrue(records.get(0).stream());
        assertEquals(5320, records.get(0).costMicroUsd());
        assertEquals(CallStatus.OK, records.get(0).status());
    }

    @Test
    void streamExhaustionWithoutUsageChunkWritesZeroTokensRecord() {
        Governance.Preflight preflight = preflight();
        ChatRequest request = request("deepseek-v4-flash", true);
        Stream<StreamChunk> stream = Stream.of(contentChunk("Hello"), contentChunk(" world"));

        try (Stream<StreamChunk> wrapped = governance.wrapStream(preflight, request, stream, "deepseek")) {
            wrapped.forEach(chunk -> {});
        }

        List<CallRecord> records = callStore.recentCalls(10);
        assertEquals(1, records.size(), "clean exhaustion writes one zero-tokens record");
        assertEquals(0, records.get(0).costMicroUsd());
        assertEquals(CallStatus.OK, records.get(0).status());
    }

    // --------------------------------------------------------------- abort path

    @Test
    void abortedStreamWritesNoCallRecord() {
        // Record-nothing decision: a client-aborted stream
        // (close before exhaustion, no terminal usage chunk) records no CallRecord —
        // matching the metrics side and the reference recorder; the closed
        // CallStatus set is unchanged (no CANCELLED variant). The seam javadoc pins
        // the same rule (CallStore.recordCall / CallRecord).
        Governance.Preflight preflight = preflight();
        ChatRequest request = request("deepseek-v4-flash", true);
        Stream<StreamChunk> upstream = Stream.of(contentChunk("Hello"), contentChunk(" world"));

        try (Stream<StreamChunk> wrapped = governance.wrapStream(preflight, request, upstream, "deepseek")) {
            Iterator<StreamChunk> it = wrapped.iterator();
            assertTrue(it.hasNext(), "the stream yields its first chunk");
            it.next(); // consume exactly one chunk, then close before exhaustion
        }

        assertTrue(
                callStore.recentCalls(10).isEmpty(),
                "aborted streams (client disconnect before exhaustion) record nothing");
        assertEquals(0, callStore.dropped(), "the abort path writes zero records (and drops zero)");
    }

    // -------------------------------------------------------------- failure map

    @Test
    void recordFailureMapsCoarseStatusClasses() {
        Governance.Preflight preflight = preflight();
        ChatRequest request = request("deepseek-v4-flash", false);

        governance.recordFailure(null, preflight, request, 429, 10, null);
        governance.recordFailure(null, preflight, request, 400, 10, "deepseek");
        governance.recordFailure(null, preflight, request, 502, 10, "deepseek");
        governance.recordFailure(null, preflight, request, 500, 10, null);

        List<CallRecord> records = callStore.recentCalls(10);
        assertEquals(4, records.size());
        // recentCalls is newest-first.
        assertEquals(CallStatus.ERROR_INTERNAL, records.get(0).status());
        assertNull(records.get(0).provider(), "no dispatch threaded ⇒ null provider, never a guessed one");
        assertEquals(CallStatus.ERROR_UPSTREAM, records.get(1).status());
        assertEquals("deepseek", records.get(1).provider(), "the dispatched backend rides failure rows");
        assertEquals(CallStatus.ERROR_CLIENT, records.get(2).status());
        assertEquals("deepseek", records.get(2).provider());
        assertEquals(CallStatus.ERROR_LIMIT, records.get(3).status());
    }

    // -------------------------------------------------- enforce (coverage gaps)

    @Test
    void enforceWithNullModelResolvesZeroRateWithoutThrowing() {
        // Governance.enforce with a null request.model is
        // unreachable from the controllers (checkScope 403s a blank model first) but
        // Governance is package-visible and tests construct ChatRequests directly —
        // the zero-rate fallback must never crash the gate.
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, null, null, null, null));
        ChatRequest request = request(null, false); // null model
        HttpRequest<?> http = HttpRequest.POST("/v1/chat/completions", "")
                .setAttribute(KeyAuthFilter.KEY_ATTRIBUTE, created.record());

        Governance.Preflight preflight = governance.enforce(http, request);

        assertEquals(created.record().id(), preflight.key().id(), "the governing key rides the preflight");
        assertEquals(PricingRate.ZERO, preflight.rate(), "a null model resolves to the zero rate, never crashes");
        assertEquals(
                Governance.DEFAULT_MAX_TOKENS,
                preflight.estimateTokens(),
                "the reference fallback applies when neither the request nor a row has max tokens");
        assertFalse(preflight.reserved(), "no budget on the key ⇒ no reservation");
    }

    // ----------------------------------- a throwing CallStore must not corrupt the request path

    @Test
    void finalizeSurvivesAThrowingNotifierAndWritesExactlyOneOkRow() {
        // The notifier dispatch after the OK call-row write must be contained —
        // a throwing adapter would escape finalize AFTER the OK record, and the
        // controller's catch would then record a 5xx bucket AND a second (ERROR) row:
        // metrics says 5xx, the ledger carries OK+ERROR for one request, and a completed
        // 200 becomes a 500. Guarded at the call site (best-effort by contract), the
        // request keeps its 200 and the ledger keeps exactly one OK row.
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, null, null, null, null));
        KeyRecord key = created.record();
        // soft preflight → the notifier path fires.
        Governance.Preflight preflight =
                new Governance.Preflight(key, priceTable.rateFor("deepseek-v4-flash"), 1000, 5320, false, true, 0);
        ChatRequest request = request("deepseek-v4-flash", false);
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(14, 12, 26),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());

        Governance withThrowingNotifier = new Governance(
                allowAllLimiter(),
                priceTable,
                recordingLedger(),
                throwingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                callStore);

        Governance.Finalized finalized = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> withThrowingNotifier.finalize(null, request, response, preflight, 12, null),
                "a throwing notifier must never break the finalize path (the response stays 200)");
        assertTrue(finalized.softExceeded(), "the soft flag still returns to the caller");

        List<CallRecord> records = callStore.recentCalls(10);
        assertEquals(1, records.size(), "exactly one OK row — no second (ERROR) write from the controller catch");
        assertEquals(CallStatus.OK, records.get(0).status());
    }

    /** A notifier that throws on dispatch — the best-effort-by-contract violation the guard contains. */
    private static Notifier throwingNotifier() {
        return new Notifier() {
            @Override
            public void notify(String event, Map<String, Object> payload) {
                throw new IllegalStateException("webhook exploded");
            }

            @Override
            public void forgetKey(String keyId) {}
        };
    }

    /**
     * The writer is failure-tolerant — {@code callStore.recordCall} is
     * wrapped (log-and-drop), so a Pg-write failure can neither replace the client's
     * true error envelope (the recordFailure path) nor abort the settle/accounting
     * (the finalize path). The store is a delegate that throws only on {@code recordCall}.
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
    void recordFailureSurvivesAThrowingCallStore() {
        // A 429's recordFailure must not propagate the store failure out of the
        // controller's catch — the client keeps the true rate_limit_error envelope.
        Governance failingWriter = new Governance(
                allowAllLimiter(),
                priceTable,
                recordingLedger(),
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                new ThrowingRecordCallStore());
        Governance.Preflight preflight = preflight();
        ChatRequest request = request("deepseek-v4-flash", false);

        // A throwing recordCall must be swallowed, not propagated out of the
        // controller's catch — the client keeps its true 429 envelope.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> failingWriter.recordFailure(null, preflight, request, 429, 10, null));
    }

    @Test
    void finalizeSurvivesAThrowingCallStoreAndStillSettles() {
        // A finalize write failure must not abort settle/recordSpend/accumulate — the
        // accounting the hard cap depends on runs regardless of the ledger write.
        RecordingLedger ledger = new RecordingLedger();
        Governance failingWriter = new Governance(
                allowAllLimiter(),
                priceTable,
                ledger,
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                new ThrowingRecordCallStore());
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, null, null, null, null));
        Governance.Preflight preflight = new Governance.Preflight(
                created.record(), priceTable.rateFor("deepseek-v4-flash"), 1000, 5320, true, false, 0);
        ChatRequest request = request("deepseek-v4-flash", false);
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(14, 12, 26),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());

        Governance.Finalized finalized = failingWriter.finalize(null, request, response, preflight, 12, null);

        assertEquals(
                5_320,
                ledger.spendByKey(created.record().id(), 0),
                "the actual cost settles despite the write failure");
        assertFalse(finalized.softExceeded(), "no soft flag — preflight was not soft");
    }

    /**
     * A rate limiter whose settle throws — the fixed-window variant's
     * {@code Math.addExact} wraps at ~9.2e18 tokens (Postgres bigint parity), so an
     * adversarial/pathological counter failure must never escape the settle path and
     * turn a completed 200 into a 5xx.
     */
    private static final class ThrowingAccumulateRateLimiter implements RateLimiter {

        @Override
        public RateLimitResult tryAcquire(String keyId, int limit, long cost) {
            return new RateLimitResult.Allowed(0);
        }

        @Override
        public boolean wouldExceed(String keyId, int limit, long estimate) {
            return false;
        }

        @Override
        public long accumulate(String keyId, int limit, long actual) {
            throw new ArithmeticException("count + actual overflow (simulated wrap)");
        }
    }

    @Test
    void finalizeSurvivesAThrowingAccumulateAndStillSettles() {
        // The finalize TPM settle (and its stream twin, settleChunk) are guarded like
        // the call-ledger write: a pathological accumulate failure is logged and dropped,
        // so the request path keeps its 200 and the cost accounting still runs.
        RecordingLedger ledger = new RecordingLedger();
        Governance failingSettle = new Governance(
                new ThrowingAccumulateRateLimiter(),
                priceTable,
                ledger,
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                new InMemoryCallStore(CLOCK, 1000));
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, null, null, null, 100));
        Governance.Preflight preflight = new Governance.Preflight(
                created.record(), priceTable.rateFor("deepseek-v4-flash"), 1000, 5320, true, false, 0);
        ChatRequest request = request("deepseek-v4-flash", false);
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(14, 12, 26),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());

        Governance.Finalized finalized = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> failingSettle.finalize(null, request, response, preflight, 12, null),
                "a throwing TPM settle must never break the finalize path");
        assertEquals(
                5_320,
                ledger.spendByKey(created.record().id(), 0),
                "the ledger settle still runs despite the limiter failure");
        assertFalse(finalized.softExceeded());
    }

    @Test
    void streamSettleSurvivesAThrowingAccumulate() {
        // The stream settle runs OUTSIDE tryAdvance's catch (settleChunk), so the guard
        // is what keeps a pathological accumulate from escaping the SSE stream as a 5xx.
        Governance failingSettle = new Governance(
                new ThrowingAccumulateRateLimiter(),
                priceTable,
                recordingLedger(),
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                new InMemoryCallStore(CLOCK, 1000));
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, null, null, null, 100));
        Governance.Preflight preflight = new Governance.Preflight(
                created.record(), priceTable.rateFor("deepseek-v4-flash"), 1000, 5320, false, false, 0);
        ChatRequest request = request("deepseek-v4-flash", true);
        Stream<StreamChunk> stream = Stream.of(usageChunk(new Usage(14, 12, 26)));

        List<StreamChunk> chunks = new java.util.ArrayList<>();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> {
                    try (Stream<StreamChunk> wrapped = failingSettle.wrapStream(preflight, request, stream, null)) {
                        wrapped.forEach(chunks::add);
                    }
                },
                "a throwing TPM settle must never escape the stream settle");
        assertEquals(1, chunks.size(), "the usage chunk passes through unchanged");
    }

    // ------------------------------------------- a throwing ledger RELEASE must never
    // ------------------------------------------- mask the true error or skip the upstream close

    @Test
    void abortedStreamWithThrowingReleaseStillClosesUpstreamAndWritesNoRow() {
        // releaseIfUnsettled runs in the wrap's CLOSE hook, before the upstream close:
        // the settledOrReleased CAS is already won, so an unguarded release throw would
        // both leak the reservation and — worse — skip the upstream.close that follows
        // it (a leaked adapter connection). The guard logs and drops, and the close
        // still runs (pinned by the onClose flag below).
        ThrowingLedger ledger = new ThrowingLedger("release");
        Governance failing = new Governance(
                allowAllLimiter(),
                priceTable,
                ledger,
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                callStore);
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, 1.5, null, null, null));
        KeyRecord key = created.record();
        ChatRequest request = request("deepseek-v4-flash", true);
        Governance.Preflight preflight = enforceReserved(failing, key);
        AtomicBoolean upstreamClosed = new AtomicBoolean();
        Stream<StreamChunk> upstream = Stream.of(contentChunk("Hello")).onClose(() -> upstreamClosed.set(true));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> {
                    try (Stream<StreamChunk> wrapped = failing.wrapStream(preflight, request, upstream, "deepseek")) {
                        Iterator<StreamChunk> it = wrapped.iterator();
                        assertTrue(it.hasNext(), "the stream yields its first chunk");
                        it.next(); // consume exactly one chunk, then close before exhaustion
                    }
                },
                "a throwing ledger release must be contained in the stream close hook");

        assertTrue(upstreamClosed.get(), "the upstream close still runs after a contained release failure");
        assertTrue(callStore.recentCalls(10).isEmpty(), "the abort path records nothing");
    }

    @Test
    void midStreamUpstreamFailureWithThrowingReleaseKeepsTheTrueErrorAndClosesUpstream() {
        // tryAdvance's Throwable catch releases AFTER the settledOrReleased CAS: an
        // unguarded release would replace the ORIGINAL upstream error with the ledger's
        // failure (the client sees the wrong cause, the ERROR_UPSTREAM row's status
        // mapping is decided by an exception that is not the request's). The guard keeps
        // the true envelope, still writes the single ERROR_UPSTREAM row, and still
        // closes the upstream.
        ThrowingLedger ledger = new ThrowingLedger("release");
        Governance failing = new Governance(
                allowAllLimiter(),
                priceTable,
                ledger,
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                callStore);
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, 1.5, null, null, null));
        KeyRecord key = created.record();
        ChatRequest request = request("deepseek-v4-flash", true);
        Governance.Preflight preflight = enforceReserved(failing, key);
        AtomicBoolean upstreamClosed = new AtomicBoolean();
        IllegalStateException upstreamError = new IllegalStateException("upstream died mid-stream");

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> {
                    try (Stream<StreamChunk> wrapped = failing.wrapStream(
                            preflight, request, failingStream(upstreamError, upstreamClosed), "deepseek")) {
                        wrapped.forEach(chunk -> {});
                    }
                },
                "the upstream failure surfaces from the pull");
        assertSame(upstreamError, thrown, "the upstream error is the envelope, never the ledger release failure");

        List<CallRecord> records = callStore.recentCalls(10);
        assertEquals(1, records.size(), "exactly one ERROR_UPSTREAM row for the failed stream: " + records);
        assertEquals(CallStatus.ERROR_UPSTREAM, records.get(0).status());
        assertTrue(upstreamClosed.get(), "the upstream close still runs after a contained release failure");
    }

    /** A source stream whose first {@code tryAdvance} throws — a mid-stream upstream death. */
    private static Stream<StreamChunk> failingStream(RuntimeException error, AtomicBoolean closed) {
        Spliterator<StreamChunk> failing = new Spliterator<>() {
            @Override
            public boolean tryAdvance(Consumer<? super StreamChunk> action) {
                throw error;
            }

            @Override
            public Spliterator<StreamChunk> trySplit() {
                return null;
            }

            @Override
            public long estimateSize() {
                return 1;
            }

            @Override
            public int characteristics() {
                return Spliterator.ORDERED;
            }
        };
        return StreamSupport.stream(failing, false).onClose(() -> closed.set(true));
    }

    // ------------------------------------------- a throwing settle/recordSpend or
    // ------------------------------------------- negative usage must never leak the reservation

    @Test
    void finalizeWithThrowingSettleReleasesReservationAndWritesNoOkRow() {
        // A throwing ledger settle (a Postgres SQL failure on the billing path)
        // must not leak the reservation (a leaked pending balance 429s the key "budget
        // exceeded" forever) nor leave an OK CallRecord for the controller's catch to
        // duplicate — the OK write is the LAST side effect of finalize, so a mid-accounting
        // throw leaves the row count to the controller's recordFailure: exactly one (ERROR)
        // row in production.
        assertFinalizeFailureReleasesReservation("settle");
    }

    @Test
    void finalizeWithThrowingRecordSpendReleasesReservationAndWritesNoOkRow() {
        // The Pg ring-insert shape: recordSpend throws (spend_entries write
        // failure) — the same release + no-OK-row contract as a throwing settle.
        assertFinalizeFailureReleasesReservation("recordSpend");
    }

    @Test
    void finalizeWithNegativeUsageReleasesReservationAndWritesNoOkRow() {
        // A malformed upstream (OpenAiMessageCodec clamps only the cache claim —
        // prompt/completion pass through raw) yields a canonical with negative counts;
        // CostCalculator rejects it and finalize must release the reservation without
        // writing an OK row (production: the controller writes exactly one ERROR row).
        ThrowingLedger ledger = new ThrowingLedger(null);
        Governance failing = new Governance(
                allowAllLimiter(),
                priceTable,
                ledger,
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                callStore);
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, 1.5, null, null, null));
        KeyRecord key = created.record();
        ChatRequest request = request("deepseek-v4-flash", false);
        Governance.Preflight preflight = enforceReserved(failing, key);

        ChatResponse response = chatResponse(new Usage(-5, 10, 5));
        assertThrows(
                RuntimeException.class,
                () -> failing.finalize(null, request, response, preflight, 12, null),
                "a negative-usage canonical must fail the settle path, not corrupt the ledger");

        assertEquals(0, ledger.pending(key.id()), "the cost rejection must not leak the reservation");
        assertTrue(callStore.recentCalls(10).isEmpty(), "no OK CallRecord when the accounting path throws");
    }

    @Test
    void softFinalizeWithThrowingSpendReadWritesExactlyOneOkRow() {
        // notifySoft reads spendByKey AFTER a successful settle (soft preflight ⇒ the
        // notifier/header path fires). The read lives inside the guard and the notify
        // runs BEFORE the OK write: a transient ledger read failure can neither escape
        // finalize — the controller's catch would write an ERROR row after the OK row
        // (OK+ERROR for one request) and hand the client a 500 for a fully-accounted
        // 200 — nor release a reservation that was settled correctly.
        ThrowingLedger ledger = new ThrowingLedger("spendByKey");
        Governance failing = new Governance(
                allowAllLimiter(),
                priceTable,
                ledger,
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                callStore);
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, 1.5, null, null, null));
        KeyRecord key = created.record();
        Governance.Preflight soft =
                new Governance.Preflight(key, priceTable.rateFor("deepseek-v4-flash"), 1000, 5320, true, true, 0);

        Governance.Finalized finalized = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> failing.finalize(
                        null,
                        request("deepseek-v4-flash", false),
                        chatResponse(new Usage(14, 12, 26)),
                        soft,
                        12,
                        "deepseek"),
                "a ledger read failure after a successful settle must not fail a fully-accounted 200");

        assertTrue(finalized.softExceeded(), "the soft flag still returns to the caller");
        assertEquals(0, ledger.pending(key.id()), "the settle stands — no spurious release of a settled reservation");
        List<CallRecord> records = callStore.recentCalls(10);
        assertEquals(1, records.size(), "exactly one OK row — never OK+ERROR for one request: " + records);
        assertEquals(CallStatus.OK, records.get(0).status());
    }
    // ------------------------- behind (the OK write is the LAST side effect, everywhere)

    @Test
    void keyedFinalizeWithThrowingUsageRecorderWritesNoOkRow() {
        // The recorder runs BEFORE the OK row (the keyed branch's order, now applied to
        // every settle path): a throwing recordUsage propagates with the row count left
        // to the controller's recordFailure — exactly one (ERROR) row in production,
        // never OK+ERROR for one request — and the reservation is released.
        ThrowingLedger ledger = new ThrowingLedger(null);
        Governance failing = new Governance(
                allowAllLimiter(),
                priceTable,
                ledger,
                new LoggingNotifier(),
                0.8,
                CLOCK,
                new ThrowingUsageRecorder(),
                callStore);
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, 1.5, null, null, null));
        KeyRecord key = created.record();
        Governance.Preflight preflight = enforceReserved(failing, key);

        assertThrows(
                IllegalStateException.class,
                () -> failing.finalize(
                        null,
                        request("deepseek-v4-flash", false),
                        chatResponse(new Usage(14, 12, 26)),
                        preflight,
                        12,
                        null),
                "the recorder failure is the true envelope, not swallowed after an OK row");

        assertEquals(0, ledger.pending(key.id()), "the reservation must not leak from the recorder failure");
        assertTrue(callStore.recentCalls(10).isEmpty(), "no OK CallRecord before the recorder's throw");
    }

    @Test
    void authOffFinalizeWithThrowingUsageRecorderWritesNoRow() {
        // The auth-off branch follows the same order: a throwing recorder must not
        // leave an OK row and then propagate into the controller catch (which would
        // write a second ERROR_INTERNAL row — OK+ERROR for one request).
        Governance failing = new Governance(
                allowAllLimiter(),
                priceTable,
                recordingLedger(),
                new LoggingNotifier(),
                0.8,
                CLOCK,
                new ThrowingUsageRecorder(),
                callStore);

        assertThrows(
                IllegalStateException.class,
                () -> failing.finalize(
                        null,
                        request("deepseek-v4-flash", false),
                        chatResponse(new Usage(14, 12, 26)),
                        Governance.Preflight.NONE,
                        12,
                        null));

        assertTrue(callStore.recentCalls(10).isEmpty(), "no OK row was written before the recorder threw");
    }

    @Test
    void streamSettleWithThrowingUsageRecorderWritesNoOkRow() {
        // settleChunk records usage BEFORE the OK row: a throwing recorder propagates
        // out of the pull (the client sees the error frame) without having written a
        // settled OK row for a stream that accounted as failed, and the reservation is
        // released by the settle guard.
        ThrowingLedger ledger = new ThrowingLedger(null);
        Governance failing = new Governance(
                allowAllLimiter(),
                priceTable,
                ledger,
                new LoggingNotifier(),
                0.8,
                CLOCK,
                new ThrowingUsageRecorder(),
                callStore);
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, 1.5, null, null, null));
        KeyRecord key = created.record();
        ChatRequest request = request("deepseek-v4-flash", true);
        Governance.Preflight preflight = enforceReserved(failing, key);

        assertThrows(
                IllegalStateException.class,
                () -> {
                    try (Stream<StreamChunk> wrapped = failing.wrapStream(
                            preflight, request, Stream.of(usageChunk(new Usage(14, 12, 26))), null)) {
                        wrapped.forEach(chunk -> {});
                    }
                },
                "the recorder failure escapes the pull as the stream's error, after no OK row");

        assertEquals(0, ledger.pending(key.id()), "the reservation must not leak from the recorder failure");
        assertTrue(callStore.recentCalls(10).isEmpty(), "no OK CallRecord was written for the failed settle");
    }

    /** {@link MetricsRecorder} whose {@code recordUsage} always throws — the ordering pin's double. */
    private static final class ThrowingUsageRecorder implements MetricsRecorder {
        @Override
        public void recordRequest(String face, int status, long durationMillis, String keyId) {}

        @Override
        public void recordUsage(String keyId, Usage usage, long costMicroUsd) {
            throw new IllegalStateException("registry unregistered");
        }

        @Override
        public void forgetKey(String keyId) {}
    }

    @Test
    void streamSettleWithThrowingLedgerReleasesReservationAndWritesNoOkRow() {
        // Streaming: the settledOrReleased CAS flips BEFORE settleChunk runs,
        // so a mid-settle throw must release the reservation inside settleChunk — onClose's
        // `settledOrReleased.get` skip would otherwise leak it (the flag never sticks
        // without accounting).
        ThrowingLedger ledger = new ThrowingLedger("settle");
        Governance failing = new Governance(
                allowAllLimiter(),
                priceTable,
                ledger,
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                callStore);
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, 1.5, null, null, null));
        KeyRecord key = created.record();
        ChatRequest request = request("deepseek-v4-flash", true);
        Governance.Preflight preflight = enforceReserved(failing, key);
        Stream<StreamChunk> stream = Stream.of(usageChunk(new Usage(14, 12, 26)));

        assertThrows(
                RuntimeException.class,
                () -> {
                    try (Stream<StreamChunk> wrapped = failing.wrapStream(preflight, request, stream, null)) {
                        wrapped.forEach(chunk -> {});
                    }
                },
                "a throwing ledger settle must surface from the stream settle");
        assertEquals(0, ledger.pending(key.id()), "a mid-settle throw must release the reservation");
        assertTrue(callStore.recentCalls(10).isEmpty(), "no OK CallRecord when the stream settle throws");
    }

    @Test
    void streamSettleWithNegativeUsageReleasesReservationAndWritesNoOkRow() {
        // Streaming: same contract as the throwing-settle stream case, but the
        // failure is the CostCalculator rejection of a negative-usage terminal chunk.
        ThrowingLedger ledger = new ThrowingLedger(null);
        Governance failing = new Governance(
                allowAllLimiter(),
                priceTable,
                ledger,
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                callStore);
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, 1.5, null, null, null));
        KeyRecord key = created.record();
        ChatRequest request = request("deepseek-v4-flash", true);
        Governance.Preflight preflight = enforceReserved(failing, key);
        Stream<StreamChunk> stream = Stream.of(usageChunk(new Usage(-5, 10, 5)));

        assertThrows(
                RuntimeException.class,
                () -> {
                    try (Stream<StreamChunk> wrapped = failing.wrapStream(preflight, request, stream, null)) {
                        wrapped.forEach(chunk -> {});
                    }
                },
                "a negative-usage terminal chunk must surface from the stream settle");
        assertEquals(0, ledger.pending(key.id()), "a mid-settle throw must release the reservation");
        assertTrue(callStore.recentCalls(10).isEmpty(), "no OK CallRecord when the stream settle rejects");
    }

    /** enforce a budgeted key and assert a reservation was taken. */
    private Governance.Preflight enforceReserved(Governance governance, KeyRecord key) {
        HttpRequest<?> http =
                HttpRequest.POST("/v1/chat/completions", "").setAttribute(KeyAuthFilter.KEY_ATTRIBUTE, key);
        Governance.Preflight preflight = governance.enforce(http, request("deepseek-v4-flash", false));
        assertTrue(preflight.reserved(), "a budgeted key must reserve at enforce");
        return preflight;
    }

    /** Assert the shared release + no-OK-row contract for a throwing ledger operation. */
    private void assertFinalizeFailureReleasesReservation(String failingOp) {
        ThrowingLedger ledger = new ThrowingLedger(failingOp);
        Governance failing = new Governance(
                allowAllLimiter(),
                priceTable,
                ledger,
                new LoggingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                callStore);
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, 1.5, null, null, null));
        KeyRecord key = created.record();
        Governance.Preflight preflight = enforceReserved(failing, key);

        ChatResponse response = chatResponse(new Usage(14, 12, 26));
        assertThrows(
                RuntimeException.class,
                () -> failing.finalize(null, request("deepseek-v4-flash", false), response, preflight, 12, null),
                "a throwing " + failingOp + " must fail the settle path");

        assertEquals(0, ledger.pending(key.id()), "the reservation must not leak from the " + failingOp + " failure");
        assertTrue(callStore.recentCalls(10).isEmpty(), "no OK CallRecord when the accounting path throws");

        // a follow-up request reserves again — a leaked reservation would deny it.
        Governance.Preflight again = enforceReserved(failing, key);
        assertTrue(again.reserved(), "the reservation released: a follow-up request can reserve");
    }

    /**
     * A SpendLedger that fails at a configurable point ({@code "settle"}, {@code
     * "recordSpend"}, {@code "release"} or {@code "spendByKey"}; {@code null} = never)
     * but otherwise mirrors the in-memory reserve/release math so {@link #pending} is
     * observable from the gateway package.
     */
    private static final class ThrowingLedger implements SpendLedger {

        private final String failingOp;
        private final Map<String, Long> settled = new ConcurrentHashMap<>();
        private final Map<String, Long> pending = new ConcurrentHashMap<>();

        ThrowingLedger(String failingOp) {
            this.failingOp = failingOp;
        }

        @Override
        public long spendByKey(String keyId, long windowSeconds) {
            if ("spendByKey".equals(failingOp)) {
                throw new IllegalStateException("postgres spend ledger read failed");
            }
            return settled.getOrDefault(keyId, 0L);
        }

        @Override
        public long totalSpendByKey(String keyId) {
            return settled.getOrDefault(keyId, 0L);
        }

        @Override
        public List<LedgerEntry> recent(String keyId, int n) {
            return List.of();
        }

        @Override
        public ReserveResult reserve(
                String keyId, long estimateMicroUsd, long hardCapMicroUsd, double softFraction, long windowSeconds) {
            if (hardCapMicroUsd <= 0) {
                pending.merge(keyId, estimateMicroUsd, Long::sum);
                return new ReserveResult.Allowed(
                        false, settled.getOrDefault(keyId, 0L), pending.getOrDefault(keyId, 0L), 0);
            }
            long pend = pending.getOrDefault(keyId, 0L) + estimateMicroUsd;
            long sett = settled.getOrDefault(keyId, 0L);
            if (sett >= hardCapMicroUsd - pend) {
                pending.put(keyId, pend - estimateMicroUsd);
                return new ReserveResult.Denied(sett, pending.getOrDefault(keyId, 0L));
            }
            pending.put(keyId, pend);
            boolean soft = sett >= (long) Math.floor(hardCapMicroUsd * softFraction) - pend;
            return new ReserveResult.Allowed(soft, sett, pend, 0);
        }

        @Override
        public void settle(String keyId, long estimateMicroUsd, long actualMicroUsd, long reservationWindowStart) {
            if ("settle".equals(failingOp)) {
                throw new IllegalStateException("postgres spend ledger settle failed");
            }
            pending.compute(keyId, (k, v) -> v == null ? null : Math.max(0, v - estimateMicroUsd));
            settled.merge(keyId, Math.max(actualMicroUsd, 0), Long::sum);
        }

        @Override
        public void release(String keyId, long estimateMicroUsd, long reservationWindowStart) {
            if ("release".equals(failingOp)) {
                throw new IllegalStateException("postgres spend ledger release failed");
            }
            pending.compute(keyId, (k, v) -> v == null ? null : Math.max(0, v - estimateMicroUsd));
        }

        @Override
        public void recordSpend(String keyId, long amountMicroUsd) {
            if ("recordSpend".equals(failingOp)) {
                throw new IllegalStateException("postgres spend ledger recordSpend failed");
            }
        }

        long pending(String keyId) {
            return pending.getOrDefault(keyId, 0L);
        }
    }

    /** The ledger stub: tracks settled spend separately from recorded ring entries. */
    private static final class RecordingLedger implements SpendLedger {
        private final Map<String, Long> settled = new ConcurrentHashMap<>();

        @Override
        public long spendByKey(String keyId, long windowSeconds) {
            return settled.getOrDefault(keyId, 0L);
        }

        @Override
        public long totalSpendByKey(String keyId) {
            return settled.getOrDefault(keyId, 0L);
        }

        @Override
        public List<LedgerEntry> recent(String keyId, int n) {
            return List.of();
        }

        @Override
        public ReserveResult reserve(
                String keyId, long estimateMicroUsd, long hardCapMicroUsd, double softFraction, long windowSeconds) {
            return new ReserveResult.Allowed(false, 0, estimateMicroUsd, 0);
        }

        @Override
        public void settle(String keyId, long estimateMicroUsd, long actualMicroUsd, long reservationWindowStart) {
            settled.merge(keyId, actualMicroUsd, Long::sum);
        }

        @Override
        public void release(String keyId, long estimateMicroUsd, long reservationWindowStart) {}

        @Override
        public void recordSpend(String keyId, long microUsd) {}
    }

    // ---------------------------------------------------------------- helpers

    private Governance.Preflight preflight() {
        KeyStore.CreatedKey created = keyStore.create(
                new KeyStore.KeyCreateRequest("test", List.of("deepseek-v4-flash"), null, null, null, null, null));
        return new Governance.Preflight(
                created.record(), priceTable.rateFor("deepseek-v4-flash"), 1000, 5320, false, false, 0);
    }

    private static ChatRequest request(String model, boolean stream) {
        return new ChatRequest(
                model,
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
                stream,
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                Map.of());
    }

    private static StreamChunk contentChunk(String text) {
        return new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, text, null), null)),
                null,
                Map.of());
    }

    private static StreamChunk usageChunk(Usage usage) {
        return new StreamChunk(
                "chatcmpl-1", "chat.completion.chunk", 1_700_000_000L, "deepseek-v4-flash", List.of(), usage, Map.of());
    }

    private static ChatResponse chatResponse(Usage usage) {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                usage,
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }
}
