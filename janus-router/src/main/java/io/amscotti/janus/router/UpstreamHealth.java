package io.amscotti.janus.router;

import java.util.List;
import java.util.Objects;

/**
 * Per-upstream health state: passive, consecutive-failure
 * tracking keyed by backend <b>identity</b>, consumed by the resilient {@link Router} to
 * filter candidates per attempt. The standard implementation is
 * {@link PassiveUpstreamHealth}; this interface is the seam the router and the circuit
 * breaker consume (the breaker ports {@link #recordFailure} signals).
 *
 * <p><b>Contract.</b>
 *
 * <ul>
 * <li>{@link #recordFailure(ChatBackend)} feeds one failed attempt; the implementation
 * flips the backend unhealthy at its {@code allowedFails} consecutive failures.
 * <li>{@link #recordSuccess(ChatBackend)} resets the counter and clears the unhealthy
 * state (passive recovery).
 * <li>{@link #healthy(List)} filters the candidate list down to the healthy subset,
 * applying cooldown probation and the optional active {@link HealthProbe} seam.
 * It is a <b>pure eligibility filter — it never claims anything</b>: the single trial
 * attempt for a cooldown-elapsed unhealthy backend is claimed at <i>dispatch time</i> via
 * {@link #claimTrial(ChatBackend)} on the backend actually picked (mirroring the circuit
 * breaker's {@code canTry}/{@code claimProbe} decoupling), so an admitted-but-unpicked
 * candidate does not burn its trial. <b>Fail-open:</b> an empty healthy subset returns
 * the full input list — stale health state must never hard-fail a request (the
 * "all-upstreams-down policy"; re-checked by the chaos drill).
 * </ul>
 *
 * <p>Disabled instances ({@link #disabled}) are no-ops: {@code healthy} returns the
 * input unchanged, record hooks do nothing — this is the -identical
 * {@link ResilienceConfig#none} wiring.
 */
public interface UpstreamHealth {

    /** Record one failed attempt on {@code backend}. */
    void recordFailure(ChatBackend backend);

    /** Record one successful attempt on {@code backend} (passive recovery). */
    void recordSuccess(ChatBackend backend);

    /**
     * Healthy subset of {@code candidates} (config order preserved). A <b>pure
     * eligibility filter</b>: an unhealthy backend whose cooldown has elapsed is admitted
     * as trial-eligible without claiming anything — the trial is claimed on the picked
     * backend at dispatch time via {@link #claimTrial}. Fail-open: empty subset → returns
     * the full input list. A no-op instance returns the input unchanged.
     */
    List<ChatBackend> healthy(List<ChatBackend> candidates);

    /**
     * Dispatch-time claim (mirrors the breaker's {@code claimProbe}, paired with the
     * pure {@link #healthy} gate the same way {@code canTry} pairs with the probe claim):
     * atomically claims the single trial attempt for a cooldown-elapsed unhealthy
     * {@code backend} and returns {@code true} iff the backend may receive a dispatch
     * right now. Healthy/never-failed backends are freely dispatchable ({@code true},
     * nothing claimed); an unhealthy backend still cooling down — or whose single trial
     * a concurrent dispatch already claimed — returns {@code false}, and the caller
     * re-picks instead of double-dispatching onto the claimed trial. The claim is settled
     * by the attempt's terminal outcome: {@link #recordSuccess} recovers the backend,
     * {@link #recordFailure} re-cooldowns it, and a terminal outcome that is neither (a
     * non-retryable client error, an abandoned stream) frees the slot via
     * {@link #releaseTrial} without an outcome. The default is a free pass (no trial
     * discipline) for no-op and custom implementations.
     */
    default boolean claimTrial(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
        return true;
    }

    /**
     * Releases a claimed {@link #claimTrial} slot on a terminal outcome that is
     * <b>neither</b> a trial success <b>nor</b> a trial failure — the two documented
     * cases where neither {@link #recordSuccess} nor {@link #recordFailure} fires: a
     * <b>non-retryable</b> failure (client 4xx/auth — the client's fault, never an
     * upstream degradation, so it must neither recover nor re-cooldown the backend) and
     * an <b>abandoned stream</b> (closed before its first chunk was consumed — no
     * outcome at all). Mirrors {@code CircuitBreaker#releaseProbe}: records nothing,
     * leaves the unhealthy state intact, and makes the single-trial slot claimable
     * again immediately instead of letting the claim expire at the end of a full extra
     * cooldown window. A no-op by default (nothing to release for no-op and custom
     * implementations).
     */
    default void releaseTrial(ChatBackend backend) {
        Objects.requireNonNull(backend, "backend");
    }

    /**
     * Dispatch-eligibility of {@code backend} from <b>passive state only</b>: the
     * optional active {@link HealthProbe} is deliberately <b>not</b> consulted. This is
     * the observability accessor — a {@code /metrics} scrape must never trigger probe I/O
     * from the scrape thread (the gauge re-reads on every scrape), so the gauge path uses
     * this instead of {@link #healthy}. Same trial-eligibility answer as {@link #healthy}
     * for a backend on cooldown probation, minus the probe veto. Abstract because a
     * default delegating to {@link #healthy} would run the {@link HealthProbe} (and its
     * fail-open path) from scrape threads — exactly what this accessor forbids.
     */
    boolean passivelyHealthy(ChatBackend backend);

    /**
     * Optional active-probe seam (the "optionally active {@code /health} probe" half of
     * consulted during cooldown probation — an unhealthy backend whose cooldown
     * elapsed is eligible for a trial attempt only if {@link #isHealthy} says so.
     * The gateway ships the seam and the lazy call site; the HTTP-backed probe
     * implementation is future work.
     */
    @FunctionalInterface
    interface HealthProbe {

        boolean isHealthy(ChatBackend backend);
    }

    /** No-op health state: filtering passes candidates through, record hooks do nothing. */
    static UpstreamHealth disabled() {
        return Disabled.INSTANCE;
    }

    /** No-op singleton (see {@link #disabled()}). */
    final class Disabled implements UpstreamHealth {

        private static final UpstreamHealth INSTANCE = new Disabled();

        private Disabled() {}

        @Override
        public void recordFailure(ChatBackend backend) {
            Objects.requireNonNull(backend, "backend");
        }

        @Override
        public void recordSuccess(ChatBackend backend) {
            Objects.requireNonNull(backend, "backend");
        }

        @Override
        public List<ChatBackend> healthy(List<ChatBackend> candidates) {
            return Objects.requireNonNull(candidates, "candidates");
        }

        @Override
        public boolean passivelyHealthy(ChatBackend backend) {
            Objects.requireNonNull(backend, "backend");
            return true; // no-op health state: everything is dispatch-eligible
        }
    }
}
