package io.amscotti.janus.gateway;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single, <b>documented revocation seam</b> for per-key notifier/metrics
 * state. {@code DedupNotifier.lastNotifiedWindow} and the per-key Micrometer counters
 * are bounded only by the live key set + this prune, so <b>every</b> key-revocation
 * path must funnel its prune through {@link #forget(String)} — never call
 * {@link MetricsRecorder#forgetKey} / {@link Notifier#forgetKey} directly. Today the
 * only revocation path is {@link AdminKeysController#delete} (via {@code
 * revokeAndForget}); a future non-controller path (a direct {@code KeyStore.revoke}, a
 * lifecycle job) that forgets the seam would silently grow both maps/series unboundedly.
 *
 * <p>Best-effort by contract (the pre-seam contract in {@code AdminKeysController}): a
 * throwing recorder/notifier adapter is logged and dropped — a deleting adapter must
 * never turn a successful revoke into a 500 after the key is gone. The failure logs are
 * exception-class-name-only (log hygiene — an adapter exception must never echo
 * key/URL material).
 *
 * <p>Thread-safe (both collaborators are; this seam holds no state).
 */
final class KeyRevocationHooks {

    private static final Logger LOG = LoggerFactory.getLogger(KeyRevocationHooks.class);

    private final MetricsRecorder metricsRecorder;
    private final Notifier notifier;

    KeyRevocationHooks(MetricsRecorder metricsRecorder, Notifier notifier) {
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    /** Drop {@code keyId}'s per-key state from both collaborators (best-effort). */
    void forget(String keyId) {
        try {
            metricsRecorder.forgetKey(keyId);
        } catch (RuntimeException e) {
            LOG.warn(
                    "metrics forgetKey dropped (adapter failure): {}",
                    e.getClass().getSimpleName());
        }
        try {
            notifier.forgetKey(keyId);
        } catch (RuntimeException e) {
            LOG.warn(
                    "notifier forgetKey dropped (adapter failure): {}",
                    e.getClass().getSimpleName());
        }
    }
}
