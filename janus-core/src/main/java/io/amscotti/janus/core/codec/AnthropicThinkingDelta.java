package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Thinking streaming delta. Wire shape:
 * {@code {"type":"thinking_delta","thinking":"..."}} — maps to/from a canonical
 * {@code Delta.reasoningContent} text fragment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicThinkingDelta(String type, String thinking) implements AnthropicDelta {}
