package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A redacted-thinking content block. Wire shape:
 * {@code {"type":"redacted_thinking","data":"..."}} — {@code data} is an opaque
 * provider payload that must replay byte-identically in tool loops; the codec
 * maps it to/from {@code ReasoningContent.redactedData}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicRedactedThinkingBlock(String type, String data) implements AnthropicContentBlock {}
