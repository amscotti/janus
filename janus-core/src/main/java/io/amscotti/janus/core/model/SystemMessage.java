package io.amscotti.janus.core.model;

/**
 * System prompt message. {@code content} is plain text in the canonical model.
 *
 * <p>{@code name} is the optional OpenAI wire field (participant name, legal on system
 * messages — the OpenAI schema allows {@code name} on every role); {@code null} unless
 * the caller set it. Mirroring {@link UserMessage}/{@link AssistantMessage}/
 * {@link ToolMessage}: the OpenAI codec maps {@code OpenAiMessage.name} from/to
 * {@code SystemMessage.name} and re-emits it inside the message. A <em>named</em> system
 * message keeps its per-message canonical home and stays in {@link ChatRequest#messages}
 * (it is <em>not</em> flattened into {@code ChatRequest.system}); an unnamed system
 * message flattens as documented. The Anthropic wire has no per-message name home — the
 * value is dropped on the Anthropic leg (documented non-idempotence).
 */
public record SystemMessage(String content, String name) implements Message {

    /** Convenience form without a participant name. */
    public SystemMessage(String content) {
        this(content, null);
    }

    @Override
    public ChatRole role() {
        return ChatRole.SYSTEM;
    }

    /** Conversation text is excluded from log output unless content logging is
     * explicitly enabled ({@code [janus.privacy] log-content}). */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "SystemMessage[content=" + content + ", name=" + name + "]";
        }
        return "SystemMessage[name=" + name + ", content not logged]";
    }
}
