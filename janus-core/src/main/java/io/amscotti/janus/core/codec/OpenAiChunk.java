package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One SSE {@code chat.completion.chunk}. {@code usage} is nullable (only present on the
 * terminal chunk when {@code stream_options.include_usage} was requested). On encode the
 * codec defaults {@code object} to {@code "chat.completion.chunk"} (and id/model when
 * null, mirroring the reference face). {@code extras} is decode-only (unknown top-level
 * chunk fields).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChunk(
        String id,
        String object,
        long created,
        String model,
        List<OpenAiChunkChoice> choices,
        OpenAiUsage usage,

        @JsonAnySetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    public OpenAiChunk {
        choices = choices == null ? null : List.copyOf(choices);
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
