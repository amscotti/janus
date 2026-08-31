package io.amscotti.janus.router;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * per-upstream circuit breaker: a hard, explicit
 * closed → open → half-open state machine keyed by {@link ChatBackend} <b>identity</b>
 * (same rule as the strategies and health: one singleton backend serving several
 * aliases shares one breaker entry; backends must not override {@code equals}/{@code
 * hashCode}). The breaker <i>refuses</i> dispatch (except its single half-open probe)
 * while {@link UpstreamHealth} <i>ranks</i> candidates — two deliberately
 * orthogonal mechanisms consuming the same per-attempt failure events.
 *
 * <p><b>State machine.</b> CLOSED → OPEN at {@code failureThreshold} failures within a
 * rolling {@code window}; OPEN denies {@link #canTry} until the cooldown elapses, then
 * admits <b>exactly one</b> half-open probe; probe success → CLOSED (counter reset),
 * probe failure → OPEN with a fresh cooldown.
 *
 * <p><b>Check vs claim are decoupled (no probe leak).</b> {@link #canTry} is a pure
 * gate — it never mutates state — and {@link #claimProbe} is the atomic dispatch-time
 * claim, invoked only on the backend the router actually dispatches to. Filtering
 * candidates never leaks a claim onto a candidate the load balancer does not pick: a
 * cooldown-elapsed OPEN backend that is gated but not picked stays OPEN on its original
 * schedule and is dispatched by a later request. {@link #claimProbe} admits OPEN
 * backends regardless of cooldown — the router's normal path only routes cooldown-elapsed
 * OPEN backends to it (the {@link #canTry} gate filters the rest), while the all-open
 * fail-open probe deliberately dispatches one request to a cooldown-pending OPEN backend
 * (documented divergence from fail-fast, re-checked by the chaos drill); that
 * probe's failure re-trips the breaker with a <b>fresh</b> cooldown, and its success
 * recovers it — the plan's "probe outcome re-trips or recovers" holds for fail-open too.
 *
 * <p><b>Two failure signals.</b> Connection-attempt failures
 * ({@link #recordConnectFailure}) and stream failures before the first chunk
 * ({@link #recordStreamFailure} with {@code beforeFirstChunk = true}) both increment the
 * same per-backend counter — a provider that connects but produces junk streams still
 * trips. A stream failure after partial delivery ({@code beforeFirstChunk = false}) is
 * transient — it never counts — but it <i>does</i> release a claimed half-open probe
 * (deliberate divergence from the reference implementation semantics, which leaks the probe). The probe
 * slot is released on <b>any</b> terminal outcome: failure (either signal), success, or
 * an abandoned stream ({@link #releaseProbe}).
 *
 * <p><b>Streaming-safe.</b> {@link #canTry} and {@link #claimProbe} are consulted only
 * at dispatch time; recording happens at connect time or at chunk boundaries inside the
 * router's stream wrap — the breaker never aborts an open stream, and state changes only
 * affect <i>subsequent</i> requests.
 *
 * <p><b>Implementation.</b> Per-backend {@link Entry} in a {@link ConcurrentHashMap};
 * every transition is atomic via {@code compute} (no locks, no scheduler). The rolling
 * window and cooldown are checked lazily against an injectable {@link Clock} (default
 * system clock) so tests drive them without sleeping. Thread-safe; native-image safe
 * (JDK-only types, no reflection).
 */
public final class CircuitBreaker {

    /** Breaker state per backend. */
    public enum State {
        /** Normal operation: dispatch freely. */
        CLOSED,
        /** Refusing dispatch until the cooldown elapses. */
        OPEN,
        /** Cooldown elapsed: exactly one probe may be in flight. */
        HALF_OPEN
    }

    // The disabled singleton never consults the clock; a fixed dummy keeps the
    // constructor argument honest.
    private static final CircuitBreaker DISABLED =
            new CircuitBreaker(0, 0, 0, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    private final int failureThreshold;
    private final long windowMillis;
    private final long cooldownMillis;
    private final Clock clock;
    private final ConcurrentHashMap<ChatBackend, Entry> entries;

    private CircuitBreaker(int failureThreshold, long windowMillis, long cooldownMillis, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.windowMillis = windowMillis;
        this.cooldownMillis = cooldownMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entries = new ConcurrentHashMap<>();
    }

    /**
     * Create a breaker from validated config (system clock). Any config whose
     * {@code failureThreshold} is {@code 0} — the {@link CircuitBreakerConfig#disabled}
     * sentinel and the operator-facing "threshold 0 disables the breaker" form — maps to
     * the {@link #disabled} singleton.
     */
    public static CircuitBreaker create(CircuitBreakerConfig config) {
        return create(config, Clock.systemUTC());
    }

    /** Test seam: inject the clock driving the rolling window and cooldown. */
    public static CircuitBreaker create(CircuitBreakerConfig config, Clock clock) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        if (config.failureThreshold() == 0) {
            return DISABLED; // CircuitBreakerConfig.disabled() sentinel
        }
        return new CircuitBreaker(
                config.failureThreshold(),
                config.window().toMillis(),
                config.cooldown().toMillis(),
                clock);
    }

    /**
     * No-op singleton: {@link #canTry} and {@link #claimProbe} always true, all record
     * hooks and {@link #releaseProbe} do nothing, {@link #state} is always
     * {@link State#CLOSED}. The 3-arg
     * {@link Router#resilient(Map, LoadBalancer, ResilienceConfig)} path runs on this
     * instance, preserving the balanced behavior byte-for-byte.
     */
    public static CircuitBreaker disabled() {
        return DISABLED;
    }

    /**
     * Current {@link State} of {@code backend} (diagnostics/tests; backends with no
     * recorded activity are {@link State#CLOSED}).
     */
    public State state(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        if (failureThreshold == 0) {
            return State.CLOSED;
        }
        Entry entry = entries.get(backend);
        return entry == null ? State.CLOSED : entry.state;
    }

    /**
     * Pure dispatch gate (no side effects): whether {@code backend} may be routed on the
     * normal path right now. CLOSED → {@code true}; OPEN → {@code true} only after the
     * cooldown has elapsed (the half-open probe is admitted by {@link #claimProbe}, not
     * here); HALF_OPEN → {@code true} only while the single probe slot is free (a
     * concurrent in-flight probe denies). The router filters candidates through this
     * gate, then atomically claims the <i>dispatched</i> backend via {@link #claimProbe}
     * — the check never claims, so candidates the load balancer does not pick are never
     * wedged by a leaked claim (no half-open probe leak). No {@link Entry} is created for
     * never-failed backends.
     */
    public boolean canTry(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        if (failureThreshold == 0) {
            return true;
        }
        Entry entry = entries.get(backend);
        if (entry == null) {
            return true; // never-failed backend: CLOSED
        }
        long now = clock.millis();
        return switch (entry.state) {
            case CLOSED -> true;
            case OPEN -> now - entry.openedAtMillis >= cooldownMillis;
            case HALF_OPEN -> !entry.probeClaimed;
        };
    }

    /**
     * Dispatch-time claim: atomically claims the single probe slot on {@code backend}
     * and returns {@code true} iff the backend may receive a dispatch right now. This is
     * the only place a probe is admitted — with {@link #canTry} a pure gate, the check
     * and the claim are decoupled, so filtering never leaks a claim onto a candidate the
     * load balancer does not pick. CLOSED → {@code true} (no slot needed); OPEN → claims
     * the probe (state → HALF_OPEN) regardless of cooldown — the router's normal path
     * only reaches OPEN via the {@link #canTry} gate (cooldown elapsed), while the
     * all-open fail-open probe deliberately dispatches to a cooldown-pending OPEN backend
     * (documented divergence, re-checked by the chaos drill); HALF_OPEN → {@code true} only while
     * the slot is free (CAS claim), {@code false} for a concurrent in-flight probe — the
     * router re-picks (bounded) instead of double-dispatching onto a busy slot. Every
     * claim is released by the terminal outcome of the dispatched request
     * ({@link #recordSuccess}, {@link #recordConnectFailure},
     * {@link #recordStreamFailure} or {@link #releaseProbe}).
     */
    public boolean claimProbe(ChatBackend backend) {
        // Back-compat form: the router's all-open fail-open path (and the tests) —
        // a cooldown-pending OPEN backend may claim (the documented divergence).
        return claimProbe(backend, true);
    }

    /**
     * Timing window between gate and claim: {@link #canTry} and this claim are
     * decoupled, so a backend that was CLOSED at filter time can be OPEN by claim
     * time (a concurrent failure trips it in between). The explicit {@code failOpen}
     * flag distinguishes the two callers: {@code true} ONLY for the all-open fallback
     * (all candidates breaker-blocked — dispatching beats failing); the normal path
     * requires the cooldown to have genuinely elapsed.
     */
    public boolean claimProbe(ChatBackend backend, boolean failOpen) {
        Objects.requireNonNull(backend, "backend");
        if (failureThreshold == 0) {
            return true;
        }
        AtomicBoolean admitted = new AtomicBoolean();
        // Every admission decision is atomic under the per-key bin lock: the absent-entry
        // (never-failed, CLOSED) case is decided here, not by a get-then-compute
        // 4). Returning null keeps the map bounded by the backends that have actually failed
        // — no entry is materialized for never-failed backends. This removes the TOCTOU
        // where a concurrent recordFailure between a get and the compute could deny a claim
        // that was legitimate at read time.
        entries.compute(backend, (key, entry) -> {
            if (entry == null) {
                admitted.set(true); // never-failed backend: CLOSED, no slot to claim
                return null;
            }
            switch (entry.state) {
                case CLOSED -> admitted.set(true);
                case OPEN -> {
                    long now = clock.millis();
                    boolean cooldownElapsed = now - entry.openedAtMillis >= cooldownMillis;
                    if (cooldownElapsed || failOpen) {
                        // Cooldown genuinely elapsed (the canTry-gated normal path), or
                        // the router's all-open fail-open fallback (every candidate
                        // breaker-blocked — dispatching beats failing; the documented
                        // divergence, re-checked by the chaos drill). Otherwise — the
                        // TOCTOU case, OPEN only because a concurrent failure tripped it
                        // after canTry said CLOSED — the claim is REFUSED and the state
                        // is left untouched: the cooldown holds.
                        entry.state = State.HALF_OPEN;
                        entry.probeClaimed = true;
                        admitted.set(true);
                    }
                }
                case HALF_OPEN -> {
                    if (!entry.probeClaimed) {
                        entry.probeClaimed = true;
                        admitted.set(true);
                    }
                }
            }
            return entry;
        });
        return admitted.get();
    }

    /**
     * Signal 1 — a connection-attempt failure (nothing was delivered): {@code complete}
     * threw or {@code stream} threw at connect. Counts against the threshold.
     */
    public void recordConnectFailure(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        if (failureThreshold == 0) {
            return;
        }
        recordFailure(backend);
    }

    /**
     * Signal 2 — a stream terminated in failure. {@code beforeFirstChunk = true} counts
     * against the threshold; {@code false} (failure after partial delivery) is a
     * transient no-op on the counter, but releases a claimed half-open probe so the
     * state machine cannot deadlock.
     */
    public void recordStreamFailure(ChatBackend backend, boolean beforeFirstChunk) {
        Objects.requireNonNull(backend, "backend");
        if (failureThreshold == 0) {
            return;
        }
        if (beforeFirstChunk) {
            recordFailure(backend);
        } else {
            freeProbeIfHalfOpen(backend); // transient failure: free the probe slot
        }
    }

    /**
     * A probe success — the half-open probe (or the all-open fail-open probe, which also
     * claims through {@link #claimProbe}) — closes the breaker and clears the counter
     * (fresh rolling window). A plain success on a CLOSED backend leaves the rolling-window
     * failure count intact: the breaker opens at {@code failureThreshold}
     * failures *within the window*, so an intermittently-degraded backend serving
     * F,S,F,S,… must still trip once the windowed count crosses the threshold — resetting on
     * every success would collapse the documented window semantics into a consecutive-failure
     * threshold.
     */
    public void recordSuccess(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        if (failureThreshold == 0) {
            return;
        }
        entries.compute(backend, (key, entry) -> {
            if (entry == null) {
                return null;
            }
            if (entry.state == State.HALF_OPEN || entry.openedAtMillis != 0) {
                // Probe recovery (HALF_OPEN, or OPEN carrying a non-zero open timestamp) —
                // the only success that closes the breaker and starts a fresh window.
                entry.failures = 0;
                entry.windowStartMillis = 0;
                entry.openedAtMillis = 0;
                entry.state = State.CLOSED;
                entry.probeClaimed = false;
            }
            return entry;
        });
    }

    /**
     * Frees the half-open probe slot on an abandoned stream (early close with no
     * exception — client disconnect). Records no success/failure; the state stays
     * HALF_OPEN so the next {@link #claimProbe} re-claims the probe.
     */
    public void releaseProbe(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        if (failureThreshold == 0) {
            return;
        }
        freeProbeIfHalfOpen(backend);
    }

    /** Shared counter path for signal 1 and signal 2 (before the first chunk). */
    private void recordFailure(ChatBackend backend) {
        entries.compute(backend, (key, entry) -> {
            Entry e = entry == null ? new Entry() : entry;
            long now = clock.millis();
            if (e.state == State.HALF_OPEN) {
                // Probe failure — the half-open probe, including the all-open fail-open
                // probe (its dispatch-time claim moved OPEN → HALF_OPEN first): re-open
                // with a fresh cooldown (the probe re-trips).
                e.state = State.OPEN;
                e.openedAtMillis = now;
                e.probeClaimed = false;
                return e;
            }
            if (e.state == State.CLOSED) {
                if (now - e.windowStartMillis >= windowMillis) {
                    // Rolling window expired: start a fresh window at 1.
                    e.failures = 1;
                    e.windowStartMillis = now;
                } else {
                    e.failures++;
                }
                if (e.failures >= failureThreshold) {
                    e.state = State.OPEN;
                    e.openedAtMillis = now;
                    e.probeClaimed = false;
                }
            }
            // OPEN: no-op — the cooldown keeps running from the original open. The
            // router never reaches this branch (every dispatch claims first, so a
            // router-driven failure lands on HALF_OPEN and re-opens with a fresh
            // cooldown); direct API calls against an OPEN entry are ignored by design.
            return e;
        });
    }

    /**
     * Frees a claimed half-open probe slot (transient stream failure or abandoned
     * stream); a no-op for every other state.
     */
    private void freeProbeIfHalfOpen(ChatBackend backend) {
        entries.compute(backend, (key, entry) -> {
            if (entry == null) {
                return null;
            }
            if (entry.state == State.HALF_OPEN) {
                entry.probeClaimed = false;
            }
            return entry;
        });
    }

    /**
     * Per-backend state; mutated only inside {@code entries.compute} (per-key atomic).
     * {@code openedAtMillis} and {@code probeClaimed} are volatile so the lock-free
     * {@link #canTry} gate reads coherent values (a stale gate read is harmless — the
     * dispatch-time {@link #claimProbe} is authoritative). Entries are created only by
     * failure recording, so the map stays bounded by the number of backends that have
     * actually failed.
     */
    private static final class Entry {

        int failures;
        long windowStartMillis;
        volatile long openedAtMillis;
        volatile State state = State.CLOSED;
        volatile boolean probeClaimed;
    }
}
