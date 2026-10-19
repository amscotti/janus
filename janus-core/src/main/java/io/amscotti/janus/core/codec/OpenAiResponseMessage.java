package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The message inside a response choice — on the wire always {@code role:"assistant"}
 * with optional {@code tool_calls}. {@code role} stays a raw string (the codec maps it
 * to a canonical {@code Message} subtype, defaulting a missing role to assistant).
 * {@code reasoningContent} is the {@code reasoning_content} field emitted by
 * reasoning models on this wire; it maps to the canonical reasoning slot.
 * Remaining unknown message fields ride {@code extras} and are folded into the
 * response {@code extras}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiResponseMessage(
        String role,
        String content,
        List<OpenAiToolCall> toolCalls,
        String reasoningContent,

        @JsonAnySetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    /** Compatibility form at the earlier (4-arg) arity — no reasoning content. */
    public OpenAiResponseMessage(
            String role, String content, List<OpenAiToolCall> toolCalls, Map<String, Object> extras) {
        this(role, content, toolCalls, null, extras);
    }

    public OpenAiResponseMessage {
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
