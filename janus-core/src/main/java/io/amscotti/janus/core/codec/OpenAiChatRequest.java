package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions request wire shape (request DTO). Field names serialize in
 * snake_case via the codec mapper's {@code SNAKE_CASE} naming strategy
 * ({@code streamOptions → stream_options}). Token caps use
 * {@code max_completion_tokens} on the wire (GPT-5.x rejects {@code max_tokens});
 * {@code max_tokens} is still accepted on decode via {@link JsonAlias}.
 *
 * <p>{@code reasoningEffort} is the chat-wire
 * {@code reasoning_effort} string — the canonical reasoning map's {@code effort}
 * entry, mapped in both directions (the slot was previously never serialized on this
 * leg, silently dropping effort on the oo route).
 *
 * <p>Mapping contract (see {@link OpenAiMessageCodec}): {@code extras} is
 * <em>decode-only</em> — unknown top-level request fields are captured here via
 * {@code @JsonAnySetter} and folded into {@code ChatRequest.extras}. The codec always
 * constructs encode DTOs with {@code extras = null} so it is never emitted; outbound
 * {@code extras} are merged at the top level of the encoded payload instead.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatRequest(
        String model,
        List<OpenAiMessage> messages,
        List<OpenAiTool> tools,
        Object toolChoice,
        Boolean stream,
        Map<String, Object> streamOptions,
        Double temperature,
        Double topP,
        Integer topK,

        @JsonProperty("max_completion_tokens") @JsonAlias("max_tokens")
        Integer maxTokens,

        List<String> stop,
        Long seed,
        Integer n,
        Double frequencyPenalty,
        Double presencePenalty,
        Map<String, Object> logitBias,
        Map<String, Object> responseFormat,
        String reasoningEffort,

        @JsonAnySetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    public OpenAiChatRequest {
        messages = messages == null ? null : List.copyOf(messages);
        tools = tools == null ? null : List.copyOf(tools);
        // A wire "stop":[null] is client-malformed, but it must not escape the DTO
        // constructor as a raw NullPointerException from List.copyOf (this constructor
        // runs during Jackson deserialization, before any codec validation could see
        // the list) — null-tolerant copy, same class as the map contract below; the
        // pass-through reaches the upstream, which rejects it with a typed error.
        stop = stop == null ? null : Collections.unmodifiableList(new ArrayList<>(stop));
        // The opaque pass-through contract applies to every payload map: logitBias/
        // responseFormat/streamOptions are wire objects that may legitimately carry
        // null-valued fields (Map.copyOf would NPE on a null value, letting a raw
        // NPE escape the codec's typed-error contract). HashMap allows null values; wrap
        // for immutability.
        logitBias = logitBias == null ? null : Collections.unmodifiableMap(new HashMap<>(logitBias));
        responseFormat = responseFormat == null ? null : Collections.unmodifiableMap(new HashMap<>(responseFormat));
        streamOptions = streamOptions == null ? null : Collections.unmodifiableMap(new HashMap<>(streamOptions));
        // extras is the opaque pass-through contract: JSON objects with null-valued
        // fields are legitimate payloads, so the copy must tolerate null values
        // (Map.copyOf would NPE). HashMap allows null values; wrap for immutability.
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
