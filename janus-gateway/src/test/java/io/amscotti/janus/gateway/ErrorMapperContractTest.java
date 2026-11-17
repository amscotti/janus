package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.provider.ProviderException;
import io.micronaut.http.HttpStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

/**
 * Contract / drift-guard tests across {@link ErrorMapper}, {@link AnthropicErrorMapper},
 * and {@link GatewayJson}: every {@link ProviderException} {@code TYPE_*} constant,
 * {@link KeyAuthException.Reason}, and {@link RateLimitExceededException.Reason} must map
 * explicitly on both faces without falling through to the untyped 500 fallback, and
 * {@link GatewayJson} must reject duplicate JSON object keys.
 */
class ErrorMapperContractTest {

    private final ErrorMapper openAiMapper = new ErrorMapper();
    private final AnthropicErrorMapper anthropicMapper = new AnthropicErrorMapper();

    @Test
    void everyProviderExceptionTypeMapsExplicitlyOnBothFaces() throws Exception {
        List<String> types = new ArrayList<>();
        for (Field field : ProviderException.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())
                    && field.getType() == String.class
                    && field.getName().startsWith("TYPE_")) {
                types.add((String) field.get(null));
            }
        }
        assertFalse(types.isEmpty(), "expected ProviderException.TYPE_* constants");

        for (String type : types) {
            Integer upstreamStatus = ProviderException.TYPE_UPSTREAM_4XX.equals(type)
                            || ProviderException.TYPE_UNSUPPORTED_REQUEST.equals(type)
                    ? 400
                    : null;
            ProviderException ex = new ProviderException(type, "detail-" + type, upstreamStatus, null);

            ErrorMapper.ErrorMapping openAi = openAiMapper.map(ex);
            assertNotEquals(
                    "internal server error",
                    openAi.envelope().message(),
                    "ErrorMapper fell through to unexpected() for " + type);
            assertEquals("detail-" + type, openAi.envelope().message(), "ErrorMapper dropped detail for " + type);

            AnthropicErrorMapper.ErrorMapping anthropic = anthropicMapper.map(ex);
            assertNotEquals(
                    "internal server error",
                    anthropic.envelope().error().message(),
                    "AnthropicErrorMapper fell through to unexpected() for " + type);
            assertEquals(
                    "detail-" + type,
                    anthropic.envelope().error().message(),
                    "AnthropicErrorMapper dropped detail for " + type);

            if (ProviderException.TYPE_UNSUPPORTED_REQUEST.equals(type)) {
                assertEquals(HttpStatus.BAD_REQUEST, openAi.status());
                assertEquals(ErrorMapper.TYPE_INVALID_REQUEST, openAi.envelope().type());
                assertEquals(HttpStatus.BAD_REQUEST, anthropic.status());
                assertEquals(
                        AnthropicErrorMapper.TYPE_INVALID_REQUEST,
                        anthropic.envelope().error().type());
            }
        }
    }

    @Test
    void everyKeyAuthAndRateLimitReasonMapsExplicitlyOnBothFaces() {
        for (KeyAuthException.Reason reason : KeyAuthException.Reason.values()) {
            KeyAuthException ex = new KeyAuthException(reason, "auth-" + reason.name());
            ErrorMapper.ErrorMapping openAi = openAiMapper.map(ex);
            AnthropicErrorMapper.ErrorMapping anthropic = anthropicMapper.map(ex);
            assertTrue(
                    openAi.status() == HttpStatus.UNAUTHORIZED || openAi.status() == HttpStatus.FORBIDDEN,
                    "ErrorMapper unexpected status for " + reason + ": " + openAi.status());
            assertEquals(openAi.status(), anthropic.status(), "status parity mismatch for " + reason);
        }
        for (RateLimitExceededException.Reason reason : RateLimitExceededException.Reason.values()) {
            RateLimitExceededException ex = new RateLimitExceededException(reason, 30L, "k-test");
            ErrorMapper.ErrorMapping openAi = openAiMapper.map(ex);
            AnthropicErrorMapper.ErrorMapping anthropic = anthropicMapper.map(ex);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, openAi.status(), reason.name());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, anthropic.status(), reason.name());
        }
    }

    @Test
    void gatewayJsonRejectsDuplicateObjectKeys() {
        assertThrows(
                JacksonException.class,
                () -> GatewayJson.mapper().readTree("{\"name\":\"first\",\"name\":\"second\"}"));
    }
}
