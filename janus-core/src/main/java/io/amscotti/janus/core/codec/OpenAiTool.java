package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A tool declaration on an OpenAI request ({@code "type":"function"} + function
 * metadata). The canonical request models tools as {@code ToolCall} whose
 * {@code FunctionCall.arguments} carries the JSON-schema string; the codec parses it
 * into {@link OpenAiFunction#parameters} on encode and serializes it back on decode.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiTool(String type, OpenAiFunction function) {}
