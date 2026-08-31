package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * One response choice. {@code finishReason} is the raw wire value; the codec normalizes
 * it into {@code ChatResponse.stopReason} on decode and emits it verbatim per choice on
 * encode. Choice-level unknown fields (e.g. {@code logprobs}) are captured in
 * {@code extras} and folded into the response {@code extras} by the codec.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChoice(
        int index,
        OpenAiResponseMessage message,
        String finishReason,

        @JsonAnySetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    public OpenAiChoice {
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
