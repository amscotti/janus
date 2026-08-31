package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code message_stop} event — terminal marker. Wire shape: {@code {"type":"message_stop"}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicMessageStop(String type) implements AnthropicSsePayload {}
