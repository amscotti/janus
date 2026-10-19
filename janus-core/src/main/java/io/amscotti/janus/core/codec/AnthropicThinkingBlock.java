package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An extended/adaptive-thinking content block. Wire shape:
 * {@code {"type":"thinking","thinking":"...","signature":"..."}}. The codec
 * maps these to/from canonical {@code ReasoningContent} (text + signature)
 * on assistant messages so signed thinking survives tool-loop replay.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicThinkingBlock(String type, String thinking, String signature) implements AnthropicContentBlock {}
