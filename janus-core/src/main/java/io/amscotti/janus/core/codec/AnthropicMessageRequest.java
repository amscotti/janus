package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages request wire shape ({@code POST /v1/messages}). Field names
 * serialize in snake_case via the codec mapper's {@code SNAKE_CASE} naming strategy
 * ({@code maxTokens → max_tokens}, {@code stopSequences → stop_sequences},
 * {@code toolChoice → tool_choice}, {@code cacheControl → cache_control}).
 *
 * <p><b> D1:</b> {@code streamOptions} is a <em>decode-only</em> slot — the wire
 * field has no Anthropic home (real Anthropic rejects {@code stream_options}); the
 * codec's {@code encodeRequest} always passes {@code null} here so it is never
 * emitted on the Anthropic wire (documented non-idempotence, codec javadoc (8)).
 *
 * <p>Mapping contract (see {@link AnthropicMessageCodec}): {@code system} is
 * {@code Object} — the string form on the plain path, or a text-block array (decode
 * joins; any non-text block is rejected — Anthropic system accepts text blocks only,
 * documented). {@code thinking} and {@code cacheControl} are opaque passthroughs
 * ({@code reasoning} ↔ {@code thinking}, -reserved slots). {@code tools}/
 * {@code toolChoice} translate bidirectionally ({@code input_schema}, {@code tool_choice}
 * via {@link ToolChoiceMapper}). {@code extras} is <em>decode-only</em> — unknown
 * top-level fields are captured here via {@code @JsonAnySetter} and folded into
 * {@code ChatRequest.extras}; the codec always constructs encode DTOs with
 * {@code extras = null} so it is never emitted (outbound extras merge at the top level,
 * base-wins).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicMessageRequest(
        String model,
        List<AnthropicMessage> messages,
        Object system,
        Integer maxTokens,
        Double temperature,
        Double topP,
        Integer topK,
        List<String> stopSequences,
        Boolean stream,
        Map<String, Object> streamOptions,
        List<AnthropicTool> tools,
        Object toolChoice,
        Object thinking,
        Object cacheControl,
        Map<String, Object> outputConfig,

        @JsonAnySetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    public AnthropicMessageRequest {
        messages = messages == null ? null : List.copyOf(messages);
        // A wire "stop_sequences":[null] must not escape the DTO constructor as a raw
        // NullPointerException from List.copyOf (this constructor runs during Jackson
        // deserialization, before any codec validation could see the list) —
        // null-tolerant copy, same class as the map contract below; the pass-through
        // reaches the upstream, which rejects it with a typed error.
        stopSequences = stopSequences == null ? null : Collections.unmodifiableList(new ArrayList<>(stopSequences));
        tools = tools == null ? null : List.copyOf(tools);
        // The opaque pass-through contract applies to every payload map: streamOptions
        // is a wire object that may legitimately carry null-valued fields
        // Map.copyOf would NPE on a null value). HashMap allows null values; wrap for
        // immutability.
        streamOptions = streamOptions == null ? null : Collections.unmodifiableMap(new HashMap<>(streamOptions));
        outputConfig = outputConfig == null ? null : Collections.unmodifiableMap(new HashMap<>(outputConfig));
        // extras is the opaque pass-through contract: JSON objects with null-valued
        // fields are legitimate payloads, so the copy must tolerate null values
        // (Map.copyOf would NPE). HashMap allows null values; wrap for immutability.
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
