package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.Usage;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link PricingRate}/{@link PriceTable}/{@link CostCalculator}: per-1K
 * token rates keyed by <b>model alias</b> ( scope-by-alias semantics — today alias
 * == upstream model, no remap; divergence documented in {@link PriceTable}), exact
 * integer micro-USD cost (1 USD = 1_000_000 micro-USD; the DeepSeek 0.14/0.28 rates ×
 * a fixture usage must equal the manual integer calculation exactly — the
 * "exact price×usage" assertion), cache tokens at their own rates when present / zero
 * when absent, an unknown model yielding the zero rate (logged once — metering never
 * crashes on a new model), and half-micro rounding at the boundary (long arithmetic
 * only, no BigDecimal, native-image clean).
 */
class CostCalculatorTest {

    /** Integer-arithmetic fixture rates (not official V4 Flash). */
    private static final PricingRate DEEPSEEK = new PricingRate(0.14, 0.28, 0.0, 0.0, 4096);

    /**
     * Official xAI Grok 4.6 rates (docs.x.ai, Aug 2026): $2.00 input / $0.50 cached
     * / $6.00 output per 1M tokens below the 200k long-context threshold. Janus
     * prices USD per 1K, so $2.00/1M = 0.002, $0.50/1M = 0.0005, $6.00/1M = 0.006.
     * Long-context (≥200k prompt) doubling is modeled via {@link PricingRate#forPromptTokens}.
     */
    private static final PricingRate GROK_46 = new PricingRate(0.002, 0.006, 0.0005, 0.0, 4096);

    private static final PricingRate GROK_46_TIERED =
            new PricingRate(0.002, 0.006, 0.0005, 0.0, 4096, 0.0, 200_000, 0.004, 0.012, 0.001, 0.0);

    @Test
    void deepSeekRatesMatchManualIntegerCalculationExactly() {
        Usage usage = new Usage(1_234, 567, 1_801);
        // manual: 1234 × 0.14 + 567 × 0.28 = 172.76 + 158.76 = 331.52 USD
        // → 331_520 micro-USD (integer arithmetic, no float drift)
        assertEquals(331_520L, CostCalculator.costMicroUsd(usage, DEEPSEEK));
    }

    @Test
    void longContextTierUsesFullPromptIncludingCache() {
        // Codecs store regular-only promptTokens (cache subtracted). A 199_000
        // regular + 1_000 cache-read prompt is 200k to the vendor — the long tier
        // must apply. Regular-only would stay on the short row.
        Usage justUnder = new Usage(199_000, 10, 199_010, null, 999L);
        Usage atFloor = new Usage(199_000, 10, 199_010, null, 1_000L);
        assertEquals(GROK_46_TIERED, GROK_46_TIERED.forPromptTokens(justUnder.billedPromptTokens()));
        assertEquals(
                0.004,
                GROK_46_TIERED.forPromptTokens(atFloor.billedPromptTokens()).inputPer1K());
    }

    @Test
    void longContextTierAppliesAtAndAboveThreshold() {
        Usage shortPrompt = new Usage(199_999, 100, 200_099);
        Usage longPrompt = new Usage(200_000, 100, 200_100);
        assertEquals(GROK_46_TIERED, GROK_46_TIERED.forPromptTokens(199_999));
        PricingRate longRate = GROK_46_TIERED.forPromptTokens(200_000);
        assertEquals(0.004, longRate.inputPer1K());
        assertEquals(0.012, longRate.outputPer1K());
        assertEquals(0.001, longRate.cacheReadPer1K());
        assertEquals(
                CostCalculator.costMicroUsd(shortPrompt, GROK_46),
                CostCalculator.costMicroUsd(shortPrompt, GROK_46_TIERED.forPromptTokens(shortPrompt.promptTokens())));
        assertEquals(
                CostCalculator.costMicroUsd(longPrompt, new PricingRate(0.004, 0.012, 0.001, 0.0, 4096)),
                CostCalculator.costMicroUsd(longPrompt, GROK_46_TIERED.forPromptTokens(longPrompt.promptTokens())));
    }

