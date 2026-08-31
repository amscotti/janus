package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.OpenAiCodecException;
import io.amscotti.janus.gateway.dto.OpenAiErrorEnvelope;
import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.router.UnknownModelException;
import io.amscotti.janus.store.UnpricedModelException;
import io.micronaut.http.HttpStatus;
import java.util.regex.Pattern;

/**
 * Table-driven exception → HTTP status + OpenAI error-envelope mapping. The one
 * place exception types become wire errors; every row is pinned by {@code
 * ErrorMapperTest}.
 *
 * <pre>
 * Source | HTTP | error.type | code
 * OpenAiCodecException TYPE_INVALID_REQUEST | 400 | invalid_request_error | null
 * OpenAiCodecException TYPE_API_ERROR | 500 | api_error | null
 * UnknownModelException | 404 | invalid_request_error | model_not_found
 * ProviderException TYPE_AUTH | 401 | authentication_error | null
 * ProviderException TYPE_RATE_LIMITED | 429 | rate_limit_error | rate_limit_exceeded
 * ProviderException TYPE_UPSTREAM_5XX | 502 | server_error | null
 * ProviderException TYPE_UPSTREAM_4XX | 502* | api_error | null
 * ProviderException TYPE_NETWORK | 502 | api_error | null
 * ProviderException TYPE_TIMEOUT | 504 | api_error | null
 * ProviderException TYPE_BAD_UPSTREAM_PAYLOAD | 502 | api_error | null
 * KeyAuthException MISSING|INVALID|EXPIRED|BAD_MASTER | 401 | authentication_error | invalid_api_key
 * KeyAuthException REVOKED|SCOPE_DENIED | 403 | permission_error | forbidden
 * RateLimitExceededException RATE_LIMIT_EXCEEDED | 429 | rate_limit_error | rate_limit_exceeded
 * RateLimitExceededException BUDGET_EXCEEDED_HARD | 429 | rate_limit_error | insufficient_quota
 * any other runtime exception | 500 | api_error | null
 * (* = upstream statusCode when present <b>and in the 4xx band</b>, else 502)
 * </pre>
 *
 * <p><b>Upstream-message redaction.</b> Every {@link ProviderException} row carries
 * {@link #redactSecrets} of the upstream message before it is placed in the envelope: real
 * providers echo the presented key inside 401 bodies ("Incorrect API key provided:
 * sk-…"), and the gateway's own provider key must never reach a client through a forwarded
 * envelope. The {@code sk-} secret shape is replaced with {@code <redacted>} (the
 * committed-fixture convention); non-matching messages pass through unchanged, so the
 * upstream error text is preserved wherever it carries no secret.
 *
 * <p> rows ({@link KeyAuthException}, the auth middleware's typed failures — see
 * the exception javadoc for the reference 401/403 rationale): {@code MISSING|INVALID|
 * EXPIRED|BAD_MASTER} → 401 {@code authentication_error}; {@code REVOKED|SCOPE_DENIED}
 * → 403 {@code permission_error} (revoked keys are an authorization failure — clients
 * can distinguish "bad key" from "key taken away", the reference implementation semantics).
 *
 * <p> row ({@link RateLimitExceededException}, the <b>gateway-originated</b> 429
 * thrown by {@link Governance}): {@code RATE_LIMIT_EXCEEDED|BUDGET_EXCEEDED_HARD} →
 * 429 {@code rate_limit_error} on both faces' envelopes (the header split — the
 * {@code Retry-After} on rate-limit denials, none on budget denials — is the
 * exception handler's job, see {@link GatewayExceptionHandler}); upstream 429
 * passthrough ({@code ProviderException.TYPE_RATE_LIMITED}) is untouched apart from
 * the {@code code}. The OpenAI envelope additionally discriminates with the
 * the reference implementation's {@code error_code/1} vocabulary — {@code rate_limit_exceeded}
 * for the rate-limit denial and the upstream 429 passthrough, and
 * {@code insufficient_quota} for the budget denial (a client can distinguish
 * "throttle, retry later" from "budget exhausted, retry is pointless"); the
 * auth/permission rows carry {@code invalid_api_key}/{@code forbidden} (real OpenAI
 * returns {@code code: "invalid_api_key"} on 401s, and SDKs stop retrying on
 * {@code invalid_api_key} — the same client-confusion class {@code insufficient_quota}
 * was added to fix); the Anthropic envelope has no code field, so its
 * {@code rate_limit_error} (Anthropic's own over-limit type) is unchanged.
 *
 * <p><b>404 for unknown models is a decision:</b> OpenAI itself returns 404
 * ({@code invalid_request_error} / {@code model_not_found}), so a drop-in OpenAI
 * face matches SDK expectations (a 503 would be treated as retryable and hammered).
 * The envelope {@code message} is the exception's
 * message verbatim (upstream-derived messages are {@link #redactSecrets secret-redacted},
 * and the untyped 500 row carries the fixed {@code "internal server error"}); {@code param}
 * is always null in v1.
 */
