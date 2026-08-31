package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API request wire shape ({@code POST /v1/responses}) — part of the
 * Responses plan. Field names serialize snake_case via the codec mapper's strategy.
 * The codec parses {@code input} (String shorthand or typed item list) and the tool
 * list from the raw tree — message/input-item dispatch is structural, mirroring the
 * OpenAI chat codec's role-based decode — so this DTO carries the envelope fields the
 * canonical maps directly plus the captures the 400/drop tables need.
 *
 * <p><b>Stateless contract:</b> {@code store} defaults to {@code false} HERE
 * (absent ≡ false — the server-side OpenAI default is true but every stateless client
 * omits it or sends false; a 400-on-absent would break default SDK calls); an explicit
 * {@code store: true}, {@code previous_response_id}, {@code conversation} or
 * {@code background: true} is a typed 400 at decode. {@code include} and
 * {@code truncation: "disabled"} are accepted-and-dropped (documented).
 *
 * <p>{@code extras} is decode-only capture (unknown fields) — the Responses face never
 * re-emits a decoded request (it encodes responses only), so extras exist for
 * diagnostics/round-trip property tests, not for an outbound merge.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiResponsesRequest(
        String model,
        @JsonIgnore Object input,
        String instructions,
        List<Map<String, Object>> tools,
        Object toolChoice,
        Double temperature,
        Double topP,

        @JsonProperty("max_output_tokens") Integer maxOutputTokens,

        Map<String, Object> reasoning,
        Map<String, Object> text,
        Boolean stream,
        Boolean store,
        String truncation,
        Boolean background,

        @JsonProperty("previous_response_id") String previousResponseId,

        Object conversation,
        List<String> include,
        Map<String, Object> metadata,
        String user,

        @JsonAnySetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    public OpenAiResponsesRequest {
        tools = tools == null ? null : List.copyOf(tools);
        include = include == null ? null : List.copyOf(include);
        reasoning = reasoning == null ? null : Collections.unmodifiableMap(new HashMap<>(reasoning));
        text = text == null ? null : Collections.unmodifiableMap(new HashMap<>(text));
        metadata = metadata == null ? null : Collections.unmodifiableMap(new HashMap<>(metadata));
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
