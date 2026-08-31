package io.amscotti.janus.router;

import static io.amscotti.janus.router.BackendException.TYPE_AUTH;
import static io.amscotti.janus.router.BackendException.TYPE_BAD_UPSTREAM_PAYLOAD;
import static io.amscotti.janus.router.BackendException.TYPE_NETWORK;
import static io.amscotti.janus.router.BackendException.TYPE_RATE_LIMITED;
import static io.amscotti.janus.router.BackendException.TYPE_TIMEOUT;
import static io.amscotti.janus.router.BackendException.TYPE_UPSTREAM_4XX;
import static io.amscotti.janus.router.BackendException.TYPE_UPSTREAM_5XX;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * step 2: {@link RetryClassifier} classification matrix — retryable
 * {@link BackendException}s → true; non-retryable ones, unknown throwables, {@link Error}s
 * and null → false. The router never retries what it cannot classify (fail fast,
 * propagate — contract).
 */
class RetryClassifierTest {

    private final RetryClassifier classifier = DefaultRetryClassifier.INSTANCE;

    @Test
    void retryableBackendExceptionsAreRetryable() {
        for (String type : List.of(TYPE_RATE_LIMITED, TYPE_UPSTREAM_5XX, TYPE_NETWORK, TYPE_TIMEOUT)) {
            assertTrue(classifier.isRetryable(new BackendException(type, "boom")), type);
        }
    }

    @Test
    void nonRetryableBackendExceptionsAreNotRetryable() {
        for (String type : List.of(TYPE_AUTH, TYPE_UPSTREAM_4XX, TYPE_BAD_UPSTREAM_PAYLOAD)) {
            assertFalse(classifier.isRetryable(new BackendException(type, "boom")), type);
        }
    }

    @Test
    void unknownThrowablesAreNotRetryable() {
        assertFalse(classifier.isRetryable(new IllegalStateException("boom")));
        assertFalse(classifier.isRetryable(new RuntimeException("boom")));
        assertFalse(classifier.isRetryable(new AssertionError("kaboom")));
        assertFalse(classifier.isRetryable(new BackendException("made_up_type", "boom")));
    }

    @Test
    void nullIsNotRetryable() {
        assertFalse(classifier.isRetryable(null));
    }
}
