package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link PgKeyStore}, the Postgres {@link KeyStore}: mirrors the
 * {@link InMemoryKeyStoreTest} suite over a real Postgres (Testcontainers) — the
 * tests are the spec, this class re-asserts them through the JDBC piece:
 * create round-trip (full key shown once, only salt+hash stored), prefix-collision
 * retry (unique index), {@code findByPrefix}, idempotent revoke (true for an
 * existing id, false for unknown), authenticate outcomes (OK/INVALID/REVOKED/
 * EXPIRED, timing-safe via the shipped {@link KeyHash}, {@code lastUsedAt} bumped
 * only on OK, revoke-wins-racing-auth), deterministic secret-free {@code list}
 * and no-regress {@code touch}.
 */
@Testcontainers(disabledWithoutDocker = true)
class PgKeyStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    private DataSource dataSource;
    private PgKeyStore store;

    @BeforeAll
    static void startDatabase() {
        PgTestDb.ensureStarted();
        PgTestDb.migrate();
    }

    @BeforeEach
    void freshDatabase() {
        dataSource = PgTestDb.newDataSource();
        PgTestDb.truncateAll(dataSource);
        store = new PgKeyStore(dataSource, CLOCK);
    }

    @AfterEach
    void closePool() {
        PgTestDb.close(dataSource);
    }

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
        // The full key is not part of the record; the hash verifies and never equals the secret.
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();
        assertTrue(KeyHash.verify(record.salt(), record.secretHash(), parsed.secret()));
        assertFalse(new String(record.secretHash(), StandardCharsets.UTF_8).contains(parsed.secret()));
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
    void garbageStatusInTheDatabaseSurfacesAsTheStoreSeamException() {
        // valueOf on DB text throws a bare IllegalArgumentException for any
        // unexpected stored value (manual DB edit, downgrade after a future enum value),
        // bypassing the store's SQLException → IllegalStateException seam. Both read
        // paths must surface the store exception type.
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("garbage", null, null, null, null, null, null));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("UPDATE keys SET status = ? WHERE id = ?")) {
            ps.setString(1, "NOT_A_STATUS");
            ps.setString(2, created.record().id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to corrupt the status cell", e);
        }
        assertThrows(
                IllegalStateException.class,
                () -> store.findByPrefix(created.record().prefix()),
                "findByPrefix maps an unknown stored status to the store's IllegalStateException seam");
        assertThrows(
                IllegalStateException.class,
                store::list,
                "list maps an unknown stored status to the store's IllegalStateException seam");
    }

    @Test
    void listIsOrderedByCreationThenIdLikeTheInMemoryReference() {
        // Coverage: the Pg list's ORDER BY created_at, id parity with
        // InMemoryKeyStore.list was untested (the parity harness asserts size/type).
        // Same-instant creations (fixed clock) tie-break by id — the InMemory list's
        // (createdAt, id) comparator — so the views must come back in that order.
        KeyStore.CreatedKey b = store.create(new KeyStore.KeyCreateRequest("b", null, null, null, null, null, null));
        KeyStore.CreatedKey a = store.create(new KeyStore.KeyCreateRequest("a", null, null, null, null, null, null));
        List<String> expected =
                List.of(a.record().id(), b.record().id()).stream().sorted().toList();
        assertEquals(
                expected,
                store.list().stream().map(KeyRecordView::id).toList(),
                "same created_at ties order by id, matching the in-memory reference");
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
        // KeyRecordView has no salt/secretHash accessors (compile-time structural redaction).
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
    void authenticateReturnsTheExactlyWrittenLastUsedAt() {
        // The bumped path used to reconstruct the returned record's
        // lastUsedAt from the pre-bump snapshot (`withLastUsedAt(now)`), which is exact
        // while the bump writes `now` — the value is now read back from the row
        // (RETURNING last_used_at), so the returned record can never drift from the DB
        // under any interleaving. Pin the invariant: a successful auth's returned
        // lastUsedAt exactly equals the committed row's value.
        MutableClock clock = new MutableClock(Instant.parse("2026-08-03T00:00:00Z"));
        PgKeyStore store = new PgKeyStore(dataSource, clock);
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("exact", null, null, null, null, null, null));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();

        KeyStore.AuthResult first = store.authenticate(parsed.prefix(), parsed.secret());
        assertEquals(KeyStore.AuthOutcome.OK, first.outcome());
        assertEquals(clock.millis(), first.record().lastUsedAt().longValue(), "the first bump is the written value");

        clock.advanceMillis(1);
        KeyStore.AuthResult second = store.authenticate(parsed.prefix(), parsed.secret());
        assertEquals(clock.millis(), second.record().lastUsedAt().longValue(), "the second bump is the written value");
        assertEquals(
                store.findByPrefix(parsed.prefix()).orElseThrow().lastUsedAt(),
                second.record().lastUsedAt(),
                "the returned record's lastUsedAt exactly equals the committed row's value");
    }

    @Test
    void touchReadsTheClockOnceSoTheNoRegressGuardIsConsistent() {
        // Touch read the clock twice — once for the value written, once for
        // the no-regress guard — so a clock returning a different value per call could
        // write a LOWER value than the guard admitted (a last_used_at regression).
        // Force the old double-read: pre-seed a last_used_at of 1500, then touch with a
        // clock whose first read is 1000 (the old write value) and second is 2000 (the
        // old guard). The single-now form computes the guard from the same 1000 and
        // leaves the 1500 untouched.
        MutableClock createClock = new MutableClock(Instant.parse("2026-08-03T00:00:00Z"));
        PgKeyStore createStore = new PgKeyStore(dataSource, createClock);
        KeyStore.CreatedKey created =
                createStore.create(new KeyStore.KeyCreateRequest("t", null, null, null, null, null, null));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("UPDATE keys SET last_used_at = ? WHERE id = ?")) {
            ps.setLong(1, 1_500L);
            ps.setString(2, created.record().id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to pre-seed last_used_at", e);
        }

        PgKeyStore touchStore = new PgKeyStore(dataSource, sequencedClock(1_000, 2_000));
        touchStore.touch(created.record().prefix());

        long after = store.findByPrefix(created.record().prefix()).orElseThrow().lastUsedAt();
        assertEquals(1_500, after, "a single-now touch guards on 1000 and leaves the 1500 untouched");
        assertTrue(after >= 1_500, "a touch must never regress last_used_at below the pre-existing value");
    }

    /** A clock that returns each value in turn (per-call, cycling) — the touch double-read trap. */
    private static Clock sequencedClock(long... millis) {
        AtomicInteger index = new AtomicInteger();
        return new Clock() {
            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(millis[index.getAndIncrement() % millis.length]);
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
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
    void revokeWinsOverARacingAuth() throws Exception {
        // The "no torn state" contract through the DB: a revoke that lands
        // between the auth's read and its lastUsedAt bump wins (the bump's
        // status='ACTIVE' WHERE matches 0 rows) — the auth either completes as OK
        // before the revoke (the last request through) or sees REVOKED; it can never
        // observe a half-transitioned key. Repeated races must stay in those two
        // outcomes, with revoke always returning true (the id exists).
        int iterations = 25;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                KeyStore.CreatedKey created =
                        store.create(new KeyStore.KeyCreateRequest("race", null, null, null, null, null, null));
                KeyGenerator.Parsed parsed =
                        KeyGenerator.parse(created.fullKey()).orElseThrow();
                CountDownLatch start = new CountDownLatch(1);
                var authFuture = pool.submit(() -> {
                    start.await();
                    return store.authenticate(parsed.prefix(), parsed.secret());
                });
                var revokeFuture = pool.submit(() -> {
                    start.await();
                    return store.revoke(created.record().id());
                });
                start.countDown();
                KeyStore.AuthResult auth = authFuture.get(30, TimeUnit.SECONDS);
                assertTrue(revokeFuture.get(30, TimeUnit.SECONDS), "the id exists — revoke must return true");
                assertTrue(
                        auth.outcome() == KeyStore.AuthOutcome.OK || auth.outcome() == KeyStore.AuthOutcome.REVOKED,
                        "no torn state: the racing auth sees OK or REVOKED, got " + auth.outcome());
            }
        } finally {
            pool.shutdownNow();
        }
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
        // No-regress on revoked auth (coverage gap): a correct-secret auth on a revoked
        // key must not bump lastUsedAt — the OK-only bump is asserted only on the happy
        // path in the mirror suite, so pin the revoked case here too.
        MutableClock clock = new MutableClock(Instant.parse("2026-08-03T00:00:00Z"));
        PgKeyStore store = new PgKeyStore(dataSource, clock);
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
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(new KeyStore.KeyCreateRequest(
                        "dur-hi", null, null, null, KeyStore.MAX_BUDGET_DURATION_SECONDS + 1, null, null)),
                "a budget_duration above 10 years would alias the lifetime window (in-memory mirror)");
        assertThrows(
                IllegalArgumentException.class,
                () -> store.create(new KeyStore.KeyCreateRequest("dur-0", null, null, null, 0L, null, null)),
                "a zero budget_duration is rejected");
        assertTrue(store.list().isEmpty(), "rejected creates must not persist any key");
    }

    @Test
    void budgetDurationPersistsThroughThePgRecordAndView() {
        // The V2 keys.budget_duration column round-trips through create →
        // findByPrefix (FULL_COLUMNS) → list (VIEW_COLUMNS).
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("dur", null, null, 5.0, 2_592_000L, null, null));
        assertEquals(Long.valueOf(2_592_000L), created.record().budgetDuration(), "30 days in seconds");
        assertEquals(
                Long.valueOf(2_592_000L),
                store.findByPrefix(created.record().prefix()).orElseThrow().budgetDuration(),
                "findByPrefix maps budget_duration");
        assertEquals(Long.valueOf(2_592_000L), store.list().get(0).budgetDuration(), "list maps budget_duration");
    }

    @Test
    void authenticateNeverReportsOkAfterARevokeCommittedBeforeItsWrite() throws Exception {
        // The Pg auth is a read-then-write (SELECT then UPDATE WHERE
        // status='ACTIVE'), so a revoke committing *between* the two used to be silently
        // reported as OK with the stale ACTIVE snapshot. Force the exact interleaving
        // deterministically: hold a FOR UPDATE row lock, start an auth (its SELECT reads
        // the still-ACTIVE committed row, its bump UPDATE blocks on the lock), commit the
        // revoke through the lock holder, then let the auth's bump proceed — it matches 0
        // rows and the re-classification must return REVOKED, never OK.
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("race", null, null, null, null, null, null));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();

        try (Connection lockHolder = dataSource.getConnection()) {
            lockHolder.setAutoCommit(false);
            try (PreparedStatement lock = lockHolder.prepareStatement("SELECT id FROM keys WHERE id = ? FOR UPDATE")) {
                lock.setString(1, created.record().id());
                lock.executeQuery();
            }
            try (PreparedStatement revoke =
                    lockHolder.prepareStatement("UPDATE keys SET status = 'REVOKED' WHERE id = ?")) {
                revoke.setString(1, created.record().id());
                revoke.executeUpdate();
            }

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                var future = pool.submit(() -> store.authenticate(parsed.prefix(), parsed.secret()));
                // give the auth time to complete its SELECT (it reads the still-ACTIVE
                // committed row) and block on the FOR UPDATE lock in its bump UPDATE
                Thread.sleep(200);
                lockHolder.commit(); // the revoke lands before the auth's own write
                KeyStore.AuthResult auth = future.get(30, TimeUnit.SECONDS);
                assertEquals(
                        KeyStore.AuthOutcome.REVOKED,
                        auth.outcome(),
                        "a revoke committed before the auth's write must win (REVOKED), never OK");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void createRetriesOnPrefixCollisionWithoutClobbering() {
        // Coverage: the Pg create collision-retry loop was only exercised
        // statistically (62^8 prefixes make a real collision effectively impossible).
        // Force it deterministically via the package-private material seam: the first
        // generated material collides with an existing row, the retry must return a
        // *different* prefix and never clobber the pre-existing row.
        KeyStore.CreatedKey existing =
                store.create(new KeyStore.KeyCreateRequest("first", null, null, null, null, null, null));
        KeyGenerator.Generated fresh = KeyGenerator.generate();
        KeyGenerator.Generated collision = new KeyGenerator.Generated(
                existing.record().prefix(),
                fresh.secret(),
                fresh.salt(),
                fresh.secretHash(),
                KeyGenerator.fullKey(existing.record().prefix(), fresh.secret()));
        KeyGenerator.Generated retry = KeyGenerator.generate();
        List<KeyGenerator.Generated> sequence = List.of(collision, retry);
        java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        PgKeyStore collisionStore = new PgKeyStore(dataSource, CLOCK, () -> sequence.get(index.getAndIncrement()));

        KeyStore.CreatedKey created =
                collisionStore.create(new KeyStore.KeyCreateRequest("second", null, null, null, null, null, null));
        assertNotEquals(
                collision.prefix(),
                created.record().prefix(),
                "a colliding prefix must be retried with fresh material");
        assertEquals(retry.prefix(), created.record().prefix());

        KeyRecord original = store.findByPrefix(existing.record().prefix()).orElseThrow();
        assertEquals(existing.record().id(), original.id(), "the pre-existing row must never be clobbered");
        assertEquals(KeyStatus.ACTIVE, original.status());
        assertTrue(
                KeyHash.verify(
                        original.salt(),
                        original.secretHash(),
                        KeyGenerator.parse(existing.fullKey()).orElseThrow().secret()),
                "the pre-existing row's credential must still verify");
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
            f.get(60, TimeUnit.SECONDS);
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
