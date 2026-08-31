package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link PriceTable} robustness: the once-logged unknown-alias set stays
 * <b>bounded</b> (a client sending a unique fake alias per request must not grow memory
 * without limit), and the alias echoed into the warning line is <b>sanitized</b> (an
 * embedded newline must not forge a second log record; over-long aliases are truncated).
 * The zero-rate fallback and log-once semantics themselves are unchanged (pinned by
 * {@link CostCalculatorTest}).
 */
class PriceTableTest {

    private final PriceTable table = PriceTable.of(Map.of("deepseek-v4-flash", new PricingRate(0.14, 0.28, 4096)));

    @Test
    void requirePricedThrowsInsteadOfZeroRate() {
        PriceTable strict = PriceTable.of(Map.of("priced", new PricingRate(0.14, 0.28, 4096)), true);
        assertEquals(0.14, strict.rateFor("priced").inputPer1K());
        UnpricedModelException thrown = assertThrows(UnpricedModelException.class, () -> strict.rateFor("no-row"));
        assertEquals("no-row", thrown.model());
        assertTrue(thrown.getMessage().contains("no-row"), thrown.getMessage());
        assertFalse(strict.contains("no-row"));
        assertTrue(strict.contains("priced"));
    }

    @Test
    void loggedUnknownSetIsBounded() {
        // Distinct aliases beyond the cap are still priced at zero but never added to the
        // once-logged set — the set cannot grow without bound (memory-DoS hygiene).
        for (int i = 0; i < PriceTable.MAX_UNKNOWN_LOGGED * 4; i++) {
            assertEquals(PricingRate.ZERO, table.rateFor("fake-alias-" + i), "unknown aliases always zero-rate");
        }
        assertEquals(
                PriceTable.MAX_UNKNOWN_LOGGED,
                table.loggedUnknownSize(),
                "the once-logged set is capped at MAX_UNKNOWN_LOGGED");
    }

    @Test
    void sanitizeForLogStripsControlCharactersToASingleRecord() {
        // An embedded newline must become a space — the log line stays one record
        // (log-forgery hygiene), and the stored dedup key is the sanitized form.
        String safe = PriceTable.sanitizeForLog("evil\nmodel\r\ttab");
        assertFalse(safe.contains("\n"), "no newline reaches the log line: " + safe);
        assertFalse(safe.contains("\r"), "no carriage return reaches the log line: " + safe);
        assertFalse(safe.contains("\t"), "no tab reaches the log line: " + safe);
        assertEquals("evil model  tab", safe);

        // NUL and DEL are control chars too.
        assertEquals("a b", PriceTable.sanitizeForLog("a\u0000b"));
        assertEquals("a b", PriceTable.sanitizeForLog("a\u007fb"));
    }

    @Test
    void sanitizeForLogTruncatesOverLongAliases() {
        String longAlias = "m".repeat(500);
        String safe = PriceTable.sanitizeForLog(longAlias);
        assertTrue(
                safe.length() <= PriceTable.MAX_MODEL_LOG_LENGTH + 3,
                "truncated to the max length (plus the ellipsis)");
        assertTrue(safe.startsWith("m".repeat(PriceTable.MAX_MODEL_LOG_LENGTH)), safe);
        assertTrue(safe.endsWith("..."), safe);
    }

    @Test
    void logOnceSemanticsAreUnchangedAndBounded() {
        table.rateFor("unknown-model");
        table.rateFor("unknown-model");
        assertEquals(1, table.loggedUnknownSize(), "repeated lookups of one alias log once");
        assertEquals(PricingRate.ZERO, table.rateFor("unknown-model"));

        table.rateFor("\nnewline-alias");
        assertEquals(2, table.loggedUnknownSize(), "a distinct alias adds one entry (its sanitized form)");
    }

    // ------------------------------------------------------ per-backend override

    @Test
    void backendKeyedRowOverridesAliasRowForThatBackend() {
        // One alias served by two backends at two prices: a row keyed by a backend
        // (provider) name is a per-backend override the cost-based LB prices through.
        PriceTable multi = PriceTable.of(Map.of(
                "claude-3-5-sonnet", new PricingRate(3.0, 15.0, 0),
                "openrouter", new PricingRate(1.2, 6.0, 0),
                "anthropic", new PricingRate(3.0, 15.0, 0)));
        assertEquals(1.2, multi.rateFor("claude-3-5-sonnet", "openrouter").inputPer1K(), "backend row wins");
        assertEquals(3.0, multi.rateFor("claude-3-5-sonnet", "anthropic").inputPer1K(), "explicit per-backend row");
        assertEquals(
                3.0,
                multi.rateFor("claude-3-5-sonnet", "unlisted-backend").inputPer1K(),
                "backend without a row falls back to the alias row");
        // The alias-only surface (governance metering) is undisturbed by backend-keyed rows.
        assertEquals(3.0, multi.rateFor("claude-3-5-sonnet").inputPer1K());
        assertEquals(
                new PricingRate(1.2, 6.0, 0),
                multi.rateFor("openrouter"),
                "a backend-name key also prices as its own alias row (no lookup ambiguity)");
    }

    @Test
    void rateForModelBackendNeverSuppressesUnknownAliasFallback() {
        assertEquals(PricingRate.ZERO, table.rateFor("unknown-alias", "any-backend"), "unknown alias stays zero");
        assertEquals(
                table.rateFor("deepseek-v4-flash"),
                table.rateFor("deepseek-v4-flash", null),
                "null backend → the alias row, unchanged");
    }

    @Test
    void emptyTableReportsIsEmpty() {
        assertTrue(PriceTable.EMPTY.isEmpty(), "no rows → empty");
        assertFalse(table.isEmpty(), "a row → not empty");
        assertFalse(PriceTable.of(Map.of("deepseek-v4-flash", new PricingRate(0.14, 0.28, 4096)))
                .isEmpty());
    }

    @Test
    void loggedUnknownSetStaysWithinTheCapUnderAConcurrentDistinctAliasBurst() throws Exception {
        // The once-logged set must not grow past MAX_UNKNOWN_LOGGED under a
        // concurrent flood of distinct fake aliases. The add-then-trim guard keeps the
        // final set at exactly the cap even when many threads race the size check —
        // a thread whose add pushes the set over the cap removes its own element.
        int threads = 8;
        int perThread = PriceTable.MAX_UNKNOWN_LOGGED * 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger zeroRated = new AtomicInteger();
        try {
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        assertEquals(
                                PricingRate.ZERO,
                                table.rateFor("fake-" + threadId + "-" + i),
                                "unknown aliases always zero-rate");
                        zeroRated.incrementAndGet();
                    }
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "the alias burst must finish");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                threads * perThread,
                zeroRated.get(),
                "every sprayed alias was zero-rated (the fallback is unaffected by the guard)");
        assertTrue(
                table.loggedUnknownSize() <= PriceTable.MAX_UNKNOWN_LOGGED,
                "the once-logged set never exceeds the cap under concurrency (got " + table.loggedUnknownSize() + ")");
    }
}
