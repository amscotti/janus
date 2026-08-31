package io.amscotti.janus.store;

/**
 * One pricing row: USD-per-1K-token rates keyed by <b>model alias</b>. {@code
 * defaultMaxTokens} is the per-model reserve factor when a request omits
 * {@code max_tokens} (gateway fallback 4096).
 *
 * <p><b>Long-context tier.</b> Vendors (xAI Grok, Anthropic, …) often charge a
 * higher rate once prompt tokens cross a threshold (commonly 200k). When
 * {@code longContextThreshold > 0} and prompt tokens are at or above it, {@link
 * #forPromptTokens(long)} returns a row whose input/output/cache rates are the
 * {@code long-*} values. The whole request is billed at the higher tier (vendor
 * typical: not blended). Threshold 0 disables the feature.
 *
 * <p>Cache rates are optional — absent ⇒ 0. All components are non-negative
 * (fail-fast at construction).
 *
 * @param inputPer1K USD per 1K prompt tokens
 * @param outputPer1K USD per 1K completion tokens
 * @param cacheReadPer1K USD per 1K cache-read tokens; 0 when omitted
 * @param cacheCreationPer1K USD per 1K cache-creation tokens; 0 when omitted
 * @param defaultMaxTokens reserve factor; 0 ⇒ gateway 4096 fallback
 * @param webSearchPer1K USD per 1K hosted web searches; 0 when omitted
 * @param longContextThreshold prompt-token floor for the long-context tier; 0 = off
 * @param longInputPer1K long-tier input rate
 * @param longOutputPer1K long-tier output rate
 * @param longCacheReadPer1K long-tier cache-read rate
 * @param longCacheCreationPer1K long-tier cache-creation rate
 */
public record PricingRate(
        double inputPer1K,
        double outputPer1K,
        double cacheReadPer1K,
        double cacheCreationPer1K,
        int defaultMaxTokens,
        double webSearchPer1K,
        int longContextThreshold,
        double longInputPer1K,
        double longOutputPer1K,
        double longCacheReadPer1K,
        double longCacheCreationPer1K) {

    /** Compatibility form with per-search pricing and no long-context tier. */
    public PricingRate(
            double inputPer1K,
            double outputPer1K,
            double cacheReadPer1K,
            double cacheCreationPer1K,
            int defaultMaxTokens,
            double webSearchPer1K) {
        this(
                inputPer1K,
                outputPer1K,
                cacheReadPer1K,
                cacheCreationPer1K,
                defaultMaxTokens,
                webSearchPer1K,
                0,
                0.0,
                0.0,
                0.0,
                0.0);
    }

    /** Compatibility form at the earlier (5-arg) arity — no per-search pricing. */
    public PricingRate(
            double inputPer1K,
            double outputPer1K,
            double cacheReadPer1K,
            double cacheCreationPer1K,
            int defaultMaxTokens) {
        this(inputPer1K, outputPer1K, cacheReadPer1K, cacheCreationPer1K, defaultMaxTokens, 0.0);
    }

    /**
     * The zero rate for unknown models: metering never crashes on a new model —
     * cost is $0 until an operator sets a rate. Logged once per unknown model by
     * {@link PriceTable}.
     */
    public static final PricingRate ZERO = new PricingRate(0.0, 0.0, 0.0, 0.0, 0);

    /** Convenience form without cache rates. */
    public PricingRate(double inputPer1K, double outputPer1K, int defaultMaxTokens) {
        this(inputPer1K, outputPer1K, 0.0, 0.0, defaultMaxTokens);
    }

    public PricingRate {
        if (inputPer1K < 0
                || outputPer1K < 0
                || cacheReadPer1K < 0
                || cacheCreationPer1K < 0
                || defaultMaxTokens < 0
                || webSearchPer1K < 0
                || longContextThreshold < 0
                || longInputPer1K < 0
                || longOutputPer1K < 0
                || longCacheReadPer1K < 0
                || longCacheCreationPer1K < 0) {
            throw new IllegalArgumentException(
                    "rates, default-max-tokens and long-context knobs must be non-negative (got input="
                            + inputPer1K
                            + ", output="
                            + outputPer1K
                            + ", cacheRead="
                            + cacheReadPer1K
                            + ", cacheCreation="
                            + cacheCreationPer1K
                            + ", defaultMaxTokens="
                            + defaultMaxTokens
                            + ", webSearch="
                            + webSearchPer1K
                            + ", longContextThreshold="
                            + longContextThreshold
                            + ")");
        }
        if (longContextThreshold > 0
                && longInputPer1K == 0.0
                && longOutputPer1K == 0.0
                && longCacheReadPer1K == 0.0
                && longCacheCreationPer1K == 0.0) {
            throw new IllegalArgumentException(
                    "long-context-threshold is set but every long-* rate is 0 — set long-input-per-1k /"
                            + " long-output-per-1k (and cache rates if needed)");
        }
    }

    /**
     * The rate that applies to a request whose prompt is {@code promptTokens}
     * long. Below the threshold (or when the tier is off) this row is returned
     * unchanged; at or above the threshold the long-context rates apply to the
     * <em>whole</em> request (not blended).
     */
    public PricingRate forPromptTokens(long promptTokens) {
        if (longContextThreshold <= 0 || promptTokens < longContextThreshold) {
            return this;
        }
        return new PricingRate(
                longInputPer1K,
                longOutputPer1K,
                longCacheReadPer1K,
                longCacheCreationPer1K,
                defaultMaxTokens,
                webSearchPer1K);
    }
}
