package io.amscotti.janus.gateway;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * — {@link Notifier} decorator that dedups
 * {@code :budget_exceeded} events <b>per key per 60-second window</b> (the same aligned
 * window the {@code SpendLedger} and the rate limiters use): once a key's soft-cap
 * crossing has been dispatched in window N, further soft-crossing events for the same
 * key are suppressed until window N+1. Without this, a key parked over the soft line
 * fires the notifier on <em>every</em> subsequent request (the "spam
 * {@code :budget_exceeded} notifier can be spammed per request for a key parked over
 * the soft line").
 *
 * <p>Semantics:
 *
 * <ul>
 * <li>dedup applies only to {@link #EVENT_BUDGET_EXCEEDED} events carrying a
 * non-null {@code key_id} payload entry — every other event passes through
 * undeduped (future lifecycle events);
 * <li>the window index is {@code floor(nowSeconds / 60)} — aligned with the ledger's
 * {@code WINDOW_SECONDS}, so a key crossing soft at 00:00:59 and again at
 * 00:01:01 fires twice (two windows), while ten requests within one window fire
 * once;
 * <li>the check-and-mark runs inside a single atomic {@code compute} on the key's
 * entry, so concurrent requests for one key cannot double-fire within a window;
 * <li>a clock that moves backward re-fires (a test-clock artifact, never a
 * production concern).
 * </ul>
 *
 * <p>The reservation-time spurious-warning half (a large reservation that
 * settles far below can warn on a request whose actual is near zero) is <b>accepted
 * and documented</b> (the reference-aligned — soft fires on the reserve-time
 * {@code settled + pending} total); the dedup bounds its cost to one notifier event
 * per key per window.
 *
 * <p><b>Prune on key delete.</b> {@link #forgetKey} drops a key's dedup window
 * entry so a deleted-and-recreated key fires {@code :budget_exceeded} again in the
 * same window (the state would otherwise grow unboundedly across key churn with no
 * eviction — growth is bounded by the live key
 * set). {@link AdminKeysController} calls it from {@code revokeAndForget} on a
 * successful {@code POST /key/delete}. A re-fire within the window is the intended
 * behavior after deletion: the key no longer exists, so suppressing a future
 * soft-crossing event for it would only delay the notifier for its next incarnation.
 *
 * <p>Thread-safe. The {@link Clock} is the shared bean (fixed in tests, system in
 * production — the no-real-time discipline).
 */
final class DedupNotifier implements Notifier {

    /** The aligned dedup window length in seconds (the ledger's window). */
    static final long WINDOW_SECONDS = 60;

    private final Notifier delegate;
    private final Clock clock;
    // One Long per live key (the last window its soft-crossing was dispatched in).
    // Never scanned or time-evicted — bounded by the live key set and pruned on key
    // delete via forgetKey, so a key parked over-soft for days holds one Long for its
    // lifetime (the map is deliberately not swept; the live
    // key set is the bound).
    private final ConcurrentMap<String, Long> lastNotifiedWindow = new ConcurrentHashMap<>();

    DedupNotifier(Notifier delegate, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void notify(String event, Map<String, Object> payload) {
        Object keyId = payload == null ? null : payload.get("key_id");
        if (!EVENT_BUDGET_EXCEEDED.equals(event) || keyId == null) {
            delegate.notify(event, payload);
            return;
        }
        long window = Math.floorDiv(clock.millis() / 1000, WINDOW_SECONDS);
        AtomicBoolean fire = new AtomicBoolean();
        lastNotifiedWindow.compute(keyId.toString(), (key, last) -> {
            if (last != null && last == window) {
                fire.set(false);
                return last;
            }
            fire.set(true);
            return window;
        });
        if (fire.get()) {
            delegate.notify(event, payload);
        }
    }

    @Override
    public void forgetKey(String keyId) {
        if (keyId != null) {
            lastNotifiedWindow.remove(keyId);
        }
    }

    /** Package-private accessor for the factory test (asserts the wrapped sink). */
    Notifier delegate() {
        return delegate;
    }

    /**
     * Package-private observability seam: whether {@code keyId} currently has a
     * dedup window entry — the wiring test asserts {@code POST /key/delete} prunes it.
     */
    boolean remembers(String keyId) {
        return lastNotifiedWindow.containsKey(keyId);
    }
}
