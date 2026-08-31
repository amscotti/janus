package io.amscotti.janus.router;

import io.amscotti.janus.core.model.ChatRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Session-affinity selection: a client-supplied session id deterministically picks
 * the backend via <b>rendezvous (highest-random-weight, HRW) hashing</b> — every
 * candidate is scored with {@code fnv1a32(sessionId + "|" + backend.name)}, the
 * highest score wins, a score tie keeps the config-order-first candidate. Config
 * key: {@code "session-affinity"}.
 *
 * <p><b>Why HRW.</b> Stateless and node-independent — both nodes of a two-gateway
 * cluster compute the same pick with zero new shared state (unlike a sticky ring).
 * Consistent — removing an unhealthy backend (the router's health/breaker filters
 * narrow the candidate pool before this pick ever runs) moves only the sessions
 * that hashed to it; a session-sticky {@code hash mod N} would reshuffle every
 * session on any membership change. {@link ChatBackend#name} is a stable key:
 * duplicate provider names within one alias are rejected at {@link Router}
 * construction (mirrored by the gateway's boot-time {@code ModelListFactory} check),
 * so the score input identifies exactly one candidate.
 *
 * <p><b>Session id carrier.</b> The gateway folds the inbound
 * {@code x-janus-session-id} request header into {@link ChatRequest#meta} under
 * {@link #META_SESSION_ID} on all three faces; this strategy <b>reads</b> that entry
 * and never writes meta (the whitelisted-reader rule on the meta contract — the
 * session id is client-chosen routing input, never logged, never forwarded
 * upstream). The value is trimmed; blank-after-trim or a non-String value counts as
 * absent and the pick falls back to an inner {@link RoundRobinLoadBalancer}
 * (hardcoded — the reference {@code simple_shuffle}-fallback precedent), which owns the
 * usual per-alias {@code AtomicLong} counters.
 *
 * <p><b>Health/breaker/retry interplay — all inherited, none changed.</b> The pick
 * only ever chooses within the pool the router hands it (post-health-filter,
 * post-breaker-filter, narrowed on claim contention) — a smaller pool is exactly
 * HRW over a smaller set. The sticky pick applies at attempt 0; retries walk the
 * config-order fallback chain (the router's documented contract), and the all-tried
 * re-pick re-enters this strategy with the same session id, landing on the same
 * deterministic winner among the remaining candidates. {@code weights} are ignored
 * under this strategy (the gateway warns at boot when both are configured).
 *
 * <p>Stateless on the HRW path; thread-safe (the fallback round-robin is
 * thread-safe, the hash is a pure function). Native-image clean — no reflection,
 * no randomness.
 */
public final class SessionAffinityLoadBalancer implements LoadBalancer {

    /**
     * The meta key the gateway folds the inbound {@code x-janus-session-id} header
     * under — the single documented definition shared by the gateway fold and this
     * strategy's read.
     */
    public static final String META_SESSION_ID = "janus.session-id";

    /** Hardcoded fallback for requests with no (usable) session id. */
    private final RoundRobinLoadBalancer fallback = new RoundRobinLoadBalancer();

    @Override
    public String name() {
        return "session-affinity";
    }

    /**
     * The router's entry point: the session id from
     * {@link ChatRequest#meta} under {@link #META_SESSION_ID} decides the pick —
     * absent/blank/non-String delegates to the round-robin fallback.
     */
    @Override
    public ChatBackend pick(String model, List<ChatBackend> candidates, ChatRequest request) {
        Objects.requireNonNull(request, "request");
        String sessionId = sessionIdOf(request);
        if (sessionId == null) {
            return fallback.pick(model, candidates);
        }
        return stickyPick(candidates, sessionId);
    }

    /**
     * Request-blind form (direct/test use — the router never calls it): no request
     * means no session id, so this is the fallback round-robin pick.
     */
    @Override
    public ChatBackend pick(String model, List<ChatBackend> candidates) {
        return fallback.pick(model, candidates);
    }

    private static ChatBackend stickyPick(List<ChatBackend> candidates, String sessionId) {
        // Same contract shape as the other strategies: pick is a public method, so an
        // empty list fails with the contract message (the router guarantees non-empty).
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("session-affinity pick requires a non-empty candidate list (session "
                    + LogSafe.text(sessionId) + ")");
        }
        ChatBackend winner = null;
        int bestScore = 0;
        boolean first = true;
        // Strictly-greater keeps the config-order-first candidate on a score tie
        // (tie → config order), and Integer.compareUnsigned makes the comparison
        // unsigned — FNV-1a 32-bit values regularly have the sign bit set, and a
        // signed compare would invert half the ordering. No Math.abs anywhere
        // (Integer.MIN_VALUE would negate to itself).
        for (ChatBackend candidate : candidates) {
            int score = fnv1a32(sessionId + "|" + candidate.name());
            if (first || Integer.compareUnsigned(score, bestScore) > 0) {
                winner = candidate;
                bestScore = score;
                first = false;
            }
        }
        return winner;
    }

    /** The trimmed session id, or {@code null} when absent/blank/non-String (absent semantics). */
    private static String sessionIdOf(ChatRequest request) {
        Object value = request.meta().get(META_SESSION_ID);
        if (!(value instanceof String sessionId)) {
            return null;
        }
        String trimmed = sessionId.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    /**
     * FNV-1a 32-bit over the UTF-8 bytes of {@code key}: offset basis 2166136261
     * ({@code 0x811C9DC5}), prime 16777619 ({@code 0x01000193}), plain {@code int}
     * arithmetic with wraparound — deterministic on every JVM (the pinned pick
     * sequence in the strategy test guards this function's exact behavior).
     */
    static int fnv1a32(String key) {
        int hash = 0x811C9DC5;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ b) * 0x01000193;
        }
        return hash;
    }
}
