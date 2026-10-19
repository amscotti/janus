package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Catch-all for {@code content_block_delta} delta types this codec does not
 * model. Jackson 3's {@code defaultImpl} on {@link AnthropicDelta}
 * deserializes any unrecognized {@code type} id into this record so an
 * unknown delta never aborts the stream (Anthropic's versioning contract).
 * Never re-emitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicUnknownDelta() implements AnthropicDelta {}
