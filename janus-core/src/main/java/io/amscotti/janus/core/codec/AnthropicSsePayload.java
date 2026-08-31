package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One SSE event payload, discriminated by the wire {@code type} property
 * ({@code message_start} | {@code content_block_start} | {@code content_block_delta} |
 * {@code content_block_stop} | {@code message_delta} | {@code message_stop} | {@code ping}
 * | {@code error}), which is a real record component on every subtype
 * ({@code @JsonTypeInfo(As.EXISTING_PROPERTY)}).
 *
 * <p>{@link AnthropicMessageCodec#decodeChunk} dispatches on the SSE {@code event} line
 * and maps each payload to a canonical {@code StreamChunk}: text and tool-arguments
 * deltas, finish/usage on {@code message_delta}; block-open/stop, {@code message_stop}
 * and {@code ping} are no-ops. The event payloads carry no extras capture (the same
 * precedent — documented).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AnthropicMessageStart.class, name = "message_start"),
    @JsonSubTypes.Type(value = AnthropicContentBlockStart.class, name = "content_block_start"),
    @JsonSubTypes.Type(value = AnthropicContentBlockDelta.class, name = "content_block_delta"),
    @JsonSubTypes.Type(value = AnthropicContentBlockStop.class, name = "content_block_stop"),
    @JsonSubTypes.Type(value = AnthropicMessageDelta.class, name = "message_delta"),
    @JsonSubTypes.Type(value = AnthropicMessageStop.class, name = "message_stop"),
    @JsonSubTypes.Type(value = AnthropicPing.class, name = "ping"),
    @JsonSubTypes.Type(value = AnthropicErrorPayload.class, name = "error"),
})
public sealed interface AnthropicSsePayload
        permits AnthropicMessageStart,
                AnthropicContentBlockStart,
                AnthropicContentBlockDelta,
                AnthropicContentBlockStop,
                AnthropicMessageDelta,
                AnthropicMessageStop,
                AnthropicPing,
                AnthropicErrorPayload {}
