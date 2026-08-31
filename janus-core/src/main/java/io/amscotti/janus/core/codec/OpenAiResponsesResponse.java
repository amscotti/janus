package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The Responses API response object ({@code object: "response"}). Encode-side only in
 * Built from the canonical {@code ChatResponse} with the request-echo fields
 * reconstructed from the canonical (instructions ← system, tools ← canonical tools,
 * …). Fixed stateless constants per the plan: {@code store: false} always,
 * {@code truncation: "disabled"}; {@code reasoning.summary} is echoed as null (the
 * input spelling is a string, the output an array — never echoed verbatim).
 *
 * <p>Field order is component order (Jackson) and pinned byte-exactly by the codec's
 * wire-shape guard tests; always-present members are set explicitly (nulls included
 * where OpenAI emits them) so the shape is deterministic.
 */
public record OpenAiResponsesResponse(
        String id,
        String object,
        long created_at,
        String status,
        Object error,
        @JsonInclude(JsonInclude.Include.NON_NULL) IncompleteDetails incomplete_details,
        String instructions,
        Map<String, Object> metadata,
        String model,
        List<OpenAiResponsesOutputItem> output,
        Boolean parallel_tool_calls,
        Double temperature,
        Object tool_choice,
        List<Map<String, Object>> tools,
        Double top_p,

        @JsonProperty("max_output_tokens") @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer max_output_tokens,

        String previous_response_id,
        Map<String, Object> reasoning,
        boolean store,
        Map<String, Object> text,
        String truncation,
        OpenAiResponsesUsage usage) {

    public OpenAiResponsesResponse {
        output = output == null ? List.of() : List.copyOf(output);
        tools = tools == null ? null : List.copyOf(tools);
        metadata = metadata == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
        reasoning = reasoning == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(reasoning));
        text = text == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(text));
    }

    /** {@code incomplete_details} — present only on {@code status: "incomplete"}. */
    public record IncompleteDetails(String reason) {}
}
