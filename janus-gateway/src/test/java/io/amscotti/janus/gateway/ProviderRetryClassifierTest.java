package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.router.BackendException;
import io.amscotti.janus.router.DefaultRetryClassifier;
import io.amscotti.janus.router.RetryClassifier;
import org.junit.jupiter.api.Test;

/**
 * classifier bridge tests (pure JVM): real adapter failures ({@code
 * ProviderException}) retry iff {@code retryable} — 429/5xx/network/timeout yes,
 * auth/4xx/bad-payload no; everything else falls through to the default semantics
 * ({@link DefaultRetryClassifier}: router {@link BackendException} flag honored, unknown
 * unchecked exceptions / {@link Error}s / null → not retryable).
 */
class ProviderRetryClassifierTest {

    private static final RetryClassifier CLASSIFIER = ProviderRetryClassifier.INSTANCE;

    @Test
    void providerExceptionRetryableTypesRetry() {
        assertTrue(CLASSIFIER.isRetryable(new ProviderException(ProviderException.TYPE_RATE_LIMITED, "429")));
        assertTrue(CLASSIFIER.isRetryable(new ProviderException(ProviderException.TYPE_UPSTREAM_5XX, "500")));
        assertTrue(CLASSIFIER.isRetryable(new ProviderException(ProviderException.TYPE_NETWORK, "refused")));
        assertTrue(CLASSIFIER.isRetryable(new ProviderException(ProviderException.TYPE_TIMEOUT, "timeout")));
    }

    @Test
    void providerExceptionNonRetryableTypesDoNotRetry() {
        assertFalse(CLASSIFIER.isRetryable(new ProviderException(ProviderException.TYPE_AUTH, "401")));
        assertFalse(CLASSIFIER.isRetryable(new ProviderException(ProviderException.TYPE_UPSTREAM_4XX, "400")));
        assertFalse(CLASSIFIER.isRetryable(new ProviderException(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, "junk")));
    }

    @Test
    void backendExceptionPassthroughHonorsRetryableFlag() {
        assertTrue(CLASSIFIER.isRetryable(new BackendException(BackendException.TYPE_NETWORK, "refused")));
        assertTrue(CLASSIFIER.isRetryable(new BackendException(BackendException.TYPE_RATE_LIMITED, "429")));
        assertFalse(CLASSIFIER.isRetryable(new BackendException(BackendException.TYPE_AUTH, "401")));
        assertFalse(CLASSIFIER.isRetryable(new BackendException(BackendException.TYPE_UPSTREAM_4XX, "400")));
    }

    @Test
    void unknownUncheckedExceptionErrorAndNullFallThroughToDefaultSemantics() {
        assertFalse(CLASSIFIER.isRetryable(new IllegalStateException("unknown")));
        assertFalse(CLASSIFIER.isRetryable(new AssertionError("error")));
        assertFalse(CLASSIFIER.isRetryable(null));
    }

    @Test
    void sharedSingletonIsStateless() {
        assertSame(ProviderRetryClassifier.INSTANCE, CLASSIFIER);
    }
}
