package io.amscotti.janus.router;

import java.util.Objects;

/**
 * resilience bundle handed to {@link Router#resilient}:
 * the retry policy (bounded attempts, exponential backoff + jitter), the per-upstream
 * health state (consecutive-failure tracking + cooldown probation) and the retryability
 * classifier. All three are non-null; {@link #none} reproduces the balanced
 * behavior byte-for-byte ({@code maxRetries 0}, disabled health).
 *
 * <p>Plain data holder — the circuit breaker adds fields/factory variants additively,
 * and {@code Router.resilient}'s signature is the only call site (the gateway wires a
 * real config in from {@code [router]} TOML).
 */
public record ResilienceConfig(RetryPolicy retryPolicy, UpstreamHealth health, RetryClassifier classifier) {

    public ResilienceConfig {
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(classifier, "classifier");
    }

    /**
     * The -identical configuration: exactly one attempt ({@code maxRetries 0} — the
     * attempt loop never consults the classifier), no-op health. {@link Router#balanced}
     * delegates here, so behavior is the refactor's spec.
     */
    public static ResilienceConfig none() {
        return new ResilienceConfig(
                new RetryPolicy(0, 1, 1, 0.0), UpstreamHealth.disabled(), DefaultRetryClassifier.INSTANCE);
    }
}
