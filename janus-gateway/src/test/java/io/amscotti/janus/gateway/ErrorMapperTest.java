package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.OpenAiCodecException;
import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.router.UnknownModelException;
import io.amscotti.janus.store.UnpricedModelException;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

/**
 * step 2/3: the error-mapping table (every row of the Design-notes table, plus the
 * unexpected-exception fallback) — exception → HTTP status + OpenAI envelope
 * type/code. The envelope byte shape is pinned separately by {@link
 * OpenAiErrorEnvelopeTest}.
 */
class ErrorMapperTest {

    private final ErrorMapper mapper = new ErrorMapper();

    @Test
    void unpricedModelIs400InvalidRequest() {
        ErrorMapper.ErrorMapping mapping = mapper.map(new UnpricedModelException("no-row"));
        assertEquals(HttpStatus.BAD_REQUEST, mapping.status());
        assertEquals(ErrorMapper.TYPE_INVALID_REQUEST, mapping.envelope().type());
        assertNull(mapping.envelope().code());
        assertTrue(
                mapping.envelope().message().contains("no-row"),
                mapping.envelope().message());
    }

    @Test
    void invalidRequestCodecExceptionIs400() {
        ErrorMapper.ErrorMapping mapping =
                mapper.map(new OpenAiCodecException(OpenAiCodecException.TYPE_INVALID_REQUEST, "malformed body"));
        assertEquals(HttpStatus.BAD_REQUEST, mapping.status());
        assertEquals(ErrorMapper.TYPE_INVALID_REQUEST, mapping.envelope().type());
        assertEquals("malformed body", mapping.envelope().message());
        assertNull(mapping.envelope().code());
    }

