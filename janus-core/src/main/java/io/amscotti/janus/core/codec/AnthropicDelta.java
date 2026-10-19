package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A {@code content_block_delta} payload's inner {@code delta}, discriminated by the wire
 * {@code type} property ({@code text_delta} | {@code input_json_delta} |
 * {@code thinking_delta} | {@code signature_delta}).
 *
 * <p>The codec translates all four: {@link AnthropicTextDelta} → text-delta
 * chunks, {@link AnthropicInputJsonDelta} → verbatim tool-call fragment
 * chunks, {@link AnthropicThinkingDelta}/{@link AnthropicSignatureDelta} →
 * reasoning-fragment chunks. {@link AnthropicUnknownDelta} is the
 * {@code defaultImpl} catch-all for unrecognized {@code type} ids — the codec
 * drops them instead of aborting the stream (Anthropic's versioning contract).
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        defaultImpl = AnthropicUnknownDelta.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = AnthropicTextDelta.class, name = "text_delta"),
    @JsonSubTypes.Type(value = AnthropicInputJsonDelta.class, name = "input_json_delta"),
    @JsonSubTypes.Type(value = AnthropicThinkingDelta.class, name = "thinking_delta"),
    @JsonSubTypes.Type(value = AnthropicSignatureDelta.class, name = "signature_delta"),
})
public sealed interface AnthropicDelta
        permits AnthropicTextDelta,
                AnthropicInputJsonDelta,
                AnthropicThinkingDelta,
                AnthropicSignatureDelta,
                AnthropicUnknownDelta {}
