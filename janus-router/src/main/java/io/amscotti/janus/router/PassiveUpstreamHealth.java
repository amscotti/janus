package io.amscotti.janus.router;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Standard {@link UpstreamHealth} implementation: passive
 * consecutive-failure tracking with lazy cooldown probation. Config keys 's
 * {@code [router]} TOML binds: {@code allowed_fails}, {@code cooldown_time}.
 *
 * <p><b>Semantics.</b> {@link #recordFailure} increments a per-backend consecutive-failure
 * counter; at {@code allowedFails} consecutive failures the backend flips unhealthy and
 * gets a probation deadline of {@code now + cooldownMillis}. {@link #recordSuccess}
 * resets the counter and clears the unhealthy state (passive recovery). An unhealthy
 * backend whose cooldown has elapsed is eligible for one trial attempt —
 * {@link #healthy(List)} admits it again (consulting the optional {@link HealthProbe}
 * first); a trial failure re-cooldowns it, a trial success recovers it. The trial is
 * claimed atomically <b>at dispatch time</b> ({@link #claimTrial}, on the picked backend
 * only — the same canTry/claimProbe decoupling the {@link CircuitBreaker} uses), so a
 * burst of concurrent dispatches after cooldown sends at most one request to the
 * still-degraded backend, while an admitted-but-unpicked candidate keeps its trial (a
 * load-balancer strategy that keeps preferring another backend cannot starve the
 * degraded one out of its recovery probes). The claim is settled by the attempt's
 * terminal outcome — {@code recordSuccess} recovers, {@code recordFailure} re-cooldowns,
 * and a terminal outcome that is neither (a non-retryable client error, a stream
 * abandoned before its first chunk) is freed by {@link #releaseTrial} without an
 * outcome, mirroring the breaker's {@code releaseProbe}. Cooldown is
 * <b>lazy</b>: the TTL is checked when candidates are filtered, no scheduler thread.
 *
 * <p><b>Keying.</b> State is keyed by backend <i>identity</i> (same rule as the
 * strategies): one singleton backend serving several aliases shares one health record.
 * Thread-safe: per-backend counters are atomic and the state map is identity-keyed under
 * a monitor guarding <b>both</b> reads ({@link #healthy}) and writes ({@code record*}),
 * so concurrent filters and records never observe torn state or lose updates.
 *
 * <p>Fail-fast construction: {@code allowedFails < 1} and {@code cooldownMillis < 0} are
 * rejected up front. Fail-open filtering: {@link #healthy(List)} returns the full input
 * when the healthy subset is empty.
 */
public final class PassiveUpstreamHealth implements UpstreamHealth {

    private final int allowedFails;
    private final long cooldownMillis;
    private final Clock clock;
    private final HealthProbe probe;
    private final Map<ChatBackend, State> states;

    public PassiveUpstreamHealth(int allowedFails, long cooldownMillis) {
        this(allowedFails, cooldownMillis, Clock.systemUTC(), null);
    }

    public PassiveUpstreamHealth(int allowedFails, long cooldownMillis, Clock clock) {
        this(allowedFails, cooldownMillis, clock, null);
    }

    public PassiveUpstreamHealth(int allowedFails, long cooldownMillis, Clock clock, HealthProbe probe) {
        if (allowedFails < 1) {
            throw new IllegalArgumentException("allowedFails must be >= 1: " + allowedFails);
        }
        if (cooldownMillis < 0) {
            throw new IllegalArgumentException("cooldownMillis must be >= 0: " + cooldownMillis);
        }
        this.allowedFails = allowedFails;
        this.cooldownMillis = cooldownMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.probe = probe;
        this.states = Collections.synchronizedMap(new IdentityHashMap<>());
    }

    @Override
    public void recordFailure(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        State state = stateOf(backend);
        synchronized (state) {
            state.trialClaimed = false; // the claimed trial (if any) is settled by this failure
            if (state.consecutiveFailures < allowedFails) {
                state.consecutiveFailures++;
            }
            if (state.consecutiveFailures >= allowedFails) {
                state.unhealthy = true;
                state.probationUntil = clock.millis() + cooldownMillis;
            }
        }
    }

    @Override
    public void recordSuccess(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        State state = stateOf(backend);
        synchronized (state) {
            state.trialClaimed = false; // the claimed trial (if any) is settled by this success
            state.consecutiveFailures = 0;
            state.unhealthy = false;
            state.probationUntil = 0;
        }
    }

    @Override
    public List<ChatBackend> healthy(List<ChatBackend> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<ChatBackend> healthy = new ArrayList<>(candidates.size());
        long now = clock.millis();
        for (ChatBackend backend : candidates) {
            State state = stateOrNull(backend);
            boolean admit = false;
            boolean checkProbe = false;
            if (state == null) {
                admit = true;
            } else {
                synchronized (state) {
                    if (!state.unhealthy) {
                        admit = true;
                    } else if (state.probationUntil <= now) {
                        checkProbe = true;
                    }
                }
            }
            if (checkProbe) {
                admit = probe == null || probe.isHealthy(backend);
            }
            if (admit) {
                healthy.add(backend);
            }
        }
        if (healthy.isEmpty() && !candidates.isEmpty()) {
            // Fail-open: an all-unhealthy upstream set must not hard-fail the request.
            return candidates;
        }
        return healthy;
    }

    @Override
    public boolean claimTrial(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        State state = stateOrNull(backend);
        if (state == null) {
            return true; // healthy (or never-failed): freely dispatchable, nothing to claim
        }
        synchronized (state) {
            if (!state.unhealthy) {
                return true;
            }
            long now = clock.millis();
            if (state.probationUntil > now) {
                return false; // still cooling down, or a concurrent dispatch holds the single trial
            }
            // Claim the trial: extend the probation deadline and mark trialClaimed atomically
            // under the per-backend state monitor so concurrent dispatches and outcomes never race.
            state.probationUntil = now + cooldownMillis;
            state.trialClaimed = true;
            return true;
        }
    }

    @Override
    public void releaseTrial(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        State state = stateOrNull(backend);
        if (state == null) {
            return;
        }
        // Only a claim this layer actually holds is freed: a concurrent recordFailure
        // clears trialClaimed and sets a genuine cooldown window that survives under lock.
        synchronized (state) {
            if (state.trialClaimed) {
                state.trialClaimed = false;
                state.probationUntil = clock.millis();
            }
        }
    }

    @Override
    public boolean passivelyHealthy(ChatBackend backend) {
        // The observability answer without the active probe — a /metrics scrape
        // must never trigger probe I/O from the scrape thread. Same passive
        // trial-eligibility semantics as healthy (probe veto deliberately omitted).
        State state = stateOrNull(backend);
        if (state == null) {
            return true;
        }
        synchronized (state) {
            if (!state.unhealthy) {
                return true;
            }
            return state.probationUntil <= clock.millis();
        }
    }

    private State stateOf(ChatBackend backend) {
        synchronized (states) {
            State state = states.get(backend);
            if (state == null) {
                state = new State();
                states.put(backend, state);
            }
            return state;
        }
    }

    /** Read-only map access, under the same monitor as {@link #stateOf} (m1): a
     * concurrent {@code record*} may be structurally modifying the map (new-backend
     * {@code put} or resize), so the filter path must not touch it unsynchronized. */
    private State stateOrNull(ChatBackend backend) {
        synchronized (states) {
            return states.get(backend);
        }
    }

    private static final class State {

        int consecutiveFailures;
        boolean unhealthy;
        long probationUntil;

        /** Whether a dispatch currently holds the single trial slot (set by
         * {@link #claimTrial}, cleared by the terminal outcome or {@link #releaseTrial}). */
        boolean trialClaimed;
    }
}
