package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One tool invocation (assistant {@code tool_calls}, streaming deltas). {@code index} is
 * nullable: it exists only on streaming chunks (OpenAI requires it there); non-streaming
 * request/response {@code tool_calls} carry no index. The canonical {@code ToolCall}
 * round-trips it ({@link OpenAiMessageCodec#decodeChunk} preserves the wire index, and
 * {@link OpenAiMessageCodec#encodeChunk} falls back to list position when the canonical
 * value is absent).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiToolCall(Integer index, String id, String type, OpenAiFunctionCall function) {}
