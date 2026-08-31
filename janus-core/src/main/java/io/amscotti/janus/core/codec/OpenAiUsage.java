package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Token accounting on the wire ({@code prompt_tokens}/{@code completion_tokens}/
 * {@code total_tokens}). Components are nullable because {@code total_tokens} may be
 * absent (the codec defaults it to prompt + completion) and {@code usage} itself is
 * absent unless {@code stream_options.include_usage} was requested.
 *
 * <p><b>Cache tokens.</b> {@code prompt_tokens_details.cached_tokens} is the OpenAI
 * spelling of cache-read input tokens and {@code prompt_cache_hit_tokens} the DeepSeek/
 * Kimi top-level alias. Both are captured so {@code OpenAiMessageCodec.toCanonicalUsage}
 * can split {@code prompt_tokens} into regular + cache-read and price the cached portion
 * at the cache rate (the {@link Usage} convention).
 *
 * <p><b>Reasoning tokens.</b> {@code completion_tokens_details.reasoning_tokens} maps to
 * {@link io.amscotti.janus.core.model.Usage#reasoningTokens} for accounting transparency;
 * providers typically already include those tokens inside {@code completion_tokens}, so
 * pricing still uses the prompt + completion rates (no double bill).
 *
 * <p>Unknown members of {@code usage} and of {@code prompt_tokens_details} /
 * {@code completion_tokens_details} are silently dropped (documented scope).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiUsage(
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        OpenAiPromptTokensDetails promptTokensDetails,
        Long promptCacheHitTokens,
        OpenAiCompletionTokensDetails completionTokensDetails) {

    /** Backward-compatible form without completion-token details. */
    public OpenAiUsage(
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            OpenAiPromptTokensDetails promptTokensDetails,
            Long promptCacheHitTokens) {
        this(promptTokens, completionTokens, totalTokens, promptTokensDetails, promptCacheHitTokens, null);
    }
}
