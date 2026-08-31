package io.amscotti.janus.store;

import io.amscotti.janus.core.model.Usage;

/**
 * Exact per-request cost in <b>integer micro-USD</b> (; 1 USD = 1_000_000
 * micro-USD, the Pricing.total_micro_usd rationale): {@code cost}
 * is the sum of {@code tokens × rate} per term, each term scaled by
 * {@code × 1_000_000 / 1_000} and the total rounded at the half-micro boundary
 * (round-half-up). The arithmetic is IEEE double (per-term scaling summed — the
 * better form; a single {@code sum(tokens × rate) × 1000} would round
 * differently), not BigDecimal: for the design magnitudes the integer equality holds
 * exactly (the "exact price×usage" assertion is an integer-equality test against
 * DeepSeek's published 0.14/0.28 rates, pinned by {@code CostCalculatorTest}).
 *
 * <p><b>Two informational float bounds.</b> (1) An exact {@code x.5}-micro
 * product can land a hair below {@code x.5} in double and round down instead of
 * half-up — sub-micro-USD, unreachable for realistic rates (pinned by the
 * half-micro table test). (2) Token counts beyond 2^53 (~9.0e15) lose integer
 * precision, far beyond any reachable billing magnitude. The ledger stores micro-USD
 * integers; float USD is display-only.
 *
 * <p>Cache tokens cost at their own rates when present, zero when the row omits them
 * (Anthropic-only fields on DeepSeek rows). Non-negative usage and rates are
 * validated ({@link PricingRate} validates the row; this class validates the usage).
 */
public final class CostCalculator {

    private static final double MICRO_PER_USD = 1_000_000.0;
    private static final double TOKENS_PER_K = 1_000.0;

    private CostCalculator() {}

    /**
     * The exact cost of {@code usage} at {@code rate}, in integer micro-USD. A null
     * usage (upstream omitted it) costs zero; negative token counts are caller bugs →
     * {@link IllegalArgumentException} (fail-fast, discipline).
     */
    public static long costMicroUsd(Usage usage, PricingRate rate) {
        return (long) Math.floor(tokenMicroUsd(usage, rate) + 0.5);
    }

    /**
     * The UNROUNDED token micro-USD sum — the single-rounding seam (
     * the search overload used to floor the token half first and floor the total
     * again, losing sub-half parts that summed past the boundary — 0.4μ + 0.4μ gave 0
     * instead of 1). Null usage is a plain 0.0 so search-only responses price exactly
     * their searches; negative token counts are caller bugs →
     * {@link IllegalArgumentException} (fail-fast, discipline) for both overloads.
     */
    private static double tokenMicroUsd(Usage usage, PricingRate rate) {
        if (usage == null) {
            return 0.0;
        }
        long prompt = usage.promptTokens();
        long completion = usage.completionTokens();
        long cacheRead = usage.cacheReadInputTokens() == null ? 0 : usage.cacheReadInputTokens();
        long cacheCreation = usage.cacheCreationInputTokens() == null ? 0 : usage.cacheCreationInputTokens();
        if (prompt < 0 || completion < 0 || cacheRead < 0 || cacheCreation < 0) {
            throw new IllegalArgumentException(
                    "usage token counts must be non-negative (got prompt=" + prompt + ", completion=" + completion
                            + ", cacheRead=" + cacheRead + ", cacheCreation=" + cacheCreation + ")");
        }
        return prompt * rate.inputPer1K() * MICRO_PER_USD / TOKENS_PER_K
                + completion * rate.outputPer1K() * MICRO_PER_USD / TOKENS_PER_K
                + cacheRead * rate.cacheReadPer1K() * MICRO_PER_USD / TOKENS_PER_K
                + cacheCreation * rate.cacheCreationPer1K() * MICRO_PER_USD / TOKENS_PER_K;
    }

    /**
     * Exact micro-USD including hosted web-search billing — token pricing per the
     * base method plus {@code searchCount × webSearchPer1K} (Anthropic bills searches
     * per 1k besides result tokens, which arrive as ordinary input tokens). A null
     * usage still prices its searches (a search-only response with no usage frame).
     */
    public static long costMicroUsd(Usage usage, PricingRate rate, long searchCount) {
        if (searchCount < 0) {
            throw new IllegalArgumentException("search count must be non-negative (got " + searchCount + ")");
        }
        double searchMicro = searchCount * rate.webSearchPer1K() * MICRO_PER_USD / TOKENS_PER_K;
        return (long) Math.floor(tokenMicroUsd(usage, rate) + searchMicro + 0.5);
    }

