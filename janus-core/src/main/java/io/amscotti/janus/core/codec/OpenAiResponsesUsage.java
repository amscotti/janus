package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The Responses API usage object: token counts with the <b>full</b> input on the left
 * (cached tokens counted INSIDE {@code input_tokens}, split out in
 * {@code input_tokens_details}) — the same convention as the OpenAI chat wire, so the
 * canonical regular/cache split is re-merged on encode (the inverse of the chat
 * chat codec's cache-split decode).
 *
 * <p>Every component is non-null on encode (details objects only when their counts
 * exist — {@code @JsonInclude(NON_NULL)} on those members).
 */
public record OpenAiResponsesUsage(
        long input_tokens,
        long output_tokens,
        long total_tokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) InputTokensDetails input_tokens_details,
        @JsonInclude(JsonInclude.Include.NON_NULL) OutputTokensDetails output_tokens_details) {

    /** {@code input_tokens_details} — canonical cache-read/write split. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InputTokensDetails(
            long cached_tokens,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long cache_write_tokens,
            @JsonInclude(JsonInclude.Include.NON_NULL) CacheWriteTokensDetails cache_write_tokens_details) {

        /** Compatibility form carrying only cache-read tokens. */
        public InputTokensDetails(long cached_tokens) {
            this(cached_tokens, null, null);
        }
    }

    /** OpenAI's cache-write lifetime breakdown. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CacheWriteTokensDetails(
            @JsonProperty("ttl_30m_tokens") Long ttl30mTokens,
            @JsonProperty("ttl_12h_tokens") Long ttl12hTokens) {}

    /** {@code output_tokens_details.reasoning_tokens} — canonical reasoning usage. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputTokensDetails(long reasoning_tokens) {}
}
