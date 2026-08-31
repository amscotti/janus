package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * {@link PgSpendLedger}, the Postgres {@link SpendLedger}: mirrors the
 * {@link InMemorySpendLedgerTest} suite over a real Postgres — reserve/settle/
 * release math + clamping (double release a harmless no-op), the hard-cap atomic
 * no-overspend reserve (a concurrent burst admits exactly the fitting count), no-cap
 * ({@code ≤ 0}) always allowed, the soft flag, unknown key ⇒ zero spend / empty
 * recent, and the {@code recordSpend} ↔ {@code recent} ring (retention-bounded,
 * newest first, bigserial order).
 */
@Testcontainers(disabledWithoutDocker = true)
class PgSpendLedgerTest {

    private static final Instant START = Instant.parse("2026-08-03T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private DataSource dataSource;
    private PgSpendLedger ledger;

    @BeforeAll
    static void startDatabase() {
        PgTestDb.ensureStarted();
        PgTestDb.migrate();
    }

    @BeforeEach
    void freshDatabase() {
        dataSource = PgTestDb.newDataSource();
        PgTestDb.truncateAll(dataSource);
        ledger = new PgSpendLedger(dataSource, clock, 2);
    }

    @AfterEach
    void closePool() {
        PgTestDb.close(dataSource);
    }

    @Test
    void settleAccumulatesAllTimeSpendByKeyNeverTrimmed() {
        ledger.settle("k1", 300, 250, 0);
        assertEquals(250, ledger.spendByKey("k1", 0));
        ledger.settle("k1", 300, 250, 0);
        ledger.settle("k1", 300, 250, 0);
        ledger.settle("k1", 300, 250, 0);
        assertEquals(1_000, ledger.spendByKey("k1", 0), "lifetime settled spend is never trimmed");
    }

    @Test
    void recordSpendAppendsNewestFirstBoundedByRingRetention() {
        ledger.recordSpend("k1", 10);
        ledger.recordSpend("k1", 20);
        ledger.recordSpend("k1", 30);

        List<SpendLedger.LedgerEntry> recent = ledger.recent("k1", 10);
        assertEquals(2, recent.size(), "ring retention of 2 bounds the buffer");
        assertEquals(30, recent.get(0).microUsd(), "newest first");
        assertEquals(20, recent.get(1).microUsd());
        assertEquals(1, ledger.recent("k1", 1).size(), "n clamps to the ring size");
        assertTrue(ledger.recent("k1", 0).isEmpty(), "n ≤ 0 yields an empty view");
        assertTrue(ledger.recent("unknown", 10).isEmpty(), "unknown key has no entries");
    }

    @Test
    void reserveIncrementThenCheckIsAtomicUnderConcurrency() throws Exception {
        long cap = 1_000;
        long estimate = 300; // exactly 3 reservations fit; the 4th must roll back
        int threads = 8;
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
                    SpendLedger.ReserveResult result = ledger.reserve("k1", estimate, cap, 0.8, 0);
                    if (result instanceof SpendLedger.ReserveResult.Allowed) {
                        allowed.incrementAndGet();
                    }
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "reservations must finish");
        } finally {
            pool.shutdownNow();
        }
        // No overspend beyond one request: the atomic increment-then-check admits
        // exactly floor(cap / estimate) and rolls every other reservation back.
        assertEquals(3, allowed.get(), "the 4th concurrent reservation must roll back");
        assertEquals(900, ledger.pending("k1"), "exactly three estimates remain pending");
    }

    @Test
    void settleCorrectsTheReservation() {
        SpendLedger.ReserveResult reserve = ledger.reserve("k1", 300, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, reserve);
        assertEquals(300, ledger.pending("k1"));

        ledger.settle("k1", 300, 250, 0);
        assertEquals(0, ledger.pending("k1"), "pending −= estimate");
        assertEquals(250, ledger.spendByKey("k1", 0), "settled += actual");
    }

    @Test
    void settleClampsNegativeActualToZeroLikeTheReference() {
        // The JDBC path clamps (never throws) on a
        // negative actual — the max(actual, 0) reference semantic, matching
        // the SQL GREATEST(?, 0) already in the settle statement. The in-memory
        // reference's Math.max clamp is the same semantic (its unreachable-for-
        // compliant-callers guard stays untouched in recorded in RESULTS.md);
        // the parity suite stays green either way (no contract test passes a
        // negative actual).
        ledger.reserve("k1", 300, 1_000, 0.8, 0);
        ledger.settle("k1", 300, -42, 0);
        assertEquals(0, ledger.pending("k1"), "pending −= estimate regardless of the actual's sign");
        assertEquals(0, ledger.spendByKey("k1", 0), "a negative actual clamps to zero settled spend");
        ledger.settle("k1", 300, -1, 0);
        assertEquals(0, ledger.spendByKey("k1", 0), "clamping repeats (settled never goes negative)");
    }

    @Test
    void settleNeverUnderflowsPending() {
        // Settle clamps pending like release (GREATEST(pending - ?, 0))
        // — a settle that overdraws the reservation (settle-after-release, or a
        // double-settle) must never drive pending negative, because a negative pending
        // would loosen the hard cap's settled + pending ≥ cap admission check.
        ledger.reserve("k1", 300, 1_000, 0.8, 0);
        ledger.release("k1", 300, 0);
        ledger.settle("k1", 300, 250, 0); // a racing settle after the release returned the estimate
        assertEquals(0, ledger.pending("k1"), "settle after release must not drive pending negative");
        assertEquals(250, ledger.spendByKey("k1", 0), "only the actual settles");

        ledger.settle("k2", 300, 250, 0);
        ledger.settle("k2", 300, 250, 0); // double settle with the same estimate
        assertEquals(0, ledger.pending("k2"), "a double settle never underflows pending");
        assertEquals(500, ledger.spendByKey("k2", 0), "each settle commits its actual");
    }

    @Test
    void deniedPayloadIsInternallyConsistentUnderConcurrency() throws Exception {
        // Every Denied payload must be a single snapshot — settled and
        // pending read in one statement, never two racing SELECTs. In this pure-reserve
        // burst (no settle/release in flight) the post-rollback totals are stable at
        // (0, 900), so the invariant is deterministic: 0 ≤ totals, never over the cap.
        long cap = 1_000;
        long estimate = 300;
        int threads = 8;
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
                    SpendLedger.ReserveResult result = ledger.reserve("k1", estimate, cap, 0.8, 0);
                    if (result instanceof SpendLedger.ReserveResult.Denied denied) {
                        assertTrue(denied.settledMicroUsd() >= 0, "denied payload settled is non-negative");
                        assertTrue(denied.pendingMicroUsd() >= 0, "denied payload pending is non-negative");
                        assertTrue(
                                denied.settledMicroUsd() + denied.pendingMicroUsd() <= cap,
                                "denied payload totals never exceed the cap");
                    }
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "reservations must finish");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(900, ledger.pending("k1"), "exactly three estimates remain pending (all denials rolled back)");
    }

    @Test
    void deniedPayloadIsInternallyConsistentUnderConcurrentSettleAndRelease() throws Exception {
        // The denied-reservation payload must be a single snapshot even
        // with settle/release racing a denied reserve — the pure-reserve burst above has
        // no settle/release in flight. reserve runs inside one transaction whose failed
        // upsert holds the row lock until the totals read, so a concurrent
        // settle/release cannot interleave between the denial and the payload: every
        // Denied payload reports totals that are internally consistent and, at these
        // magnitudes, never exceed the cap (settled stays at the pre-seed 500 while
        // pending oscillates).
        long cap = 1_000;
        long estimate = 300;
        ledger.settle("k1", 0, 500, 0); // committed base spend; pending admission is capped
        int threads = 8;
        int rounds = 40;
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
                    for (int r = 0; r < rounds; r++) {
                        SpendLedger.ReserveResult result = ledger.reserve("k1", estimate, cap, 0.8, 0);
                        if (result instanceof SpendLedger.ReserveResult.Denied denied) {
                            assertTrue(denied.settledMicroUsd() >= 0, "denied payload settled is non-negative");
                            assertTrue(denied.pendingMicroUsd() >= 0, "denied payload pending is non-negative");
                            assertTrue(
                                    denied.settledMicroUsd() + denied.pendingMicroUsd() <= cap,
                                    "denied payload totals never exceed the cap");
                        }
                        // A settle (actual 0, so settled stays at the 500 pre-seed) or a
                        // release racing the denied reserves — both free pending — must
                        // never make a payload internally inconsistent.
                        ledger.settle("k1", estimate, 0, 0);
                        ledger.release("k1", estimate, 0);
                    }
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "reservations must finish");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(500, ledger.spendByKey("k1", 0), "settled is untouched by the race");
    }

    @Test
    void hugeSettledTotalDeniesCleanlyInsteadOfRaisingBigintOverflow() {
        // At saturation the reserve guard must deny cleanly like the
        // in-memory reference (hugeSettledTotalNeverWrapsTheCapGuardNegative) — never a
        // Postgres "bigint out of range" 5xx on the request path. The guard uses the
        // overflow-safe rewrite (settled < cap - pending - estimate, never the raw sum),
        // so a settled total near Long.MAX_VALUE denies without the arithmetic ever
        // exceeding bigint.
        ledger.settle("k1", 0, Long.MAX_VALUE / 2, 0);
        ledger.settle("k1", 0, Long.MAX_VALUE / 2, 0); // settled = Long.MAX_VALUE - 1
        assertEquals(Long.MAX_VALUE - 1, ledger.spendByKey("k1", 0));

        SpendLedger.ReserveResult denied = ledger.reserve("k1", 1_000_000, 1_000_000_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Denied.class, denied, "an astronomically spent key must be denied");
        assertEquals(0, ledger.pending("k1"), "the denied reservation is rolled back");
        assertEquals(Long.MAX_VALUE - 1, ledger.spendByKey("k1", 0), "prior spend is untouched");
    }

    @Test
    void settleSaturatesAtBigintMaxInsteadOfRaisingOverflow() {
        // Settle used to evaluate the raw `settled + actual` sum in SQL — a
        // settled total near Long.MAX made a further settle raise a Postgres "bigint out
        // of range" 5xx on the billing path, where the in-memory reference saturates
        // (its saturatingAdd clamps at Long.MAX_VALUE). The accumulation is now
        // saturated in the statement (CASE... ELSE
        // settled + actual), never exceeding bigint — both backends saturate
        // identically. Mirror the in-memory saturation test's shape.
        ledger.settle("k1", 0, Long.MAX_VALUE - 1, 0);
        assertEquals(Long.MAX_VALUE - 1, ledger.spendByKey("k1", 0));

        ledger.settle("k1", 0, 1_000, 0); // the next settle must not raise bigint overflow
        assertEquals(Long.MAX_VALUE, ledger.spendByKey("k1", 0), "the committed total saturates at bigint max");

        ledger.settle("k1", 0, 1_000, 0); // and stays saturated
        assertEquals(Long.MAX_VALUE, ledger.spendByKey("k1", 0));
        assertEquals(0, ledger.pending("k1"), "settle's pending release is unaffected by the saturation");
    }

    @Test
    void releaseRollsBackTheReservation() {
        ledger.reserve("k1", 300, 1_000, 0.8, 0);
        assertEquals(300, ledger.pending("k1"));
        ledger.release("k1", 300, 0);
        assertEquals(0, ledger.pending("k1"), "release is the aborted-stream rollback");
        assertEquals(0, ledger.spendByKey("k1", 0), "released reservations never settle");
        ledger.release("k1", 300, 0); // double release is a harmless no-op
        assertEquals(0, ledger.pending("k1"));
    }

    @Test
    void settleAfterPeriodRolloverCreditsTheReservationWindow() {
        SpendLedger.ReserveResult reserve = ledger.reserve("k1", 300, 1_000, 0.8, 0);
        clock.advanceSeconds(61); // the request overran the 60s period boundary
        ledger.settle("k1", 300, 250, ((SpendLedger.ReserveResult.Allowed) reserve).windowStartEpochSeconds());
        assertEquals(0, ledger.pending("k1"), "settle credits the window the reservation was made against");
        assertEquals(250, ledger.spendByKey("k1", 0));
    }

    @Test
    void reserveSelfRegistersUnknownKeys() {
        SpendLedger.ReserveResult reserve = ledger.reserve("fresh-key", 500, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, reserve, "first reserve creates the entry");
        assertEquals(500, ledger.pending("fresh-key"));
        assertEquals(0, ledger.spendByKey("fresh-key", 0));
    }

    @Test
    void hardCapDeniesAndRollsBackLeavingPriorSpendIntact() {
        ledger.settle("k1", 0, 800, 0); // already spent 800 of a 1000 cap
        SpendLedger.ReserveResult denied = ledger.reserve("k1", 300, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Denied.class, denied, "800 + 300 ≥ 1000 → hard deny");
        assertEquals(0, ledger.pending("k1"), "the denied reservation is rolled back (equal decrement)");
        assertEquals(800, ledger.spendByKey("k1", 0), "prior spend is untouched");
    }

    @Test
    void freshKeyReservationOverTheCapIsDeniedNotCreated() {
        // The semantics for a self-registering fresh key: estimate ≥ cap ⇒ denied
        // with nothing pending (the increment is rolled back inside the atomic step).
        SpendLedger.ReserveResult denied = ledger.reserve("fresh-over-cap", 1_500, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Denied.class, denied, "estimate ≥ cap on a fresh key ⇒ denied");
        assertEquals(0, ledger.pending("fresh-over-cap"), "no pending leak from the denied reservation");
        assertEquals(0, ledger.spendByKey("fresh-over-cap", 0));
    }

    @Test
    void noCapAlwaysAllowed() {
        SpendLedger.ReserveResult first = ledger.reserve("k1", 500, 0, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, first, "hardCap ≤ 0 means no cap");
        SpendLedger.ReserveResult second = ledger.reserve("k1", 500, -1, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, second, "negative cap means no cap too");
        assertEquals(1_000, ledger.pending("k1"));
        assertFalse(((SpendLedger.ReserveResult.Allowed) first).soft(), "no cap ⇒ never soft");
    }

    @Test
    void noCapReserveSaturatesPendingInsteadOfRaisingBigintOverflow() {
        // The no-cap branch has no estimate clamp to lean on (there is no cap to clamp
        // to), so the pending accumulation must saturate in SQL — mirroring the
        // in-memory reference's unconditional saturatingAdd
        // (noCapReserveSaturatesPendingInsteadOfWrappingNegative): the raw
        // `pending + EXCLUDED.pending` sum on a saturated estimate raises a Postgres
        // "bigint out of range" 5xx (IllegalStateException at the store seam), where
        // the in-memory ledger saturates at Long.MAX_VALUE.
        SpendLedger.ReserveResult first = ledger.reserve("k1", Long.MAX_VALUE, -1, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, first, "no cap ⇒ every reservation is allowed");
        assertEquals(Long.MAX_VALUE, ledger.pending("k1"), "the saturated estimate lands pending at bigint max");

        SpendLedger.ReserveResult second = ledger.reserve("k1", 1, -1, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, second, "still no cap ⇒ still allowed, no 5xx");
        assertEquals(Long.MAX_VALUE, ledger.pending("k1"), "pending saturates, never exceeds bigint");

        // A later capped reserve reads the SATURATED pending and hard-denies — the
        // Java-side estimate clamp keeps the guard's subtraction inside bigint.
        SpendLedger.ReserveResult denied = ledger.reserve("k1", 1, 1_000, 0.8, 0);
        assertInstanceOf(
                SpendLedger.ReserveResult.Denied.class,
                denied,
                "a saturated pending hard-denies any capped reservation, cleanly (no bigint overflow)");
        assertEquals(Long.MAX_VALUE, ledger.pending("k1"), "the denied reservation rolls back to the saturated value");
        assertEquals(0, ledger.spendByKey("k1", 0), "nothing settles");
    }

    @Test
    void saturatedEstimateNeverAdmitsAgainstAModestCap() {
        // The capped branch clamps the estimate to the cap Java-side — the in-memory
        // reference's defense-in-depth clamp (saturatedEstimateNeverAdmitsAgainstAModestCap):
        // outcome-preserving (estimate ≥ cap is always denied) AND it keeps the guard's
        // `cap − pending − estimate` subtraction inside bigint instead of raising a
        // Postgres "bigint out of range" on the evaluation.
        SpendLedger.ReserveResult denied = ledger.reserve("k1", Long.MAX_VALUE, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Denied.class, denied, "a saturated estimate can never be admitted");
        assertEquals(0, ledger.pending("k1"), "no pending leak from the denied reservation");
        assertEquals(0, ledger.spendByKey("k1", 0));

        // and the ledger is still usable afterwards — the cap guard never wedged.
        SpendLedger.ReserveResult allowed = ledger.reserve("k1", 100, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, allowed, "the ledger recovers after the clamp");
    }

    @Test
    void softFractionFlagsAllowedReservationsWithoutBlocking() {
        SpendLedger.ReserveResult first = ledger.reserve("k1", 300, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, first);
        assertFalse(((SpendLedger.ReserveResult.Allowed) first).soft(), "300 < 800 soft cap");
        ledger.reserve("k1", 300, 1_000, 0.8, 0);
        SpendLedger.ReserveResult third = ledger.reserve("k1", 300, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, third, "900 < 1000 hard cap → still allowed");
        assertTrue(
                ((SpendLedger.ReserveResult.Allowed) third).soft(),
                "900 ≥ 800 soft cap → flagged soft, traffic proceeds");
        SpendLedger.ReserveResult fourth = ledger.reserve("k1", 300, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Denied.class, fourth, "1200 ≥ 1000 hard cap → denied");
    }

    @Test
    void softFlagMatchesTheInMemoryReferenceAtNearSaturationTotals() {
        // The Pg soft flag must use the in-memory reference's overflow-safe
        // form (settled >= softCap - pending, never settled + pending). At huge but
        // admissible totals the reserve cap bounds settled+pending below Long.MAX (the
        // wrap is unreachable on the allowed path), so this pins the parity — the two
        // implementations must not diverge, whatever the magnitudes.
        long cap = Long.MAX_VALUE;
        long softCap = (long) Math.floor(cap * 0.9);
        long settled = (long) (cap * 0.6);
        long pending = (long) (cap * 0.3);
        ledger.settle("k1", 0, settled, 0);
        SpendLedger.ReserveResult result = ledger.reserve("k1", pending, cap, 0.9, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, result, "0.9 cap sum is admitted");
        boolean expectedSoft = settled >= softCap - pending;
        assertEquals(
                expectedSoft,
                ((SpendLedger.ReserveResult.Allowed) result).soft(),
                "the soft flag matches the in-memory reference's overflow-safe form");
        assertEquals(
                settled >= softCap - pending,
                settled + pending >= softCap,
                "sanity: the two forms agree at these magnitudes (no wrap yet)");
    }

    @Test
    void perKeyLedgersAreIsolated() {
        ledger.settle("a", 0, 100, 0);
        ledger.reserve("b", 300, 1_000, 0.8, 0);
        assertEquals(100, ledger.spendByKey("a", 0));
        assertEquals(0, ledger.spendByKey("b", 0));
        assertEquals(300, ledger.pending("b"));
        assertEquals(0, ledger.pending("a"));
    }

    // ------------------------------------------------- budget reset windows
    // The parity rule: every windowed pin of InMemorySpendLedgerTest, mirrored over
    // a real Postgres — both backends key ledger state by (keyId, windowStart), so a
    // straddled reservation settles into the SAME window entry on both.

    private static final long WINDOW = 60;

    private static long windowStartOf(SpendLedger.ReserveResult result) {
        return ((SpendLedger.ReserveResult.Allowed) result).windowStartEpochSeconds();
    }

    @Test
    void windowedBudgetResetsSettledOnEpochRollover() {
        ledger.settle("k1", 0, 800, START.getEpochSecond()); // W base spend: 800 of a 1000 cap
        SpendLedger.ReserveResult denied = ledger.reserve("k1", 300, 1_000, 0.8, WINDOW);
        assertInstanceOf(SpendLedger.ReserveResult.Denied.class, denied, "800 + 300 ≥ 1000 in window W → deny");

        clock.advanceSeconds(WINDOW + 1); // roll into W+1
        SpendLedger.ReserveResult next = ledger.reserve("k1", 300, 1_000, 0.8, WINDOW);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, next, "the new window's row starts settled = 0");
        assertEquals(0, ((SpendLedger.ReserveResult.Allowed) next).settledMicroUsd(), "the rollover reset the cap");
        assertEquals(
                START.getEpochSecond() + WINDOW, windowStartOf(next), "the window start is the aligned epoch of W+1");
        assertEquals(800, ledger.totalSpendByKey("k1"), "the all-time view keeps W's spend");
    }

    @Test
    void settleAfterBudgetWindowRolloverCreditsTheReservationWindowNotTheCurrent() {
        // The straddle-parity pin (the PG half): a reservation made in W that settles
        // after the rollover credits W's row — W+1 stays zero, observed through a
        // fresh-window reserve whose admission guard reads settled = 0.
        long w = windowStartOf(ledger.reserve("k1", 300, 1_000, 0.8, WINDOW));
        clock.advanceSeconds(WINDOW + 1); // the request overran the boundary into W+1
        ledger.settle("k1", 300, 250, w);

        SpendLedger.ReserveResult next = ledger.reserve("k1", 300, 1_000, 0.8, WINDOW);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, next);
        assertEquals(
                0,
                ((SpendLedger.ReserveResult.Allowed) next).settledMicroUsd(),
                "W+1's row is zero — the straddled settle credited W, not the current window");
        assertEquals(
                0,
                ledger.spendByKey("k1", WINDOW),
                "the budget view reads the newest window row (W+1, created at zero by the reserve)");
        assertEquals(250, ledger.totalSpendByKey("k1"), "the all-time view holds W's settled actual");
    }

    @Test
    void straddledReservationIsInvisibleToTheNewWindowGuard() {
        // The documented limitation, pinned identically to the in-memory suite: a
        // reservation that settles after the rollover is invisible to the NEW window's
        // admission guard — its cost counts against the reservation's window and the
        // all-time total only, never the new window.
        long w = windowStartOf(ledger.reserve("k1", 800, 1_000, 0.8, WINDOW));
        clock.advanceSeconds(WINDOW + 1);
        ledger.settle("k1", 800, 750, w); // W: settled 750, all-time 750

        SpendLedger.ReserveResult next = ledger.reserve("k1", 800, 1_000, 0.8, WINDOW);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, next, "the straddled reservation is invisible");
        assertEquals(750, ledger.totalSpendByKey("k1"), "the all-time view still counts it");
    }

    @Test
    void spendByKeyReturnsCurrentWindowOnlyAndTotalSpendNeverDecreases() {
        long w1 = windowStartOf(ledger.reserve("k1", 100, 10_000, 0.8, WINDOW));
        ledger.settle("k1", 100, 250, w1);
        assertEquals(250, ledger.spendByKey("k1", WINDOW));
        assertEquals(250, ledger.totalSpendByKey("k1"));

        clock.advanceSeconds(WINDOW); // W+1
        long w2 = windowStartOf(ledger.reserve("k1", 100, 10_000, 0.8, WINDOW));
        ledger.settle("k1", 100, 250, w2);
        assertEquals(250, ledger.spendByKey("k1", WINDOW), "the budget view holds W+1 only, not W + W+1");
        assertEquals(500, ledger.totalSpendByKey("k1"), "the all-time view accumulates across windows");
    }

    @Test
    void rolloverBoundaryEachReservationSettlesIntoItsOwnWindow() {
        // Two reserves straddling the boundary — one lands in W, one in W+1 — each
        // settles into its own window row; no pending is lost in either window.
        long w1 = windowStartOf(ledger.reserve("k1", 300, 1_000, 0.8, WINDOW));
        clock.advanceSeconds(WINDOW); // the rollover second
        long w2 = windowStartOf(ledger.reserve("k1", 300, 1_000, 0.8, WINDOW));
        assertEquals(w1 + WINDOW, w2, "the second reserve landed in the next window");

        ledger.settle("k1", 300, 250, w1);
        ledger.settle("k1", 300, 250, w2);
        assertEquals(500, ledger.totalSpendByKey("k1"), "both actuals committed");
        assertEquals(0, ledger.pending("k1"), "no pending lost: the newest window's reservation released");
        assertEquals(250, ledger.spendByKey("k1", WINDOW), "the budget view reads W+1 only");
    }

    @Test
    void windowedRowsArePrunedToFoldedAllTimeAndKeptHorizon() throws Exception {
        // The PG bounded-retention pin: windowed rows are pruned to current +
        // PRUNE_KEEP_WINDOWS prior windows, and each pruned row's settled is FOLDED
        // into the key's window-0 accumulator row inside the same atomic statement —
        // totalSpendByKey (the sum) never decreases across a prune.
        long w0 = windowStartOf(ledger.reserve("k1", 100, 1_000_000, 0.8, WINDOW));
        long w = w0;
        for (int i = 0; i < 5; i++) {
            ledger.settle("k1", 100, 100, w);
            clock.advanceSeconds(WINDOW);
            w += WINDOW;
            ledger.reserve("k1", 100, 1_000_000, 0.8, WINDOW);
        }
        // six windows touched: each iteration settles the previous window's
        // reservation; correct the last one explicitly
        ledger.settle("k1", 100, 100, w);
        assertEquals(600, ledger.totalSpendByKey("k1"), "six windows committed before the prune");
        assertEquals(6, spendRowCount("k1"), "one row per window accrued");

        ledger.pruneStaleWindows("k1", w, WINDOW);
        // Kept: the fold target (window 0) + current + PRUNE_KEEP_WINDOWS prior windows.
        assertEquals(
                PgSpendLedger.PRUNE_KEEP_WINDOWS + 2,
                spendRowCount("k1"),
                "window-0 accumulator + current + PRUNE_KEEP_WINDOWS prior rows survive");
        assertEquals(600, ledger.totalSpendByKey("k1"), "the prune folds pruned settled into window 0 — never lost");
        assertEquals(100, ledger.spendByKey("k1", WINDOW), "the budget view still reads the newest window row");
    }

    @Test
    void settleOntoAPrunedWindowRowReRegistersItWithTheActual() throws Exception {
        // The settle-vs-prune interleaving pin: settle is ONE self-registering upsert,
        // so a settle whose reservation window row was deleted mid-flight (here: the
        // prune's DELETE ran directly, standing in for the interleaving between a
        // settle's statements) re-creates the row with settled = actual — the actual
        // is never silently dropped from totalSpendByKey (the in-memory parity: a
        // straddled settle onto a pruned window re-creates the entry).
        long w = windowStartOf(ledger.reserve("k1", 300, 1_000_000, 0.8, WINDOW));
        try (var connection = dataSource.getConnection();
                var ps = connection.prepareStatement("DELETE FROM spend WHERE key_id = 'k1' AND window_start = ?")) {
            ps.setLong(1, w);
            ps.executeUpdate();
        }
        assertEquals(0, ledger.totalSpendByKey("k1"), "the deleted row left no spend");

        ledger.settle("k1", 300, 250, w);
        assertEquals(
                250, ledger.totalSpendByKey("k1"), "the straddled settle re-registered its window with the actual");
        assertEquals(250, ledger.spendByKey("k1", WINDOW), "the re-created row is the key's newest window");
        assertEquals(0, ledger.pending("k1"), "the re-created row carries no pending");
    }

    @Test
    void preV2SpendRowReadsAsTheLifetimeWindowZero() throws Exception {
        // The V2 backfill pin: the window_start column defaults to 0, so a row
        // written pre-V2 shape (no window) IS the lifetime window-0 row — the budget
        // and all-time views both read it unchanged.
        try (var connection = dataSource.getConnection();
                var ps = connection.prepareStatement(
                        "INSERT INTO spend (key_id, settled, pending) VALUES ('pre-v2', 500, 0)")) {
            ps.executeUpdate();
        }
        assertEquals(500, ledger.spendByKey("pre-v2", 0), "a defaulted row reads as the lifetime budget view");
        assertEquals(500, ledger.totalSpendByKey("pre-v2"), "a defaulted row reads in the all-time sum");
    }

    private long spendRowCount(String keyId) throws Exception {
        try (var connection = dataSource.getConnection();
                var ps = connection.prepareStatement("SELECT count(*) FROM spend WHERE key_id = ?")) {
            ps.setString(1, keyId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