class ErrorMapper {

    static final String TYPE_INVALID_REQUEST = "invalid_request_error";
    static final String TYPE_API_ERROR = "api_error";
    static final String TYPE_AUTHENTICATION_ERROR = "authentication_error";
    static final String TYPE_RATE_LIMIT_ERROR = "rate_limit_error";
    static final String TYPE_SERVER_ERROR = "server_error";
    static final String TYPE_PERMISSION_ERROR = "permission_error";
    static final String CODE_MODEL_NOT_FOUND = "model_not_found";
    static final String CODE_INSUFFICIENT_QUOTA = "insufficient_quota";
    static final String CODE_INVALID_API_KEY = "invalid_api_key";
    static final String CODE_FORBIDDEN = "forbidden";
    static final String CODE_RATE_LIMIT_EXCEEDED = "rate_limit_exceeded";

    /**
     * The operator-key secret shape real providers echo inside 401/403 bodies
     * ("Incorrect API key provided: sk-…"). Replaced with {@code <redacted>} in
     * upstream-derived envelope messages so a forwarded error can never leak the
     * gateway's own provider key to a client. Matches the committed-fixture redaction
     * convention ({@code fixtures/errors/README.md}).
     *
     * <p><b>Defense-in-depth.</b> The shipped adapters build generic messages and
     * deliberately never place upstream body text in the exception (see
     * {@code OpenAiCompatibleAdapter}/{@code AnthropicAdapter}), so this regex has no
     * live trigger today — it exists for any future codec/adapter that does embed
     * upstream text. The pattern anchors on the {@code sk-} prefix with no length
     * floor, so short {@code sk-…} secrets and the {@code sk-ant-api03-…} Anthropic
     * shape are all caught; the tradeoff is that any message that legitimately contains
     * the {@code sk-} prefix is redacted, which is acceptable for a gateway error path.
     */
    private static final Pattern SECRET_SHAPE = Pattern.compile("sk-[A-Za-z0-9_-]+");

    /** HTTP status + envelope pair produced by {@link #map(Throwable)}. */
    record ErrorMapping(HttpStatus status, OpenAiErrorEnvelope envelope) {}

    ErrorMapper() {}

    ErrorMapping map(Throwable throwable) {
        if (throwable instanceof OpenAiCodecException codec) {
            return switch (codec.type()) {
                case OpenAiCodecException.TYPE_INVALID_REQUEST ->
                    mapping(HttpStatus.BAD_REQUEST, TYPE_INVALID_REQUEST, redactSecrets(codec.getMessage()), null);
                case OpenAiCodecException.TYPE_API_ERROR ->
                    mapping(HttpStatus.INTERNAL_SERVER_ERROR, TYPE_API_ERROR, redactSecrets(codec.getMessage()), null);
                default -> unexpected(throwable);
            };
        }
        if (throwable instanceof UnknownModelException unknown) {
            return mapping(HttpStatus.NOT_FOUND, TYPE_INVALID_REQUEST, unknown.getMessage(), CODE_MODEL_NOT_FOUND);
        }
        if (throwable instanceof UnpricedModelException unpriced) {
            return mapping(HttpStatus.BAD_REQUEST, TYPE_INVALID_REQUEST, unpriced.getMessage(), null);
        }
        if (throwable instanceof io.amscotti.janus.router.BackendException backend) {
            // The router's own typed dispatch failure (probe-slot contention during a
            // half-open transition, no claimable candidate) — the same availability
            // class as an upstream network failure: 502 api_error, never the untyped
            // 500 (a probe-slot race is transient, retryable by the client).
            return mapping(HttpStatus.BAD_GATEWAY, TYPE_API_ERROR, redactSecrets(backend.getMessage()), null);
        }
        if (throwable instanceof ProviderException provider) {
            return switch (provider.type()) {
                case ProviderException.TYPE_AUTH ->
                    mapping(
                            HttpStatus.UNAUTHORIZED,
                            TYPE_AUTHENTICATION_ERROR,
                            redactSecrets(provider.getMessage()),
                            null);
                case ProviderException.TYPE_RATE_LIMITED ->
                    mapping(
                            HttpStatus.TOO_MANY_REQUESTS,
                            TYPE_RATE_LIMIT_ERROR,
                            redactSecrets(provider.getMessage()),
                            CODE_RATE_LIMIT_EXCEEDED);
                case ProviderException.TYPE_UPSTREAM_5XX ->
                    mapping(HttpStatus.BAD_GATEWAY, TYPE_SERVER_ERROR, redactSecrets(provider.getMessage()), null);
                case ProviderException.TYPE_UPSTREAM_4XX -> {
                    int status =
                            provider.statusCode() != null ? provider.statusCode() : HttpStatus.BAD_GATEWAY.getCode();
                    // Upstream codes not in Micronaut's HttpStatus enum (nginx 444/499 …)
                    // must not throw inside the error handler — fall back to 502. The
                    // passthrough is additionally restricted to the 4xx band — an upstream
                    // 3xx/1xx/2xx/5xx (or an out-of-band misclassification) must never surface
                    // as a redirect/2xx error response (a 3xx reply has no Location header and
                    // OpenAI SDKs follow redirects); it is clamped to 502 instead.
                    yield mapping(resolveStatus(status), TYPE_API_ERROR, redactSecrets(provider.getMessage()), null);
                }
                case ProviderException.TYPE_NETWORK ->
                    mapping(HttpStatus.BAD_GATEWAY, TYPE_API_ERROR, redactSecrets(provider.getMessage()), null);
                case ProviderException.TYPE_TIMEOUT ->
                    mapping(HttpStatus.GATEWAY_TIMEOUT, TYPE_API_ERROR, redactSecrets(provider.getMessage()), null);
                case ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD ->
                    mapping(HttpStatus.BAD_GATEWAY, TYPE_API_ERROR, redactSecrets(provider.getMessage()), null);
                default -> unexpected(throwable);
            };
        }
        if (throwable instanceof KeyAuthException keyAuth) {
            return switch (keyAuth.reason()) {
                case MISSING, INVALID, EXPIRED, BAD_MASTER ->
                    // Real OpenAI returns code "invalid_api_key" on 401s, and
                    // SDKs branch on it (e.g. to stop retrying).
                    mapping(
                            HttpStatus.UNAUTHORIZED,
                            TYPE_AUTHENTICATION_ERROR,
                            keyAuth.getMessage(),
                            CODE_INVALID_API_KEY);
                case REVOKED, SCOPE_DENIED ->
                    // The reference's error_code/1 maps authorization to "forbidden".
                    mapping(HttpStatus.FORBIDDEN, TYPE_PERMISSION_ERROR, keyAuth.getMessage(), CODE_FORBIDDEN);
            };
        }
        if (throwable instanceof RateLimitExceededException rateLimit) {
            return switch (rateLimit.reason()) {
                case RATE_LIMIT_EXCEEDED ->
                    mapping(
                            HttpStatus.TOO_MANY_REQUESTS,
                            TYPE_RATE_LIMIT_ERROR,
                            rateLimit.getMessage(),
                            CODE_RATE_LIMIT_EXCEEDED);
                case BUDGET_EXCEEDED_HARD ->
                    // The OpenAI envelope discriminates the budget denial — a budget cap
                    // does not refill on a timer, so clients can stop retrying (the reference
                    // reference emits error_code "insufficient_quota" for budget denials).
                    mapping(
                            HttpStatus.TOO_MANY_REQUESTS,
                            TYPE_RATE_LIMIT_ERROR,
                            rateLimit.getMessage(),
                            CODE_INSUFFICIENT_QUOTA);
            };
        }
        return unexpected(throwable);
    }

