package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The plain text content block — the only block the codec translates. Wire shape:
 * {@code {"type":"text","text":"..."}} plus optional {@code cache_control}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicTextBlock(String type, String text, Object cacheControl) implements AnthropicContentBlock {

    /** Text block with no cache marker. */
    public AnthropicTextBlock(String type, String text) {
        this(type, text, null);
    }
}
