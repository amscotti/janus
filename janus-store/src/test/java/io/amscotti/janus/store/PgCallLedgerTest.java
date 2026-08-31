package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
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
 * {@link PgCallLedger}, the Postgres call ledger: {@code recordCall}
 * insert + per-key prune under the advisory lock (retention r: r+1 records ⇒ a ring
 * of r, oldest evicted, {@code dropped} == 1; retention-1 boundary), same-
 * millisecond ordering by {@code seq} (the stable tie-break the contract smoke
 * depends on), per-key isolation, {@code ""}/{@code null} sentinel equivalence for
 * auth-off, the global newest-first view, and concurrent {@code recordCall}s ⇒ an
 * exact {@code dropped} count (each overflow evicts exactly once).
 */
@Testcontainers(disabledWithoutDocker = true)
class PgCallLedgerTest {

    private static final Instant START = Instant.parse("2026-08-03T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private DataSource dataSource;

    @BeforeAll
    static void startDatabase() {
        PgTestDb.ensureStarted();
        PgTestDb.migrate();
    }

    @BeforeEach
    void freshDatabase() {
        dataSource = PgTestDb.newDataSource();
        PgTestDb.truncateAll(dataSource);
    }

    @AfterEach
    void closePool() {
        PgTestDb.close(dataSource);
    }

    private PgCallLedger ledger(int retention) {
        return new PgCallLedger(dataSource, retention);
    }

    @Test
    void recordCallInsertsPrunesAndCountsDropped() {
        PgCallLedger ledger = ledger(2);
        for (int i = 1; i <= 3; i++) {
            ledger.recordCall(record("r" + i, "k", START.toEpochMilli() + i));
        }
        List<CallRecord> recent = ledger.recentCalls("k", 10);
        assertEquals(2, recent.size(), "the ring holds exactly retention entries");
        assertEquals(
                List.of("r3", "r2"),
                recent.stream().map(CallRecord::requestId).toList(),
                "newest first, oldest (r1) evicted");
        assertEquals(1, ledger.dropped(), "one overflow, one drop");
    }

    @Test
    void retentionOneBoundaryKeepsOnlyTheNewest() {
        PgCallLedger ledger = ledger(1);
        for (int i = 1; i <= 3; i++) {
            ledger.recordCall(record("r" + i, "k", START.toEpochMilli() + i));
        }
        assertEquals(
                List.of("r3"),
                ledger.recentCalls("k", 10).stream().map(CallRecord::requestId).toList(),
                "retention 1 keeps exactly the newest record");
        assertEquals(2, ledger.dropped(), "every overflow is counted");
    }

    @Test
    void sameMillisecondTimestampsOrderStablyBySeq() {
        PgCallLedger ledger = ledger(10);
        long sameMillis = START.toEpochMilli();
        for (int i = 1; i <= 4; i++) {
            ledger.recordCall(record("r" + i, "k", sameMillis));
        }
        // The contract's concurrency smoke pins ordering that must not depend on
        // at_epoch_millis alone: seq (BIGSERIAL insert order) is the stable tie-break.
        assertEquals(
                List.of("r4", "r3", "r2", "r1"),
                ledger.recentCalls("k", 10).stream().map(CallRecord::requestId).toList(),
                "same-timestamp records order by seq (insert order), newest last-inserted first");
    }

    @Test
    void perKeyRingsAreIsolated() {
        PgCallLedger ledger = ledger(2);
        ledger.recordCall(record("a1", "a", 1));
        ledger.recordCall(record("b1", "b", 2));
        assertEquals(
                List.of("a1"),
                ledger.recentCalls("a", 10).stream().map(CallRecord::requestId).toList());
        assertEquals(
                List.of("b1"),
                ledger.recentCalls("b", 10).stream().map(CallRecord::requestId).toList());
        assertTrue(ledger.recentCalls("c", 10).isEmpty(), "a never-touched key has no ring");
        assertEquals(0, ledger.dropped(), "one record per key never overflows");
    }

    @Test
    void garbageStatusInTheDatabaseSurfacesAsTheStoreSeamException() {
        // CallStatus.valueOf on DB text throws a bare IllegalArgumentException
        // for any unexpected stored value, bypassing the store's SQLException →
        // IllegalStateException seam (the gateway error mapper expects the store seam).
        PgCallLedger ledger = ledger(2);
        ledger.recordCall(record("r1", "k", 1));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("UPDATE calls SET status = ? WHERE key_id = ?")) {
            ps.setString(1, "NOT_A_STATUS");
            ps.setString(2, "k");
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to corrupt the status cell", e);
        }
        assertThrows(
                IllegalStateException.class,
                () -> ledger.recentCalls("k", 10),
                "recentCalls maps an unknown stored status to the store's IllegalStateException seam");
        assertThrows(
                IllegalStateException.class,
                () -> ledger.recentCalls(10),
                "the global view maps an unknown stored status to the store seam too");
    }

    @Test
    void authOffSentinelEquivalence() {
        PgCallLedger ledger = ledger(2);
        ledger.recordCall(record("anon-1", null, 1));
        assertEquals(1, ledger.recentCalls("", 10).size(), "the sentinel ring holds auth-off records");
        assertEquals(1, ledger.recentCalls((String) null, 10).size(), "null keyId addresses the sentinel ring");
        assertTrue(ledger.recentCalls("k", 10).isEmpty(), "real keys never share the sentinel ring");
        assertEquals(
                null,
                ledger.recentCalls((String) null, 10).get(0).keyId(),
                "an auth-off record round-trips with null keyId (the sentinel is a storage detail)");
    }

