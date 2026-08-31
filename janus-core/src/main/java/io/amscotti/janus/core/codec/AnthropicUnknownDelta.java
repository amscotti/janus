package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Catch-all for {@code content_block_delta} delta types this codec does not model
 * ({@code thinking_delta}, {@code signature_delta}, …). Jackson 3's {@code defaultImpl}
 * on {@link AnthropicDelta} deserializes any unrecognized {@code type} id into this
 * record so an unknown delta no longer aborts the stream (extended-thinking streams
 * deliver {@code thinking_delta}/{@code signature_delta} frames the codec drops — the
 * canonical model has no thinking-content home). Never re-emitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicUnknownDelta() implements AnthropicDelta {}
