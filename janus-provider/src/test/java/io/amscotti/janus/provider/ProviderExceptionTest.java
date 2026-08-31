package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * step 1: {@link ProviderException} fields and semantics — type discriminator, nullable
 * statusCode, retryable derivation ( retry policy input) and constructors.
 */
class ProviderExceptionTest {

    @Test
    void exposesTypeMessageAndNullStatusCode() {
        ProviderException e = new ProviderException(ProviderException.TYPE_UPSTREAM_4XX, "boom");
        assertEquals("upstream_4xx", e.type());
        assertEquals("boom", e.getMessage());
        assertNull(e.statusCode());
        assertFalse(e.retryable());
        assertNull(e.getCause());
    }

    @Test
    void carriesStatusCodeAndCause() {
        Throwable cause = new RuntimeException("upstream exploded");
        ProviderException e = new ProviderException(ProviderException.TYPE_UPSTREAM_5XX, "boom", 502, cause);
        assertEquals(Integer.valueOf(502), e.statusCode());
        assertSame(cause, e.getCause());
    }

    @Test
    void carriesRetryAfterSecondsWhenPresent() {
        // The upstream Retry-After (429s) survives on the exception for the
        // gateway's header passthrough; the older forms default it to null.
        Throwable cause = new RuntimeException("throttled");
        ProviderException e = new ProviderException(ProviderException.TYPE_RATE_LIMITED, "slow down", 429, cause, 120L);
        assertEquals(Long.valueOf(120), e.retryAfterSeconds());
        assertSame(cause, e.getCause());

        assertNull(new ProviderException(ProviderException.TYPE_RATE_LIMITED, "slow down").retryAfterSeconds());
        assertNull(
                new ProviderException(ProviderException.TYPE_RATE_LIMITED, "slow down", 429, null).retryAfterSeconds());
    }

    @Test
    void retryableTypesAreRateLimitedFiveXxNetworkAndTimeout() {
        for (String retryable : List.of(
                ProviderException.TYPE_RATE_LIMITED,
                ProviderException.TYPE_UPSTREAM_5XX,
                ProviderException.TYPE_NETWORK,
                ProviderException.TYPE_TIMEOUT)) {
            assertTrue(new ProviderException(retryable, "m").retryable(), retryable + " must be retryable");
        }
        for (String terminal : List.of(
                ProviderException.TYPE_AUTH,
                ProviderException.TYPE_UPSTREAM_4XX,
                ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD)) {
            assertFalse(new ProviderException(terminal, "m").retryable(), terminal + " must not be retryable");
        }
    }

    @Test
    void nullTypeIsRejected() {
        assertThrows(NullPointerException.class, () -> new ProviderException(null, "m"));
    }
}
