package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.janus.gateway.dto.OpenAiErrorEnvelope;
import org.junit.jupiter.api.Test;

/**
 * step 2/3: the OpenAI error-envelope byte shape — {@code message}/{@code type}
 * always present, {@code param} always present (null when unset), {@code code}
 * omitted when null — wrapped in {@code {"error": …}} by {@link GatewayJson#errorBody}.
 */
class OpenAiErrorEnvelopeTest {

    @Test
    void nullCodeIsOmittedAndParamAlwaysPresent() {
        String json =
                GatewayJson.errorBody(new OpenAiErrorEnvelope("bad request", "invalid_request_error", null, null));
        assertEquals(
                "{\"error\":{\"message\":\"bad request\",\"type\":\"invalid_request_error\",\"param\":null}}", json);
    }

    @Test
    void codeIsEmittedWhenPresent() {
        String json = GatewayJson.errorBody(
                new OpenAiErrorEnvelope("unknown model: gpt-4", "invalid_request_error", null, "model_not_found"));
        assertEquals(
                "{\"error\":{\"message\":\"unknown model: gpt-4\",\"type\":\"invalid_request_error\","
                        + "\"param\":null,\"code\":\"model_not_found\"}}",
                json);
    }

    @Test
    void paramIsPreservedWhenSet() {
        String json =
                GatewayJson.errorBody(new OpenAiErrorEnvelope("nope", "invalid_request_error", "temperature", null));
        assertEquals(
                "{\"error\":{\"message\":\"nope\",\"type\":\"invalid_request_error\",\"param\":\"temperature\"}}",
                json);
    }

    @Test
    void rejectsNullMessageAndType() {
        assertThrows(NullPointerException.class, () -> new OpenAiErrorEnvelope(null, "type", null, null));
        assertThrows(NullPointerException.class, () -> new OpenAiErrorEnvelope("message", null, null, null));
    }
}
