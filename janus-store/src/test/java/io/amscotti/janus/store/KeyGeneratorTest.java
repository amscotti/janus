package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link KeyGenerator} material: the key string is
 * {@code sk-janus-<prefix>-<secret>} (the documented brand; the reference
 * reference uses {@code janus_<prefix>_<secret>} — the {@code sk-} prefix is
 * the contract and matches LiteLLM vocabulary), the prefix is a non-secret ~8-char base62 index, the
 * secret is high-entropy base62 (43 chars ≈ 32 bytes / 256 bits) and is <em>never
 * stored</em> — only a per-key salted SHA-256 hash + salt persist. The full key is
 * returned exactly once at creation (the {@link KeyGenerator.Generated} record); the
 * {@link KeyRecord} has no field for it (compile-time guarantee).
 */
class KeyGeneratorTest {

    /** Byte-exact key shape: {@code sk-janus-} + 8 base62 + {@code -} + 43 base62. */
    private static final Pattern KEY_SHAPE = Pattern.compile("sk-janus-[0-9A-Za-z]{8}-[0-9A-Za-z]{43}");

    @Test
    void generatedKeysMatchSkJanusShape() {
        KeyGenerator.Generated generated = KeyGenerator.generate();
        assertTrue(KEY_SHAPE.matcher(generated.fullKey()).matches(), generated.fullKey());
    }

    @Test
    void prefixAndSecretHaveDocumentedLengthsAndAlphabet() {
        KeyGenerator.Generated generated = KeyGenerator.generate();
        assertEquals(KeyGenerator.PREFIX_LENGTH, generated.prefix().length());
        assertEquals(KeyGenerator.SECRET_LENGTH, generated.secret().length());
        for (char c : (generated.prefix() + generated.secret()).toCharArray()) {
            assertTrue(KeyGenerator.ALPHABET.indexOf(c) >= 0, "non-base62 character: " + c);
        }
    }

    @Test
    void twoCreationsDiffer() {
        KeyGenerator.Generated a = KeyGenerator.generate();
        KeyGenerator.Generated b = KeyGenerator.generate();
        assertNotEquals(a.fullKey(), b.fullKey());
        assertNotEquals(a.prefix(), b.prefix(), "8-char prefix collision is improbable");
        assertNotEquals(a.secret(), b.secret());
    }

    @Test
    void saltDiffersPerKeyAndHashNeverEqualsPlaintext() {
        KeyGenerator.Generated a = KeyGenerator.generate();
        KeyGenerator.Generated b = KeyGenerator.generate();
        assertFalse(Arrays.equals(a.salt(), b.salt()), "salt must be fresh per key");
        assertFalse(
                Arrays.equals(a.secretHash(), a.secret().getBytes(StandardCharsets.UTF_8)),
                "the persisted hash must never equal the plaintext secret");
    }

    @Test
    void parseRoundTripsValidKey() {
        KeyGenerator.Generated generated = KeyGenerator.generate();
        Optional<KeyGenerator.Parsed> parsed = KeyGenerator.parse(generated.fullKey());
        assertTrue(parsed.isPresent(), "generated key must parse");
        assertEquals(generated.prefix(), parsed.get().prefix());
        assertEquals(generated.secret(), parsed.get().secret());
    }

    @Test
    void parseRejectsMalformedKeys() {
        KeyGenerator.Generated generated = KeyGenerator.generate();
        assertTrue(KeyGenerator.parse(null).isEmpty());
        assertTrue(KeyGenerator.parse("").isEmpty());
        assertTrue(KeyGenerator.parse("sk-janus-" + generated.prefix()).isEmpty(), "missing secret");
        assertTrue(
                KeyGenerator.parse("acme-" + generated.prefix() + "-" + generated.secret())
                        .isEmpty(),
                "wrong brand");
        assertTrue(KeyGenerator.parse("sk-janus-bad!char-" + generated.secret()).isEmpty(), "non-base62 prefix");
        assertTrue(
                KeyGenerator.parse("sk-janus-" + generated.prefix() + "-short").isEmpty(), "wrong secret length");
        assertTrue(
                KeyGenerator.parse("sk-janus-" + generated.prefix() + "-" + generated.secret() + "extra")
                        .isEmpty(),
                "trailing part");
    }

    @Test
    void concurrentGenerationIsCollisionFreeAndUnbiased() throws Exception {
        // Coverage: generate/generateId collision-freeness under many
        // concurrent threads was untested (the store's concurrent-create covers ids
        // indirectly). Hammer SecureRandom from 8 threads and assert: every full key,
        // prefix and secret is unique, and the base62 alphabet is roughly uniform (the
        // documented rejection sampling — 248 = 62×4 accepted values, index % 62 uniform).
        int threads = 8;
        int perThread = 100;
        int totalChars = threads * perThread * (KeyGenerator.PREFIX_LENGTH + KeyGenerator.SECRET_LENGTH);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> prefixes = ConcurrentHashMap.newKeySet();
        Set<String> secrets = ConcurrentHashMap.newKeySet();
        Set<String> fullKeys = ConcurrentHashMap.newKeySet();
        int[] charCounts = new int[KeyGenerator.ALPHABET.length()];
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                for (int i = 0; i < perThread; i++) {
                    KeyGenerator.Generated generated = KeyGenerator.generate();
                    prefixes.add(generated.prefix());
                    secrets.add(generated.secret());
                    fullKeys.add(generated.fullKey());
                    synchronized (charCounts) {
                        for (char c : (generated.prefix() + generated.secret()).toCharArray()) {
                            charCounts[KeyGenerator.ALPHABET.indexOf(c)]++;
                        }
                    }
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(threads * perThread, prefixes.size(), "prefixes must be collision-free under concurrency");
        assertEquals(threads * perThread, secrets.size(), "secrets must be collision-free under concurrency");
        assertEquals(threads * perThread, fullKeys.size(), "full keys must be collision-free under concurrency");
        // Uniformity: expected totalChars / 62 per char; allow a very generous band so the
        // test is not flaky while still catching a broken sampling scheme (e.g. mod-256).
        double expectedPerChar = (double) totalChars / KeyGenerator.ALPHABET.length();
        for (int count : charCounts) {
            assertTrue(
                    count > expectedPerChar * 0.4,
                    "character distribution must stay within a generous uniform band (got " + count + ", expected ~"
                            + expectedPerChar + ")");
        }
    }

    @Test
    void fullKeyIsExactlyTheBrandedConcatenation() {
        KeyGenerator.Generated generated = KeyGenerator.generate();
        assertEquals(
                "sk-janus-" + generated.prefix() + "-" + generated.secret(),
                KeyGenerator.fullKey(generated.prefix(), generated.secret()));
    }
}