    /**
     * The conservative pre-dispatch output cost estimate in micro-USD (the
     * reserve factor): completion is the {@code estimateTokens} reserve —
     * {@code outputPer1K × estimateTokens}. The prompt half of the estimate is
     * {@link #estimatePromptMicroUsd} — the gateway sums the two into the budget
     * admission estimate; {@code settle} corrects the reservation to
     * actual at finalize.
     */
    public static long estimateMicroUsd(long estimateTokens, PricingRate rate) {
        if (estimateTokens < 0) {
            throw new IllegalArgumentException("estimateTokens must be non-negative (got " + estimateTokens + ")");
        }
        return (long) Math.floor(estimateTokens * rate.outputPer1K() * MICRO_PER_USD / TOKENS_PER_K + 0.5);
    }

    /**
     * Cost split for response headers: each term rounded independently, billed
     * {@code totalMicroUsd} from {@link #costMicroUsd(Usage, PricingRate, long)}.
     */
    public static CostBreakdown breakdown(Usage usage, PricingRate rate, long searchCount) {
        if (searchCount < 0) {
            throw new IllegalArgumentException("search count must be non-negative (got " + searchCount + ")");
        }
        long total = costMicroUsd(usage, rate, searchCount);
        long search = roundMicro(searchCount * rate.webSearchPer1K() * MICRO_PER_USD / TOKENS_PER_K);
        if (usage == null) {
            return new CostBreakdown(0, 0, 0, 0, search, total);
        }
        long prompt = usage.promptTokens();
        long completion = usage.completionTokens();
        long cacheRead = usage.cacheReadInputTokens() == null ? 0 : usage.cacheReadInputTokens();
        long cacheCreation = usage.cacheCreationInputTokens() == null ? 0 : usage.cacheCreationInputTokens();
        if (prompt < 0 || completion < 0 || cacheRead < 0 || cacheCreation < 0) {
            throw new IllegalArgumentException(
                    "usage token counts must be non-negative (got prompt=" + prompt + ", completion=" + completion
                            + ", cacheRead=" + cacheRead + ", cacheCreation=" + cacheCreation + ")");
        }
        long input = roundMicro(prompt * rate.inputPer1K() * MICRO_PER_USD / TOKENS_PER_K);
        long output = roundMicro(completion * rate.outputPer1K() * MICRO_PER_USD / TOKENS_PER_K);
        long read = roundMicro(cacheRead * rate.cacheReadPer1K() * MICRO_PER_USD / TOKENS_PER_K);
        long create = roundMicro(cacheCreation * rate.cacheCreationPer1K() * MICRO_PER_USD / TOKENS_PER_K);
        return new CostBreakdown(input, output, read, create, search, total);
    }

    private static long roundMicro(double unrounded) {
        return (long) Math.floor(unrounded + 0.5);
    }

    /**
     * The conservative pre-dispatch <b>prompt</b>-cost estimate in micro-USD (
     * — a reserve that priced prompt at 0 "unknown pre-dispatch" would let
     * a single prompt-heavy request drive the hard cap arbitrarily past cap before the
     * next-request gate caught it). {@code promptTokens} is the gateway's content-length
     * heuristic ({@code Governance.estimatePromptTokens}); each prompt token is billed
     * at exactly one of the row's three input rates, so the true per-token worst case is
     * {@code max(inputPer1K, cacheReadPer1K, cacheCreationPer1K)} and the estimate is
     * priced at that maximum: a cache-<em>creation</em> prompt — the most
     * expensive Anthropic rate, 1.25× input vs 0.1× for reads — is covered up front, and
     * {@code max(input, read)} is a strictly more accurate bound than the old
     * {@code input + read} when a row has no creation rate. Conservative by design:
     * {@code settle} corrects to actual at finalize, so an over-estimate merely releases
     * the difference.
     */
    public static long estimatePromptMicroUsd(long promptTokens, PricingRate rate) {
        if (promptTokens < 0) {
            throw new IllegalArgumentException("promptTokens must be non-negative (got " + promptTokens + ")");
        }
        double worst = Math.max(rate.inputPer1K(), Math.max(rate.cacheReadPer1K(), rate.cacheCreationPer1K()));
        double perToken = worst * MICRO_PER_USD / TOKENS_PER_K;
        return (long) Math.floor(promptTokens * perToken + 0.5);
    }
}
