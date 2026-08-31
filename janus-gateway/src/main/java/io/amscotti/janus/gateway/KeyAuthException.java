package io.amscotti.janus.gateway;

import java.util.Objects;

/**
 * Typed authentication/authorization failure thrown by the auth path —
 * {@link KeyAuthFilter} (request auth + admin master-key auth) and the controllers'
 * scope check — and mapped to face-appropriate envelopes by {@link ErrorMapper} /
 * {@link AnthropicErrorMapper} through {@link GatewayExceptionHandler} (path-aware
 * since. Reasons and wire semantics :
 *
 * <pre>
 * Reason | HTTP | OpenAI/Anthropic type
 * MISSING | 401 | authentication_error
 * INVALID | 401 | authentication_error
 * EXPIRED | 401 | authentication_error
 * BAD_MASTER | 401 | authentication_error
 * REVOKED | 403 | permission_error
 * SCOPE_DENIED | 403 | permission_error
 * </pre>
 *
 * <p><b>Revoked → 403, not 401 (documented decision; see {@link
 * io.amscotti.janus.store.KeyStatus}).</b> the reference lets clients distinguish a bad key
 * from a key taken away; the spec wording ("invalid/revoked keys get
 * 401") is flagged for the gate — the change, if the gate insists, is one mapper
 * row + one filter test.
 *
 * <p><b>Secret hygiene.</b> Messages are fixed per reason — never the presented key,
 * never the master key, and the optional {@code keyId} (non-secret, for metrics /
 * debugging) is never interpolated into a message.
 */
final class KeyAuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The typed failure; see the class javadoc for the envelope mapping. */
    enum Reason {
        MISSING,
        INVALID,
        EXPIRED,
        REVOKED,
        SCOPE_DENIED,
        BAD_MASTER
    }

    private final Reason reason;
    private final String keyId;

    KeyAuthException(Reason reason, String keyId) {
        super(message(reason));
        this.reason = Objects.requireNonNull(reason, "reason");
        this.keyId = keyId;
    }

    Reason reason() {
        return reason;
    }

    /** The non-secret key id implicated (null when none — missing/bad-master). */
    String keyId() {
        return keyId;
    }

    private static String message(Reason reason) {
        return switch (reason) {
            case MISSING -> "missing Authorization or x-api-key header";
            case INVALID -> "invalid or unknown credentials";
            case EXPIRED -> "gateway key has expired";
            case REVOKED -> "gateway key has been revoked";
            case SCOPE_DENIED -> "key is not permitted to access the requested model";
            case BAD_MASTER -> "invalid master key";
        };
    }
}
