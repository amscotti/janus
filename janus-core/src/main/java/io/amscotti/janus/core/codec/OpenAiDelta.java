package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming content delta; all components nullable — a chunk may carry only a role, only
 * content, or only tool-call fragments. Unknown delta fields ride {@code extras} and are
 * folded into the chunk {@code extras} by the codec — except on the re-encode direction,
 * where the codec re-emits them <em>inside</em> the delta via {@code extras} (the
 * delta-level reasoning home, {@code Delta.reasoning}); {@code @JsonAnyGetter} inlines
 * them at the delta level instead of a nested {@code "extras"} object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiDelta(
        String role,
        String content,
        List<OpenAiToolCall> toolCalls,

        @JsonAnySetter @JsonAnyGetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    public OpenAiDelta {
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
