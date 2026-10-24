package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The {@code prompt_tokens_details} object OpenAI and OpenAI-compatible endpoints attach to
 * usage: the breakdown of {@code prompt_tokens} by token class. {@code cached_tokens} is
 * cache-read; {@code cache_write_tokens} is GPT-5.6+ cache-creation, split by minimum
 * lifetime in {@code cache_write_tokens_details}. Sibling members
 * ({@code audio_tokens}, {@code reasoning_tokens}, …) are silently dropped, matching
 * the codec's tolerant-decode contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiPromptTokensDetails(
        Long cachedTokens, Long cacheWriteTokens, CacheWriteTokensDetails cacheWriteTokensDetails) {

    /** Compatibility form before the cache-write lifetime split. */
    public OpenAiPromptTokensDetails(Long cachedTokens, Long cacheWriteTokens) {
        this(cachedTokens, cacheWriteTokens, null);
    }

    /** Cache-read only (no cache-write split). */
    public OpenAiPromptTokensDetails(Long cachedTokens) {
        this(cachedTokens, null, null);
    }

    /** {@code prompt_tokens_details.cache_write_tokens_details} for GPT-5.6+ usage. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CacheWriteTokensDetails(
            @JsonProperty("ttl_30m_tokens") Long ttl30mTokens,
            @JsonProperty("ttl_12h_tokens") Long ttl12hTokens) {}
}
