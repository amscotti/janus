package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code message_start} event: embeds the partial {@link AnthropicMessageResponse}
 * (id, type {@code "message"}, role, model, empty {@code content}, {@code usage} with
 * zeroed output tokens). Wire shape:
 * {@code {"type":"message_start","message":{...}}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicMessageStart(String type, AnthropicMessageResponse message) implements AnthropicSsePayload {}
