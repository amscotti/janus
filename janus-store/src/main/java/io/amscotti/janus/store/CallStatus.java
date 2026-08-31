package io.amscotti.janus.store;

/**
 * The closed set of per-request outcomes a {@link CallRecord} carries (; the
 * LiteLLM {@code SpendLogs} status vocabulary trimmed to Janus's axes — the /
 * error-mapper kinds). Mapped from the gateway exception kinds at the / wiring
 * point (the writer is the {@code Governance} finalize/stream-settle path);
 * only defines the type.
 */
public enum CallStatus {
    /** The request completed (or the client boundary finished it cleanly). */
    OK,

    /** The resolved upstream failed: 5xx, network error, or mid-stream abort. */
    ERROR_UPSTREAM,

    /** The client request was rejected before dispatch (4xx, invalid body). */
    ERROR_CLIENT,

    /** Rate-limit or budget denial (429 — the / gates). */
    ERROR_LIMIT,

    /** An internal gateway error (unhandled exception on the request path). */
    ERROR_INTERNAL
}
