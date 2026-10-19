package io.amscotti.janus.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Assistant message. {@code toolCalls} is nullable (absent unless the assistant emitted
 * tool calls); {@link ToolCall#function}.{@code arguments} stays raw JSON. {@code name}
 * is the optional OpenAI wire field (participant name, legal on assistant messages);
 * {@code null} unless the caller set it — the OpenAI codec maps it from/to
 * {@code OpenAiMessage.name}. {@code reasoningContents} holds complete reasoning
 * blocks (signed Anthropic thinking, redacted thinking, or unsigned foreign
 * {@code reasoning_content} text) in wire order; {@code null} when absent.
 */
public record AssistantMessage(
        String content, List<ToolCall> toolCalls, String name, List<ReasoningContent> reasoningContents)
        implements Message {

    /** Convenience form without a participant name or reasoning content. */
    public AssistantMessage(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, null, null);
    }

    /** Compatibility form at the earlier (3-arg) arity — no reasoning content. */
    public AssistantMessage(String content, List<ToolCall> toolCalls, String name) {
        this(content, toolCalls, name, null);
    }

    public AssistantMessage {
        // Null-tolerant unmodifiable copy (matches ChatRequest's list contract): a null
        // element is malformed wire input, but it must not escape construction as a raw
        // NullPointerException from List.copyOf — the codecs reject it with a typed
        // error.
        toolCalls = toolCalls == null ? null : Collections.unmodifiableList(new ArrayList<>(toolCalls));
        reasoningContents =
                reasoningContents == null ? null : Collections.unmodifiableList(new ArrayList<>(reasoningContents));
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
            return "AssistantMessage[content=" + content + ", toolCalls=" + toolCalls + ", name=" + name
                    + ", reasoningContents=" + reasoningContents + "]";
        }
        return "AssistantMessage[toolCalls=" + (toolCalls == null ? 0 : toolCalls.size()) + ", name=" + name
                + ", reasoningContents=" + (reasoningContents == null ? 0 : reasoningContents.size())
                + ", content not logged]";
    }
}
