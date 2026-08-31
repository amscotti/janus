package io.amscotti.janus.router;

import java.util.Objects;

/**
 * Unchecked, router-visible typed failure contract: what a
 * {@link ChatBackend} failure looks like to the retry/fallback machinery. Carries a
 * {@code type} discriminator (stable string, never message sniffing), a nullable
 * {@code statusCode} (the upstream HTTP status when one exists) and a {@code retryable}
 * flag the {@link RetryClassifier} reads.
 *
 * <p><b>Vocabulary</b> mirrors {@code io.amscotti.janus.provider.ProviderException}
 * (auth / rate_limited / upstream_5xx / upstream_4xx / network / timeout /
 * bad_upstream_payload) <b>without importing it</b> — AGENTS.md pins provider/router to
 * depend on core only, so the router cannot see the provider type. The two vocabularies
 * are bridged by the gateway at the composition root (a provider-aware {@link RetryClassifier} or
 * a {@code ProviderAdapterChatBackend} translation); this class is the router side of
 * that boundary.
 *
 * <p>{@code retryable} is derived from the type (the "never on 4xx" rule is baked into
 * the flag): rate limits, upstream 5xx, network and timeout failures are retryable; auth,
 * other 4xx and malformed-payload failures are not.
 */
public final class BackendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Upstream rejected our credentials (HTTP 401). Not retryable. */
    public static final String TYPE_AUTH = "auth";

    /** Upstream rate limited us (HTTP 429 / rate-limit error frame). Retryable. */
    public static final String TYPE_RATE_LIMITED = "rate_limited";

    /** Upstream server error (HTTP 5xx / server error frame). Retryable. */
    public static final String TYPE_UPSTREAM_5XX = "upstream_5xx";

    /** Any other upstream client-error status (HTTP 4xx, 3xx). Not retryable. */
    public static final String TYPE_UPSTREAM_4XX = "upstream_4xx";

    /** Transport failure (connection refused/reset, DNS, ...). Retryable. */
    public static final String TYPE_NETWORK = "network";

    /** Upstream did not answer within the request timeout. Retryable. */
    public static final String TYPE_TIMEOUT = "timeout";

    /** Upstream sent a body/chunk that failed codec parsing or SSE framing. Not retryable. */
    public static final String TYPE_BAD_UPSTREAM_PAYLOAD = "bad_upstream_payload";

    private final String type;
    private final Integer statusCode;
    private final boolean retryable;

    public BackendException(String type, String message) {
        this(type, message, null, null);
    }

    public BackendException(String type, String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.type = Objects.requireNonNull(type, "type");
        this.statusCode = statusCode;
        this.retryable = isRetryable(type);
    }

    /** Error-type discriminator (see the type constants). */
    public String type() {
        return type;
    }

    /** Upstream HTTP status when one exists, else null. */
    public Integer statusCode() {
        return statusCode;
    }

    /** Whether the retry policy should retry this failure (derived from {@link #type()}). */
    public boolean retryable() {
        return retryable;
    }

    private static boolean isRetryable(String type) {
        return switch (type) {
            case TYPE_RATE_LIMITED, TYPE_UPSTREAM_5XX, TYPE_NETWORK, TYPE_TIMEOUT -> true;
            default -> false;
        };
    }
}
