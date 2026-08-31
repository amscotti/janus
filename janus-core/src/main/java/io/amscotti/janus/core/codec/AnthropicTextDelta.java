package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Text streaming delta. Wire shape: {@code {"type":"text_delta","text":"..."}} — the
 * plain-path streaming unit the codec translates.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicTextDelta(String type, String text) implements AnthropicDelta {}
