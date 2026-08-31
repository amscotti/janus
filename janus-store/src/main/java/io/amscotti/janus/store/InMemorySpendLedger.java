package io.amscotti.janus.store;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory {@link SpendLedger}. One {@link WindowEntry} per
 * {@code (keyId, windowStart)} in a {@link ConcurrentHashMap} — the map key mirrors
 * the Postgres primary key, so both backends land a straddled reservation's settle in
 * <b>the same window entry</b> (the straddle-parity contract). Every mutation
 * ({@code reserve}/{@code settle}/{@code release}) runs inside a single atomic
 * {@code compute} on that window entry, so the increment-then-check in
 * {@link #reserve} is race-free — a concurrent burst against a hard cap admits exactly
 * {@code floor((cap − 1) ÷ estimate)} reservations from a zero base and rolls every
 * other one back (no overspend beyond one request, pinned by the concurrency test).
 * The {@code ≥} comparison denies the cap-exact reservation, so cap 1000 / estimate
 * 250 admits 3, not 4.
 *
 * <p><b>Reset windows.</b> {@code windowSeconds == 0} is the <b>lifetime</b> budget:
 * a single window entry at epoch 0 — every pre-window call site maps here unchanged
 * (zero-diff semantics). A positive {@code windowSeconds} derives
 * {@code windowStart = floorDiv(nowSeconds, windowSeconds) × windowSeconds}; a
 * reserve in a newer epoch creates that window's entry with {@code settled = 0}
 * (<b>forward-only</b> rollover — a stepped-back clock keeps the stored window, the
 * {@link FixedWindowRateLimiter} stance), and {@code settle}/{@code release} target
 * the <b>reservation's</b> window ({@code reservationWindowStart} from
 * {@link ReserveResult.Allowed}). A straddled reservation is invisible to the new
 * window's guard — documented limitation, pinned by the suites.
 *
 * <p><b>The all-time accumulator.</b> Per-key state beyond the window entries is one
 * {@link KeyEntry}: the bounded {@code recent} ring (usage records, newest first,
 * retention-configurable) plus {@code allTimeSettled} — the exact all-time committed
 * total behind {@link #totalSpendByKey}. The accumulator is updated on every settle
 * (independent of which window the settle lands in), so pruning window entries
 * (below) never makes the all-time view drift: a pruned window's settled is already
 * folded into the scalar before its entry is dropped.
 *
 * <p><b>Bounded memory.</b> Window entries are pruned to the newest
 * {@value #PRUNE_KEEP_WINDOWS} + 1 windows per key (current + prior, the
 * {@link PgRateLimiter#PRUNE_KEEP_WINDOWS} pattern) by a <b>sampled write-path
 * janitor</b> — one sweep per {@value #PRUNE_INTERVAL} reserves. A racing straddled
 * settle onto a just-pruned window simply re-creates that window's entry (the
 * all-time accumulator is untouched either way; the budget view only ever reads the
 * newest window).
 *
 * <p><b>Units.</b> Integer micro-USD everywhere (1 USD = 1_000_000); {@code settle}
 * clamps both the pending release (a settle that overdraws pending — a racing release
 * or double-settle — stays at 0, mirroring {@code release}) and the
 * actual (max(actual, 0) — a negative actual is a caller bug, never a
 * ledger debt). <b>Settle is deliberately not idempotent</b>: each call adds
 * its actual to {@code settled} — the clamp protects the cap guard, never the committed
 * total; exactly-once is the gateway's caller-side {@code settledOrReleased} CAS (see
 * {@link SpendLedger#settle}).
 *
 * <p>Thread-safe. The {@link Clock} is injected  for ring entry
 * timestamps and window starts; tests pin a fixed/mutable clock, production wires the
 * shared system-clock bean.
 */
public final class InMemorySpendLedger implements SpendLedger {

    /**
     * Retention horizon: window entries older than the newest this-many windows past
     * the current one are pruned (the current window + the last
     * {@value #PRUNE_KEEP_WINDOWS} survive) — mirrors {@link PgRateLimiter}.
     */
    static final int PRUNE_KEEP_WINDOWS = 2;

    /** Sampled janitor: one prune sweep per this many reserves (best-effort). */
    static final int PRUNE_INTERVAL = 1024;

    private final Clock clock;
    private final int retention;
    private final ConcurrentMap<LedgerKey, WindowEntry> ledgers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, KeyEntry> keys = new ConcurrentHashMap<>();
    private final AtomicLong reserves = new AtomicLong();

    public InMemorySpendLedger(Clock clock, int retention) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retention <= 0) {
            throw new IllegalArgumentException("retention must be positive (got " + retention + ")");
        }
        this.retention = retention;
    }

    @Override
    public long spendByKey(String keyId, long windowSeconds) {
        Objects.requireNonNull(keyId, "keyId");
        WindowEntry newest = newestWindow(keyId);
        return newest == null ? 0 : newest.settled();
    }

    @Override
    public long totalSpendByKey(String keyId) {
        Objects.requireNonNull(keyId, "keyId");
        KeyEntry entry = keys.get(keyId);
        return entry == null ? 0 : entry.allTimeSettled();
    }

    @Override
    public List<LedgerEntry> recent(String keyId, int n) {
        Objects.requireNonNull(keyId, "keyId");
        KeyEntry entry = keys.get(keyId);
        if (entry == null || n <= 0) {
            return List.of();
        }
        synchronized (entry.recent()) {
            int count = Math.min(n, entry.recent().size());
            List<LedgerEntry> result = new ArrayList<>(count);
            var it = entry.recent().iterator();
            for (int i = 0; i < count && it.hasNext(); i++) {
                result.add(it.next());
            }
            return Collections.unmodifiableList(result);
        }
    }

    @Override
    public ReserveResult reserve(
            String keyId, long estimateMicroUsd, long hardCapMicroUsd, double softFraction, long windowSeconds) {
        Objects.requireNonNull(keyId, "keyId");
        requireNonNegative(estimateMicroUsd, "estimateMicroUsd");
        if (softFraction < 0 || softFraction > 1) {
            throw new IllegalArgumentException("softFraction must be in [0, 1] (got " + softFraction + ")");
        }
        // Defense-in-depth: clamp the estimate to the cap when a cap applies.
        // An estimate ≥ cap can never be admitted (settled ≥ 0 ⟹ settled + estimate ≥
        // cap ⟹ the increment-then-check below denies), so clamping is outcome-preserving
        // AND it bounds `pending` inside the atomic compute — a saturated estimate
        // (~Long.MAX_VALUE micro-USD, reachable only from a pathologically misconfigured
        // rate) could otherwise wrap the pending counter and flip the overflow-safe
        // admission guard (deny-everything in one direction, admit-over-cap in the
        // mirror). The budget side is already saturated in Governance.toMicroUsd; this
        // clamps the estimate side of the same arithmetic. The no-cap path (cap ≤ 0) has
        // no clamp to lean on, so the pending accumulation itself saturates (see
        // saturatingAdd) — unconditionally, on both paths.
        long estimate = hardCapMicroUsd > 0 && estimateMicroUsd > hardCapMicroUsd ? hardCapMicroUsd : estimateMicroUsd;
        long windowStart = windowStart(windowSeconds);
        LedgerKey ledgerKey = new LedgerKey(keyId, windowStart);
        AtomicReference<ReserveResult> result = new AtomicReference<>();
        ledgers.compute(ledgerKey, (key, entry) -> {
            WindowEntry e = entry == null ? new WindowEntry() : entry;
            if (hardCapMicroUsd <= 0) {
                // No cap (null budget ⇒ the gateway skips; defensive: ≤ 0 means "no cap").
                // The saturating add is unconditional: without a cap there is no clamp to
                // bound the estimate, so a saturated estimate followed by any second
                // reserve would wrap `pending` negative — corrupting the counter a later
                // capped reserve's admission guard reads (a negative pending loosens the
                // hard cap). Saturate at Long.MAX_VALUE instead.
                e.pending = saturatingAdd(e.pending, estimate);
                result.set(new ReserveResult.Allowed(false, e.settled, e.pending, windowStart));
                return e;
            }
            long pendingBefore = e.pending;
            e.pending = saturatingAdd(pendingBefore, estimate); // increment first…
            // Overflow-safe check: settled + pending >= cap ⟺ settled >= cap − pending.
            // The rewritten form never sums two huge longs into a wrapped negative total —
            // a wraparound would flip the guard and admit an over-cap reservation (the
            // in-memory behavior; the Postgres store errors loudly instead). A saturated
            // pending (Long.MAX_VALUE) makes cap − pending ≤ 0 ≤ settled ⇒ deny, so the
            // saturation can never wedge the guard open.
            if (e.settled >= hardCapMicroUsd - e.pending) {
                e.pending = pendingBefore; // …then check; on crossing, roll back to the exact pre-increment value (a
                // saturated increment rolls back exactly too)
                result.set(new ReserveResult.Denied(e.settled, e.pending));
                return e;
            }
            long softCap = (long) Math.floor(hardCapMicroUsd * softFraction);
            boolean soft = e.settled >= softCap - e.pending;
            result.set(new ReserveResult.Allowed(soft, e.settled, e.pending, windowStart));
            return e;
        });
        maybePruneStaleWindows();
        return result.get();
    }

    @Override
    public void settle(String keyId, long estimateMicroUsd, long actualMicroUsd, long reservationWindowStart) {
        Objects.requireNonNull(keyId, "keyId");
        requireNonNegative(estimateMicroUsd, "estimateMicroUsd");
        long actual = Math.max(actualMicroUsd, 0);
        // The all-time accumulator grows on EVERY settle, whichever window the
        // reservation landed in — pruning window entries can never make
        // totalSpendByKey drift (a pruned window's settled is folded in here first).
        keys.compute(keyId, (key, entry) -> {
            KeyEntry k = entry == null ? new KeyEntry() : entry;
            k.allTimeSettled = saturatingAdd(k.allTimeSettled, actual);
            return k;
        });
        ledgers.compute(new LedgerKey(keyId, reservationWindowStart), (key, entry) -> {
            // Self-registering like reserve (shard creates the counter on
            // first use): a key's committed total exists from its first settle.
            WindowEntry e = entry == null ? new WindowEntry() : entry;
            // Clamp at 0 like release: a settle that overdraws pending — a
            // release that already returned the estimate, or a double-settle — must
            // never drive pending negative (a negative pending would loosen the hard
            // cap's settled + pending ≥ cap admission check).
            e.pending = Math.max(0, e.pending - estimateMicroUsd);
            e.settled = saturatingAdd(e.settled, actual);
            return e;
        });
    }

    @Override
    public void release(String keyId, long estimateMicroUsd, long reservationWindowStart) {
        Objects.requireNonNull(keyId, "keyId");
        requireNonNegative(estimateMicroUsd, "estimateMicroUsd");
        ledgers.compute(new LedgerKey(keyId, reservationWindowStart), (key, entry) -> {
            if (entry == null) {
                return null; // releasing a never-reserved window is a harmless no-op
            }
            // Clamp at 0: a release cannot undo more than was reserved (the gateway's
            // stream wrap CASes settle-vs-release, but a racing close must not drive
            // the pending counter negative).
            entry.pending = Math.max(0, entry.pending - estimateMicroUsd);
            return entry;
        });
    }

    @Override
    public void recordSpend(String keyId, long amountMicroUsd) {
        Objects.requireNonNull(keyId, "keyId");
        requireNonNegative(amountMicroUsd, "amountMicroUsd");
        keys.compute(keyId, (key, entry) -> {
            KeyEntry e = entry == null ? new KeyEntry() : entry;
            // Writers synchronize on the deque exactly like readers (recent): the
            // CHM compute serializes writers per key but not readers.
            synchronized (e.recent) {
                e.recent.addFirst(new LedgerEntry(clock.millis(), amountMicroUsd));
                while (e.recent.size() > retention) {
                    e.recent.removeLast();
                }
            }
            return e;
        });
    }

    /**
     * Remove each key's window entries beyond the newest {@value #PRUNE_KEEP_WINDOWS}
     * + 1 (current + prior windows — the {@code PgRateLimiter} retention pattern).
     * Package-private for the tests (deterministic prune assertions); the sampled
     * {@link #maybePruneStaleWindows} calls it on the reserve path. All-time
     * accuracy is unaffected: {@link #totalSpendByKey} reads the per-key accumulator,
     * never the window entries.
     */
    void pruneStaleWindows() {
        Map<String, List<Long>> windowsByKeys = new HashMap<>();
        for (LedgerKey key : ledgers.keySet()) {
            windowsByKeys
                    .computeIfAbsent(key.keyId(), ignored -> new ArrayList<>())
                    .add(key.windowStartSeconds());
        }
        for (Map.Entry<String, List<Long>> entry : windowsByKeys.entrySet()) {
            List<Long> windows = entry.getValue();
            windows.sort(Collections.reverseOrder());
            for (int i = PRUNE_KEEP_WINDOWS + 1; i < windows.size(); i++) {
                ledgers.remove(new LedgerKey(entry.getKey(), windows.get(i)));
            }
        }
    }

    private void maybePruneStaleWindows() {
        if (reserves.incrementAndGet() % PRUNE_INTERVAL == 0) {
            pruneStaleWindows();
        }
    }

    /** The window start for {@code windowSeconds} (0 ⇒ the single lifetime window, epoch 0). */
    private long windowStart(long windowSeconds) {
        if (windowSeconds <= 0) {
            return 0;
        }
        long nowSeconds = clock.millis() / 1000;
        return Math.floorDiv(nowSeconds, windowSeconds) * windowSeconds;
    }

    /**
     * The key's newest window entry (the budget view's row), or null when the key has none.
     * The full-map scan is O(total entries), not O(this key's) — acceptable because the
     * post-prune domain is bounded (≤ {@value #PRUNE_KEEP_WINDOWS} + 1 surviving windows
     * per key, plus any not-yet-swept windows between sampled janitor runs); a per-key
     * window index is the upgrade path if key counts ever make the scan hot.
     */
    private WindowEntry newestWindow(String keyId) {
        WindowEntry newest = null;
        long newestStart = Long.MIN_VALUE;
        for (Map.Entry<LedgerKey, WindowEntry> entry : ledgers.entrySet()) {
            LedgerKey key = entry.getKey();
            if (key.keyId().equals(keyId) && key.windowStartSeconds() > newestStart) {
                newestStart = key.windowStartSeconds();
                newest = entry.getValue();
            }
        }
        return newest;
    }

    /** Package-private observability seam for the concurrency/rollback tests (the newest window's pending). */
    long pending(String keyId) {
        Objects.requireNonNull(keyId, "keyId");
        WindowEntry newest = newestWindow(keyId);
        return newest == null ? 0 : newest.pending();
    }

    /**
     * Live window-entry count for one key (package-private test accessor — the prune pin).
     * Like {@link #newestWindow}, this scans the full map — the bounded post-prune domain
     * (≤ {@value #PRUNE_KEEP_WINDOWS} + 1 windows per key) keeps it cheap; a per-key index
     * is the future option.
     */
    int windowCount(String keyId) {
        Objects.requireNonNull(keyId, "keyId");
        int count = 0;
        for (LedgerKey key : ledgers.keySet()) {
            if (key.keyId().equals(keyId)) {
                count++;
            }
        }
        return count;
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative (got " + value + ")");
        }
    }

    /**
     * {@code a + b} saturated at {@link Long#MAX_VALUE}, never wrapped. Both inputs are
     * non-negative here (the estimate is guarded ≥ 0; {@code settled}/{@code pending}
     * start at 0, move only through this saturating add and are clamped at 0 by
     * {@code settle}/{@code release}), so the only overflow is positive — a wrapped
     * negative would corrupt both the no-cap counter and the capped path's
     * {@code settled >= cap − pending} admission read. The cap-side clamp bounds the
     * estimate; this bounds the sum, unconditionally (both reserve paths).
     */
    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        // Same sign in both inputs and the sum ⇒ no wrap; a sign flip ⇒ overflow.
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /**
     * The map key mirroring the Postgres {@code spend} primary key — one ledger entry
     * per {@code (keyId, windowStart)} so a straddled reservation's settle lands in
     * the same window on both backends (parity by construction).
     */
    record LedgerKey(String keyId, long windowStartSeconds) {}

    /**
     * One window's ledger entry. Scalar fields are {@code volatile}: CHM publication
     * covers only initial publication, not later in-place writes inside
     * {@code compute}; package-private accessors keep the test seam honest.
     */
    static final class WindowEntry {
        private volatile long settled;
        private volatile long pending;

        long settled() {
            return settled;
        }

        long pending() {
            return pending;
        }
    }

    /**
     * One key's window-independent state: the all-time settled accumulator
     * ({@link #totalSpendByKey} — exact across pruned windows) and the bounded recent
     * ring, accessed under its own monitor in {@link #recent}.
     */
    static final class KeyEntry {
        private volatile long allTimeSettled;
        private final ArrayDeque<LedgerEntry> recent = new ArrayDeque<>();

        long allTimeSettled() {
            return allTimeSettled;
        }

        ArrayDeque<LedgerEntry> recent() {
            return recent;
        }
    }
}
