package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Catch-all for Anthropic content-block types this codec does not model
 * ({@code web_search_tool_result}, {@code fallback}, …). Jackson 3's
 * {@code defaultImpl} on {@link AnthropicContentBlock} deserializes any unrecognized
 * {@code type} id into this record so an unknown block never aborts Jackson parsing —
 * what the codec then does with it is path-dependent (Anthropic's versioning contract:
 * an upstream addition must not abort a client's stream). Response decode (streaming
 * and non-streaming) <em>drops</em> unknown blocks — they have no canonical home and
 * are never re-emitted. Request-message decode is a typed invalid-request error:
 * silently dropping client-supplied content would corrupt the conversation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicUnknownBlock() implements AnthropicContentBlock {}
