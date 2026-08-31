package io.amscotti.janus.core.model;

/**
 * A tool invocation on a {@link ChatRequest} or an assistant message. {@code type} is
 * nullable (OpenAI defaults it to {@code "function"}); {@code arguments} is the raw JSON
 * argument string — parsing to structured args is a codec concern, never this model.
 *
 * <p>{@code index} is the streaming wire index (OpenAI tool-call deltas) — {@code null}
 * outside streaming. The codec round-trips it so a passthrough that decodes upstream
 * chunks and re-encodes them does not renumber multi-tool-call fragments.
 */
public record ToolCall(String id, String type, FunctionCall function, Integer index) {

    /** Non-streaming convenience form — {@code index} stays null. */
    public ToolCall(String id, String type, FunctionCall function) {
        this(id, type, function, null);
    }
}