    @Test
    void longContextThresholdWithoutRatesIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PricingRate(0.002, 0.006, 0.0, 0.0, 4096, 0.0, 200_000, 0, 0, 0, 0));
    }

    @Test
    void grok46OfficialRatesMatchManualIntegerCalculationExactly() {
        // tokens × rate × 1_000_000 / 1_000:
        // 1000 × 0.002 × 1000 = 2_000; 500 × 0.006 × 1000 = 3_000; 200 × 0.0005 × 1000 = 100
        // → 5_100 micro-USD.
        Usage usage = new Usage(1_000, 500, 1_700, null, 200L);
        assertEquals(5_100L, CostCalculator.costMicroUsd(usage, GROK_46));
        assertEquals(GROK_46, PriceTable.of(Map.of("grok-4.6", GROK_46)).rateFor("grok-4.6"));
    }

    @Test
    void webSearchesCostAtThePerSearchRateBesidesTokens() {
        // Anthropic bills searches per 1k besides result tokens (which arrive as
        // ordinary input tokens). 10 searches at $10/1k = $0.10 = 100_000 micro; a
        // null usage still prices its searches (search-only response).
        PricingRate withSearch = new PricingRate(0.14, 0.28, 0.0, 0.0, 4096, 10.0);
        assertEquals(100_000L, CostCalculator.costMicroUsd(new Usage(0, 0, 0), withSearch, 10));
        assertEquals(100_000L, CostCalculator.costMicroUsd(null, withSearch, 10));
        assertEquals(331_520L + 100_000L, CostCalculator.costMicroUsd(new Usage(1_234, 567, 1_801), withSearch, 10));
        assertEquals(0L, CostCalculator.costMicroUsd(null, DEEPSEEK, 5), "no per-search rate => searches free");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> CostCalculator.costMicroUsd(null, withSearch, -1));
    }

    @Test
    void tokenAndSearchMicroRoundAsOneSumNeverDoubleFloor() {
        // The search overload used to delegate to the
        // 2-arg method (already floored) and floor AGAIN — two sub-half parts summed
        // to a half-up roundable total were lost (0.4μ + 0.4μ → 0 instead of 1). The
        // combined value must round ONCE: token 0.4μ (1 token × 0.0004/1K) + search
        // 0.4μ (1 search × 0.0004/1K) = 0.8μ → 1.
        PricingRate fractional = new PricingRate(0.0004, 0.0, 0.0, 0.0, 4096, 0.0004);
        assertEquals(1L, CostCalculator.costMicroUsd(new Usage(1, 0, 1), fractional, 1));
        // pure halves on each side stay deterministic
        assertEquals(0L, CostCalculator.costMicroUsd(new Usage(1, 0, 1), fractional, 0), "0.4μ alone floors to 0");
        assertEquals(0L, CostCalculator.costMicroUsd(null, fractional, 1), "search 0.4μ alone floors to 0");
        assertEquals(1L, CostCalculator.costMicroUsd(new Usage(2, 0, 2), fractional, 1), "0.8μ + 0.4μ = 1.2μ → 1");
    }

    @Test
    void breakdownSplitsTheSameArithmeticAsTheBilledTotal() {
        PricingRate rate = new PricingRate(0.14, 0.28, 0.07, 0.09, 4096, 10.0);
        Usage usage = new Usage(1_000, 1_000, 2_000, 100L, 200L);
        CostBreakdown split = CostCalculator.breakdown(usage, rate, 10);
        assertEquals(140_000L, split.inputMicroUsd());
        assertEquals(280_000L, split.outputMicroUsd());
        assertEquals(14_000L, split.cacheReadMicroUsd());
        assertEquals(9_000L, split.cacheCreationMicroUsd());
        assertEquals(100_000L, split.searchMicroUsd());
        assertEquals(CostCalculator.costMicroUsd(usage, rate, 10), split.totalMicroUsd());
        assertEquals(CostBreakdown.ZERO, CostCalculator.breakdown(null, PricingRate.ZERO, 0));
    }

    @Test
    void cacheTokensCostAtTheirOwnRatesWhenPresent() {
        PricingRate rate = new PricingRate(0.14, 0.28, 0.07, 0.09, 4096);
        // Usage(cacheCreation=100, cacheRead=200): 1000×0.14 + 1000×0.28 + 200×0.07 +
        // 100×0.09 = 140 + 280 + 14 + 9 = 443 USD → 443_000 micro-USD.
        Usage usage = new Usage(1_000, 1_000, 2_000, 100L, 200L);
        assertEquals(443_000L, CostCalculator.costMicroUsd(usage, rate));
    }

    @Test
    void cacheTokensCostZeroWhenRatesAbsent() {
        // DeepSeek rows omit the cache rates → the cache fields (Anthropic-only today)
        // must cost zero, not crash or invent a rate.
        Usage usage = new Usage(1_000, 1_000, 2_000, 100L, 200L);
        assertEquals(420_000L, CostCalculator.costMicroUsd(usage, DEEPSEEK));
        assertEquals(0L, CostCalculator.costMicroUsd(usage, PricingRate.ZERO));
    }

    @Test
    void unknownModelYieldsZeroRateFromTable() {
        PriceTable table = PriceTable.of(Map.of("deepseek-v4-flash", DEEPSEEK));
        assertEquals(PricingRate.ZERO, table.rateFor("no-such-model"), "unknown model → zero rate, never crashes");
        assertEquals(PricingRate.ZERO, table.rateFor("no-such-model"), "repeated lookups stay zero (logged once)");
        assertEquals(DEEPSEEK, table.rateFor("deepseek-v4-flash"), "known aliases resolve to their row");
    }

    @Test
    void emptyTableYieldsZeroRatesForEverything() {
        assertEquals(PricingRate.ZERO, PriceTable.EMPTY.rateFor("anything"));
    }

    @Test
    void halfMicroBoundaryRoundsUp() {
        // 1 micro-USD per 1K input tokens: 500 tokens = exactly 0.5 micro → rounds up;
        // 499 tokens = 0.499 micro → rounds down. Pins the half-micro rounding rule.
        PricingRate microRate = new PricingRate(0.000001, 0.0, 0.0, 0.0, 100);
        assertEquals(0L, CostCalculator.costMicroUsd(new Usage(499, 0, 499), microRate));
        assertEquals(1L, CostCalculator.costMicroUsd(new Usage(500, 0, 500), microRate));
    }

    @Test
    void zeroUsageYieldsZeroCost() {
        assertEquals(0L, CostCalculator.costMicroUsd(new Usage(0, 0, 0), DEEPSEEK));
    }

    @Test
    void nullUsageYieldsZeroCost() {
        // Coverage: an upstream that omitted usage entirely costs zero — the
        // gateway's finalize/wrapStream settle a $0 entry, never a crash.
        assertEquals(0L, CostCalculator.costMicroUsd(null, DEEPSEEK));
    }

    @Test
    void openAiCachedShapeBillsRegularAtInputAndCachedAtCacheRate() {
        // The OpenAI codec splits prompt_tokens into regular + cache-read
        // (prompt_tokens_details.cached_tokens → cacheReadInputTokens, subtracted from
        // prompt). Cost must be (regular × input + cached × cacheRead) — never the
        // additive double bill (prompt × input + cached × cacheRead on the full count).
        PricingRate cacheRate = new PricingRate(0.14, 0.28, 0.07, 0.09, 4096);
        Usage split = new Usage(9, 12, 26, null, 5L);
        // 9 × 0.14 + 12 × 0.28 + 5 × 0.07 = 1.26 + 3.36 + 0.35 = 4.97 USD.
        assertEquals(4_970L, CostCalculator.costMicroUsd(split, cacheRate));
        // The full-count double bill would be 14 × 0.14 + 3.36 + 0.35 = 5.67 USD — the
        // bug this pins against.
        assertNotEquals(5_670L, CostCalculator.costMicroUsd(split, cacheRate));
    }

    @Test
    void estimateMicroUsdScalesTheReserveToOutputRate() {
        // Coverage: the conservative pre-dispatch estimate = outputPer1K ×
        // estimateTokens (prompt unknown pre-dispatch).
        assertEquals(
                280_000L, CostCalculator.estimateMicroUsd(1_000, DEEPSEEK), "1000 × 0.28 × 1000 = 280_000 micro-USD");
        assertEquals(0L, CostCalculator.estimateMicroUsd(0, DEEPSEEK));
        assertEquals(0L, CostCalculator.estimateMicroUsd(100, PricingRate.ZERO));
    }

    @Test
    void estimateMicroUsdRejectsNegativeEstimate() {
        assertThrows(IllegalArgumentException.class, () -> CostCalculator.estimateMicroUsd(-1, DEEPSEEK));
    }

    @Test
    void estimatePromptMicroUsdPricesInputAtTheWorstSingleRate() {
        // The pre-dispatch prompt estimate prices each prompt token at
        // max(inputPer1K, cacheReadPer1K, cacheCreationPer1K) — the true per-token
        // worst case, since a prompt token bills at exactly ONE of the three rates. A
        // row whose cache rates are all below the input rate prices at the input rate
        // (the old input + cacheRead form over-priced the cache-read case).
        assertEquals(
                140_000L,
                CostCalculator.estimatePromptMicroUsd(1_000, DEEPSEEK),
                "1000 × max(0.14, 0.0, 0.0) × 1000 = 140_000 micro-USD");
        assertEquals(0L, CostCalculator.estimatePromptMicroUsd(0, DEEPSEEK));
        assertEquals(0L, CostCalculator.estimatePromptMicroUsd(100, PricingRate.ZERO));

        // A row with cache rates below input: the worst single rate is the input rate —
        // never the additive input + cacheRead.
        PricingRate cacheRate = new PricingRate(0.14, 0.28, 0.07, 0.09, 4096);
        assertEquals(
                140_000L,
                CostCalculator.estimatePromptMicroUsd(1_000, cacheRate),
                "1000 × max(0.14, 0.07, 0.09) × 1000 = 140_000 micro-USD");
    }

    @Test
    void estimatePromptMicroUsdCoversTheCacheCreationPath() {
        // For Anthropic-class rows the cache-write rate is the most
        // expensive per-token rate (1.25× input vs 0.1× for reads) — the estimate must
        // price at max(input, read, creation) so a prompt entirely written to cache is
        // covered by the admission estimate, not settled unguarded against the hard cap.
        PricingRate sonnet = new PricingRate(3.0, 15.0, 0.30, 3.75, 4096);
        long estimate = CostCalculator.estimatePromptMicroUsd(1_000, sonnet);
        assertEquals(3_750_000L, estimate, "1000 × max(3.0, 0.30, 3.75) × 1000 = 3_750_000 micro-USD");

        // A fully cache-created prompt bills only at the creation rate (the Anthropic
        // input_tokens contract: regular-only after the last breakpoint).
        long fullyCreated = CostCalculator.costMicroUsd(new Usage(0, 0, 0, 1_000L, null), sonnet);
        assertEquals(3_750_000L, fullyCreated, "1000 × 3.75 × 1000 = 3_750_000 micro-USD");
        // The old input + cacheRead form (3.3 micro/token) would have under-priced it.
        assertTrue(estimate >= fullyCreated, "the admission estimate must cover a fully cache-created prompt");

        // And the other two single-rate prompt shapes are covered too (input-dominant
        // and read-dominant rows price at their own worst rate).
        long fullyRegular = CostCalculator.costMicroUsd(new Usage(1_000, 0, 1_000, null, null), sonnet);
        assertTrue(estimate >= fullyRegular, "covers a fully regular (cache-miss) prompt");
        long fullyRead = CostCalculator.costMicroUsd(new Usage(0, 0, 0, null, 1_000L), sonnet);
        assertTrue(estimate >= fullyRead, "covers a fully cache-read prompt");

        // A creation-dominant row with no read rate stays conservative (max over the
        // present rates).
        PricingRate creationOnly = new PricingRate(0.14, 0.28, 0.0, 0.21, 4096);
        assertEquals(
                210_000L,
                CostCalculator.estimatePromptMicroUsd(1_000, creationOnly),
                "1000 × max(0.14, 0.0, 0.21) × 1000 = 210_000 micro-USD");
        long createdOnly = CostCalculator.costMicroUsd(new Usage(0, 0, 0, 1_000L, null), creationOnly);
        assertEquals(210_000L, createdOnly, "1000 × 0.21 × 1000 = 210_000 micro-USD");
    }

    @Test
    void halfMicroBoundaryRoundsUpAcrossARateTable() {
        // A table-driven half-micro rounding pin — exact x.5-micro products
        // must round half-up, and a hair below stays down. Double arithmetic is exact
        // for these shapes (the float boundary is documented as informational).
        assertEquals(
                1L,
                CostCalculator.costMicroUsd(new Usage(1_000, 0, 1_000), new PricingRate(0.0000005, 0.0, 4096)),
                "0.0000005 $/1K × 1000 tokens = exactly 0.5 micro → half-up");
        assertEquals(
                0L,
                CostCalculator.costMicroUsd(new Usage(999, 0, 999), new PricingRate(0.0000005, 0.0, 4096)),
                "0.0000005 $/1K × 999 tokens = 0.4995 micro → down");
        assertEquals(
                1L,
                CostCalculator.costMicroUsd(new Usage(1_001, 0, 1_001), new PricingRate(0.0000005, 0.0, 4096)),
                "0.0000005 $/1K × 1001 tokens = 0.5005 micro → up");
        assertEquals(
                1L,
                CostCalculator.costMicroUsd(new Usage(500, 0, 500), new PricingRate(0.000001, 0.0, 4096)),
                "0.000001 $/1K × 500 tokens = exactly 0.5 micro → half-up");
        assertEquals(
                0L,
                CostCalculator.costMicroUsd(new Usage(499, 0, 499), new PricingRate(0.000001, 0.0, 4096)),
                "0.000001 $/1K × 499 tokens = 0.499 micro → down");
        // The DeepSeek pricing rows stay integer-exact at a non-boundary count (the 2^53
        // precision bound is documented; these magnitudes are far below it).
        assertEquals(
                331_520L,
                CostCalculator.costMicroUsd(new Usage(1_234, 567, 1_801), DEEPSEEK),
                "the design integer-equality assertion still holds exactly");
    }

    @Test
    void estimatePromptMicroUsdRejectsNegativeTokens() {
        assertThrows(IllegalArgumentException.class, () -> CostCalculator.estimatePromptMicroUsd(-1, DEEPSEEK));
    }

    @Test
    void validatesNonNegativeRatesAndUsage() {
        assertThrows(IllegalArgumentException.class, () -> new PricingRate(-0.1, 0.28, 0.0, 0.0, 100));
        assertThrows(IllegalArgumentException.class, () -> new PricingRate(0.14, 0.28, 0.0, 0.0, -5));
        assertThrows(IllegalArgumentException.class, () -> CostCalculator.costMicroUsd(new Usage(-1, 5, 4), DEEPSEEK));
        assertThrows(
                IllegalArgumentException.class,
                () -> CostCalculator.costMicroUsd(new Usage(5, 5, 10, -1L, null), DEEPSEEK));
    }
}
