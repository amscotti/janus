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
 * Unknown message fields ride {@code extras} and are folded into the response
 * {@code extras}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiResponseMessage(
        String role,
        String content,
        List<OpenAiToolCall> toolCalls,

        @JsonAnySetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    public OpenAiResponseMessage {
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
