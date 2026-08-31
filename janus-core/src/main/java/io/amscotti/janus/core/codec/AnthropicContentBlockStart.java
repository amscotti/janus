package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code content_block_start} event. Wire shape:
 * {@code {"type":"content_block_start","index":0,"content_block":{...}}}. On decode a
 * {@code tool_use} block becomes the first-fragment tool-call chunk (id + name + empty
 * arguments); text block-open signals carry no canonical content → ignored. On
 * stream-encode the encoder synthesizes these for both text and {@code tool_use} blocks
 * (block indices assigned in arrival order — ).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicContentBlockStart(String type, int index, AnthropicContentBlock contentBlock)
        implements AnthropicSsePayload {}
