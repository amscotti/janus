package io.amscotti.janus.store;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;

/**
 * Cryptographically random key material.
 * The full key string is {@code sk-janus-<prefix>-<secret>}:
 *
 * <ul>
 * <li><b>Brand</b> {@code sk-janus-} — the key format specifies
 * {@code sk-janus-...}; the reference implementation semantics uses {@code janus_<prefix>_<secret>}
 * (divergence deliberate: this repo's key format is the contract and {@code sk-} matches
 * LiteLLM vocabulary operators already know).
 * <li><b>Prefix</b> — a non-secret, short, random base62 string
 * ({@link #PREFIX_LENGTH} chars ≈ 47.6 bits) used as the O(1)
 * {@code ConcurrentHashMap} lookup key; safe to log/index.
 * <li><b>Secret</b> — a high-entropy base62 string ({@link #SECRET_LENGTH} chars ≈
 * 32 bytes / 256 bits) that is <b>never stored</b> — only {@code salt} +
 * salted SHA-256 {@code secretHash} persist ({@link KeyHash}).
 * </ul>
 *
 * <p>The full key is returned <b>exactly once</b> at creation ({@link #generate}'s
 * {@link Generated#fullKey}; the store returns it from {@code create} only — list/
 * delete never echo it). {@link #parse} is the auth-path decoder: it validates the
 * brand, part count, lengths and base62 alphabet, so a malformed presented key is
 * rejected before any store lookup.
 */
public final class KeyGenerator {

    /** The branded prefix of every key string ({@code sk-janus-<prefix>-<secret>}). */
    public static final String BRAND = "sk-janus";

    /** Key-string separator (base62 contains neither {@code -} nor {@code _}). */
    public static final String SEPARATOR = "-";

    /** Prefix length in base62 chars (non-secret index, ~47.6 bits). */
    public static final int PREFIX_LENGTH = 8;

    /** Secret length in base62 chars (≈ 32 bytes / 256 bits of entropy). */
    public static final int SECRET_LENGTH = 43;

    /** Salt length in bytes (uses 16). */
    public static final int SALT_BYTES = 16;

    /** Base62 alphabet ({@code 0-9A-Za-z}) — the documented key alphabet. */
    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** 62 × 4 = 248 ≤ 256: rejection threshold for unbiased per-char sampling. */
    private static final int ALPHABET_REJECTION_LIMIT = 248;

    /** Shared thread-safe source of randomness (also used for key ids). */
    static final SecureRandom RANDOM = new SecureRandom();

    private KeyGenerator() {}

    /**
     * Key material produced exactly once at creation. {@code salt}/{@code secretHash}
     * accessors return defensive copies.
     *
     * @param prefix non-secret index
     * @param secret the high-entropy secret — returned here, never persisted
     * @param salt per-key random salt
     * @param secretHash salted SHA-256 of {@code secret}
     * @param fullKey the complete {@code sk-janus-<prefix>-<secret>} string
     */
    public record Generated(String prefix, String secret, byte[] salt, byte[] secretHash, String fullKey) {

        public Generated {
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(secret, "secret");
            Objects.requireNonNull(salt, "salt");
            Objects.requireNonNull(secretHash, "secretHash");
            salt = salt.clone();
            secretHash = secretHash.clone();
        }

        @Override
        public byte[] salt() {
            return salt.clone();
        }

        @Override
        public byte[] secretHash() {
            return secretHash.clone();
        }
    }

    /** The decoded {@code {prefix, secret}} pair of a valid key string. */
    public record Parsed(String prefix, String secret) {}

    /** Generate fresh key material: unbiased base62 prefix + secret, fresh salt, hash. */
    public static Generated generate() {
        String prefix = randomAlphabet(PREFIX_LENGTH);
        String secret = randomAlphabet(SECRET_LENGTH);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] secretHash = KeyHash.hash(salt, secret);
        return new Generated(prefix, secret, salt, secretHash, fullKey(prefix, secret));
    }

    /**
     * Decode a presented key string into its {@code {prefix, secret}} pair. Empty when
     * the string is not a well-formed {@code sk-janus-<prefix>-<secret>}: wrong brand,
     * wrong part count, wrong lengths, or non-base62 characters.
     */
    public static Optional<Parsed> parse(String fullKey) {
        if (fullKey == null) {
            return Optional.empty();
        }
        String[] parts = fullKey.split(SEPARATOR, -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        if (!"sk".equals(parts[0]) || !"janus".equals(parts[1])) {
            return Optional.empty();
        }
        String prefix = parts[2];
        String secret = parts[3];
        if (prefix.length() != PREFIX_LENGTH || secret.length() != SECRET_LENGTH) {
            return Optional.empty();
        }
        if (!isBase62(prefix) || !isBase62(secret)) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(prefix, secret));
    }

    /** The complete {@code sk-janus-<prefix>-<secret>} string. */
    public static String fullKey(String prefix, String secret) {
        return BRAND + SEPARATOR + prefix + SEPARATOR + secret;
    }

    private static String randomAlphabet(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            int index;
            do {
                index = RANDOM.nextInt(256);
            } while (index >= ALPHABET_REJECTION_LIMIT);
            // 248 = 62 × 4 accepted values, so index % 62 is uniform over the alphabet.
            chars[i] = ALPHABET.charAt(index % ALPHABET.length());
        }
        return new String(chars);
    }

    private static boolean isBase62(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (ALPHABET.indexOf(value.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
