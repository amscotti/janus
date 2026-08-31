package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.store.RateLimiter.RateLimitResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
 * {@link PgRateLimiter}, the Postgres fixed-window {@link RateLimiter}:
 * mirrors the {@link FixedWindowRateLimiterTest} suite over a real Postgres —
 * epoch-aligned 60s windows (the window-in-PK makes a stale row's rollover a plain
 * insert win), exactly-the-cap admitted then denied with {@code retryAfterSeconds ≥
 * 0}, denied never consumes, {@code wouldExceed} non-consuming, and
 * {@code accumulate} returning the pinned <b>window total</b> (the contract
 * meaning). The concurrent burst admits exactly the cap — the single atomic upsert's
 * {@code WHERE} re-check against the locked row is the no-overspend guarantee.
 */
@Testcontainers(disabledWithoutDocker = true)
class PgRateLimiterTest {

    /** Epoch-aligned instant (epoch seconds divisible by 60): window starts at 0 mod 60. */
    private static final Instant START = Instant.parse("2026-08-03T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private DataSource dataSource;
    private PgRateLimiter limiter;

    @BeforeAll
    static void startDatabase() {
        PgTestDb.ensureStarted();
        PgTestDb.migrate();
    }

    @BeforeEach
    void freshDatabase() {
        dataSource = PgTestDb.newDataSource();
        PgTestDb.truncateAll(dataSource);
        limiter = new PgRateLimiter(dataSource, clock);
    }

    @AfterEach
    void closePool() {
        PgTestDb.close(dataSource);
    }

    @Test
    void allowsUpToCapPerAlignedWindowThenDeniesWithRetryAfter() {
        for (int i = 1; i <= 3; i++) {
            RateLimitResult result = limiter.tryAcquire("k1", 3, 1);
            assertInstanceOf(RateLimitResult.Allowed.class, result, "request " + i + " must be allowed");
            assertEquals(i, ((RateLimitResult.Allowed) result).count());
        }
        RateLimitResult denied = limiter.tryAcquire("k1", 3, 1);
        assertInstanceOf(RateLimitResult.Denied.class, denied);
        assertEquals(
                60, ((RateLimitResult.Denied) denied).retryAfterSeconds(), "window end is 60s away at a pinned clock");
    }

    @Test
    void windowRolloverResetsTheCounter() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k1", 3, 1);
        }
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", 3, 1));

