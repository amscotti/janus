package io.amscotti.janus.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming content delta. All components nullable — an SSE chunk may carry only a role,
 * only content, or only tool-call fragments.
 *
 * <p>{@code reasoningContent} is the typed reasoning fragment (Anthropic
 * {@code thinking_delta}/{@code signature_delta}, an OpenAI-wire
 * {@code reasoning_content} string); see {@link ReasoningContent}.
 *
 * <p>{@code reasoning} is the delta-level pass-through home (mirrors
 * {@link ChatRequest#reasoning}): remaining OpenAI delta-level unknowns that
 * have no wire component ride here on decode (instead of being hoisted to
 * the chunk top level) and are re-emitted <em>inside</em> the delta on the
 * OpenAI encode. {@code null} unless the codec captured delta-level unknowns.
 */
public record Delta(
        ChatRole role,
        String content,
        List<ToolCall> toolCalls,
        Map<String, Object> reasoning,
        ReasoningContent reasoningContent) {

    /** Convenience form without delta-level reasoning. */
    public Delta(ChatRole role, String content, List<ToolCall> toolCalls) {
        this(role, content, toolCalls, null, null);
    }

    /** Compatibility form at the earlier (4-arg) arity — no typed reasoning fragment. */
    public Delta(ChatRole role, String content, List<ToolCall> toolCalls, Map<String, Object> reasoning) {
        this(role, content, toolCalls, reasoning, null);
    }

    public Delta {
        // Null-tolerant unmodifiable copy (matches ChatRequest's list contract): a null
        // element is malformed wire input, but it must not escape construction as a raw
        // NullPointerException from List.copyOf — the codecs reject it with a typed
        // error.
        toolCalls = toolCalls == null ? null : Collections.unmodifiableList(new ArrayList<>(toolCalls));
        reasoning = reasoning == null ? null : Collections.unmodifiableMap(new HashMap<>(reasoning));
    }

    /** Conversation text is excluded from log output unless content logging is
     * explicitly enabled ({@code [janus.privacy] log-content}). */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "Delta[role=" + role + ", content=" + content + ", toolCalls=" + toolCalls + ", reasoning="
                    + reasoning + ", reasoningContent=" + reasoningContent + "]";
        }
        return "Delta[role=" + role + ", toolCalls=" + (toolCalls == null ? 0 : toolCalls.size()) + ", reasoning="
                + (reasoning == null ? "absent" : "present") + ", reasoningContent="
                + (reasoningContent == null ? "absent" : "present") + ", content not logged]";
    }
}
