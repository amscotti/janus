package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tool-arguments streaming delta. Wire shape:
 * {@code {"type":"input_json_delta","partial_json":"..."}}. {@link
 * AnthropicMessageCodec#decodeChunk} translates these to canonical tool-call fragment
 * chunks — {@code partial_json} passes through <em>verbatim</em> (the codec never
 * accumulates or validates partial JSON — the value is opaque here).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicInputJsonDelta(String type, String partialJson) implements AnthropicDelta {}
