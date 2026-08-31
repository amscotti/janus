package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * One Anthropic request message. {@code role} is a raw wire string here — validation
 * (must be {@code user} or {@code assistant}; Anthropic has no system/tool message
 * roles — the system prompt is the top-level {@code system} field) is the codec's job.
 *
 * <p>{@code content} is {@code Object}: the string form (plain path) or a
 * {@code List<AnthropicContentBlock>} (decode produces a list of maps, converted per
 * block by the codec; encode always emits the string form — or a block array for
 * tool-call/tool-result messages). Unknown message fields have
 * no canonical home ( {@code Message} subtypes are sealed records without extras);
 * {@link AnthropicMessageCodec#decodeRequest} folds them into the request {@code extras}
 * so nothing is silently dropped (nested unknowns re-emerge
 * top-level on encode).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicMessage(
        String role,
        Object content,

        @JsonAnySetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    public AnthropicMessage {
        // extras is the opaque pass-through contract: JSON objects with null-valued
        // fields are legitimate payloads, so the copy must tolerate null values
        // (Map.copyOf would NPE). HashMap allows null values; wrap for immutability.
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
