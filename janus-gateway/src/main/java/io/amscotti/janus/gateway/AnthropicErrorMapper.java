package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.AnthropicCodecException;
import io.amscotti.janus.core.codec.AnthropicErrorBody;
import io.amscotti.janus.core.codec.AnthropicErrorPayload;
import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.router.UnknownModelException;
import io.amscotti.janus.store.UnpricedModelException;
import io.micronaut.http.HttpStatus;

/**
 * Table-driven exception → HTTP status + Anthropic error-envelope mapping. The
 * one place exception types become Anthropic wire errors; every row is pinned by
 * {@code AnthropicErrorMapperTest} (mirrors the faces/anthropic.ex
 * the Anthropic type map plus the shared {@link ErrorMapper} table).
 *
 * <pre>
 * Source | HTTP | error.type
 * AnthropicCodecException TYPE_INVALID_REQUEST | 400 | invalid_request_error
 * AnthropicCodecException TYPE_API_ERROR | 500 | api_error
 * UnknownModelException | 404 | not_found_error
 * ProviderException TYPE_AUTH | 401 | authentication_error
 * ProviderException TYPE_RATE_LIMITED | 429 | rate_limit_error
 * ProviderException TYPE_UPSTREAM_5XX | 502 | api_error
 * ProviderException TYPE_UPSTREAM_4XX | 502* | api_error
 * ProviderException TYPE_NETWORK | 502 | api_error
 * ProviderException TYPE_TIMEOUT | 504 | api_error
 * ProviderException TYPE_BAD_UPSTREAM_PAYLOAD | 502 | api_error
 * any other runtime exception | 500 | api_error
 * (* = upstream statusCode when present <b>and in the 4xx band</b>, 502 fallback
 * for codes missing from Micronaut's HttpStatus enum)
 * </pre>
 *
 * <p>Upstream {@link ProviderException} messages and the codec rows' messages are
 * {@link ErrorMapper#redactSecrets redacted} before entering the envelope (the
 * operator's provider key, which upstreams echo inside 401 bodies, must never reach a
 * client, and a codec parse-error string can echo an offending upstream token).
 *
 * <p> rows ({@link KeyAuthException}, the auth middleware's typed failures — see
 * the exception javadoc for the reference 401/403 rationale): {@code MISSING|INVALID|
 * EXPIRED|BAD_MASTER} → 401 {@code authentication_error}; {@code REVOKED|SCOPE_DENIED}
 * → 403 {@code permission_error} (Anthropic's own wire vocabulary has
 * {@code permission_error} for revoked/scope-denied keys).
 *
 * <p> row ({@link RateLimitExceededException}, the <b>gateway-originated</b> 429
 * thrown by {@link Governance}): {@code RATE_LIMIT_EXCEEDED|BUDGET_EXCEEDED_HARD} →
 * 429 {@code rate_limit_error} — Anthropic's own wire type for over-limit requests —
 * on both faces' envelopes (the {@code Retry-After} split is the exception handler's
 * job, see {@link GatewayExceptionHandler}); upstream 429 passthrough
 * ({@code ProviderException.TYPE_RATE_LIMITED}) is untouched.
 *
 * <p><b>404 {@code not_found_error} for unknown models is a decision:</b> the real
 * Anthropic API returns 404 {@code not_found_error} for unknown models and the
 * {@code anthropic} Python SDK treats 404 as a client error (never retried) — same
 * with the same reasoning as the OpenAI-face 404. The envelope {@code message} is the exception message
 * verbatim (upstream-derived messages are secret-redacted, and the untyped 500 row
 * carries the fixed {@code "internal server error"}).
 *
 * <p><b>Envelope rendering (Design notes).</b> The wire shape lives in the core codec's
 * public DTOs ({@link AnthropicErrorPayload} → {@code {"type":"error","error":{…}}})
 * which are already registered in janus-core's reflect-config — no gateway-owned DTO,
 * no new gateway reflect entries; {@link GatewayJson#anthropicErrorBody} serializes
 * them with the shared gateway mapper.
 */
class AnthropicErrorMapper {

    static final String TYPE_INVALID_REQUEST = "invalid_request_error";
    static final String TYPE_API_ERROR = "api_error";
    static final String TYPE_AUTHENTICATION_ERROR = "authentication_error";
    static final String TYPE_RATE_LIMIT_ERROR = "rate_limit_error";
    static final String TYPE_NOT_FOUND_ERROR = "not_found_error";
    static final String TYPE_PERMISSION_ERROR = "permission_error";

    /** HTTP status + envelope pair produced by {@link #map(Throwable)}. */
    record ErrorMapping(HttpStatus status, AnthropicErrorPayload envelope) {}

    AnthropicErrorMapper() {}

