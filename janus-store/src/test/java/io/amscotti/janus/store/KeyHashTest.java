package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link KeyHash}: per-key salted SHA-256 with a timing-safe verify
 * ({@link java.security.MessageDigest#isEqual}, which folds the length difference into
 * the result instead of short-circuiting). The length leak on a mismatched-length
 * presented secret is acceptable and documented: the key prefix is public by design and
 * the secret length is fixed (43 base62 chars) — the security re-pass re-verifies.
 */
class KeyHashTest {

    private static final byte[] SALT = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static final String SECRET = "ZmVj9QhkWy3tL0sXn8aP7bR1uD5gI2cV4oB6mN0jK";

    @Test
    void correctSecretVerifies() {
        byte[] hash = KeyHash.hash(SALT, SECRET);
        assertTrue(KeyHash.verify(SALT, hash, SECRET));
    }

    @Test
    void wrongSecretFails() {
        byte[] hash = KeyHash.hash(SALT, SECRET);
        assertFalse(KeyHash.verify(SALT, hash, "wrong-secret"));
    }

    @Test
    void wrongLengthSecretFails() {
        byte[] hash = KeyHash.hash(SALT, SECRET);
        assertFalse(KeyHash.verify(SALT, hash, "short"));
    }

    @Test
    void hashIsDeterministicForSameSaltAndSecret() {
        assertArrayEquals(KeyHash.hash(SALT, SECRET), KeyHash.hash(SALT, SECRET));
    }

    @Test
    void sameSecretDifferentSaltHashesDiffer() {
        byte[] otherSalt = new byte[] {16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
        assertNotEquals(
                java.util.HexFormat.of().formatHex(KeyHash.hash(SALT, SECRET)),
                java.util.HexFormat.of().formatHex(KeyHash.hash(otherSalt, SECRET)));
    }

    @Test
    void hashIsSha256Length() {
        byte[] hash = KeyHash.hash(SALT, SECRET);
        assertEquals(32, hash.length, "SHA-256 digest is 32 bytes");
    }

    @Test
    void verifyRejectsNullArguments() {
        byte[] hash = KeyHash.hash(SALT, SECRET);
        assertFalse(KeyHash.verify(null, hash, SECRET), "null salt must not verify");
        assertFalse(KeyHash.verify(SALT, null, SECRET), "null stored hash must not verify");
        assertFalse(KeyHash.verify(SALT, hash, null), "null presented secret must not verify");
    }
}
