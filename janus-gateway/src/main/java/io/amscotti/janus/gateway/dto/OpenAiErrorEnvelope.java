package io.amscotti.janus.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * OpenAI-shaped error body ({@code {"error": {"message", "type", "param", "code"}}}) —
 * one of the three gateway-serialized types (with {@link ModelsResponse} and {@link
 * ModelEntry}); listed in the gateway {@code reflect-config.json} for native-image.
 *
 * <p>Wire shape pinned by {@code OpenAiErrorEnvelopeTest}: {@code message} and {@code
 * type} are always present; {@code param} is always present (null when no parameter is
 * implicated — OpenAI emits it unconditionally); {@code code} is <em>omitted</em> when
 * null (OpenAI omits absent codes) via the component-level {@code @JsonInclude(NON_NULL)}.
 * The {@code {"error": …}} wrapper is added by {@link GatewayJson#errorBody}.
 */
public record OpenAiErrorEnvelope(
        @JsonProperty("message") String message,
        @JsonProperty("type") String type,
        @JsonProperty("param") String param,

        @JsonProperty("code") @JsonInclude(JsonInclude.Include.NON_NULL)
        String code) {

    public OpenAiErrorEnvelope {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(type, "type");
        // param is intentionally nullable but always serialized; code null is omitted.
    }
}
