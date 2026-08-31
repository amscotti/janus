package io.amscotti.janus.store;

import java.util.List;

/**
 * Per-key spend accounting (the design names {@code spendByKey} as a
 * {@code CallStore} operation, so this interface is the seam extracts —
 * {@code KeyStore} precedent). Three concerns, mirroring the reference
 * {@code Usage.Budget}/{@code Core.Budgets} split:
 *
 * <ul>
 * <li><b>Windowed settled spend</b> — {@link #spendByKey} (the <b>budget view</b>:
 * the current reset window's settled total; see "Reset windows" below) plus
 * {@link #totalSpendByKey} (the <b>all-time view</b>, never trimmed — budgets with
 * no reset window check it, and any future "total spend by key" report reads it).
 * Both grow via {@link #settle} (the reservation correction: pending −= estimate,
 * settled += actual, Budget.settle).
 * <li><b>Bounded recent entries</b> — {@link #recent}, a per-key ring buffer fed by
 * {@link #recordSpend} (the per-request usage record; retention-configurable,
 * metrics consume it).
 * <li><b>The atomic reserve/settle/release flow</b> — {@link #reserve} runs
 * <b>increment-then-check inside one atomic compute</b> on the key's window entry, so
 * a concurrent burst against a hard cap can never overspend beyond one request
 * (the "concurrent spend racing" contract); {@link #settle} corrects
 * a reservation to actual at finalize; {@link #release}
 * rolls an aborted reservation back (upstream failure, mid-stream abort) — a
 * reservation never leaks.
 * </ul>
 *
 * <p><b>Units.</b> All amounts are integer micro-USD (1 USD = 1_000_000 micro-USD) —
 * the {@link CostCalculator} output; float USD is display-only (the reference
 * {@code total_micro_usd} rationale).
 *
 * <p><b>Reset windows.</b> Every ledger state entry is keyed by
 * {@code (keyId, windowStartEpochSeconds)} — mirroring the Postgres primary key —
 * where the window start is {@code floorDiv(nowSeconds, windowSeconds) ×
 * windowSeconds} (the {@code FixedWindowRateLimiter}/{@code PgRateLimiter}
 * arithmetic). {@code windowSeconds == 0} means <b>lifetime</b>: one single window
 * with epoch 0 (windowed epochs are huge positive numbers for any real timestamp,
 * so lifetime and windowed keys never collide on one entry — the exact zero-diff
 * semantics every pre-window call site maps onto). A reserve in a newer epoch
 * creates/switches to the new window entry with {@code settled = 0} —
 * <b>forward-only</b> rollover (a stepped-back clock keeps the stored window);
 * a settle/release always targets <b>the reservation's</b> window
 * ({@code reservationWindowStart}, threaded from the {@link ReserveResult.Allowed}
 * the reserve returned), so a reservation that settles after a rollover lands in
 * <em>its</em> window's entry on BOTH backends — parity by construction. A
 * straddled reservation is therefore invisible to the new window's admission guard
 * (a documented limitation, pinned identically in both suites: the actual cost is
 * committed to the reservation's window and the all-time total, never re-counted
 * against the new window).
 *
 * <p><b>The two read views.</b> {@link #spendByKey} is the budget view: the newest
 * window row's settled total (the current window once it has activity — the reserve
 * re-creates it at settled = 0 on rollover; before that first reserve the newest
 * row is still the prior window's, the same row the Postgres read observes).
 * {@link #totalSpendByKey} is the all-time view: the sum of every window's settled
 * total the key ever had, exact across pruned windows (the in-memory backend keeps
 * a per-key all-time accumulator; Postgres sums the surviving rows, and its
 * retention-bounded prune keeps current + {@code PRUNE_KEEP_WINDOWS} prior windows
 * so the sum stays exact — a pruned window's settled is folded into the in-memory
 * accumulator before its entry is dropped). The retention-bounded
 * {@code spend_entries} ring is NOT an all-time substitute — that is why the
 * all-time answer is a separate method.
 *
 * <p><b>Self-registering keys.</b> {@code reserve} creates a key's entry on first use
 * (the key id from {@link KeyRecord#id}); unknown keys report zero settled spend and
 * an empty recent view. {@code keyId} is non-secret.
 *
 * <p>The shipped implementations are in-memory and Postgres (the
 * {@code CallStore}); per-key isolation, the reservation window thread-through and
 * the straddle parity are pinned by {@code InMemorySpendLedgerTest} and mirrored by
 * {@code PgSpendLedgerTest}.
 */
public interface SpendLedger {

    /** One {@link #recent} ring entry: when the spend landed (ledger clock) + micro-USD. */
    record LedgerEntry(long atEpochMillis, long microUsd) {}

    /**
     * The outcome of {@link #reserve}: {@link Allowed} proceeds (flagging {@code soft}
     * when the reservation pushes the total past the soft fraction — traffic proceeds,
     * the gateway warns/notifies), {@link Denied} means the hard cap would be crossed
     * (the reservation was rolled back inside the same atomic step — the caller must
     * 429 without dispatching upstream). Both carry the key's window totals after the
     * operation for the notifier payload.
     */
    sealed interface ReserveResult permits ReserveResult.Allowed, ReserveResult.Denied {

