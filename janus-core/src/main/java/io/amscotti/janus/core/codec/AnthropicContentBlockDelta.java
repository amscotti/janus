package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code content_block_delta} event. Wire shape:
 * {@code {"type":"content_block_delta","index":0,"delta":{...}}} where the inner
 * {@code delta} is an {@link AnthropicDelta} — {@code text_delta} → text-delta chunks,
 * {@code input_json_delta} → verbatim tool-call fragment chunks keyed by the block
 * index.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicContentBlockDelta(String type, int index, AnthropicDelta delta) implements AnthropicSsePayload {}
