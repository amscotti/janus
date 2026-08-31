package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.JanusConfig;
import org.junit.jupiter.api.Test;

/**
 * The auth-boot hardening: auth-off is now an <b>explicit config declaration</b>
 * ({@code [janus.keys] auth = "off"}), not the silent default when the master-key env
 * var is forgotten. The default ({@code auth = "on"} / absent): a resolvable key ⇒
 * auth on; no key ⇒ <b>boot fails fast</b> with an actionable message naming both
 * fixes. Explicit {@code off} wins even when a key resolves (operator intent from a
 * file they control), loudly logged — this is the dev/bench/compose-memory posture.
 */
class MasterKeyProviderTest {

    private static JanusConfig.KeysConfig keys(String auth, String envName) {
        return new JanusConfig.KeysConfig(envName == null ? null : envName, auth);
    }

    @Test
    void defaultOnWithKeyResolvesTheKey() {
        assertEquals("master-1", MasterKeyProvider.resolveMasterKey(keys(null, null), "master-1"));
        assertEquals("master-1", MasterKeyProvider.resolveMasterKey(keys("on", null), "master-1"));
    }

    @Test
    void defaultOnWithoutKeyFailsFastWithAnActionableMessage() {
        for (String auth : new String[] {null, "on"}) {
            IllegalStateException unset = assertThrows(
                    IllegalStateException.class, () -> MasterKeyProvider.resolveMasterKey(keys(auth, null), null));
            IllegalStateException blank = assertThrows(
                    IllegalStateException.class, () -> MasterKeyProvider.resolveMasterKey(keys(auth, null), "  "));
            for (IllegalStateException e : new IllegalStateException[] {unset, blank}) {
                assertTrue(e.getMessage().contains("JANUS_MASTER_KEY"), e.getMessage());
                assertTrue(e.getMessage().contains("auth = \"off\""), "names the explicit opt-out: " + e.getMessage());
            }
        }
    }

    @Test
    void customEnvNameIsNamedInTheError() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> MasterKeyProvider.resolveMasterKey(keys("on", "PROD_MASTER"), null));
        assertTrue(e.getMessage().contains("PROD_MASTER"), e.getMessage());
        assertTrue(
                !e.getMessage().contains("JANUS_MASTER_KEY"),
                "the configured name, not the default: " + e.getMessage());
    }

    @Test
    void explicitOffWinsEvenWhenAKeyResolves() {
        assertNull(MasterKeyProvider.resolveMasterKey(keys("off", null), null));
        assertNull(MasterKeyProvider.resolveMasterKey(keys("off", null), "master-1"));
    }

    @Test
    void invalidAuthValueFailsFast() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> MasterKeyProvider.resolveMasterKey(keys("maybe", null), "k"));
        assertTrue(e.getMessage().contains("maybe") && e.getMessage().contains("\"on\" | \"off\""), e.getMessage());
    }
}
