package io.amscotti.janus.gateway;

import java.util.Objects;

/**
 * Typed <b>gateway-originated</b> 429 thrown by the {@link Governance}
 * collaborator when a keyed request crosses a rate limit or hard budget cap before
 * dispatch, and mapped to face-appropriate envelopes by {@link ErrorMapper} /
 * {@link AnthropicErrorMapper} through {@link GatewayExceptionHandler} (path-aware
 * since. Reasons and wire semantics:
 *
 * <pre>
 * Reason | HTTP | OpenAI/Anthropic type
 * RATE_LIMIT_EXCEEDED | 429 | rate_limit_error (+ Retry-After header)
 * BUDGET_EXCEEDED_HARD | 429 | rate_limit_error (+ Retry-After only when the key's
 * budget has a reset window — see below; a lifetime budget carries none)
 * </pre>
 *
 * <p>Both faces use the same {@code rate_limit_error} wire type (Anthropic's own wire
 * vocabulary for over-limit requests), mirroring how upstream 429s already map — the
 * upstream {@code ProviderException.TYPE_RATE_LIMITED} rows stay untouched; this is
 * the <b>gateway-originated</b> row.
 *
 * <p><b>Retry-After.</b> {@code retryAfterSeconds} is non-null for
 * {@code RATE_LIMIT_EXCEEDED} (the {@link RateLimiter} denial's seconds until the
 * window/bucket reopens) and for {@code BUDGET_EXCEEDED_HARD} <b>when the key's
 * budget has a reset window</b> (the key's {@code budgetDuration} — the cap refills
 * at each aligned window boundary, so the header is the seconds until that reset).
 * A <b>lifetime</b> budget ({@code budgetDuration == null}) keeps the pre-window
 * behavior: null, no header — a lifetime cap does not refill on a timer — and the
 * {@code GatewayExceptionHandler} sets the header only when the value is present
 * (the first header-carrying gateway error mapping).
 *
 * <p><b>Secret hygiene.</b> Messages are fixed per reason; {@code keyId} is the
 * non-secret {@link KeyRecord#id}  and is never interpolated into a
 * message.
 */
final class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The typed failure; see the class javadoc for the envelope + header mapping. */
    enum Reason {
        RATE_LIMIT_EXCEEDED,
        BUDGET_EXCEEDED_HARD
    }

    private final Reason reason;
    private final Long retryAfterSeconds;
    private final String keyId;

    RateLimitExceededException(Reason reason, Long retryAfterSeconds, String keyId) {
        super(message(reason));
        this.reason = Objects.requireNonNull(reason, "reason");
        this.retryAfterSeconds = retryAfterSeconds;
        this.keyId = keyId;
    }

    Reason reason() {
        return reason;
    }

    /**
     * Seconds until the rate limit reopens, or until a <b>windowed</b> budget's reset;
     * null for lifetime budget denials (no timer).
     */
    Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** The non-secret key id implicated ( metrics). */
    String keyId() {
        return keyId;
    }

    private static String message(Reason reason) {
        return switch (reason) {
            case RATE_LIMIT_EXCEEDED -> "rate limit exceeded for this key";
            case BUDGET_EXCEEDED_HARD -> "key budget exceeded";
        };
    }
}
