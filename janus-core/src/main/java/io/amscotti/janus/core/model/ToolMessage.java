package io.amscotti.janus.core.model;

/**
 * Tool result message, linked back to the originating {@link ToolCall} by id.
 * {@code name} is the optional OpenAI wire field (participant name, legal on tool
 * messages); {@code null} unless the caller set it — the OpenAI codec maps it from/to
 * {@code OpenAiMessage.name}.
 */
public record ToolMessage(String toolCallId, String content, String name) implements Message {

    /** Convenience form without a participant name. */
    public ToolMessage(String toolCallId, String content) {
        this(toolCallId, content, null);
    }

    @Override
    public ChatRole role() {
        return ChatRole.TOOL;
    }

    /** Conversation text (tool results) is excluded from log output unless content
     * logging is explicitly enabled ({@code [janus.privacy] log-content}). */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "ToolMessage[toolCallId=" + toolCallId + ", content=" + content + ", name=" + name + "]";
        }
        return "ToolMessage[toolCallId=" + toolCallId + ", name=" + name + ", content not logged]";
    }
}
