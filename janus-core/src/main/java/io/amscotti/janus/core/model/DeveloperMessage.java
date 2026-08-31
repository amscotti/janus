package io.amscotti.janus.core.model;

/**
 * OpenAI developer-prompt message (the newer-model {@code role:"developer"}). Semantically
 * system-ish — LiteLLM maps it to a system prompt for non-OpenAI providers
 * ({@code map_developer_role_to_system_role}) — but kept as a distinct canonical subtype so
 * the OpenAI wire spelling round-trips exactly. {@code name} is the optional OpenAI wire
 * field, {@code null} unless the caller set it.
 *
 * <p>Wire behavior: the OpenAI codec emits it as {@code {"role":"developer", content, name}}
 * in place (never flattened into {@link ChatRequest#system}); the Anthropic codec merges
 * its content into the top-level {@code system} field (the Anthropic wire has no developer
 * role — documented non-idempotence, mirrored on {@link SystemMessage}).
 */
public record DeveloperMessage(String content, String name) implements Message {

    /** Convenience form without a participant name. */
    public DeveloperMessage(String content) {
        this(content, null);
    }

    @Override
    public ChatRole role() {
        return ChatRole.DEVELOPER;
    }

    /** Conversation text is excluded from log output unless content logging is
     * explicitly enabled ({@code [janus.privacy] log-content}). */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "DeveloperMessage[content=" + content + ", name=" + name + "]";
        }
        return "DeveloperMessage[name=" + name + ", content not logged]";
    }
}
