package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * An Anthropic content block, discriminated by the wire {@code type} property, which is
 * a real record component on every subtype ({@code @JsonTypeInfo(As.EXISTING_PROPERTY)};
 * the wire output is exactly one {@code type} field per block).
 *
 * <p>Mapping contract (see {@link AnthropicMessageCodec}): {@link AnthropicTextBlock}
 * translates to text; {@code tool_use}/{@code tool_result} blocks translate to canonical
 * {@code ToolCall}s/{@code ToolMessage}s; user-message {@link AnthropicImageBlock}s
 * decode into canonical multimodal parts and encode re-emits them (the request face's
 * vision path — pinned by {@code AnthropicRequestCodecTest} and docs/providers.md
 * "Vision"); on the response/stream paths image blocks are dropped, as are
 * {@link AnthropicThinkingBlock}s (thinking deferred) and non-{@code web_search}
 * {@link AnthropicServerToolUseBlock}s — the decode paths must agree, so a payload
 * that streams also decodes. {@link AnthropicUnknownBlock} is the {@code defaultImpl}
 * catch-all for unrecognized {@code type} ids ({@code web_search_tool_result},
 * {@code fallback}, …) — dropped on the response paths (Anthropic's versioning
 * contract) and a typed invalid-request error on the request face (client content is
 * never silently dropped). Blocks carry no extras capture — unknown block fields are
 * dropped by design.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        defaultImpl = AnthropicUnknownBlock.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = AnthropicTextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = AnthropicToolUseBlock.class, name = "tool_use"),
    @JsonSubTypes.Type(value = AnthropicToolResultBlock.class, name = "tool_result"),
    @JsonSubTypes.Type(value = AnthropicImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = AnthropicThinkingBlock.class, name = "thinking"),
    @JsonSubTypes.Type(value = AnthropicServerToolUseBlock.class, name = "server_tool_use"),
})
public sealed interface AnthropicContentBlock
        permits AnthropicTextBlock,
                AnthropicToolUseBlock,
                AnthropicToolResultBlock,
                AnthropicImageBlock,
                AnthropicThinkingBlock,
                AnthropicServerToolUseBlock,
                AnthropicUnknownBlock {}
