package io.amscotti.janus.provider;

import java.util.Objects;

/**
 * Unchecked exception raised by {@link ProviderAdapter} implementations for every
 * upstream/transport failure. Carries a {@code type} discriminator (the gateway,, maps
 * it to the OpenAI error envelope without re-parsing), a nullable {@code statusCode} (the
 * upstream HTTP status when one exists) and a {@code retryable} flag (the router,, reads
 * it for its retry policy). Mirrors the {@link io.amscotti.janus.core.codec.OpenAiCodecException}
 * convention: stable string discriminators, never message sniffing.
 *
 * <p>Type vocabulary:
 *
 * <ul>
 * <li>{@link #TYPE_AUTH} — upstream rejected our credentials (HTTP 401/403).
 * <li>{@link #TYPE_RATE_LIMITED} — upstream rate limited us (HTTP 429 / rate-limit error
 * frame).
 * <li>{@link #TYPE_UPSTREAM_5XX} — upstream server error (HTTP 5xx / server error frame).
 * <li>{@link #TYPE_UPSTREAM_4XX} — any other upstream client-error status (HTTP 4xx, 3xx).
 * <li>{@link #TYPE_NETWORK} — transport failure (connection refused/reset, DNS,...).
 * <li>{@link #TYPE_TIMEOUT} — upstream did not answer within the request timeout.
 * <li>{@link #TYPE_BAD_UPSTREAM_PAYLOAD} — upstream sent a body/chunk that failed codec
 * parsing or SSE framing.
 * </ul>
 *
 * <p>{@code retryable} is derived from the type (the adapter's best guess for the router's retry policy):
 * rate limits, upstream 5xx, network and timeout failures are retryable; auth, other 4xx
 * and malformed-payload failures are not. If the router's retry policy refines this, only the
 * mapping here changes — the {@code type} discriminator is the stable contract.
 */
public final class ProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String TYPE_AUTH = "auth";
    public static final String TYPE_RATE_LIMITED = "rate_limited";
    public static final String TYPE_UPSTREAM_5XX = "upstream_5xx";
    public static final String TYPE_UPSTREAM_4XX = "upstream_4xx";
    public static final String TYPE_NETWORK = "network";
    public static final String TYPE_TIMEOUT = "timeout";
    public static final String TYPE_BAD_UPSTREAM_PAYLOAD = "bad_upstream_payload";

    private final String type;
    private final Integer statusCode;
    private final boolean retryable;
    private final Long retryAfterSeconds;

    public ProviderException(String type, String message) {
        this(type, message, null, null, null);
    }

    public ProviderException(String type, String message, Integer statusCode, Throwable cause) {
        this(type, message, statusCode, cause, null);
    }

    /**
     * The full form: adds the upstream {@code Retry-After} delta-seconds carried on the
     * upstream response's {@code Retry-After} header (the provider's precise
     * backoff window must survive the passthrough instead of forcing SDK clients back to
     * default backoff). Null when the response carried no parseable delta-seconds
     * header.
     */
    public ProviderException(String type, String message, Integer statusCode, Throwable cause, Long retryAfterSeconds) {
        super(message, cause);
        this.type = Objects.requireNonNull(type, "type");
        this.statusCode = statusCode;
        this.retryable = isRetryable(type);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Error-type discriminator, ready for error-envelope mapping. */
    public String type() {
        return type;
    }

    /** Upstream HTTP status when one exists, else null. */
    public Integer statusCode() {
        return statusCode;
    }

    /** Whether the router's retry policy should retry this failure. */
    public boolean retryable() {
        return retryable;
    }

    /**
     * The upstream {@code Retry-After} delta-seconds, null when absent or
     * unparseable. Only ever surfaced to clients on a rate-limit failure; the upstream
     * header, when present on a 429, is carried verbatim (a stalled or capped
     * error-body read must not discard the head-derived backoff window). The gateway's
     * exception handler forwards it as the {@code Retry-After} response header so
     * clients retry in the provider's window.
     */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static boolean isRetryable(String type) {
        return switch (type) {
            case TYPE_RATE_LIMITED, TYPE_UPSTREAM_5XX, TYPE_NETWORK, TYPE_TIMEOUT -> true;
            default -> false;
        };
    }
}
