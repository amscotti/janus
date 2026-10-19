package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Thinking-signature streaming delta. Wire shape:
 * {@code {"type":"signature_delta","signature":"..."}} — maps to/from a
 * canonical {@code Delta.reasoningContent} signature fragment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicSignatureDelta(String type, String signature) implements AnthropicDelta {}
