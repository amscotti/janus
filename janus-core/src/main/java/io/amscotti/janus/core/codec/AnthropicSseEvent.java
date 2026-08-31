package io.amscotti.janus.core.codec;

/**
 * One typed SSE frame produced by {@link AnthropicStreamEncoder}: the {@code event} name
 * (e.g. {@code message_start}) plus the raw JSON {@code data} payload —
 * {@link AnthropicMessageCodec#decodeChunk} consumes exactly these two fields. The codec
 * is frame-agnostic — SSE transport framing ({@code "event: X\ndata: Y\n\n"} line
 * encoding, {@code [DONE]}-style termination) is the gateway's job (/,
 * precedent: framing was the publisher's concern).
 */
public record AnthropicSseEvent(String event, String dataJson) {}
