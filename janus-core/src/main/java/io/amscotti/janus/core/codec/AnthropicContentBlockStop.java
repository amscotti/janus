package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code content_block_stop} event. Wire shape:
 * {@code {"type":"content_block_stop","index":0}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicContentBlockStop(String type, int index) implements AnthropicSsePayload {}
