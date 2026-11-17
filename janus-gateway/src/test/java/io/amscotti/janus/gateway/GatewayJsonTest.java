package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.AnthropicErrorBody;
import io.amscotti.janus.core.codec.AnthropicErrorPayload;
import io.amscotti.janus.gateway.dto.OpenAiErrorEnvelope;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * {@link GatewayJson}: happy-path serialization plus malformed JSON read errors,
 * strict duplicate-key detection, and unserializable DTO write failures.
 */
class GatewayJsonTest {

    @Test
    void mapperRejectsMalformedJsonAndDuplicateKeys() {
        assertThrows(JacksonException.class, () -> GatewayJson.mapper().readTree("{not valid json"));
        assertThrows(
                JacksonException.class, () -> GatewayJson.mapper().readValue("[1,2,3]", OpenAiErrorEnvelope.class));
        assertThrows(JacksonException.class, () -> GatewayJson.mapper().readTree("{\"k\":1,\"k\":2}"));
    }

    @Test
    void writeThrowsIllegalStateOnUnserializableValue() {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> GatewayJson.write(cyclic));
        assertTrue(thrown.getMessage().contains("failed to serialize gateway DTO"), thrown.getMessage());
        assertInstanceOf(JacksonException.class, thrown.getCause());
    }

    @Test
    void errorBodiesAndWriteRenderExpectedJson() {
        String openAi = GatewayJson.errorBody(new OpenAiErrorEnvelope("bad", "invalid_request_error", "model", null));
        JsonNode openAiNode = GatewayJson.mapper().readTree(openAi);
        assertEquals("bad", openAiNode.path("error").path("message").asString());
        assertEquals(
                "invalid_request_error", openAiNode.path("error").path("type").asString());
        assertEquals("model", openAiNode.path("error").path("param").asString());

        String anthropic = GatewayJson.anthropicErrorBody(
                new AnthropicErrorPayload("error", new AnthropicErrorBody("invalid_request_error", "bad")));
        JsonNode anthropicNode = GatewayJson.mapper().readTree(anthropic);
        assertEquals("error", anthropicNode.path("type").asString());
        assertEquals(
                "invalid_request_error",
                anthropicNode.path("error").path("type").asString());

        String written = GatewayJson.write(Map.of("ok", true));
        assertEquals("{\"ok\":true}", written);
    }
}
