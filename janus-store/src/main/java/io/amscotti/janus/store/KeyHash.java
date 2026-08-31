package io.amscotti.janus.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Per-key salted SHA-256 hashing + timing-safe verification ({@code
 * Janus.Keys.generate_material}/{@code verify_secret}). At rest Janus
 * persists only {@code salt} + {@code hash(salt, secret)} — never the plaintext
 * secret; auth compares digests with {@link MessageDigest#isEqual}, which folds the
 * length difference into the result instead of short-circuiting.
 *
 * <p><b>Length-leak note (documented, re-verifies).</b> {@code MessageDigest.isEqual}
 * on differently-sized inputs still runs a full compare, so the standard timing side
 * channel is closed. The one residual leak is the <em>stored-hash length</em>, which is
 * fixed (SHA-256 = 32 bytes) and the <em>presented-secret length</em> is compared
 * implicitly — acceptable because the key prefix is public by design and the secret
 * length is fixed ({@link KeyGenerator#SECRET_LENGTH}); the security re-pass
 * re-verifies this stance.
 *
 * <p><b>Prefix-existence timing note (companion, re-verifies).</b> The auth path
 * ({@link KeyStore#authenticate}) returns {@code INVALID} for an unknown prefix with
 * <em>no</em> SHA-256 work, but runs a full hash for a known prefix with a wrong
 * secret — a measurable timing differential that reveals only which prefixes exist.
 * The prefix is the public O(1) index by design (exposed in {@code GET /key/list})
 * and ~47.6 bits (infeasible to enumerate), so the stance is accepted; see
 * {@link KeyStore#authenticate} for the full rationale.
 */
public final class KeyHash {

    private KeyHash() {}

    /**
     * {@code SHA-256(salt ‖ secret)} over UTF-8 bytes — the stored digest.
     *
     * @param salt per-key random salt (never null)
     * @param secret the plaintext secret (never null)
     * @return the 32-byte digest
     */
    public static byte[] hash(byte[] salt, String secret) {
        Objects.requireNonNull(salt, "salt");
        Objects.requireNonNull(secret, "secret");
        MessageDigest digest = sha256();
        digest.update(salt);
        return digest.digest(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Timing-safe verification of a presented secret against a stored digest. Any null
     * argument (including a null stored hash) fails closed.
     *
     * @param salt the stored per-key salt; null → false
     * @param storedHash the stored digest; null → false
     * @param presentedSecret the secret to verify; null → false
     */
    public static boolean verify(byte[] salt, byte[] storedHash, String presentedSecret) {
        if (salt == null || storedHash == null || presentedSecret == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(salt, presentedSecret), storedHash);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA; this cannot happen on a JDK 25 runtime.
            throw new IllegalStateException("JCA does not provide SHA-256", e);
        }
    }
}