        /**
         * The reservation was taken (pending += estimate); {@code soft} flags the soft
         * tier. {@code windowStartEpochSeconds} is the reset window the reservation
         * landed in — the caller threads it back into {@link #settle}/
         * {@link #release} so a straddled reservation credits <b>its</b> window, never
         * whichever window the clock is in at finalize time.
         */
        record Allowed(boolean soft, long settledMicroUsd, long pendingMicroUsd, long windowStartEpochSeconds)
                implements ReserveResult {}

        /** The hard cap would be crossed; the increment was rolled back (equal decrement). */
        record Denied(long settledMicroUsd, long pendingMicroUsd) implements ReserveResult {}
    }

    /**
     * The <b>budget view</b> of {@code keyId}'s settled spend: the newest reset
     * window row's total (for a lifetime key — {@code windowSeconds == 0} — the single
     * window-0 row, so this equals {@link #totalSpendByKey}). The number the hard cap
     * checks against and the soft-cap header/notifier report. {@code 0} for an
     * unknown key.
     *
     * <p>{@code windowSeconds} carries the caller's budget-window context (0 =
     * lifetime; the key's {@code budgetDuration} otherwise) — both shipped
     * implementations read the newest window row for the key, so the memory and
     * Postgres views cannot diverge whatever the clock does between the caller's
     * epoch computation and the read.
     */
    long spendByKey(String keyId, long windowSeconds);

    /**
     * <b>All-time</b> settled spend for {@code keyId}, never trimmed — exact across
     * pruned windows (the in-memory per-key all-time accumulator; the Postgres sum
     * over surviving rows). The number a lifetime budget checks against and the one
     * any future "total spend by key" report reads for windowed keys.
     * {@code 0} for an unknown key.
     */
    long totalSpendByKey(String keyId);

    /**
     * The {@code n} newest {@link #recordSpend} entries for {@code keyId}, newest
     * first, bounded by the ring retention (fewer when the key has fewer entries;
     * {@code n ≤ 0} yields an empty view; unknown key ⇒ empty). The per-key usage
     * series consumes this.
     *
     * <p><b>Null contract:</b> a null {@code keyId} is a caller bug and throws
     * {@link NullPointerException} — unlike {@code CallStore.recentCalls}, which maps a
     * null key to the {@code ""} auth-off sentinel, the spend ledger is never called
     * for auth-off (the gateway skips the ledger entirely without a key). The two-rings
     * divergence is intentional and documented.
     */
    List<LedgerEntry> recent(String keyId, int n);

    /**
     * Reserve {@code estimateMicroUsd} against {@code keyId}'s hard cap in the reset
     * window implied by {@code windowSeconds} — increment pending then check
     * {@code settled + pending ≥ hardCapMicroUsd}, <b>both inside one atomic
     * compute</b> on the key's window entry: a concurrent burst cannot overspend
     * beyond one request (each denied reservation is rolled back with an equal
     * decrement). {@code hardCapMicroUsd ≤ 0} means "no cap" (always allowed — the
     * gateway skips the call for keys without a budget anyway; null cap = unenforced).
     * Soft tier: {@code settled + pending ≥ floor(hardCap × softFraction)} ⇒
     * {@link ReserveResult.Allowed#soft}. Unknown keys self-register.
     * {@code windowSeconds == 0} ⇒ the single lifetime window (epoch 0); a positive
     * value derives the window start
     * {@code floorDiv(nowSeconds, windowSeconds) × windowSeconds}, and a reserve in a
     * newer epoch starts that window's entry at {@code settled = 0} (forward-only
     * rollover).
     */
    ReserveResult reserve(
            String keyId, long estimateMicroUsd, long hardCapMicroUsd, double softFraction, long windowSeconds);

    /**
     * Correct a reservation to actual cost : pending −=
     * estimate, settled += max(actual, 0), both inside one atomic compute on the
     * <b>reservation's</b> window entry — {@code reservationWindowStart} is the
     * {@link ReserveResult.Allowed#windowStartEpochSeconds} the reserve returned, so
     * settling after a period rollover credits the window the reservation was made
     * against (Core.Budgets note; the straddle-parity pin — the
     * in-memory and Postgres entries are keyed identically, so both land in the same
     * window). Self-registering like {@link #reserve}: a settle on an unknown key
     * creates its entry (the committed total exists from the key's first settle).
     *
     * <p><b>Deliberately not idempotent.</b> Settling one reservation twice
     * commits its actual twice — the {@code pending} clamp protects the cap guard
     * (never a negative pending), but {@code settled} accumulates each call's actual.
     * Exactly-once is the <em>caller's</em> contract: the gateway's per-request
     * {@code settledOrReleased} CAS makes {@code finalize} and {@code release} mutually
     * exclusive paths, so a request settles at most once in the shipping flows. A future
     * gateway path that settles the same request twice (retry, re-entrant finalize)
     * must gate on that CAS, not on the ledger.
     */
    void settle(String keyId, long estimateMicroUsd, long actualMicroUsd, long reservationWindowStart);

    /**
     * Roll an aborted reservation back : pending −=
     * estimate, nothing settles — on the <b>reservation's</b> window entry
     * ({@code reservationWindowStart}, threaded from the reserve's {@link Allowed}).
     * A no-op for unknown keys; releasing an already settled/released reservation is
     * harmless.
     */
    void release(String keyId, long estimateMicroUsd, long reservationWindowStart);

    /**
     * Append one per-request usage record to {@code keyId}'s bounded recent ring
     * (timestamp from the ledger's injected clock). This is the metrics source;
     * the all-time total lives in {@link #settle}, so finalize calls both.
     */
    void recordSpend(String keyId, long amountMicroUsd);
}
