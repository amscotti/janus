package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The inner {@code error} object of an {@link AnthropicErrorPayload}. Wire shape:
 * {@code {"type":"...","message":"..."}} — {@code type} is the upstream error-type
 * vocabulary ({@code invalid_request_error}, {@code overloaded_error},...), distinct
 * from the codec's own {@link AnthropicCodecException#type} discriminator.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicErrorBody(String type, String message) {}
