package io.amscotti.janus.core.model;

/**
 * A declared tool <em>definition</em> on a {@link ChatRequest} (the {@code tools} array) —
 * distinct from a {@link ToolCall}, which is a tool <em>invocation</em> on an assistant
 * message or streaming delta. Definitions and invocations are different shapes and this
 * type split makes the conflation impossible at compile time (the canonical
 * previously stored definitions as {@code ToolCall}s with always-null {@code id}/{@code index}
 * and a {@code FunctionCall.arguments} that carried a JSON-<em>schema</em> string in the
 * definition path but real call arguments in the invocation path — a live footgun).
 *
 * <p>{@code type} is nullable and defaults to {@code "function"} on the OpenAI wire
 * (mirrors the old {@code ToolCall.type} default). {@code inputSchema} is the raw JSON
 * <em>schema</em> string (OpenAI {@code parameters} / Anthropic {@code input_schema}) —
 * parsing it to a structured object is a codec concern, never this model, exactly like
 * {@link FunctionCall#arguments} for invocations. {@code description} is the tool's
 * human-readable description ({@code null} unless the caller set it).
 *
 * <p><b>{@code strict}.</b> OpenAI structured-outputs'
 * {@code function.strict} flag — a first-class slot because it cannot ride extras (the
 * extras merge is top-level only and {@code tools} is a mapped key). {@code null} means
 * absent: the OpenAI egress omits it (byte-identical to pre-slot behavior), the
 * Anthropic egress drops it (no Anthropic equivalent — documented). The Responses face
 * maps its flat {@code tools[].strict} here.
 */
public record ToolDefinition(
        String type, String name, String description, String inputSchema, Boolean strict, Object cacheControl) {

    /** Compatibility form at the pre-cache-control arity. */
    public ToolDefinition(String type, String name, String description, String inputSchema, Boolean strict) {
        this(type, name, description, inputSchema, strict, null);
    }

    /** Compatibility form at the pre-strict arity — {@code strict} defaults to absent. */
    public ToolDefinition(String type, String name, String description, String inputSchema) {
        this(type, name, description, inputSchema, null, null);
    }

    /** Convenience form without an explicit {@code type} — the OpenAI wire defaults it to
     * {@code "function"}. */
    public ToolDefinition(String name, String description, String inputSchema) {
        this(null, name, description, inputSchema, null, null);
    }
}
