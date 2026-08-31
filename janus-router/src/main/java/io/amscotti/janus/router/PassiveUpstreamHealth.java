package io.amscotti.janus.router;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
        state.trialClaimed.set(false); // the claimed trial (if any) is settled by this failure
        if (state.consecutiveFailures.incrementAndGet() >= allowedFails) {
            state.unhealthy.set(true);
            state.probationUntil.set(clock.millis() + cooldownMillis);
        }
    }

    @Override
    public void recordSuccess(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        State state = stateOf(backend);
        state.trialClaimed.set(false); // the claimed trial (if any) is settled by this success
        state.consecutiveFailures.set(0);
        state.unhealthy.set(false);
        state.probationUntil.set(0);
    }

    @Override
    public List<ChatBackend> healthy(List<ChatBackend> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<ChatBackend> healthy = new ArrayList<>(candidates.size());
        long now = clock.millis();
        for (ChatBackend backend : candidates) {
            State state = stateOrNull(backend);
            if (state == null || !state.unhealthy.get() || trialEligible(state, backend, now)) {
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
        if (state == null || !state.unhealthy.get()) {
            return true; // healthy (or never-failed): freely dispatchable, nothing to claim
        }
        long now = clock.millis();
        long deadline = state.probationUntil.get();
        if (deadline > now) {
            return false; // still cooling down, or a concurrent dispatch holds the single trial
        }
        // Claim the trial: atomically extend the probation deadline so only one
        // concurrent dispatch wins — a concurrent record*/release loses the CAS, and a
        // claim still held from an earlier window expired with it (deadline <= now
        // here). The claimed flag marks the slot for {@link #releaseTrial}; the claim
        // is settled by the attempt's terminal outcome — recordSuccess clears it on
        // recovery, recordFailure restarts it, releaseTrial frees it without an outcome.
        if (!state.probationUntil.compareAndSet(deadline, now + cooldownMillis)) {
            return false;
        }
        state.trialClaimed.set(true);
        return true;
    }

    @Override
    public void releaseTrial(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        State state = stateOrNull(backend);
        if (state == null) {
            return;
        }
        // Only a claim this layer actually holds is freed (the flag CAS): a concurrent
        // recordFailure may have re-cooldowned the backend between the dispatch and this
        // release — that genuine cooldown window must survive. Frees the trial without
        // an outcome: the backend stays unhealthy and the next dispatch may claim a
        // fresh trial immediately (mirrors the breaker's releaseProbe on an abandoned
        // stream). Health is a soft filter, so the worst-case interleave here (a
        // release write racing a fresh re-cooldown) costs at most one extra trial
        // attempt, never a lost failure signal.
        if (state.trialClaimed.compareAndSet(true, false)) {
            state.probationUntil.set(clock.millis());
        }
    }

    @Override
    public boolean passivelyHealthy(ChatBackend backend) {
        // The observability answer without the active probe — a /metrics scrape
        // must never trigger probe I/O from the scrape thread. Same passive
        // trial-eligibility semantics as healthy (probe veto deliberately omitted).
        State state = stateOrNull(backend);
        if (state == null || !state.unhealthy.get()) {
            return true;
        }
        return state.probationUntil.get() <= clock.millis();
    }

    /** Pure trial-eligibility for an unhealthy backend: cooldown elapsed and the optional
     * {@link HealthProbe} does not veto. Claims nothing — the trial is claimed at
     * dispatch time by {@link #claimTrial}; a probe veto excludes the candidate without
     * consuming anyone's trial. */
    private boolean trialEligible(State state, ChatBackend backend, long now) {
        if (state.probationUntil.get() > now) {
            return false; // still cooling down, or the single trial is claimed (deadline extended)
        }
        return probe == null || probe.isHealthy(backend);
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

        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicBoolean unhealthy = new AtomicBoolean();
        final AtomicLong probationUntil = new AtomicLong();

        /** Whether a dispatch currently holds the single trial slot (set by
         * {@link #claimTrial}, cleared by the terminal outcome or {@link #releaseTrial}). */
        final AtomicBoolean trialClaimed = new AtomicBoolean();
    }
}
