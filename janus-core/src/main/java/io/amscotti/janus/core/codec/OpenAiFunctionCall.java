package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The function part of a tool invocation: {@code arguments} is the raw JSON argument
 * string (OpenAI semantics) — never parsed or re-serialized by the codec. Both
 * components are nullable because streaming tool-call fragments arrive incrementally
 * (name in the first fragment, arguments in later ones).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiFunctionCall(String name, String arguments) {}
