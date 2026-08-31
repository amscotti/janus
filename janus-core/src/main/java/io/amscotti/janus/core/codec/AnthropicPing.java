package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The keepalive {@code ping} event. Wire shape: {@code {"type":"ping"}}. ignores it
 * on decode ({@link AnthropicMessageCodec#decodeChunk} returns {@code null}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicPing(String type) implements AnthropicSsePayload {}
