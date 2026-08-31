package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An extended-thinking content block (response side). Wire shape:
 * {@code {"type":"thinking","thinking":"...","signature":"..."}}. The codec decodes
 * these by <em>dropping</em> them (the canonical model has no thinking-content home;
 * the reference rejects them from choices — documented + pinned). Extended-thinking content
 * pipeline (block retention, {@code reasoning} payload modeling) is deferred.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicThinkingBlock(String type, String thinking, String signature) implements AnthropicContentBlock {}
