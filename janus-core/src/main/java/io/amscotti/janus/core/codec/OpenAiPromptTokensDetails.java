package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code prompt_tokens_details} object OpenAI and OpenAI-compatible endpoints attach to
 * usage: the breakdown of {@code prompt_tokens} by token class. {@code cached_tokens} is
 * cache-read; {@code cache_write_tokens} is GPT-5.6+ cache-creation. Sibling members
 * ({@code audio_tokens}, {@code reasoning_tokens}, …) are silently dropped, matching
 * the codec's tolerant-decode contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiPromptTokensDetails(Long cachedTokens, Long cacheWriteTokens) {

    /** Cache-read only (no cache-write split). */
    public OpenAiPromptTokensDetails(Long cachedTokens) {
        this(cachedTokens, null);
    }
}
