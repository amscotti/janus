package io.amscotti.janus.router;

/**
 * Pluggable "is this failure worth another attempt?" seam:
 * the router decides retryability through this function instead of peeking at exception
 * classes, keeping provider-layer knowledge out of the router (AGENTS.md — provider and
 * router depend on core only).
 *
 * <p><b>Default.</b> {@link DefaultRetryClassifier} understands only the router's own
 * {@link BackendException}: retryable iff its {@code retryable} flag is set
 * (rate_limited / upstream_5xx / network / timeout). Anything else — unknown exception
 * types, {@link Error}s, null — is <b>not</b> retryable, so the router fails fast and
 * propagates untouched (the contract today's gateway wiring pins).
 *
 * <p><b>Gateway hand-off (do not "fix" with an import).</b> The real adapter path raises
 * {@code io.amscotti.janus.provider.ProviderException}, which the router must never see
 * (AGENTS.md). The gateway's composition root supplies a classifier (or a backend
 * translation) that maps {@code ProviderException.type} → {@link BackendException};
 * this seam is that hand-off point. Until the gateway wires a bridging classifier,
 * production behavior is unchanged.
 */
@FunctionalInterface
public interface RetryClassifier {

    /** Whether {@code error} deserves another attempt. Must not throw. */
    boolean isRetryable(Throwable error);
}
