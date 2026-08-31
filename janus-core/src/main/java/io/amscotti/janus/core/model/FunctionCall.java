package io.amscotti.janus.core.model;

/**
 * A declared tool function. {@code arguments} is the raw JSON argument string (OpenAI
 * semantics) — never parsed or re-serialized by the canonical model; for a <em>tool
 * definition</em> it carries the JSON-schema string (see {@code ChatRequest.tools}).
 *
 * <p>{@code description} is nullable: it is the tool-definition description (OpenAI
 * {@code functions[].description} / Anthropic {@code tools[].description}) — gives
 * it a canonical home so descriptions round-trip through both codecs (the previous
 * design folded it into request {@code extras} under a lossy last-wins
 * {@code "description"} key; that hack is deleted).
 */
public record FunctionCall(String name, String arguments, String description) {

    /** Convenience form without a description — every existing call site compiles
     * untouched. */
    public FunctionCall(String name, String arguments) {
        this(name, arguments, null);
    }
    /** Tool arguments are conversation-derived text: excluded from log output unless
     * content logging is explicitly enabled ({@code [janus.privacy] log-content}). */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "FunctionCall[name=" + name + ", arguments=" + arguments + ", description=" + description + "]";
        }
        return "FunctionCall[name=" + name + ", description=" + description + ", arguments not logged]";
    }
}
