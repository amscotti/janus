package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A {@code content_block_delta} payload's inner {@code delta}, discriminated by the wire
 * {@code type} property ({@code text_delta} | {@code input_json_delta}).
 *
 * <p>The codec translates both: {@link AnthropicTextDelta} → text-delta chunks and
 * {@link AnthropicInputJsonDelta} → verbatim tool-call fragment chunks.
 * {@link AnthropicUnknownDelta} is the {@code defaultImpl} catch-all for unrecognized
 * {@code type} ids ({@code thinking_delta}, {@code signature_delta}, …) — the codec
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
})
public sealed interface AnthropicDelta permits AnthropicTextDelta, AnthropicInputJsonDelta, AnthropicUnknownDelta {}
