package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages non-streaming response wire shape (and the embedded
 * {@code message} of a {@code message_start} event). Field names serialize snake_case
 * ({@code stopReason → stop_reason}, {@code stopSequence → stop_sequence}). {@code type}
 * is the wire discriminator — {@code "message"} on responses; {@code content} is a
 * declared-typed {@code List<AnthropicContentBlock>} (the sealed family's
 * {@code @JsonTypeInfo} guarantees a {@code type} field per block on encode and correct
 * block subtypes on decode).
 *
 * <p>Mapping contract (see {@link AnthropicMessageCodec}): {@code stopReason} is the raw
 * wire value (the codec normalizes it into {@code ChatResponse.stopReason});
 * {@code stopSequence} has no canonical home and is dropped on decode (documented);
 * {@code usage} cache-token fields map to the canonical {@code Usage} cache fields
 *.
 * {@code extras} is decode-only (unknown top-level fields, captured via
 * {@code @JsonAnySetter}, folded into {@code ChatResponse.extras}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicMessageResponse(
        String id,
        String type,
        String role,
        String model,
        List<AnthropicContentBlock> content,
        String stopReason,
        String stopSequence,
        AnthropicUsage usage,

        @JsonAnySetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    public AnthropicMessageResponse {
        content = content == null ? null : List.copyOf(content);
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
