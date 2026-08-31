package io.amscotti.janus.store;

/**
 * Per-request cost split in integer micro-USD. {@link #totalMicroUsd} is the
 * billed amount ({@link CostCalculator#costMicroUsd}); the other fields are the
 * same arithmetic split into input, output, cache-read, cache-creation, and
 * hosted-search terms (each rounded independently). Components may sum to
 * {@code total} ± 1 micro because the billed total rounds the unrounded sum
 * once.
 *
 * <p>Safe for response headers: integers only, no model alias, no prompt text.
 */
public record CostBreakdown(
        long inputMicroUsd,
        long outputMicroUsd,
        long cacheReadMicroUsd,
        long cacheCreationMicroUsd,
        long searchMicroUsd,
        long totalMicroUsd) {

    public static final CostBreakdown ZERO = new CostBreakdown(0, 0, 0, 0, 0, 0);
}