    ErrorMapping map(Throwable throwable) {
        if (throwable instanceof AnthropicCodecException codec) {
            return switch (codec.type()) {
                case AnthropicCodecException.TYPE_INVALID_REQUEST ->
                    mapping(HttpStatus.BAD_REQUEST, TYPE_INVALID_REQUEST, redactSecrets(codec.getMessage()));
                case AnthropicCodecException.TYPE_API_ERROR ->
                    mapping(HttpStatus.INTERNAL_SERVER_ERROR, TYPE_API_ERROR, redactSecrets(codec.getMessage()));
                default -> unexpected(throwable);
            };
        }
        if (throwable instanceof UnknownModelException unknown) {
            return mapping(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND_ERROR, unknown.getMessage());
        }
        if (throwable instanceof io.amscotti.janus.router.BackendException backend) {
            // The router's own typed dispatch failure (probe-slot contention during a
            // half-open transition, no claimable candidate) — the same availability
            // class as an upstream network failure: 502 api_error, never the untyped
            // 500 (a probe-slot race is transient, retryable by the client).
            return mapping(HttpStatus.BAD_GATEWAY, TYPE_API_ERROR, redactSecrets(backend.getMessage()));
        }
        if (throwable instanceof ProviderException provider) {
            return switch (provider.type()) {
                case ProviderException.TYPE_AUTH ->
                    mapping(HttpStatus.UNAUTHORIZED, TYPE_AUTHENTICATION_ERROR, redactSecrets(provider.getMessage()));
                case ProviderException.TYPE_RATE_LIMITED ->
                    mapping(HttpStatus.TOO_MANY_REQUESTS, TYPE_RATE_LIMIT_ERROR, redactSecrets(provider.getMessage()));
                case ProviderException.TYPE_UPSTREAM_5XX,
                        ProviderException.TYPE_NETWORK,
                        ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD ->
                    mapping(HttpStatus.BAD_GATEWAY, TYPE_API_ERROR, redactSecrets(provider.getMessage()));
                case ProviderException.TYPE_UPSTREAM_4XX -> {
                    int status =
                            provider.statusCode() != null ? provider.statusCode() : HttpStatus.BAD_GATEWAY.getCode();
                    // Upstream codes not in Micronaut's HttpStatus enum (nginx
                    // 444/499 …) must not throw inside the error handler — 502 fallback,
                    // shared with ErrorMapper. resolveStatus also clamps out-of-band
                    // (3xx/1xx/2xx/5xx) codes to 502 — never a redirect error response.
                    yield mapping(
                            ErrorMapper.resolveStatus(status), TYPE_API_ERROR, redactSecrets(provider.getMessage()));
                }
                case ProviderException.TYPE_TIMEOUT ->
                    mapping(HttpStatus.GATEWAY_TIMEOUT, TYPE_API_ERROR, redactSecrets(provider.getMessage()));
                default -> unexpected(throwable);
            };
        }
        if (throwable instanceof KeyAuthException keyAuth) {
            return switch (keyAuth.reason()) {
                case MISSING, INVALID, EXPIRED, BAD_MASTER ->
                    mapping(HttpStatus.UNAUTHORIZED, TYPE_AUTHENTICATION_ERROR, keyAuth.getMessage());
                case REVOKED, SCOPE_DENIED ->
                    mapping(HttpStatus.FORBIDDEN, TYPE_PERMISSION_ERROR, keyAuth.getMessage());
            };
        }
        if (throwable instanceof UnpricedModelException unpriced) {
            return mapping(HttpStatus.BAD_REQUEST, TYPE_INVALID_REQUEST, unpriced.getMessage());
        }
        if (throwable instanceof RateLimitExceededException rateLimit) {
            return switch (rateLimit.reason()) {
                case RATE_LIMIT_EXCEEDED, BUDGET_EXCEEDED_HARD ->
                    mapping(HttpStatus.TOO_MANY_REQUESTS, TYPE_RATE_LIMIT_ERROR, rateLimit.getMessage());
            };
        }
        return unexpected(throwable);
    }

    private static ErrorMapping unexpected(Throwable throwable) {
        // Fixed message for the untyped 500 row (the class-name/null-message fallback
        // could leak a fully qualified internal class into a client envelope).
        return mapping(HttpStatus.INTERNAL_SERVER_ERROR, TYPE_API_ERROR, "internal server error");
    }

    private static ErrorMapping mapping(HttpStatus status, String type, String message) {
        // The AnthropicErrorBody is @JsonInclude(NON_NULL) — a null message
        // would be silently omitted from the wire envelope (a missing `message` field
        // the Anthropic SDK does not expect). A null-message ProviderException must
        // map to a well-formed envelope with `message` present (mirrors the OpenAI
        // mapper's non-null guarantee at the same choke point).
        return new ErrorMapping(
                status,
                new AnthropicErrorPayload(
                        "error", new AnthropicErrorBody(type, message == null ? "upstream error" : message)));
    }

    private static String redactSecrets(String message) {
        return ErrorMapper.redactSecrets(message);
    }
}
