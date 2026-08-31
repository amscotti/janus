package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

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

    /** {@code input_tokens_details.cached_tokens} — canonical cache-read split. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InputTokensDetails(long cached_tokens) {}

    /** {@code output_tokens_details.reasoning_tokens} — canonical reasoning usage. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputTokensDetails(long reasoning_tokens) {}
}
