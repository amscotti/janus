package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link InMemoryKeyStore}: prefix-indexed {@code ConcurrentHashMap},
 * injectable {@link Clock} ( discipline — all timestamps deterministic), atomic
 * status transitions via {@code compute}, and the seam guarantee the acceptance list
 * cares about: {@link #list} exposes a redacted view with <b>no</b> secret material
 * (no {@code salt}/{@code secretHash} fields — compile-time) and {@link #create}
 * returns the full key string exactly once while the stored {@link KeyRecord} holds
 * only the salted hash.
 */
class InMemoryKeyStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    private final InMemoryKeyStore store = new InMemoryKeyStore(CLOCK);

    @Test
    void createReturnsFullKeyOnceAndRecordHoldsOnlyHash() {
        KeyStore.CreatedKey created = store.create(
                new KeyStore.KeyCreateRequest("alice", List.of("deepseek-v4-flash"), null, null, null, null, null));
        KeyRecord record = created.record();
        assertTrue(created.fullKey().startsWith("sk-janus-"), created.fullKey());
        assertEquals("alice", record.owner());
        assertEquals(List.of("deepseek-v4-flash"), record.models());
        assertEquals(KeyStatus.ACTIVE, record.status());
        assertEquals(CLOCK.millis(), record.createdAt());
        assertNull(record.lastUsedAt());
        assertNotNull(record.salt(), "salt persists (never the plaintext)");
        assertNotNull(record.secretHash(), "salted SHA-256 persists (never the plaintext)");
        // The full key is not part of the record (compile-time: KeyRecord has no field);
        // the hash must verify against the returned secret and never equal it.
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();
        assertTrue(KeyHash.verify(record.salt(), record.secretHash(), parsed.secret()));
        assertFalse(
                new String(record.secretHash(), java.nio.charset.StandardCharsets.UTF_8).contains(parsed.secret()),
                "hash bytes must never contain the plaintext secret");
    }

    @Test
    void findByPrefixResolvesCreatedKey() {
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("bob", List.of(), null, null, null, null, null));
        KeyRecord found = store.findByPrefix(created.record().prefix()).orElseThrow();
        assertEquals(created.record().id(), found.id());
        assertEquals("bob", found.owner());
        assertTrue(found.models().isEmpty(), "absent models bind an empty scope (allow all)");
    }

    @Test
    void findByPrefixReturnsEmptyForUnknownPrefix() {
        assertTrue(store.findByPrefix("unknown").isEmpty());
    }

    @Test
    void twoCreationsDiffer() {
        KeyStore.CreatedKey a = store.create(new KeyStore.KeyCreateRequest("a", null, null, null, null, null, null));
        KeyStore.CreatedKey b = store.create(new KeyStore.KeyCreateRequest("b", null, null, null, null, null, null));
        assertFalse(a.record().id().equals(b.record().id()));
        assertFalse(a.record().prefix().equals(b.record().prefix()));
    }

    @Test
    void revokeMarksRevokedAndDeniesAuth() {
        KeyStore.CreatedKey created = store.create(
                new KeyStore.KeyCreateRequest("c", List.of("deepseek-v4-flash"), null, null, null, null, null));
        assertTrue(store.revoke(created.record().id()));
        KeyRecord revoked = store.findByPrefix(created.record().prefix()).orElseThrow();
        assertEquals(KeyStatus.REVOKED, revoked.status());
        assertFalse(revoked.isActive(CLOCK.millis()), "revoked keys are not authenticatable");
    }

    @Test
    void revokeIsIdempotentAndUnknownIdIsFalse() {
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("d", null, null, null, null, null, null));
        assertTrue(store.revoke(created.record().id()));
        assertTrue(store.revoke(created.record().id()), "revoking an already-revoked key is still a found key");
        assertFalse(store.revoke("no-such-id"));
    }

    @Test
    void listExposesRedactedViewWithoutSecretMaterial() {
        KeyStore.CreatedKey a = store.create(
                new KeyStore.KeyCreateRequest("alice", List.of("deepseek-v4-flash"), null, null, null, null, null));
        KeyStore.CreatedKey b = store.create(new KeyStore.KeyCreateRequest("bob", null, null, null, null, null, null));
        List<KeyRecordView> views = store.list();
        assertEquals(2, views.size());
        Set<String> ids = views.stream().map(KeyRecordView::id).collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.contains(a.record().id()) && ids.contains(b.record().id()));
        KeyRecordView alice = views.stream()
                .filter(v -> v.id().equals(a.record().id()))
                .findFirst()
                .orElseThrow();
        assertEquals("alice", alice.owner());
        assertEquals(List.of("deepseek-v4-flash"), alice.models());
        assertEquals(KeyStatus.ACTIVE, alice.status());
        assertEquals(a.record().createdAt(), alice.createdAt());
        // KeyRecordView has no salt/secretHash accessors (compile-time) and no
        // field holding the full key string — the redaction is structural, not a filter.
    }

    @Test
    void expiryIsCheckedAgainstTheStoreClock() {
        KeyStore.CreatedKey expired =
                store.create(new KeyStore.KeyCreateRequest("e", null, CLOCK.millis() - 1_000, null, null, null, null));
        KeyStore.CreatedKey live = store.create(new KeyStore.KeyCreateRequest("f", null, null, null, null, null, null));
        KeyStore.CreatedKey future =
                store.create(new KeyStore.KeyCreateRequest("g", null, CLOCK.millis() + 10_000, null, null, null, null));
        assertFalse(expired.record().isActive(CLOCK.millis()), "past expiresAt ⇒ not authenticatable");
        assertTrue(live.record().isActive(CLOCK.millis()), "no expiresAt ⇒ active");
        assertTrue(future.record().isActive(CLOCK.millis()), "future expiresAt ⇒ active");
    }

    @Test
    void lastUsedAtUpdatesOnTouchBestEffort() {
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("h", null, null, null, null, null, null));
        assertNull(created.record().lastUsedAt());
        store.touch(created.record().prefix());
        KeyRecord touched = store.findByPrefix(created.record().prefix()).orElseThrow();
        assertEquals(CLOCK.millis(), touched.lastUsedAt().longValue());
        // Best-effort CAS: a second touch at the same fixed instant must not regress.
        store.touch(created.record().prefix());
        assertEquals(CLOCK.millis(), touched.lastUsedAt().longValue());
    }

    @Test
    void authenticateVerifiesSecretAndLifecycleAtomically() {
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("i", null, null, null, null, null, null));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();
        long now = CLOCK.millis();

        KeyStore.AuthResult ok = store.authenticate(parsed.prefix(), parsed.secret());
        assertEquals(KeyStore.AuthOutcome.OK, ok.outcome());
        assertEquals(created.record().id(), ok.record().id());
        assertEquals(now, ok.record().lastUsedAt().longValue(), "a successful auth bumps lastUsedAt atomically");

        assertEquals(
                KeyStore.AuthOutcome.INVALID,
                store.authenticate(parsed.prefix(), "wrong-secret").outcome(),
                "wrong secret ⇒ INVALID");
        assertNull(
                store.authenticate(parsed.prefix(), "wrong-secret").record(),
                "a wrong secret must not return the credential-bearing record");
        KeyStore.AuthResult unknown = store.authenticate("unknown", parsed.secret());
        assertEquals(KeyStore.AuthOutcome.INVALID, unknown.outcome());
        assertNull(unknown.record(), "unknown prefix ⇒ INVALID with no record");

        assertTrue(store.revoke(created.record().id()));
        assertEquals(
                KeyStore.AuthOutcome.REVOKED,
                store.authenticate(parsed.prefix(), parsed.secret()).outcome(),
                "revoked key ⇒ REVOKED even with the correct secret");
    }

    @Test
    void authenticateExpiresPastExpiresAt() {
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("j", null, CLOCK.millis() - 1_000, null, null, null, null));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();
        assertEquals(
                KeyStore.AuthOutcome.EXPIRED,
                store.authenticate(parsed.prefix(), parsed.secret()).outcome(),
                "correct secret on an expired key ⇒ EXPIRED (401), not OK");
    }

    @Test
    void touchIsNoOpForRevokedRecords() {
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("r", null, null, null, null, null, null));
        assertTrue(store.revoke(created.record().id()));
        store.touch(created.record().prefix());
        KeyRecord revoked = store.findByPrefix(created.record().prefix()).orElseThrow();
        assertNull(revoked.lastUsedAt(), "a revoked record's lastUsedAt must never be bumped (m1 touch guard)");
    }

    @Test
    void wrongSecretDoesNotRevealRevokedLifecycle() {
        // A REVOKED key with a wrong secret returns INVALID (not REVOKED) and no record —
        // lifecycle state is only revealed to someone holding the correct secret
        // (verify-first ordering, pinned — the reveal-ordering coverage gap).
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("r", null, null, null, null, null, null));
        assertTrue(store.revoke(created.record().id()));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();
        KeyStore.AuthResult auth = store.authenticate(parsed.prefix(), "wrong-secret");
        assertEquals(
                KeyStore.AuthOutcome.INVALID,
                auth.outcome(),
                "wrong secret must not reveal the REVOKED lifecycle state");
        assertNull(auth.record(), "a failed auth must never return credential-bearing material");
    }

    @Test
    void wrongSecretDoesNotRevealExpiredLifecycle() {
        // Same property for EXPIRED: an expired key with a wrong secret is INVALID.
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("e", null, CLOCK.millis() - 1_000, null, null, null, null));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();
        KeyStore.AuthResult auth = store.authenticate(parsed.prefix(), "wrong-secret");
        assertEquals(
                KeyStore.AuthOutcome.INVALID,
                auth.outcome(),
                "wrong secret must not reveal the EXPIRED lifecycle state");
        assertNull(auth.record(), "a failed auth must never return credential-bearing material");
    }

    @Test
    void authenticateOnRevokedKeyLeavesLastUsedAtUntouched() {
        // No-regress on revoked auth (coverage gap): the OK-only bump is asserted on the
        // happy path; a correct-secret auth on a revoked key must not bump lastUsedAt.
        MutableClock clock = new MutableClock(Instant.parse("2026-08-03T00:00:00Z"));
        InMemoryKeyStore store = new InMemoryKeyStore(clock);
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("nr", null, null, null, null, null, null));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();
        store.authenticate(parsed.prefix(), parsed.secret());
        long bumped = store.findByPrefix(parsed.prefix()).orElseThrow().lastUsedAt();
        clock.advanceSeconds(60);
        assertTrue(store.revoke(created.record().id()));
        KeyStore.AuthResult auth = store.authenticate(parsed.prefix(), parsed.secret());
        assertEquals(KeyStore.AuthOutcome.REVOKED, auth.outcome());
        assertEquals(
                bumped,
                store.findByPrefix(parsed.prefix()).orElseThrow().lastUsedAt().longValue(),
                "authenticate on a revoked key must not bump lastUsedAt");
    }

    @Test
    void createRejectsCapsThatViolateTheNullMeansNoCapInvariant() {
        // Cap validation used to live only at the admin API — a directly-seeded
        // store record with rpm=0 (the seam is public; Pg rows can be SQL-seeded) would
        // make the enforcement layer's limiter throw on every request (a 500 on the hot
        // path, never a 429). The seam must reject such requests up front.
        double saturating = Long.MAX_VALUE / 1_000_000.0;
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(new KeyStore.KeyCreateRequest("rpm", null, null, null, null, 0, null)),
                "rpm=0 must be rejected at the store seam");
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(new KeyStore.KeyCreateRequest("tpm", null, null, null, null, null, -5)),
                "negative tpm must be rejected at the store seam");
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(new KeyStore.KeyCreateRequest("nan", null, null, Double.NaN, null, null, null)),
                "NaN budget must be rejected at the store seam");
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(
                        new KeyStore.KeyCreateRequest("inf", null, null, Double.POSITIVE_INFINITY, null, null, null)),
                "infinite budget must be rejected at the store seam");
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(new KeyStore.KeyCreateRequest("neg", null, null, -1.0, null, null, null)),
                "negative budget must be rejected at the store seam");
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(new KeyStore.KeyCreateRequest("sat", null, null, saturating, null, null, null)),
                "a saturating budget must be rejected at the store seam");
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(new KeyStore.KeyCreateRequest("tiny", null, null, 0.0000001, null, null, null)),
                "a round-to-zero budget must be rejected at the store seam");
        assertTrue(store.list().isEmpty(), "rejected creates must not persist any key");
    }

    @Test
    void budgetDurationAboveTenYearsIsRejected() {
        // validateCaps bounds budget_duration to (0, 315_360_000] seconds: a duration
        // above ~nowSec would derive window epoch floorDiv(now, dur) * dur == 0 — the
        // LIFETIME row — silently aliasing the key's windowed spend onto window 0. The
        // 10-year bound makes the collision unreachable; non-positive durations are
        // rejected like every other cap.
        long tenYears = KeyStore.MAX_BUDGET_DURATION_SECONDS;
        assertEquals(315_360_000L, tenYears, "the bound is exactly 10 years of seconds");
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(new KeyStore.KeyCreateRequest("dur-hi", null, null, null, tenYears + 1, null, null)),
                "a duration above 10 years would alias the lifetime window");
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(new KeyStore.KeyCreateRequest("dur-0", null, null, null, 0L, null, null)),
                "a zero duration is rejected (null = lifetime is the only no-window spelling)");
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(new KeyStore.KeyCreateRequest("dur-neg", null, null, null, -60L, null, null)),
                "a negative duration is rejected");
        assertTrue(
                store.list().stream()
                        .noneMatch(view -> view.owner() != null && view.owner().startsWith("dur-")),
                "rejected durations must not persist any key");
        // The bound itself is accepted (a 10-year monthly-style window is a legitimate
        // operator ask), and it persists through the record/view.
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("dur-ok", null, null, 1.0, tenYears, null, null));
        assertEquals(Long.valueOf(tenYears), created.record().budgetDuration());
        assertEquals(Long.valueOf(tenYears), store.list().get(0).budgetDuration(), "the view carries budget_duration");
    }

    @Test
    void concurrentAuthOnTheSamePrefixHasNoTornOutcomes() throws Exception {
        // Authenticate holds the ConcurrentHashMap bin lock across the
        // SHA-256 verify — a same-prefix concurrency smoke keeping the trade-off visible:
        // every concurrent auth on one key sees OK and lastUsedAt is a single consistent
        // bump, never a torn state.
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("hammer", null, null, null, null, null, null));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<KeyStore.AuthResult>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return store.authenticate(parsed.prefix(), parsed.secret());
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<KeyStore.AuthResult> future : futures) {
            assertEquals(
                    KeyStore.AuthOutcome.OK,
                    future.get(30, TimeUnit.SECONDS).outcome(),
                    "every same-prefix auth sees OK, never a torn outcome");
        }
        pool.shutdown();
        assertEquals(
                CLOCK.millis(),
                store.findByPrefix(parsed.prefix()).orElseThrow().lastUsedAt().longValue(),
                "lastUsedAt is a single consistent bump under same-prefix concurrency");
    }

    @Test
    void concurrentCreateAndRevokeKeepMapInvariants() throws Exception {
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
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
                    KeyStore.CreatedKey created =
                            store.create(new KeyStore.KeyCreateRequest("t", null, null, null, null, null, null));
                    ids.add(created.record().id());
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(threads * perThread, ids.size(), "ids must be unique under concurrency");
        assertEquals(ids.size(), store.list().size(), "list size must equal created keys");
        for (String id : ids) {
            assertTrue(store.revoke(id), "every created key must be revocable");
        }
        for (KeyRecordView view : store.list()) {
            assertEquals(KeyStatus.REVOKED, view.status(), "no lost revoke under concurrency");
        }
    }
}