    private static ErrorMapping unexpected(Throwable throwable) {
        // A fixed message for the untyped 500 row — a generic "internal server error"
        // (SDKs treat 5xx as opaque anyway); specifics stay in the gateway logs only, and
        // the class-name fallback for null-message throwables can never leak a fully
        // qualified internal class into a client envelope.
        return mapping(HttpStatus.INTERNAL_SERVER_ERROR, TYPE_API_ERROR, "internal server error", null);
    }

    private static ErrorMapping mapping(HttpStatus status, String type, String message, String code) {
        // The envelope rejects a null message (compact-constructor
        // requireNonNull) and Anthropic omits one — a null-message ProviderException
        // would NPE *inside* the error mapping, escaping the worker with no terminal
        // signal (a hang) or turning the mapped 401/429/502 into a Micronaut 500.
        // Guarantee a non-null message at the mapper: the one choke point.
        return new ErrorMapping(
                status, new OpenAiErrorEnvelope(message == null ? "upstream error" : message, type, null, code));
    }

    /**
     * Micronaut {@link HttpStatus} for an arbitrary upstream code, 502 when unmapped
     * Only the <b>4xx band</b> is passed through ({@code 400 ≤ code < 500}) —
     * a 3xx/1xx/2xx/5xx code (or one absent from Micronaut's enum such as nginx 444/499)
     * falls back to 502, so the passthrough can never surface a redirect or a non-error
     * status with a JSON error body. Package-visible since {@link AnthropicErrorMapper}
     * shares the lookup for its upstream-4xx row (same fallback semantics).
     */
    static HttpStatus resolveStatus(int code) {
        if (code < 400 || code >= 500) {
            return HttpStatus.BAD_GATEWAY;
        }
        for (HttpStatus status : HttpStatus.values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return HttpStatus.BAD_GATEWAY;
    }

    /**
     * Strip {@code sk-…} secret shapes from an upstream-derived error message before
     * it is forwarded to a client (the gateway's own provider key must never leak through
     * a client-facing envelope). Null-safe; non-matching messages pass through verbatim.
     */
    static String redactSecrets(String message) {
        return message == null ? null : SECRET_SHAPE.matcher(message).replaceAll("<redacted>");
    }
}
