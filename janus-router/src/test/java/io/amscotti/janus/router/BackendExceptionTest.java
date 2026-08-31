package io.amscotti.janus.router;

import static io.amscotti.janus.router.BackendException.TYPE_AUTH;
import static io.amscotti.janus.router.BackendException.TYPE_BAD_UPSTREAM_PAYLOAD;
import static io.amscotti.janus.router.BackendException.TYPE_NETWORK;
import static io.amscotti.janus.router.BackendException.TYPE_RATE_LIMITED;
import static io.amscotti.janus.router.BackendException.TYPE_TIMEOUT;
import static io.amscotti.janus.router.BackendException.TYPE_UPSTREAM_4XX;
import static io.amscotti.janus.router.BackendException.TYPE_UPSTREAM_5XX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * step 2: {@link BackendException} — the router's typed failure contract. Pins the
 * retryable derivation matrix (rate_limited/upstream_5xx/network/timeout → retryable;
 * auth/upstream_4xx/bad_upstream_payload → not — the "never on 4xx" rule baked into the
 * flag), the nullable statusCode, and the fail-fast null-type rejection.
 */
class BackendExceptionTest {

    @Test
    void retryableTypesAreRetryable() {
        for (String type : List.of(TYPE_RATE_LIMITED, TYPE_UPSTREAM_5XX, TYPE_NETWORK, TYPE_TIMEOUT)) {
            assertTrue(new BackendException(type, "boom").retryable(), type);
        }
    }

    @Test
    void nonRetryableTypesAreNotRetryable() {
        for (String type : List.of(TYPE_AUTH, TYPE_UPSTREAM_4XX, TYPE_BAD_UPSTREAM_PAYLOAD)) {
            assertFalse(new BackendException(type, "boom").retryable(), type);
        }
    }

    @Test
    void typeAndMessageRoundTrip() {
        BackendException e = new BackendException(TYPE_NETWORK, "connection reset");
        assertEquals(TYPE_NETWORK, e.type());
        assertEquals("connection reset", e.getMessage());
    }

    @Test
    void statusCodeRoundTripsAndIsNullable() {
        assertEquals(429, new BackendException(TYPE_RATE_LIMITED, "429", 429, null).statusCode());
        assertNull(new BackendException(TYPE_RATE_LIMITED, "429").statusCode());
    }

    @Test
    void causePropagates() {
        IllegalStateException cause = new IllegalStateException("root");
        BackendException e = new BackendException(TYPE_TIMEOUT, "late", 504, cause);
        assertSame(cause, e.getCause());
    }

    @Test
    void rejectsNullType() {
        assertThrows(NullPointerException.class, () -> new BackendException(null, "boom"));
    }

    @Test
    void unknownTypeIsNotRetryable() {
        assertFalse(new BackendException("made_up_type", "boom").retryable());
    }
}
