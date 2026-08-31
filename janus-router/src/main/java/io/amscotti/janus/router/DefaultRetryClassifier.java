package io.amscotti.janus.router;

/**
 * default retry classifier: retryable iff {@code error} is a {@link BackendException}
 * whose {@code retryable} flag is set (rate_limited / upstream_5xx / network / timeout).
 * Any other throwable — unknown unchecked exceptions, {@link Error}s, null — is not
 * retryable, so the router fails fast and propagates rather than guessing about failures
 * it cannot classify (the contract preserved for today's gateway wiring).
 *
 * <p>See {@link RetryClassifier} for the hand-off: provider-layer exceptions are
 * deliberately invisible here (AGENTS.md boundary); the gateway wires the mapping at
 * the composition root.
 */
public final class DefaultRetryClassifier implements RetryClassifier {

    /** Shared instance — stateless by construction. */
    public static final RetryClassifier INSTANCE = new DefaultRetryClassifier();

    private DefaultRetryClassifier() {}

    @Override
    public boolean isRetryable(Throwable error) {
        return error instanceof BackendException backendException && backendException.retryable();
    }
}
