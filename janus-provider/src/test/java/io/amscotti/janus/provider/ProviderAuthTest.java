package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * step 1: {@link ProviderAuth} type vocabulary — bearer plus the new
 * {@code x-api-key} type the {@link AnthropicAdapter} authenticates with (Anthropic uses
 * {@code x-api-key}, not {@code Authorization: Bearer}; the SPI must report the scheme
 * honestly). Backward-compatible extension: existing types are untouched, the record's
 * validation accepts the new type, and {@code auth.type} round-trips.
 */
class ProviderAuthTest {

    @Test
    void bearerRoundTrips() {
        ProviderAuth auth = new ProviderAuth(ProviderAuth.TYPE_BEARER, "sk-test");
        assertEquals("bearer", auth.type());
        assertEquals("sk-test", auth.secret());
    }

    @Test
    void xApiKeyAcceptedAndRoundTrips() {
        ProviderAuth auth = new ProviderAuth(ProviderAuth.TYPE_X_API_KEY, "sk-ant-test");
        assertEquals("x-api-key", auth.type());
        assertEquals("sk-ant-test", auth.secret());
    }

    @Test
    void noneAcceptsNullSecret() {
        ProviderAuth auth = new ProviderAuth(ProviderAuth.TYPE_NONE, null);
        assertEquals("none", auth.type());
        assertNull(auth.secret());
    }

    @Test
    void unknownTypeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderAuth("basic", "secret"));
        assertThrows(IllegalArgumentException.class, () -> new ProviderAuth("", "secret"));
        assertThrows(IllegalArgumentException.class, () -> new ProviderAuth(null, "secret"));
    }

    @Test
    void inconsistentSecretTypePairsRejected() {
        // The record's contract ("secret holds the credential; null for
        // TYPE_NONE") is enforced — a TYPE_NONE auth carrying a secret, or a bearer /
        // x-api-key auth with a null secret, fails fast instead of silently reaching an
        // adapter (blank secrets stay allowed: the adapters omit the header when blank).
        assertThrows(IllegalArgumentException.class, () -> new ProviderAuth(ProviderAuth.TYPE_NONE, "k"));
        assertThrows(IllegalArgumentException.class, () -> new ProviderAuth(ProviderAuth.TYPE_BEARER, null));
        assertThrows(IllegalArgumentException.class, () -> new ProviderAuth(ProviderAuth.TYPE_X_API_KEY, null));
    }

    @Test
    void blankSecretAllowedForCredentialedTypes() {
        // matches the adapters' blank-omits-header contract
        new ProviderAuth(ProviderAuth.TYPE_BEARER, "");
        new ProviderAuth(ProviderAuth.TYPE_X_API_KEY, "");
    }
}