    @Test
    void apiErrorCodecExceptionIs500() {
        ErrorMapper.ErrorMapping mapping =
                mapper.map(new OpenAiCodecException(OpenAiCodecException.TYPE_API_ERROR, "bad upstream payload"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mapping.status());
        assertEquals(ErrorMapper.TYPE_API_ERROR, mapping.envelope().type());
        assertNull(mapping.envelope().code());
    }

    @Test
    void unknownModelIs404ModelNotFound() {
        ErrorMapper.ErrorMapping mapping = mapper.map(new UnknownModelException("gpt-4"));
        assertEquals(HttpStatus.NOT_FOUND, mapping.status());
        assertEquals(ErrorMapper.TYPE_INVALID_REQUEST, mapping.envelope().type());
        assertEquals(ErrorMapper.CODE_MODEL_NOT_FOUND, mapping.envelope().code());
        assertEquals("unknown model: gpt-4", mapping.envelope().message());
    }

    @Test
    void authIs401AuthenticationError() {
        ErrorMapper.ErrorMapping mapping = mapper.map(provider(ProviderException.TYPE_AUTH, "bad key", 401));
        assertEquals(HttpStatus.UNAUTHORIZED, mapping.status());
        assertEquals(ErrorMapper.TYPE_AUTHENTICATION_ERROR, mapping.envelope().type());
        assertNull(mapping.envelope().code());
    }

    // ------------------------------------------------------ KeyAuthException rows

    @Test
    void missingKeyAuthIs401AuthenticationError() {
        ErrorMapper.ErrorMapping mapping = mapper.map(new KeyAuthException(KeyAuthException.Reason.MISSING, null));
        assertEquals(HttpStatus.UNAUTHORIZED, mapping.status());
        assertEquals(ErrorMapper.TYPE_AUTHENTICATION_ERROR, mapping.envelope().type());
        assertEquals(ErrorMapper.CODE_INVALID_API_KEY, mapping.envelope().code());
    }

    @Test
    void invalidKeyAuthIs401AuthenticationError() {
        ErrorMapper.ErrorMapping mapping = mapper.map(new KeyAuthException(KeyAuthException.Reason.INVALID, "k1"));
        assertEquals(HttpStatus.UNAUTHORIZED, mapping.status());
        assertEquals(ErrorMapper.TYPE_AUTHENTICATION_ERROR, mapping.envelope().type());
        assertEquals(ErrorMapper.CODE_INVALID_API_KEY, mapping.envelope().code());
        assertEquals("invalid or unknown credentials", mapping.envelope().message());
    }

    @Test
    void expiredKeyAuthIs401AuthenticationError() {
        ErrorMapper.ErrorMapping mapping = mapper.map(new KeyAuthException(KeyAuthException.Reason.EXPIRED, "k1"));
        assertEquals(HttpStatus.UNAUTHORIZED, mapping.status());
        assertEquals(ErrorMapper.TYPE_AUTHENTICATION_ERROR, mapping.envelope().type());
        assertEquals(ErrorMapper.CODE_INVALID_API_KEY, mapping.envelope().code());
        assertEquals("gateway key has expired", mapping.envelope().message());
    }

    @Test
    void badMasterKeyAuthIs401AuthenticationError() {
        ErrorMapper.ErrorMapping mapping = mapper.map(new KeyAuthException(KeyAuthException.Reason.BAD_MASTER, null));
        assertEquals(HttpStatus.UNAUTHORIZED, mapping.status());
        assertEquals(ErrorMapper.TYPE_AUTHENTICATION_ERROR, mapping.envelope().type());
        assertEquals(ErrorMapper.CODE_INVALID_API_KEY, mapping.envelope().code());
        assertEquals("invalid master key", mapping.envelope().message());
    }

    @Test
    void revokedKeyAuthIs403PermissionError() {
        ErrorMapper.ErrorMapping mapping = mapper.map(new KeyAuthException(KeyAuthException.Reason.REVOKED, "k1"));
        assertEquals(HttpStatus.FORBIDDEN, mapping.status());
        assertEquals(ErrorMapper.TYPE_PERMISSION_ERROR, mapping.envelope().type());
        assertEquals(ErrorMapper.CODE_FORBIDDEN, mapping.envelope().code());
        assertEquals("gateway key has been revoked", mapping.envelope().message());
    }

    @Test
    void scopeDeniedKeyAuthIs403PermissionError() {
        ErrorMapper.ErrorMapping mapping = mapper.map(new KeyAuthException(KeyAuthException.Reason.SCOPE_DENIED, "k1"));
        assertEquals(HttpStatus.FORBIDDEN, mapping.status());
        assertEquals(ErrorMapper.TYPE_PERMISSION_ERROR, mapping.envelope().type());
        assertEquals(ErrorMapper.CODE_FORBIDDEN, mapping.envelope().code());
        assertEquals(
                "key is not permitted to access the requested model",
                mapping.envelope().message());
    }

    @Test
    void rateLimitedIs429RateLimitError() {
        // The upstream 429 passthrough carries code "rate_limit_exceeded" (the
        // the reference implementation's error_code/1 for :rate_limited) so clients can branch on
        // it exactly as they do on the gateway-originated denial.
        ErrorMapper.ErrorMapping mapping = mapper.map(provider(ProviderException.TYPE_RATE_LIMITED, "slow down", 429));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, mapping.status());
        assertEquals(ErrorMapper.TYPE_RATE_LIMIT_ERROR, mapping.envelope().type());
        assertEquals(ErrorMapper.CODE_RATE_LIMIT_EXCEEDED, mapping.envelope().code());
    }