        clock.advanceSeconds(60); // exactly one aligned window later
        RateLimitResult result = limiter.tryAcquire("k1", 3, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, result, "rollover must reset the counter");
        assertEquals(1, ((RateLimitResult.Allowed) result).count(), "the new window starts at count 1");
    }

    @Test
    void wouldExceedNeverConsumes() {
        assertFalse(limiter.wouldExceed("k1", 100, 50), "nothing consumed yet");
        assertFalse(limiter.wouldExceed("k1", 100, 50), "a second check sees the same state");
        RateLimitResult result = limiter.tryAcquire("k1", 100, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, result);
        assertEquals(1, ((RateLimitResult.Allowed) result).count());
    }

    @Test
    void accumulateConsumesTokensAndLaterWouldExceedSeesIt() {
        assertEquals(30, limiter.accumulate("k1", 100, 30), "fresh window: the window total equals actual");
        assertFalse(limiter.wouldExceed("k1", 100, 70), "30 + 70 = 100 is exactly at the cap, not over");
        assertTrue(limiter.wouldExceed("k1", 100, 71), "30 + 71 = 101 crosses the cap");
        assertEquals(80, limiter.accumulate("k1", 100, 50), "consume-at-finalize adds up (real spend)");
    }

    @Test
    void perKeyCountersAreIndependent() {
        limiter.tryAcquire("a", 1, 1);
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("a", 1, 1));
        RateLimitResult other = limiter.tryAcquire("b", 1, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, other, "key b shares no counter with key a");
        RateLimitResult tpm = limiter.tryAcquire("a", 100, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, tpm, "RPM denial does not touch the TPM shard");
    }

    @Test
    void retryAfterIsNonNegativeAndApproachesZeroAtRollover() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k1", 5, 1);
        }
        RateLimitResult denied = limiter.tryAcquire("k1", 5, 1);
        assertInstanceOf(RateLimitResult.Denied.class, denied);
        long retryAfter = ((RateLimitResult.Denied) denied).retryAfterSeconds();
        assertTrue(retryAfter >= 0, "Retry-After must never be negative");
        assertEquals(60, retryAfter);

        clock.advanceSeconds(59);
        RateLimitResult nearEnd = limiter.tryAcquire("k1", 5, 1);
        assertInstanceOf(RateLimitResult.Denied.class, nearEnd);
        assertEquals(1, ((RateLimitResult.Denied) nearEnd).retryAfterSeconds());

        clock.advanceSeconds(1); // exactly at the rollover boundary
        RateLimitResult afterRollover = limiter.tryAcquire("k1", 5, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, afterRollover, "rollover resets the counter");
        assertEquals(1, ((RateLimitResult.Allowed) afterRollover).count());
    }

    @Test
    void concurrentBurstAdmitsExactlyTheCap() throws Exception {
        int cap = 100;
        int threads = 16;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
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
                    for (int i = 0; i < 20; i++) { // 320 attempts against a cap of 100
                        if (limiter.tryAcquire("burst", cap, 1) instanceof RateLimitResult.Allowed) {
                            allowed.incrementAndGet();
                        }
                    }
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "the burst must finish");
        } finally {
            pool.shutdownNow();
        }
        // The atomic upsert's WHERE re-checks the cap against the locked row: the
        // counter can never exceed the cap — exactly cap requests are admitted.
        assertEquals(cap, allowed.get(), "exactly the cap is admitted under concurrency");
    }

    @Test
    void costExceedingLimitDeniesFreshWithoutConsuming() {
        RateLimitResult denied = limiter.tryAcquire("k1", 3, 5);
        assertInstanceOf(RateLimitResult.Denied.class, denied, "cost > limit must deny even on a fresh window");
        RateLimitResult next = limiter.tryAcquire("k1", 3, 1);
        assertInstanceOf(RateLimitResult.Allowed.class, next, "the denied cost-5 request consumed nothing");
        assertEquals(1, ((RateLimitResult.Allowed) next).count());
    }

    @Test
    void deniedRequestLeavesNoRow() {
        for (int i = 0; i < 3; i++) {
            assertInstanceOf(RateLimitResult.Allowed.class, limiter.tryAcquire("k1", 3, 1));
        }
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", 3, 1));
        // The non-consuming guarantee is observable in the table: the denied request
        // never inserted a row, and the cap's row is still exactly 3.
        assertEquals(1, countRows("SELECT count(*) FROM rate_limits WHERE key_id = 'k1'"), "one row for the cap");
        assertEquals(
                3,
                countRows("SELECT count FROM rate_limits WHERE key_id = 'k1' AND dimension = 'requests'"),
                "the denied request did not add to the counter");
    }

    @Test
    void staleWindowRowIsIgnoredByWouldExceed() {
        assertEquals(30, limiter.accumulate("k1", 100, 30), "W0 settles 30 tokens");
        clock.advanceSeconds(60); // W1: the W0 row is stale, only the exact window is read
        assertFalse(limiter.wouldExceed("k1", 100, 71), "the stale 30 must not count (71 fits in W1)");
        assertFalse(limiter.wouldExceed("k1", 100, 100));
        assertTrue(limiter.wouldExceed("k1", 100, 101), "only an over-cap estimate crosses in the fresh window");
        // The stale row survives (the prune has not run) but is structurally ignored.
        assertEquals(1, countRows("SELECT count(*) FROM rate_limits WHERE key_id = 'k1'"), "stale row still present");
    }

    @Test
    void pruneRemovesStaleWindowsAndKeepsTheCurrent() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k1", 3, 1); // W0 requests row
        }
        assertEquals(30, limiter.accumulate("k1", 100, 30)); // W0 tokens row

        clock.advanceSeconds(180); // three aligned windows later → W3
        assertInstanceOf(RateLimitResult.Allowed.class, limiter.tryAcquire("k1", 3, 1));
        assertEquals(5, limiter.accumulate("k1", 100, 5)); // W3 rows

        long w0 = START.toEpochMilli() / 1000;
        assertEquals(
                2, countRows("SELECT count(*) FROM rate_limits WHERE window_start = " + w0), "W0 rows exist pre-prune");
        assertEquals(4, countRows("SELECT count(*) FROM rate_limits"), "four rows across two windows");

        limiter.pruneStaleWindows();
        assertEquals(
                0,
                countRows("SELECT count(*) FROM rate_limits WHERE window_start = " + w0),
                "the stale window's rows are pruned");
        assertEquals(
                2,
                countRows("SELECT count(*) FROM rate_limits WHERE window_start = " + (w0 + 180)),
                "only the current window's rows survive");
        assertFalse(
                limiter.wouldExceed("k1", 100, 95), "post-prune reads still work off the current window (5 + 95 fits)");
    }

    @Test
    void capCheckSurvivesHugeCostsWithoutOverflowWrapping() {
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", 5, Long.MAX_VALUE));
        assertTrue(limiter.wouldExceed("k1", 5, Long.MAX_VALUE), "a huge estimate always exceeds");
        assertTrue(limiter.wouldExceed("k1", 5, 6));
        assertFalse(limiter.wouldExceed("k1", 5, 5));
        limiter.accumulate("k1", 5, 4);
        assertTrue(limiter.wouldExceed("k1", 5, Long.MAX_VALUE), "4 consumed + a huge estimate crosses, wrap-free");
    }

    @Test
    void sqlSumCannotOverflowWhenTheCounterRowIsNearSaturation() {
        // The upsert's WHERE used to evaluate the raw `count + EXCLUDED.count`
        // sum, so a row whose count is at Long.MAX made PostgreSQL raise "bigint out of
        // range" (a 5xx) instead of denying like the in-memory reference. The requests
        // counter is bounded by the int limit through the public API, so such a row is
        // only reachable by a direct DB edit — but the two implementations must not
        // diverge where they can be made to. The overflow-safe rewrite
        // `count <= limit - cost` (the reference implementation's `cost > limit - count`) denies.
        // Seed the row at saturation and drive the SQL-side sum.
        seedRateLimitRow("k1", "requests", START.toEpochMilli() / 1000, Long.MAX_VALUE);
        RateLimitResult denied = limiter.tryAcquire("k1", 5, 1);
        assertInstanceOf(
                RateLimitResult.Denied.class, denied, "a saturated counter must deny cleanly, never a bigint 5xx");
        assertInstanceOf(RateLimitResult.Denied.class, limiter.tryAcquire("k1", 5, Long.MAX_VALUE - 4));
    }

    /** Seed a rate_limits row directly (the test's raw-SQL seam; the public API bounds the counter). */
    private void seedRateLimitRow(String keyId, String dimension, long windowStart, long count) {
        try (Connection connection = dataSource.getConnection();
                java.sql.PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO rate_limits (key_id, dimension, window_start, count) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, keyId);
            ps.setString(2, dimension);
            ps.setLong(3, windowStart);
            ps.setLong(4, count);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to seed a rate_limits row", e);
        }
    }

    /** One-value SELECT helper over the test pool. */
    private long countRows(String sql) {
        try (Connection connection = dataSource.getConnection();
                java.sql.PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("test count query failed: " + sql, e);
        }
    }
}
