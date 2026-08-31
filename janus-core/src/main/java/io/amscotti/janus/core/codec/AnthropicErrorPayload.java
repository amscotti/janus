package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code error} SSE event payload. Wire shape:
 * {@code {"type":"error","error":{"type":"...","message":"..."}}}. decode maps it to
 * an {@link AnthropicCodecException} of type {@link AnthropicCodecException#TYPE_API_ERROR}
 * with the payload message (ready for error-envelope mapping); envelope rendering
 * lives in the gateway's error mapper.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicErrorPayload(String type, AnthropicErrorBody error) implements AnthropicSsePayload {}
