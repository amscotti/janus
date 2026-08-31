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
import org.junit.jupiter.api.Test;

/**
 * {@link InMemorySpendLedger}: per-key settled spend
 * ({@code spendByKey}, the budget view — current reset window; never trimmed within
 * a window — budgets need it), the all-time view
 * ({@code totalSpendByKey}, exact across pruned windows), a bounded ring buffer of
 * {@code recent} entries (retention-configurable; consumes), and the atomic
 * reserve/settle/release flow : {@code reserve} runs
 * <b>increment-then-check inside a single {@code compute}</b> — a concurrent burst
 * against a hard cap can never overspend beyond one request (the
 * "concurrent spend racing" requirement), {@code settle} corrects the reservation
 * (pending −= estimate, settled += actual), {@code release} rolls it back (aborted
 * streams), and settle/release after a period rollover credit the window the
 * reservation was made against. Unknown key ids self-register at first reserve (the key
 * id from {@link KeyRecord#id}).
 *
 * <p>Every pre-window test maps onto {@code windowSeconds = 0} — the single lifetime
 * window at epoch 0 — pinning the zero-diff semantics; the windowed tests derive
 * windows with the {@link MutableClock}.
 */
class InMemorySpendLedgerTest {

    private static final Instant START = Instant.parse("2026-08-03T00:00:00Z");

    /** A window length the windowed tests use (aligned at the epoch-anchored START). */
    private static final long WINDOW = 60;

    private final MutableClock clock = new MutableClock(START);
    private final InMemorySpendLedger ledger = new InMemorySpendLedger(clock, 2);

    private static long windowStartOf(SpendLedger.ReserveResult result) {
        return ((SpendLedger.ReserveResult.Allowed) result).windowStartEpochSeconds();
    }