    @Test
    void globalViewIsNewestFirstAcrossKeys() {
        PgCallLedger ledger = ledger(10);
        clock.advanceMillis(1);
        ledger.recordCall(record("a1", "a", clock.millis()));
        clock.advanceMillis(1);
        ledger.recordCall(record("b1", "b", clock.millis()));
        clock.advanceMillis(1);
        ledger.recordCall(record("a2", "a", clock.millis()));

        assertEquals(
                List.of("a2", "b1", "a1"),
                ledger.recentCalls(10).stream().map(CallRecord::requestId).toList(),
                "global view is newest-first across keys");
        assertTrue(ledger.recentCalls(0).isEmpty(), "n ≤ 0 ⇒ empty");
        assertEquals(2, ledger.recentCalls(2).size(), "n clamps the global view");
    }

    @Test
    void droppedIsGlobalMonotonicAcrossKeys() {
        PgCallLedger ledger = ledger(2);
        for (int i = 0; i < 3; i++) {
            ledger.recordCall(record("a" + i, "a", START.toEpochMilli() + i));
            ledger.recordCall(record("b" + i, "b", START.toEpochMilli() + i));
        }
        assertEquals(2, ledger.dropped(), "one drop per key ring overflow (3 recorded, 2 retained each)");
        assertTrue(ledger.dropped() >= 0, "monotonic non-negative");
    }

    @Test
    void concurrentRecordCallsEvictExactlyOnce() throws Exception {
        PgCallLedger ledger = ledger(2);
        int threads = 8;
        int perThread = 25;
        int total = threads * perThread;
        AtomicInteger sequence = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Same millisecond for every record — ordering must not depend on
                    // at_epoch_millis alone; the per-key advisory lock makes each
                    // overflow evict exactly once (no double-delete, no double-count).
                    for (int i = 0; i < perThread; i++) {
                        ledger.recordCall(record("req-" + sequence.incrementAndGet(), "k", START.toEpochMilli()));
                    }
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "all recordCalls must finish");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, ledger.recentCalls("k", Integer.MAX_VALUE).size(), "the ring stays bounded");
        assertEquals(total - 2, ledger.dropped(), "every overflow is counted exactly once");
    }

    @Test
    void keysCollidingUnderTheOld32BitHashAreSeparateUnderThe64BitLockKey() {
        // The per-key advisory lock used `hashtext(?)::bigint` — a 32-bit
        // hash cast to bigint, so two distinct keys whose hashtext collided serialized
        // against each other for no reason (and could collide with the migration
        // runner's fixed lock key). The lock now uses `hashtextextended(?, 0)`
        // (64-bit). Find a genuine 32-bit hashtext-colliding pair at test time and pin
        // that the 64-bit lock keys differ while both keys round-trip correctly.
        String[] pair = findHashtextCollision(dataSource);
        PgCallLedger ledger = ledger(10);
        ledger.recordCall(record("a1", pair[0], 1));
        ledger.recordCall(record("b1", pair[1], 2));
        ledger.recordCall(record("a2", pair[0], 3));
        assertEquals(
                List.of("a2", "a1"),
                ledger.recentCalls(pair[0], 10).stream()
                        .map(CallRecord::requestId)
                        .toList(),
                "key one's ring is untouched by key two");
        assertEquals(
                List.of("b1"),
                ledger.recentCalls(pair[1], 10).stream()
                        .map(CallRecord::requestId)
                        .toList());
        assertEquals(
                hashtext(dataSource, pair[0]),
                hashtext(dataSource, pair[1]),
                "sanity: the pair genuinely collides under the 32-bit hash");
        assertNotEquals(
                hashtextextended(dataSource, pair[0]),
                hashtextextended(dataSource, pair[1]),
                "the 64-bit advisory-lock keys must not collide (the fix)");
    }

    /**
     * Find two distinct strings whose Postgres {@code hashtext} (32-bit) collides, via
     * a single scan computing {@code hashtext} over generated candidates and a Java
     * birthday-bound lookup (~2^19 candidates make a collision essentially certain).
     * The pair is used to pin the 64-bit lock-key fix. (A SQL self-join on the
     * expression would be nested-looped by the planner — 2^18 × 2^18 rows — so the
     * collision is found in Java instead.)
     */
    private static String[] findHashtextCollision(DataSource dataSource) {
        String sql = "SELECT hashtext(s) AS h, s FROM (SELECT 'k' || g AS s FROM generate_series(1, 524288) g) t";
        java.util.Map<Integer, String> seen = new java.util.HashMap<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                int hash = rs.getInt(1);
                String value = rs.getString(2);
                String previous = seen.putIfAbsent(hash, value);
                if (previous != null) {
                    return new String[] {previous, value};
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("hashtext collision search failed", e);
        }
        throw new IllegalStateException("no 32-bit hashtext collision found in 2^19 candidates");
    }

    private static long hashtext(DataSource dataSource, String value) {
        return singleLong(dataSource, "SELECT hashtext(?)", value);
    }

    private static long hashtextextended(DataSource dataSource, String value) {
        return singleLong(dataSource, "SELECT hashtextextended(?, 0)", value);
    }

    private static long singleLong(DataSource dataSource, String sql, String value) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("single-value hash query failed: " + sql, e);
        }
    }

    /** A Tier-1 record with fixed non-content fields (mirrors the contract test's shape). */
    private static CallRecord record(String requestId, String keyId, long atEpochMillis) {
        return new CallRecord(
                requestId,
                keyId,
                "gpt-4o",
                "deepseek",
                10,
                20,
                30,
                null,
                null,
                250,
                100,
                false,
                CallStatus.OK,
                atEpochMillis);
    }
}
