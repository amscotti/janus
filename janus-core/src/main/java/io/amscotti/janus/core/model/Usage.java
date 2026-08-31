package io.amscotti.janus.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Token accounting for a single request/response exchange. {@code totalTokens} is a
 * stored field, never recomputed from the parts — the OpenAI wire carries it; the
 * Anthropic wire derives it as input + cache-creation + cache-read + output, because
 * Anthropic's {@code input_tokens} excludes the additive cache counts.
 *
 * <p>{@code cacheCreationInputTokens}/{@code cacheReadInputTokens} are nullable and populated
 * by the Anthropic codec (cache pricing) and, for {@code cacheReadInputTokens}, by the
 * OpenAI codec ({@code prompt_tokens_details.cached_tokens} /
 * {@code prompt_cache_hit_tokens}; the cached count is subtracted from {@code promptTokens},
 * so {@code promptTokens} is <b>regular</b> input on both faces).
 * {@code reasoningTokens} is optional display/accounting from OpenAI
 * {@code completion_tokens_details.reasoning_tokens} (typically already inside
 * {@code completionTokens} for pricing). Cache/reasoning fields are
 * {@code @JsonInclude(NON_NULL)} so canonical JSON stays byte-identical when absent
 * (pinned by the model tests).
 *
 * <p><b>Hand-built-canonical contract:</b> the OpenAI encode restores the full
 * input count (prompt + cache-read) only when {@code promptTokens + completionTokens +
 * cacheReadInputTokens == totalTokens} — the exact invariant codec-produced canonicals
 * satisfy (upstream {@code total = full input + completion}). A hand-built canonical
 * whose {@code promptTokens} already includes the cached count must therefore <em>not</em>
 * satisfy that equality (i.e. keep {@code prompt + completion == total} verbatim), or
 * the OpenAI wire would double-count the cache. Codec-produced usage always satisfies
 * the contract; the negative cases are pinned in {@code OpenAiCachedUsageFixtureTest}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Usage(
        long promptTokens,
        long completionTokens,
        long totalTokens,
        Long cacheCreationInputTokens,
        Long cacheReadInputTokens,
        Long reasoningTokens) {

    /** Convenience form without cache / reasoning tokens. */
    public Usage(long promptTokens, long completionTokens, long totalTokens) {
        this(promptTokens, completionTokens, totalTokens, null, null, null);
    }

    /** Convenience form with cache tokens, no reasoning split. */
    public Usage(
            long promptTokens,
            long completionTokens,
            long totalTokens,
            Long cacheCreationInputTokens,
            Long cacheReadInputTokens) {
        this(promptTokens, completionTokens, totalTokens, cacheCreationInputTokens, cacheReadInputTokens, null);
    }

    /**
     * Vendor prompt size for long-context tiers: regular input plus cache-read
     * and cache-creation. Codecs store regular-only {@code promptTokens} (cached
     * tokens subtracted); xAI/Anthropic still count the full prompt against the
     * 200k floor.
     */
    public long billedPromptTokens() {
        long cacheRead = cacheReadInputTokens == null ? 0 : cacheReadInputTokens;
        long cacheCreation = cacheCreationInputTokens == null ? 0 : cacheCreationInputTokens;
        return promptTokens + cacheRead + cacheCreation;
    }
}
