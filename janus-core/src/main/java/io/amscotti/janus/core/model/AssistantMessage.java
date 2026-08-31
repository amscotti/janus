package io.amscotti.janus.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Assistant message. {@code toolCalls} is nullable (absent unless the assistant emitted
 * tool calls); {@link ToolCall#function}.{@code arguments} stays raw JSON. {@code name}
 * is the optional OpenAI wire field (participant name, legal on assistant messages);
 * {@code null} unless the caller set it — the OpenAI codec maps it from/to
 * {@code OpenAiMessage.name}.
 */
public record AssistantMessage(String content, List<ToolCall> toolCalls, String name) implements Message {

    /** Convenience form without a participant name. */
    public AssistantMessage(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, null);
    }

    public AssistantMessage {
        // Null-tolerant unmodifiable copy (matches ChatRequest's list contract): a null
        // element is malformed wire input, but it must not escape construction as a raw
        // NullPointerException from List.copyOf — the codecs reject it with a typed
        // error.
        toolCalls = toolCalls == null ? null : Collections.unmodifiableList(new ArrayList<>(toolCalls));
    }

    @Override
    public ChatRole role() {
        return ChatRole.ASSISTANT;
    }

    /** Conversation text is excluded from log output unless content logging is
     * explicitly enabled ({@code [janus.privacy] log-content}). */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "AssistantMessage[content=" + content + ", toolCalls=" + toolCalls + ", name=" + name + "]";
        }
        return "AssistantMessage[toolCalls=" + (toolCalls == null ? 0 : toolCalls.size()) + ", name=" + name
                + ", content not logged]";
    }
}
