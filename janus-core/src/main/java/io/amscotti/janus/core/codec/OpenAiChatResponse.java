package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions non-streaming response wire shape. {@code extras} is
 * decode-only (unknown top-level response fields, e.g. {@code system_fingerprint} —
 * captured via {@code @JsonAnySetter}, folded into {@code ChatResponse.extras}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatResponse(
        String id,
        String object,
        long created,
        String model,
        List<OpenAiChoice> choices,
        OpenAiUsage usage,

        @JsonAnySetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    public OpenAiChatResponse {
        choices = choices == null ? null : List.copyOf(choices);
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
