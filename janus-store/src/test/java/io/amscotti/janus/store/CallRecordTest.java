package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.Usage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CallRecord}, the Tier-1 per-request usage record (the reference
 * {@code Usage.UsageEvent} analogue). Pins: field immutability/accessors; validation
 * (negative tokens/cost/duration rejected; null {@code keyId}/{@code model}/
 * {@code provider} accepted where the contract says nullable — auth-off and
 * pre-resolution failures); the {@link Usage} convenience constructor (cache-token
 * fields mapped verbatim); the {@code totalTokens} consistency helper; the closed
 * {@link CallStatus} set; and value round-trips through {@code List.copyOf} — records
 * are immutable values, so copying shares instances with no defensive-copy surprises.
 */
class CallRecordTest {

    @Test
    void accessorsReturnCanonicalFields() {
        CallRecord r = new CallRecord(
                "req-1", "k1", "gpt-4o", "deepseek", 10, 20, 30, 5L, 7L, 250, 120, true, CallStatus.OK, 1_000L);
        assertEquals("req-1", r.requestId());
        assertEquals("k1", r.keyId());
        assertEquals("gpt-4o", r.model());
        assertEquals("deepseek", r.provider());
        assertEquals(10, r.promptTokens());
        assertEquals(20, r.completionTokens());
        assertEquals(30, r.totalTokens());
        assertEquals(5L, r.cacheCreationInputTokens());
        assertEquals(7L, r.cacheReadInputTokens());
        assertEquals(250, r.costMicroUsd());
        assertEquals(120, r.durationMillis());
        assertTrue(r.stream());
        assertEquals(CallStatus.OK, r.status());
        assertEquals(1_000L, r.atEpochMillis());
    }

    @Test
    void negativeTokensCostOrDurationRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CallRecord("r", null, "m", "p", -1, 0, 0, null, null, 0, 0, false, CallStatus.OK, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CallRecord("r", null, "m", "p", 0, -1, 0, null, null, 0, 0, false, CallStatus.OK, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CallRecord("r", null, "m", "p", 0, 0, -1, null, null, 0, 0, false, CallStatus.OK, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CallRecord("r", null, "m", "p", 0, 0, 0, null, null, -1, 0, false, CallStatus.OK, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CallRecord("r", null, "m", "p", 0, 0, 0, null, null, 0, -1, false, CallStatus.OK, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CallRecord("r", null, "m", "p", 0, 0, 0, -1L, null, 0, 0, false, CallStatus.OK, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CallRecord("r", null, "m", "p", 0, 0, 0, null, -1L, 0, 0, false, CallStatus.OK, 0));
    }

    @Test
    void nullKeyIdModelProviderAcceptedWhereNullable() {
        // auth-off: no key attached to the request ( auth-off default)
        CallRecord authOff =
                new CallRecord("r1", null, "gpt-4o", "deepseek", 1, 2, 3, null, null, 0, 5, false, CallStatus.OK, 0);
        assertNull(authOff.keyId(), "auth-off records carry no key");
        // pre-resolution failure: the request never reached model resolution/routing
        CallRecord preRoute =
                new CallRecord("r2", "k1", null, null, 0, 0, 0, null, null, 0, 1, false, CallStatus.ERROR_CLIENT, 0);
        assertNull(preRoute.model(), "failed-before-resolution records carry no model");
        assertNull(preRoute.provider(), "failed-before-routing records carry no provider");
    }

    @Test
    void nullRequestIdOrStatusRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new CallRecord(null, "k", "m", "p", 0, 0, 0, null, null, 0, 0, false, CallStatus.OK, 0));
        assertThrows(
                NullPointerException.class,
                () -> new CallRecord("r", "k", "m", "p", 0, 0, 0, null, null, 0, 0, false, null, 0));
    }

    @Test
    void usageConvenienceConstructorMapsTokenAndCacheFields() {
        Usage usage = new Usage(10, 20, 30, 5L, 7L);
        CallRecord viaUsage =
                new CallRecord("r1", "k1", "gpt-4o", "deepseek", usage, 250, 120, true, CallStatus.OK, 1_000L);
        CallRecord canonical = new CallRecord(
                "r1", "k1", "gpt-4o", "deepseek", 10, 20, 30, 5L, 7L, 250, 120, true, CallStatus.OK, 1_000L);
        assertEquals(canonical, viaUsage, "the Usage constructor maps the token fields verbatim");

        CallRecord noCache = new CallRecord("r2", null, "m", "p", new Usage(1, 2, 3), 0, 0, false, CallStatus.OK, 0);
        assertEquals(3, noCache.totalTokens());
        assertNull(noCache.cacheCreationInputTokens(), "absent cache tokens stay null");
        assertNull(noCache.cacheReadInputTokens());
    }

    @Test
    void totalTokensConsistencyHelper() {
        CallRecord consistent =
                new CallRecord("r", null, "m", "p", 10, 20, 30, null, null, 0, 0, false, CallStatus.OK, 0);
        assertTrue(consistent.totalTokensConsistent(), "total == prompt + completion");
        CallRecord inconsistent =
                new CallRecord("r", null, "m", "p", 10, 20, 999, null, null, 0, 0, false, CallStatus.OK, 0);
        assertFalse(inconsistent.totalTokensConsistent(), "the wire-reported total is stored, not derived");
    }

    @Test
    void statusEnumIsTheClosedSet() {
        assertArrayEquals(
                new CallStatus[] {
                    CallStatus.OK,
                    CallStatus.ERROR_UPSTREAM,
                    CallStatus.ERROR_CLIENT,
                    CallStatus.ERROR_LIMIT,
                    CallStatus.ERROR_INTERNAL
                },
                CallStatus.values(),
                "the status vocabulary is exactly OK + the four error kinds (LiteLLM SpendLogs, trimmed)");
    }

    @Test
    void recordsRoundTripThroughListCopyOf() {
        CallRecord a = new CallRecord("r1", "k1", "m", "p", 1, 2, 3, null, null, 4, 5, false, CallStatus.OK, 6);
        CallRecord b = new CallRecord("r2", null, "m", "p", 0, 0, 0, null, null, 0, 0, true, CallStatus.ERROR_LIMIT, 7);
        List<CallRecord> records = List.of(a, b);
        List<CallRecord> copy = List.copyOf(records);
        assertEquals(records, copy, "records are plain values — copy preserves equality");
        assertEquals(records.hashCode(), copy.hashCode());
        assertSame(a, copy.get(0), "copying shares the immutable record instances (no defensive copies)");
        assertSame(b, copy.get(1));
        assertEquals(
                a,
                new CallRecord("r1", "k1", "m", "p", 1, 2, 3, null, null, 4, 5, false, CallStatus.OK, 6),
                "equal values compare equal");
        assertEquals(
                a.hashCode(),
                new CallRecord("r1", "k1", "m", "p", 1, 2, 3, null, null, 4, 5, false, CallStatus.OK, 6).hashCode(),
                "equal values hash equal");
    }
}