    @Test
    void settleAccumulatesAllTimeSpendByKeyNeverTrimmed() {
        ledger.settle("k1", 300, 250, 0);
        assertEquals(250, ledger.spendByKey("k1", 0));
        ledger.settle("k1", 300, 250, 0);
        ledger.settle("k1", 300, 250, 0);
        ledger.settle("k1", 300, 250, 0);
        // Retention is 2, but the committed total keeps growing past it (budgets need it).
        assertEquals(1_000, ledger.spendByKey("k1", 0), "lifetime settled spend is never trimmed");
        assertEquals(1_000, ledger.totalSpendByKey("k1"), "the all-time view matches the lifetime window");
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
            assertTrue(done.await(10, TimeUnit.SECONDS), "reservations must finish");
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
        assertEquals(0, windowStartOf(reserve), "windowSeconds = 0 ⇒ the lifetime window at epoch 0");
        assertEquals(300, ledger.pending("k1"));

        ledger.settle("k1", 300, 250, 0);
        assertEquals(0, ledger.pending("k1"), "pending −= estimate");
        assertEquals(250, ledger.spendByKey("k1", 0), "settled += actual");
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
    void settleNeverUnderflowsPending() {
        // Settle clamps pending like release — a settle that overdraws
        // the reservation (settle-after-release, or a double-settle) must never drive
        // pending negative, because a negative pending loosens the hard cap's
        // settled + pending ≥ cap admission check.
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
    void settleClampsNegativeActualToZeroLikeTheReference() {
        // The in-memory ledger shares the shared clamp on a negative
        // actual (the old non-negative guard was removed — both impls now clamp, one
        // rule): settled spend never goes negative, pending still releases.
        ledger.reserve("k1", 300, 1_000, 0.8, 0);
        ledger.settle("k1", 300, -42, 0);
        assertEquals(0, ledger.pending("k1"), "pending −= estimate regardless of the actual's sign");
        assertEquals(0, ledger.spendByKey("k1", 0), "a negative actual clamps to zero settled spend");
        ledger.settle("k1", 300, -1, 0);
        assertEquals(0, ledger.spendByKey("k1", 0), "clamping repeats (settled never goes negative)");
    }

    @Test
    void settleAfterPeriodRolloverCreditsTheReservationWindow() {
        SpendLedger.ReserveResult reserve = ledger.reserve("k1", 300, 1_000, 0.8, 0);
        clock.advanceSeconds(61); // the request overran the 60s period boundary
        ledger.settle("k1", 300, 250, windowStartOf(reserve));
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
    void hugeSettledTotalNeverWrapsTheCapGuardNegative() {
        // Settled near Long.MAX_VALUE must still deny a new reservation — the
        // old `settled + pending >= cap` could wrap the sum negative and ADMIT an over-cap
        // reservation (the reserve check is now overflow-safe).
        ledger.settle("k1", 0, Long.MAX_VALUE / 2, 0);
        ledger.settle("k1", 0, Long.MAX_VALUE / 2, 0); // settled = Long.MAX_VALUE - 1
        assertEquals(Long.MAX_VALUE - 1, ledger.spendByKey("k1", 0));

        SpendLedger.ReserveResult denied = ledger.reserve("k1", 1_000_000, 1_000_000_000, 0.8, 0);
        assertInstanceOf(
                SpendLedger.ReserveResult.Denied.class,
                denied,
                "an astronomically spent key must be denied, never admitted by a wrapped-negative total");
        assertEquals(0, ledger.pending("k1"), "the denied reservation is rolled back");
        assertEquals(Long.MAX_VALUE - 1, ledger.spendByKey("k1", 0), "prior spend is untouched");
    }

    @Test
    void saturatedEstimateNeverAdmitsAgainstAModestCap() {
        // Defense-in-depth: a saturated estimate (~Long.MAX_VALUE micro-USD,
        // reachable only from a pathologically misconfigured rate) must be denied against
        // a modest cap — never admitted. The clamp to the cap (an estimate ≥ cap is a
        // guaranteed denial anyway) bounds the pending counter so its arithmetic cannot
        // wrap and flip the admission guard.
        SpendLedger.ReserveResult denied = ledger.reserve("k1", Long.MAX_VALUE, 1_000, 0.8, 0);
        assertInstanceOf(
                SpendLedger.ReserveResult.Denied.class,
                denied,
                "a saturated estimate can never be admitted against a modest cap");
        assertEquals(0, ledger.pending("k1"), "the denied reservation is rolled back");
        assertEquals(0, ledger.spendByKey("k1", 0), "nothing settles");

        // and the entry is still usable afterwards — the cap guard never wedged.
        SpendLedger.ReserveResult allowed = ledger.reserve("k1", 100, 1_000, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, allowed, "the ledger recovers after the clamp");
    }

    @Test
    void noCapReserveSaturatesPendingInsteadOfWrappingNegative() {
        // The no-cap path has no estimate clamp to lean on: reserve(MAX, -1) then
        // reserve(1, -1) used to wrap `pending` negative (MAX + 1 overflow), and the
        // corrupted counter then loosened a later capped reserve's admission guard
        // (settled >= cap − pending with a negative pending admits everything). The
        // saturating add is now unconditional — pending clamps at Long.MAX_VALUE and
        // the capped reserve still hard-denies.
        SpendLedger.ReserveResult first = ledger.reserve("k1", Long.MAX_VALUE, -1, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, first, "no cap ⇒ every reservation is allowed");
        assertEquals(Long.MAX_VALUE, ledger.pending("k1"), "the saturated estimate lands pending at Long.MAX_VALUE");

        SpendLedger.ReserveResult second = ledger.reserve("k1", 1, -1, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, second, "still no cap ⇒ still allowed");
        assertEquals(Long.MAX_VALUE, ledger.pending("k1"), "pending saturates, never wraps negative");

        // A later capped reserve must read the SATURATED pending, not a wrapped one.
        SpendLedger.ReserveResult denied = ledger.reserve("k1", 1, 1_000, 0.8, 0);
        assertInstanceOf(
                SpendLedger.ReserveResult.Denied.class,
                denied,
                "a saturated pending hard-denies any capped reservation (a wrapped-negative pending would have"
                        + " admitted it)");
        assertEquals(Long.MAX_VALUE, ledger.pending("k1"), "the denied reservation rolls back to the saturated value");
        assertEquals(0, ledger.spendByKey("k1", 0), "nothing settles");
    }

    @Test
    void cappedReserveRollsBackExactlyWhenTheIncrementSaturates() {
        // The capped path's increment can saturate too: cap = Long.MAX_VALUE admits one
        // MAX-1 estimate, and a second reserve overflows the pending accumulator. The
        // deny must roll pending back to its EXACT pre-increment value (the old
        // symmetric `-= estimate` would have left the saturated sum behind).
        SpendLedger.ReserveResult allowed = ledger.reserve("k1", Long.MAX_VALUE - 1, Long.MAX_VALUE, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, allowed, "MAX-1 of a MAX cap fits");
        assertEquals(Long.MAX_VALUE - 1, ledger.pending("k1"));

        SpendLedger.ReserveResult denied = ledger.reserve("k1", 100, Long.MAX_VALUE, 0.8, 0);
        assertInstanceOf(SpendLedger.ReserveResult.Denied.class, denied, "the saturating increment crosses the cap");
        assertEquals(
                Long.MAX_VALUE - 1,
                ledger.pending("k1"),
                "the saturated increment rolls back exactly — no drift, no wrap");
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

    @Test
    void windowedBudgetResetsSettledOnEpochRollover() {
        // A windowed budget starts each aligned window at settled = 0: exhaust the
        // cap in window W (deny), advance the MutableClock past the boundary, and the
        // same reservation is allowed again in W+1.
        // START is minute-aligned: W0 is the epoch itself.
        ledger.settle("k1", 0, 800, START.getEpochSecond()); // W base spend: 800 of a 1000 cap
        SpendLedger.ReserveResult denied = ledger.reserve("k1", 300, 1_000, 0.8, WINDOW);
        assertInstanceOf(SpendLedger.ReserveResult.Denied.class, denied, "800 + 300 ≥ 1000 in window W → deny");

        clock.advanceSeconds((int) WINDOW + 1); // roll into W+1
        SpendLedger.ReserveResult next = ledger.reserve("k1", 300, 1_000, 0.8, WINDOW);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, next, "the new window's entry starts settled = 0");
        assertEquals(0, ((SpendLedger.ReserveResult.Allowed) next).settledMicroUsd(), "the rollover reset the cap");
        assertEquals(
                START.getEpochSecond() + WINDOW, windowStartOf(next), "the window start is the aligned epoch of W+1");
        assertEquals(800, ledger.totalSpendByKey("k1"), "the all-time view keeps W's spend");
    }

    @Test
    void settleAfterBudgetWindowRolloverCreditsTheReservationWindowNotTheCurrent() {
        // The straddle-parity pin: a reservation made in W that settles after the
        // rollover credits W's entry — W+1 stays zero (observed through a fresh-window
        // reserve whose admission guard reads settled = 0), the all-time view grows.
        long w = windowStartOf(ledger.reserve("k1", 300, 1_000, 0.8, WINDOW));
        clock.advanceSeconds((int) WINDOW + 1); // the request overran the boundary into W+1
        ledger.settle("k1", 300, 250, w);

        SpendLedger.ReserveResult next = ledger.reserve("k1", 300, 1_000, 0.8, WINDOW);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, next);
        assertEquals(
                0,
                ((SpendLedger.ReserveResult.Allowed) next).settledMicroUsd(),
                "W+1's entry is zero — the straddled settle credited W, not the current window");
        assertEquals(
                0,
                ledger.spendByKey("k1", WINDOW),
                "the budget view reads the newest window (W+1, created at zero by the reserve)");
        assertEquals(250, ledger.totalSpendByKey("k1"), "the all-time view holds W's settled actual");
    }

    @Test
    void straddledReservationIsInvisibleToTheNewWindowGuard() {
        // The documented limitation, pinned: a reservation that settles after the
        // rollover is invisible to the NEW window's admission guard — its cost counts
        // against the reservation's window and the all-time total only, never the new
        // window. A fresh-window reserve that would be denied if the straddled state
        // were visible is admitted.
        long w = windowStartOf(ledger.reserve("k1", 800, 1_000, 0.8, WINDOW));
        clock.advanceSeconds((int) WINDOW + 1);
        ledger.settle("k1", 800, 750, w); // W: settled 750, all-time 750

        // In W+1 the same 800 estimate would cross a 1000 cap if W's state leaked in
        // (750 + 800 ≥ 1000) — it must be allowed against the fresh window.
        SpendLedger.ReserveResult next = ledger.reserve("k1", 800, 1_000, 0.8, WINDOW);
        assertInstanceOf(SpendLedger.ReserveResult.Allowed.class, next, "the straddled reservation is invisible");
        assertEquals(750, ledger.totalSpendByKey("k1"), "the all-time view still counts it");
    }

    @Test
    void spendByKeyReturnsCurrentWindowOnlyAndTotalSpendNeverDecreases() {
        // The budget view is the CURRENT window's settled only (not the sum of
        // windows), and the all-time view never decreases — including across pruned
        // windows (the per-key all-time accumulator survives the prune).
        long w1 = windowStartOf(ledger.reserve("k1", 100, 10_000, 0.8, WINDOW));
        ledger.settle("k1", 100, 250, w1);
        assertEquals(250, ledger.spendByKey("k1", WINDOW));
        assertEquals(250, ledger.totalSpendByKey("k1"));

        clock.advanceSeconds((int) WINDOW); // W+1
        long w2 = windowStartOf(ledger.reserve("k1", 100, 10_000, 0.8, WINDOW));
        ledger.settle("k1", 100, 250, w2);
        assertEquals(250, ledger.spendByKey("k1", WINDOW), "the budget view holds W+1 only, not W + W+1");
        assertEquals(500, ledger.totalSpendByKey("k1"), "the all-time view accumulates across windows");

        clock.advanceSeconds((int) WINDOW); // W+2
        long w3 = windowStartOf(ledger.reserve("k1", 100, 10_000, 0.8, WINDOW));
        ledger.settle("k1", 100, 250, w3);
        assertEquals(750, ledger.totalSpendByKey("k1"));

        // Prune keeps current + PRUNE_KEEP_WINDOWS prior windows: W is now beyond the
        // horizon and its entry drops — the all-time accumulator must not.
        ledger.pruneStaleWindows();
        assertEquals(250, ledger.spendByKey("k1", WINDOW), "the budget view is unaffected by the prune");
        assertEquals(750, ledger.totalSpendByKey("k1"), "totalSpendByKey never decreases across pruned windows");
    }

    @Test
    void rolloverBoundaryEachReservationSettlesIntoItsOwnWindow() throws Exception {
        // Two reserves straddling the boundary — one lands in W, one in W+1 — each
        // settles into its own window entry; no pending is lost in either window.
        long w1 = windowStartOf(ledger.reserve("k1", 300, 1_000, 0.8, WINDOW));
        clock.advanceSeconds((int) WINDOW); // the rollover second
        long w2 = windowStartOf(ledger.reserve("k1", 300, 1_000, 0.8, WINDOW));
        assertEquals(w1 + WINDOW, w2, "the second reserve landed in the next window");

        ledger.settle("k1", 300, 250, w1);
        ledger.settle("k1", 300, 250, w2);
        assertEquals(500, ledger.totalSpendByKey("k1"), "both actuals committed");
        assertEquals(0, ledger.pending("k1"), "no pending lost: both windows' reservations released");
        assertEquals(250, ledger.spendByKey("k1", WINDOW), "the budget view reads W+1 only");
    }

    @Test
    void windowedRowsArePrunedToCurrentPlusKPriorWindows() {
        // The bounded-retention pin: one entry per (key, window), pruned to the newest
        // PRUNE_KEEP_WINDOWS + 1 windows per key — the ledger never accretes a row per
        // window forever.
        for (int i = 0; i < 5; i++) {
            long w = windowStartOf(ledger.reserve("k1", 100, 1_000_000, 0.8, WINDOW));
            ledger.settle("k1", 100, 100, w);
            clock.advanceSeconds((int) WINDOW);
        }
        assertEquals(5, ledger.windowCount("k1"), "five windows accrued before the prune");
        ledger.pruneStaleWindows();
        assertEquals(
                InMemorySpendLedger.PRUNE_KEEP_WINDOWS + 1,
                ledger.windowCount("k1"),
                "only current + PRUNE_KEEP_WINDOWS prior windows survive");
        assertEquals(500, ledger.totalSpendByKey("k1"), "the all-time accumulator kept all five windows");
        assertEquals(100, ledger.spendByKey("k1", WINDOW), "the newest surviving window (W4) holds its settled");
        // A settle targeting a pruned window re-registers it (self-registering settle)
        // and still counts in the all-time view — the prune never loses committed spend.
        ledger.settle("k1", 0, 50, START.getEpochSecond());
        assertEquals(550, ledger.totalSpendByKey("k1"));
        assertEquals(100, ledger.spendByKey("k1", WINDOW), "the budget view still reads the newest window");
    }
}
