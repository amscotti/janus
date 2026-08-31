package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.AnthropicErrorPayload;
import io.amscotti.janus.gateway.dto.OpenAiErrorEnvelope;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The gateway's own (deliberately tiny) JSON surface: serializing {@link
 * OpenAiErrorEnvelope} into the OpenAI error-body wrapper {@code {"error": {…}}}. The
 * chat-completions wire bytes are NOT here — {@link
 * io.amscotti.janus.core.codec.OpenAiMessageCodec} owns those ( discipline, pinned by
 * {@code CodecWireShapeGuardTest}); this helper only renders the error envelope for
 * {@link GatewayExceptionHandler} and {@link SseChunkPublisher} error frames.
 *
 * <p>Mapper contract matches the codec's {@code create} mapper (snake_case naming,
 * tolerant decode, single-value arrays) so gateway-owned DTO serialization behaves like
 * the codec's; the envelope's explicit {@code @JsonProperty}s make the naming strategy
 * moot for the wire shape itself.
 */
final class GatewayJson {

    private static final ObjectMapper MAPPER = mapper();

    private GatewayJson() {}

    static ObjectMapper mapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .build();
    }

    /** OpenAI error-body wrapper: {@code {"error": {"message": …, "type": …, …}}}. */
    static String errorBody(OpenAiErrorEnvelope envelope) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.set("error", MAPPER.valueToTree(envelope));
            return MAPPER.writeValueAsString(root);
        } catch (JacksonException e) {
            // Envelope fields are plain strings; serialization cannot fail in practice.
            throw new IllegalStateException("failed to serialize OpenAI error envelope", e);
        }
    }

    /**
     * Anthropic error envelope — the payload <em>is</em> the full wire body,
     * {@code {"type":"error","error":{"type":…,"message":…}}}, so no wrapper is added.
     * The DTO is the core codec's public {@link AnthropicErrorPayload} (codec-owned
     * vocabulary, already registered in janus-core's reflect-config — no new gateway
     * reflect entries per the Design notes).
     */
    static String anthropicErrorBody(AnthropicErrorPayload envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JacksonException e) {
            // Envelope fields are plain strings; serialization cannot fail in practice.
            throw new IllegalStateException("failed to serialize Anthropic error envelope", e);
        }
    }

    /**
     * Serialize a gateway-owned DTO ( admin keys API: {@code KeyGenerateResponse},
     * {@code KeyDeleteResponse}, {@code KeyListResponse}) — records with explicit
     * {@code @JsonProperty}s, no polymorphic types, serialized through the shared
     * gateway mapper so the wire shape is the codec's contract (snake_case naming,
     * {@code @JsonProperty} wins for the wire shape itself).
     */
    static String write(Object dto) {
        try {
            return MAPPER.writeValueAsString(dto);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "failed to serialize gateway DTO: " + dto.getClass().getName(), e);
        }
    }
}