    @Test
    void upstream5xxIs502ServerError() {
        ErrorMapper.ErrorMapping mapping = mapper.map(provider(ProviderException.TYPE_UPSTREAM_5XX, "boom", 503));
        assertEquals(HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals(ErrorMapper.TYPE_SERVER_ERROR, mapping.envelope().type());
        assertNull(mapping.envelope().code());
    }

    @Test
    void upstream4xxUsesUpstreamStatusWhenPresent() {
        ErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_UPSTREAM_4XX, "bad upstream", 400));
        assertEquals(HttpStatus.BAD_REQUEST, mapping.status());
        assertEquals(ErrorMapper.TYPE_API_ERROR, mapping.envelope().type());
    }

    @Test
    void upstream4xxDefaultsTo502WithoutStatus() {
        ErrorMapper.ErrorMapping mapping =
                mapper.map(new ProviderException(ProviderException.TYPE_UPSTREAM_4XX, "bad upstream"));
        assertEquals(HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals(ErrorMapper.TYPE_API_ERROR, mapping.envelope().type());
    }

    @Test
    void upstream4xxUnmappedCodeFallsBackTo502NotCrash() {
        // Upstream codes absent from Micronaut's HttpStatus enum (nginx 444/499 …)
        // must map to 502 instead of throwing inside the error handler.
        ErrorMapper.ErrorMapping mapping = mapper.map(provider(ProviderException.TYPE_UPSTREAM_4XX, "nginx 499", 499));
        assertEquals(HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals(ErrorMapper.TYPE_API_ERROR, mapping.envelope().type());
        assertEquals("nginx 499", mapping.envelope().message());
    }

    // ------------------------------------------- 3xx / out-of-band upstream codes

    @Test
    void upstream4xxWith3xxStatusIsClampedTo502() {
        // An upstream answering a gateway-originated request with 301/302/304
        // must NOT be passed through as a redirect with a JSON error body and no Location
        // header — the passthrough is restricted to the 4xx band, 3xx → 502.
        for (int status : new int[] {301, 302, 304, 307}) {
            ErrorMapper.ErrorMapping mapping =
                    mapper.map(provider(ProviderException.TYPE_UPSTREAM_4XX, "redirected", status));
            assertEquals(HttpStatus.BAD_GATEWAY, mapping.status(), "code " + status + " must clamp to 502");
            assertEquals(ErrorMapper.TYPE_API_ERROR, mapping.envelope().type(), "code " + status);
        }
    }

    @Test
    void upstream4xxWithNonPositiveStatusIsClampedTo502() {
        // A statusCode of 0/-1 (unset sentinel leaked through) is
        // outside every HTTP band — 502, never a fabricated status.
        for (int status : new int[] {0, -1}) {
            ErrorMapper.ErrorMapping mapping =
                    mapper.map(provider(ProviderException.TYPE_UPSTREAM_4XX, "weird", status));
            assertEquals(HttpStatus.BAD_GATEWAY, mapping.status(), "code " + status + " must clamp to 502");
        }
    }

    @Test
    void upstream4xxWith5xxStatusIsClampedTo502() {
        // Band check: a misclassified statusCode=503 attached to
        // TYPE_UPSTREAM_4XX must never surface as a 5xx error whose type is api_error —
        // the 4xx row clamps to 502, same as the 499-fallback row.
        ErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_UPSTREAM_4XX, "out of band", 503));
        assertEquals(HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals(ErrorMapper.TYPE_API_ERROR, mapping.envelope().type());
    }

    @Test
    void networkIs502ApiError() {
        ErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_NETWORK, "connection reset", null));
        assertEquals(HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals(ErrorMapper.TYPE_API_ERROR, mapping.envelope().type());
    }

    @Test
    void timeoutIs504ApiError() {
        ErrorMapper.ErrorMapping mapping = mapper.map(provider(ProviderException.TYPE_TIMEOUT, "no answer", null));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, mapping.status());
        assertEquals(ErrorMapper.TYPE_API_ERROR, mapping.envelope().type());
    }

    @Test
    void badUpstreamPayloadIs502ApiError() {
        ErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, "garbage", null));
        assertEquals(HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals(ErrorMapper.TYPE_API_ERROR, mapping.envelope().type());
    }

    @Test
    void unexpectedRuntimeExceptionIs500ApiError() {
        ErrorMapper.ErrorMapping mapping = mapper.map(new IllegalStateException("nobody expected this"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mapping.status());
        assertEquals(ErrorMapper.TYPE_API_ERROR, mapping.envelope().type());
        assertEquals("internal server error", mapping.envelope().message());
        assertNull(mapping.envelope().code());
    }

    // -------------------------------------------------- RateLimitExceededException rows

    @Test
    void rateLimitExceededIs429RateLimitError() {
        ErrorMapper.ErrorMapping mapping = mapper.map(
                new RateLimitExceededException(RateLimitExceededException.Reason.RATE_LIMIT_EXCEEDED, 60L, "k1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, mapping.status());
        assertEquals(ErrorMapper.TYPE_RATE_LIMIT_ERROR, mapping.envelope().type());
        assertEquals("rate limit exceeded for this key", mapping.envelope().message());
        assertEquals(ErrorMapper.CODE_RATE_LIMIT_EXCEEDED, mapping.envelope().code());
    }

    @Test
    void budgetExceededHardIs429RateLimitErrorWithInsufficientQuotaCode() {
        // The OpenAI envelope discriminates the budget denial — code
        // "insufficient_quota" (retry is pointless: a budget does not refill on a
        // timer) vs code=null on the rate-limit denial.
        ErrorMapper.ErrorMapping mapping = mapper.map(
                new RateLimitExceededException(RateLimitExceededException.Reason.BUDGET_EXCEEDED_HARD, null, "k1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, mapping.status());
        assertEquals(ErrorMapper.TYPE_RATE_LIMIT_ERROR, mapping.envelope().type());
        assertEquals("key budget exceeded", mapping.envelope().message());
        assertEquals(ErrorMapper.CODE_INSUFFICIENT_QUOTA, mapping.envelope().code());
    }

    @Test
    void unexpectedExceptionWithNullMessageStillYieldsEnvelope() {
        ErrorMapper.ErrorMapping mapping = mapper.map(new IllegalStateException());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mapping.status());
        assertEquals(ErrorMapper.TYPE_API_ERROR, mapping.envelope().type());
        assertEquals("internal server error", mapping.envelope().message());
    }

    // -------------------------------------------- upstream-message secret redaction

    @Test
    void upstreamAuthMessageNeverLeaksTheProviderKey() {
        // A real upstream echoes the presented key inside 401 bodies — the
        // forwarded envelope must carry neither the key nor the sk- shape. The fixture
        // convention is "<redacted>".
        String secret = "sk-abcdef0123456789abcdef0123456789";
        ErrorMapper.ErrorMapping mapping =
                mapper.map(provider(ProviderException.TYPE_AUTH, "Incorrect API key provided: " + secret, 401));
        String message = mapping.envelope().message();
        assertFalse(message.contains(secret), message);
        assertFalse(message.contains("sk-"), "the sk- shape must be redacted: " + message);
        assertTrue(message.contains("<redacted>"), message);
        assertEquals(HttpStatus.UNAUTHORIZED, mapping.status());
        assertEquals(ErrorMapper.TYPE_AUTHENTICATION_ERROR, mapping.envelope().type());
    }

    @Test
    void upstreamMessageWithoutSecretShapePassesThroughVerbatim() {
        // Redaction must be additive-only: a message with no sk- shape is forwarded
        // unchanged (the pinned ErrorFixtureTest verbatim behavior).
        ErrorMapper.ErrorMapping mapping = mapper.map(
                provider(ProviderException.TYPE_RATE_LIMITED, "Rate limit reached, please resend later", 429));
        assertEquals(
                "Rate limit reached, please resend later", mapping.envelope().message());
    }

    // ------------------------------------------- redaction shape coverage

    @Test
    void shortSecretAndAnthropicShapeAreRedacted() {
        // The pattern is anchored on the sk- prefix with no length floor — a
        // short sk-… secret and the sk-ant-api03-… Anthropic shape must both be caught
        // (the old {16,} floor let both leak).
        assertRedacted("short secret sk-abc123 leaked");
        assertRedacted("anthropic key sk-ant-api03-abcdef0123456789abcdef0123456789 leaked");
    }

    private void assertRedacted(String message) {
        ErrorMapper.ErrorMapping mapping = mapper.map(provider(ProviderException.TYPE_AUTH, message, 401));
        assertFalse(
                mapping.envelope().message().contains("sk-"), mapping.envelope().message());
        assertTrue(
                mapping.envelope().message().contains("<redacted>"),
                mapping.envelope().message());
    }

    // ------------------------------------- codec-row messages are redacted too

    @Test
    void apiErrorCodecMessageNeverLeaksTheSecret() {
        // The codec TYPE_API_ERROR row embeds a Jackson parse-error string
        // ("invalid OpenAI response: " + message) that can echo an offending upstream
        // token — route it through the same redaction choke point as the
        // ProviderException rows (defense-in-depth for future codecs/adapters).
        String secret = "sk-abcdef0123456789abcdef0123456789";
        ErrorMapper.ErrorMapping mapping = mapper.map(
                new OpenAiCodecException(OpenAiCodecException.TYPE_API_ERROR, "invalid JSON token: " + secret));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mapping.status());
        assertFalse(
                mapping.envelope().message().contains(secret),
                mapping.envelope().message());
        assertFalse(
                mapping.envelope().message().contains("sk-"), mapping.envelope().message());
        assertTrue(
                mapping.envelope().message().contains("<redacted>"),
                mapping.envelope().message());
    }

    // ------------------------------------------ null-message ProviderException

    @Test
    void providerExceptionWithNullMessageNeverYieldsAnEnvelopeNpe() {
        // The ProviderException constructor allows a null message (every
        // adapter call site happens to pass a literal, so this is a landmine, not a
        // live bug) — the mapper must degrade to a fixed non-null message. Without the
        // guard the OpenAI envelope's requireNonNull NPEs *inside* the error mapping,
        // which escapes the SSE worker with no terminal signal (a hang) or turns the
        // mapped 401/429/502 into a Micronaut default 500.
        for (String type : new String[] {
            ProviderException.TYPE_AUTH,
            ProviderException.TYPE_RATE_LIMITED,
            ProviderException.TYPE_UPSTREAM_5XX,
            ProviderException.TYPE_UPSTREAM_4XX,
            ProviderException.TYPE_NETWORK,
            ProviderException.TYPE_TIMEOUT,
            ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD
        }) {
            ErrorMapper.ErrorMapping mapping = mapper.map(new ProviderException(type, null));
            assertEquals("upstream error", mapping.envelope().message(), type);
            assertFalse(mapping.envelope().type().isBlank(), type);
            assertEquals(
                    type == ProviderException.TYPE_AUTH
                            ? HttpStatus.UNAUTHORIZED
                            : type == ProviderException.TYPE_RATE_LIMITED
                                    ? HttpStatus.TOO_MANY_REQUESTS
                                    : type == ProviderException.TYPE_TIMEOUT
                                            ? HttpStatus.GATEWAY_TIMEOUT
                                            : HttpStatus.BAD_GATEWAY,
                    mapping.status(),
                    type);
        }
    }

    private static ProviderException provider(String type, String message, Integer statusCode) {
        return new ProviderException(type, message, statusCode, null);
    }

    @Test
    void backendExceptionMapsTo502ApiErrorNeverTheUntyped500() {
        // The cross-format bug: the router's own dispatch failure (probe-slot
        // contention, no claimable candidate) fell through to the untyped 500
        // "internal server error" — a transient availability race must read as 502.
        ErrorMapper.ErrorMapping mapping = mapper.map(new io.amscotti.janus.router.BackendException(
                io.amscotti.janus.router.BackendException.TYPE_NETWORK,
                "no upstream available: every circuit-breaker probe slot is busy"));
        assertEquals(io.micronaut.http.HttpStatus.BAD_GATEWAY, mapping.status());
        assertEquals("api_error", mapping.envelope().type());
        assertTrue(mapping.envelope().message().contains("probe slot is busy"));
    }
}
