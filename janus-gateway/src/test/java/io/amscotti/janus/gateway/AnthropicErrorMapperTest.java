package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.AnthropicCodecException;
import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.router.UnknownModelException;
import io.amscotti.janus.store.UnpricedModelException;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

/**
 * step 2/3 — the Anthropic error-mapping table (every row of the Design-notes
 * table, plus the unexpected-exception fallback and the upstream-4xx unmapped-code
 * fallback): exception → HTTP status + Anthropic envelope {@code error.type}. The
 * envelope byte shape {@code {"type":"error","error":{…}}} is pinned by {@link
 * #envelopeByteShapeIsAnthropicWireFormat} via {@link GatewayJson#mapper}.
 */
class AnthropicErrorMapperTest {

    private final AnthropicErrorMapper mapper = new AnthropicErrorMapper();

    @Test
    void unpricedModelIs400InvalidRequestError() {
        AnthropicErrorMapper.ErrorMapping mapping = mapper.map(new UnpricedModelException("no-row"));
        assertEquals(HttpStatus.BAD_REQUEST, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_INVALID_REQUEST,
                mapping.envelope().error().type());
        assertTrue(
                mapping.envelope().error().message().contains("no-row"),
                mapping.envelope().error().message());
    }

    @Test
    void invalidRequestCodecExceptionIs400InvalidRequestError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(new AnthropicCodecException(AnthropicCodecException.TYPE_INVALID_REQUEST, "malformed body"));
        assertEquals(HttpStatus.BAD_REQUEST, mapping.status());
        assertEquals("error", mapping.envelope().type());
        assertEquals(
                AnthropicErrorMapper.TYPE_INVALID_REQUEST,
                mapping.envelope().error().type());
        assertEquals("malformed body", mapping.envelope().error().message());
    }

    @Test
    void apiErrorCodecExceptionIs500ApiError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(new AnthropicCodecException(AnthropicCodecException.TYPE_API_ERROR, "bad upstream payload"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_API_ERROR, mapping.envelope().error().type());
    }

    @Test
    void unknownModelIs404NotFoundError() {
        AnthropicErrorMapper.ErrorMapping mapping = mapper.map(new UnknownModelException("claude-3"));
        assertEquals(HttpStatus.NOT_FOUND, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_NOT_FOUND_ERROR,
                mapping.envelope().error().type());
        assertEquals("unknown model: claude-3", mapping.envelope().error().message());
    }

    @Test
    void authIs401AuthenticationError() {
        AnthropicErrorMapper.ErrorMapping mapping = mapper.map(provider(ProviderException.TYPE_AUTH, "bad key", 401));
        assertEquals(HttpStatus.UNAUTHORIZED, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_AUTHENTICATION_ERROR,
                mapping.envelope().error().type());
    }

    // ------------------------------------------------------ KeyAuthException rows

    @Test
    void missingKeyAuthIs401AuthenticationError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(new KeyAuthException(KeyAuthException.Reason.MISSING, null));
        assertEquals(HttpStatus.UNAUTHORIZED, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_AUTHENTICATION_ERROR,
                mapping.envelope().error().type());
    }

    @Test
    void invalidKeyAuthIs401AuthenticationError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(new KeyAuthException(KeyAuthException.Reason.INVALID, "k1"));
        assertEquals(HttpStatus.UNAUTHORIZED, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_AUTHENTICATION_ERROR,
                mapping.envelope().error().type());
        assertEquals(
                "invalid or unknown credentials", mapping.envelope().error().message());
    }

    @Test
    void expiredKeyAuthIs401AuthenticationError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(new KeyAuthException(KeyAuthException.Reason.EXPIRED, "k1"));
        assertEquals(HttpStatus.UNAUTHORIZED, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_AUTHENTICATION_ERROR,
                mapping.envelope().error().type());
        assertEquals("gateway key has expired", mapping.envelope().error().message());
    }

    @Test
    void badMasterKeyAuthIs401AuthenticationError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(new KeyAuthException(KeyAuthException.Reason.BAD_MASTER, null));
        assertEquals(HttpStatus.UNAUTHORIZED, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_AUTHENTICATION_ERROR,
                mapping.envelope().error().type());
        assertEquals("invalid master key", mapping.envelope().error().message());
    }

    @Test
    void revokedKeyAuthIs403PermissionError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(new KeyAuthException(KeyAuthException.Reason.REVOKED, "k1"));
        assertEquals(HttpStatus.FORBIDDEN, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_PERMISSION_ERROR,
                mapping.envelope().error().type());
        assertEquals("gateway key has been revoked", mapping.envelope().error().message());
    }

    @Test
    void scopeDeniedKeyAuthIs403PermissionError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(new KeyAuthException(KeyAuthException.Reason.SCOPE_DENIED, "k1"));
        assertEquals(HttpStatus.FORBIDDEN, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_PERMISSION_ERROR,
                mapping.envelope().error().type());
        assertEquals(
                "key is not permitted to access the requested model",
                mapping.envelope().error().message());
    }

    @Test
    void rateLimitedIs429RateLimitError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_RATE_LIMITED, "slow down", 429));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_RATE_LIMIT_ERROR,
                mapping.envelope().error().type());
    }

    @Test
    void upstream5xxIs502ApiError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_UPSTREAM_5XX, "boom", 503));
        assertEquals(HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_API_ERROR, mapping.envelope().error().type());
    }

    @Test
    void upstream4xxUsesUpstreamStatusWhenPresent() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_UPSTREAM_4XX, "bad upstream", 400));
        assertEquals(HttpStatus.BAD_REQUEST, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_API_ERROR, mapping.envelope().error().type());
    }

    @Test
    void upstream4xxDefaultsTo502WithoutStatus() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(new ProviderException(ProviderException.TYPE_UPSTREAM_4XX, "bad upstream"));
        assertEquals(HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_API_ERROR, mapping.envelope().error().type());
    }

    @Test
    void upstream4xxUnmappedCodeFallsBackTo502NotCrash() {
        // Upstream codes absent from Micronaut's HttpStatus enum (nginx
        // 444/499 …) must map to 502 instead of throwing inside the error handler.
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_UPSTREAM_4XX, "nginx 499", 499));
        assertEquals(HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_API_ERROR, mapping.envelope().error().type());
        assertEquals("nginx 499", mapping.envelope().error().message());
    }

    // ------------------------------------------- 3xx / out-of-band upstream codes

    @Test
    void upstream4xxWith3xxStatusIsClampedTo502() {
        // The Anthropic analogue of the OpenAI 3xx clamp — an upstream 301/302/304
        // is never passed through as a redirect with a JSON error body.
        for (int status : new int[] {301, 302, 304, 307}) {
            AnthropicErrorMapper.ErrorMapping mapping =
                    mapper.map(provider(ProviderException.TYPE_UPSTREAM_4XX, "redirected", status));
            assertEquals(HttpStatus.BAD_GATEWAY, mapping.status(), "code " + status + " must clamp to 502");
        }
    }

    @Test
    void upstream4xxWithNonPositiveOr5xxStatusIsClampedTo502() {
        for (int status : new int[] {0, -1, 503}) {
            AnthropicErrorMapper.ErrorMapping mapping =
                    mapper.map(provider(ProviderException.TYPE_UPSTREAM_4XX, "out of band", status));
            assertEquals(HttpStatus.BAD_GATEWAY, mapping.status(), "code " + status + " must clamp to 502");
            assertEquals(
                    AnthropicErrorMapper.TYPE_API_ERROR,
                    mapping.envelope().error().type(),
                    "code " + status);
        }
    }

    // -------------------------------------------- upstream-message secret redaction

    @Test
    void upstreamAuthMessageNeverLeaksTheProviderKey() {
        // The Anthropic envelope must also strip the sk- shape an upstream echoes
        // inside 401 bodies (the gateway's own provider key never reaches a client).
        String secret = "sk-ant-api03-abcdef0123456789abcdef0123456789";
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_AUTH, "invalid x-api-key: " + secret, 401));
        String message = mapping.envelope().error().message();
        assertFalse(message.contains(secret), message);
        assertFalse(message.contains("sk-"), "the sk- shape must be redacted: " + message);
        assertTrue(message.contains("<redacted>"), message);
    }

    @Test
    void upstreamMessageWithoutSecretShapePassesThroughVerbatim() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_RATE_LIMITED, "rate limit reached", 429));
        assertEquals("rate limit reached", mapping.envelope().error().message());
    }

    @Test
    void apiErrorCodecMessageNeverLeaksTheSecret() {
        // The codec TYPE_API_ERROR row's message (a Jackson parse-error string
        // that can echo an offending upstream token) runs through the same redaction
        // choke point as the ProviderException rows.
        String secret = "sk-abcdef0123456789abcdef0123456789";
        AnthropicErrorMapper.ErrorMapping mapping = mapper.map(
                new AnthropicCodecException(AnthropicCodecException.TYPE_API_ERROR, "invalid JSON token: " + secret));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mapping.status());
        assertFalse(
                mapping.envelope().error().message().contains(secret),
                mapping.envelope().error().message());
        assertFalse(
                mapping.envelope().error().message().contains("sk-"),
                mapping.envelope().error().message());
        assertTrue(
                mapping.envelope().error().message().contains("<redacted>"),
                mapping.envelope().error().message());
    }

    @Test
    void networkIs502ApiError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_NETWORK, "connection reset", null));
        assertEquals(HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_API_ERROR, mapping.envelope().error().type());
    }

    @Test
    void timeoutIs504ApiError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_TIMEOUT, "no answer", null));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_API_ERROR, mapping.envelope().error().type());
    }

    @Test
    void badUpstreamPayloadIs502ApiError() {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, "garbage", null));
        assertEquals(HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_API_ERROR, mapping.envelope().error().type());
    }

    @Test
    void unexpectedRuntimeExceptionIs500ApiError() {
        AnthropicErrorMapper.ErrorMapping mapping = mapper.map(new IllegalStateException("nobody expected this"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_API_ERROR, mapping.envelope().error().type());
        assertEquals("internal server error", mapping.envelope().error().message());
    }

    @Test
    void unexpectedExceptionWithNullMessageStillYieldsEnvelope() {
        AnthropicErrorMapper.ErrorMapping mapping = mapper.map(new IllegalStateException());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_API_ERROR, mapping.envelope().error().type());
        assertEquals("internal server error", mapping.envelope().error().message());
    }

    @Test
    void envelopeByteShapeIsAnthropicWireFormat() throws Exception {
        AnthropicErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_NETWORK, "connection reset", null));
        String body = GatewayJson.mapper().writeValueAsString(mapping.envelope());
        assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"connection reset\"}}", body);
    }

    // ------------------------------------------ null-message ProviderException

    @Test
    void providerExceptionWithNullMessageStillCarriesAPresentMessage() {
        // AnthropicErrorBody is @JsonInclude(NON_NULL) — a null-message
        // ProviderException would otherwise map to an envelope with the `message` field
        // silently omitted (a wire shape the Anthropic SDK does not expect). The mapper
        // must degrade to a fixed non-null message so `message` is always present.
        for (String type : new String[] {
            ProviderException.TYPE_AUTH,
            ProviderException.TYPE_RATE_LIMITED,
            ProviderException.TYPE_UPSTREAM_5XX,
            ProviderException.TYPE_UPSTREAM_4XX,
            ProviderException.TYPE_NETWORK,
            ProviderException.TYPE_TIMEOUT,
            ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD
        }) {
            AnthropicErrorMapper.ErrorMapping mapping = mapper.map(new ProviderException(type, null));
            assertEquals("upstream error", mapping.envelope().error().message(), type);
        }
    }

    // -------------------------------------------------- RateLimitExceededException rows

    @Test
    void rateLimitExceededIs429RateLimitError() {
        AnthropicErrorMapper.ErrorMapping mapping = mapper.map(
                new RateLimitExceededException(RateLimitExceededException.Reason.RATE_LIMIT_EXCEEDED, 60L, "k1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_RATE_LIMIT_ERROR,
                mapping.envelope().error().type(),
                "Anthropic's wire type for over-limit requests is also rate_limit_error");
        assertEquals(
                "rate limit exceeded for this key", mapping.envelope().error().message());
    }

    @Test
    void budgetExceededHardIs429RateLimitError() {
        AnthropicErrorMapper.ErrorMapping mapping = mapper.map(
                new RateLimitExceededException(RateLimitExceededException.Reason.BUDGET_EXCEEDED_HARD, null, "k1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, mapping.status());
        assertEquals(
                AnthropicErrorMapper.TYPE_RATE_LIMIT_ERROR,
                mapping.envelope().error().type());
        assertEquals("key budget exceeded", mapping.envelope().error().message());
    }

    private static ProviderException provider(String type, String message, Integer statusCode) {
        return new ProviderException(type, message, statusCode, null);
    }
}
