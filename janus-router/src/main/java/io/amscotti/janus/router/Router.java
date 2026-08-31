package io.amscotti.janus.router;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Router: maps a
 * caller-supplied model name to one or more {@link ChatBackend}s. Three construction
 * modes:
 *
 * <ul>
 * <li><b>Single-backend mode:</b> {@link #Router(Map)} — one provider
 * per model alias, exact-match lookup, one attempt per request, typed failure for
 * unknown models. No load balancing, no observation, {@code stream} passes the
 * backend's stream through <b>unwrapped</b> by identity. This is the v1 contract
 * that the tests and the gateway compile against untouched.
 * <li><b>Balanced mode:</b> {@link #balanced(Map, LoadBalancer)} — multiple
 * provider backends per model alias; {@code complete}/{@code stream} pick a backend
 * via the {@link LoadBalancer} per request and drive its observation hooks.
 * Delegates to {@link #resilient} with {@link ResilienceConfig#none}, so
 * behavior is the refactor's spec.
 * <li><b>Resilient mode:</b> {@link #resilient(Map, LoadBalancer,
 * ResilienceConfig)} — balanced selection plus health-aware candidate filtering
 * ({@link UpstreamHealth}), a bounded retry loop with exponential backoff + jitter
 * ({@link RetryPolicy}, retry only on {@link RetryClassifier}-approved failures —
 * never on 4xx/auth/unknown), and config-order fallback chains (retry attempts walk
 * the candidate list deterministically, first-untried-healthy, bounded by
 * {@code maxRetries}).
 * <li><b>Resilient + breaker mode:</b> {@link #resilient(Map, LoadBalancer,
 * ResilienceConfig, CircuitBreaker)} — the loop plus the hard per-upstream
 * circuit breaker: OPEN backends are dropped from every attempt's candidate list
 * (a pure {@link CircuitBreaker#canTry} gate), the half-open probe slot is claimed
 * atomically on the <i>picked</i> backend only ({@link CircuitBreaker#claimProbe}
 * — filtering never leaks a claim onto a candidate the load balancer does not
 * pick), all-blocked fails open to a single probe against the health-filtered
 * list, and the stream wrap records terminal outcomes (connect/zero-chunk failures
 * trip, mid-stream failures are transient, clean exhaustion recovers, early close
 * only releases the probe) — see {@link CircuitBreaker} for the state machine. The
 * 3-arg {@code resilient(...)} delegates here with {@link CircuitBreaker#disabled}.
 * </ul>
 *
 * <p><b>Type-erasure trap (do not "fix" with an overload).</b> {@code Router(Map<String,
 * ChatBackend>)} and a hypothetical {@code Router(Map<String, List<ChatBackend>>)} have
 * the same erased signature — a second constructor cannot exist. The static factories
 * {@link #balanced} and {@link #resilient} are the committed design; anyone tempted to
 * add the overload will hit a compile error and should read this note instead.
 *
 * <p><b>Construction (all modes).</b> The whole input is validated up front, no lazy
 * surprises (integrity_check philosophy — a bad {@code model_list} fails at
 * startup, not on first request): null map/load-balancer/config, null keys, blank
 * aliases, null values and null candidate-list entries are rejected; the balanced/
 * resilient modes also reject <b>empty</b> candidate lists (an alias with zero backends
 * is always a config error) and <b>duplicate</b> backends within one alias — the same
 * instance twice, or two candidates sharing a {@link ChatBackend#name} (strategies key
 * state on identity/name, so duplicates silently collapse onto the config-order-first
 * backend; the gateway's {@code ModelListFactory} boot check mirrors this). Input maps
 * and each candidate list are defensively copied
 * into unmodifiable containers preserving config (insertion) order — strategies depend on
 * it for deterministic tie breaks and {@link #models} stays insertion-ordered.
 *
 * <p><b>Routing.</b> {@link #route(String)} is an exact, case-sensitive string match.
 * Null/blank model names are caller bugs → {@link IllegalArgumentException}; unknown
 * aliases → {@link UnknownModelException}. In balanced/resilient mode {@code route}
 * returns the <i>first</i> candidate (stable, config order) so the gateway's
 * {@code router.route.name} models listing keeps its meaning — it is
 * deliberately <i>not</i> the load-balancer pick; picks happen per request in {@code
 * complete}/{@code stream} so least-inflight/latency/cost state evolves correctly.
 * (Revisit if per-alias display names are wanted under balancing.) Callers that need
 * the backend a request was <i>actually sent to</i> (per-provider accounting) must use
 * the {@code complete}/{@code stream} <b>dispatch-observer overloads</b> — re-resolving
 * through {@code route} attributes every call to the config-first candidate even when
 * the balancer picked another backend or a retry failed over.
 *
 * <p><b>Resilient attempt loop.</b> Per attempt: filter
 * candidates through {@link UpstreamHealth#healthy} (a pure gate — no trial is claimed
 * at filter time; all-unhealthy → fail-open on the full list), then through {@link
 * CircuitBreaker#canTry} (a pure gate with no side effects; all blocked → fail-open
 * probe on the health-filtered list), pick at attempt 0
 * via the load balancer, walk the config-order chain afterwards (first untried healthy
 * backend; all tried → re-pick), then atomically claim the half-open probe slot on the
 * <i>picked</i> backend ({@link CircuitBreaker#claimProbe}) and its health trial
 * ({@link UpstreamHealth#claimTrial}) — an admitted-but-unpicked candidate never burns
 * its single trial, so a strategy that keeps preferring another backend cannot starve a
 * degraded one out of its recovery probes; a pick whose slot is
 * already claimed (a concurrent probe in flight) is excluded and the pick repeats,
 * bounded by the candidate count — claims never leak onto candidates that were filtered
 * but not dispatched, and no backend is double-dispatched onto a busy probe slot. When
 * every candidate's slot is busy (transient contention — probes complete momentarily)
 * the attempt rides the same retry budget as a dispatch failure and re-enters the loop;
 * if the budget is exhausted the request fails with a network-type
 * {@link BackendException} rather than violating the exactly-one-probe discipline.
 * Then drive the {@link LoadBalancer} hooks. A failure is classified once: the
 * {@link LoadBalancer} end hook fires for <b>any</b> failure (slot release), but
 * {@code health.recordFailure} and, in the 4-arg path, {@code breaker.recordConnectFailure}
 * (signal 1) fire only for <b>retryable (transport-class)</b> failures — client-driven
 * non-retryable errors (4xx/auth/bad-payload) are the client's fault, never an upstream
 * degradation, so they must not soft-exclude an upstream or trip the breaker — but the
 * attempt still terminated, so a claimed half-open probe <b>and</b> a claimed health
 * trial are released (settled without an outcome, never left to expire a window later).
 * If
 * retryable and within budget, the loop backs off via {@link RetryPolicy#sleepBackoff};
 * otherwise it propagates untouched — earlier retryable failures attached as suppressed
 * exceptions. A success records {@code health.recordSuccess} and
 * {@code breaker.recordSuccess} (passive recovery). {@code maxRetries} retries after the
 * first attempt → at most {@code maxRetries + 1} tries. (Re-picks advance the load
 * balancer's own distribution counters — a round-robin re-pick consumes an extra cycle
 * position for one logical request; accepted by design: re-picks are
 * bounded by the retry budget and rare, and the offset self-corrects.)
 *
 * <p><b>Streaming boundary.</b> Retry/fallback is legal only until
 * the first chunk is flushed. {@code stream} runs the attempt loop while <i>opening</i>
 * the stream (connect failure/timeout/network → retryable, no bytes delivered); once a
 * backend's stream is returned it is <b>never</b> retried or failed over — the wrap
 * is preserved (first-consumed-element TTFT sample, chained {@code onClose} → end hook,
 * close releases the underlying stream). extends the wrap with terminal-outcome
 * recording at chunk boundaries for the {@link CircuitBreaker}: zero elements yielded
 * then the delegate throws → {@code recordStreamFailure(b, true)} (signal 2, counts);
 * ≥ 1 element yielded then it throws → {@code recordStreamFailure(b, false)} (transient);
 * clean exhaustion → {@code recordSuccess(b)} exactly once; early close with no
 * exception (client disconnect) → no signal, but a claimed half-open probe and a
 * claimed health trial are released.
 * Only exceptions thrown by the delegate during iteration count as signal 2 — a
 * consumer-side abort is not a provider failure. Mid-stream consumer errors surface
 * through the gateway's existing error-event path and feed the breaker through this
 * wrap — the router never retries them, and the policy forbids acting on them anyway.
 * Note: {@code health.recordSuccess} on a stream fires on the first <i>consumed</i>
 * chunk (next to the TTFT sample), or on clean exhaustion of an empty stream — never at
 * stream open. A connect-only success used to reset the consecutive-failure counter and
 * mask the zero-chunk death that followed (with {@code allowedFails > 1} such a backend
 * never flipped unhealthy); a backend that accepts connections but dies before its first
 * chunk now accumulates health failures exactly like a connect failure. A failure
 * <i>after</i> the first chunk stays health-neutral (the backend demonstrably delivered
 * bytes) — recording that failure is the breaker's transient signal, not health's.
 *
 * <p>Thread-safe: immutable after construction in all modes.
 */
public final class Router {

    private static final Logger LOG = System.getLogger("io.amscotti.janus.router.Router");

    /** The 1-arg {@code complete}/{@code stream} forms' observer — dispatch is not observed. */
    private static final Consumer<ChatBackend> NO_DISPATCH_OBSERVER = backend -> {};

    /** Single-backend mode: unmodifiable alias → backend map; null in balanced mode. */
    private final Map<String, ChatBackend> routes;

    /** Balanced mode (/): unmodifiable alias → unmodifiable candidate list; null in single mode. */
    private final Map<String, List<ChatBackend>> balancedRoutes;

    /** Balanced-mode selection/observation hook; null in single mode. */
    private final LoadBalancer loadBalancer;

    /** resilience bundle; null in single mode, {@link ResilienceConfig#none()} for {@link #balanced}. */
    private final ResilienceConfig resilienceConfig;

    /** per-backend circuit breaker; null in single mode, {@link CircuitBreaker#disabled()} for the 3-arg path. */
    private final CircuitBreaker circuitBreaker;

    /**
     * Single-backend mode: one {@link ChatBackend} per
     * model alias, no load balancing, no observation, {@code stream} unwrapped.
     */
    public Router(Map<String, ChatBackend> routes) {
        Objects.requireNonNull(routes, "routes");
        Map<String, ChatBackend> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ChatBackend> entry : routes.entrySet()) {
            String alias = Objects.requireNonNull(entry.getKey(), "model alias");
            if (alias.isBlank()) {
                throw new IllegalArgumentException("model alias must not be blank: " + alias);
            }
            copy.put(alias, Objects.requireNonNull(entry.getValue(), "backend for alias " + alias));
        }
        this.routes = Collections.unmodifiableMap(copy);
        this.balancedRoutes = null;
        this.loadBalancer = null;
        this.resilienceConfig = null;
        this.circuitBreaker = null;
    }

    private Router(
            Map<String, List<ChatBackend>> balancedRoutes,
            LoadBalancer loadBalancer,
            ResilienceConfig resilienceConfig,
            CircuitBreaker circuitBreaker) {
        this.routes = null;
        this.balancedRoutes = balancedRoutes;
        this.loadBalancer = loadBalancer;
        this.resilienceConfig = resilienceConfig;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Balanced mode: multiple backends per model alias, selected per request by
     * {@code loadBalancer}; exactly one attempt per request, no health state — identical
     * to {@code resilient(..., ResilienceConfig.none)}. See the class javadoc for
     * validation and semantics; note the type-erasure rationale for why this is a static
     * factory and not a constructor overload.
     *
     * @param routes non-null alias → non-empty candidate list map (config order)
     * @param loadBalancer non-null strategy owning selection + observation
     */
    public static Router balanced(Map<String, List<ChatBackend>> routes, LoadBalancer loadBalancer) {
        return resilient(routes, loadBalancer, ResilienceConfig.none());
    }

    /**
     * Resilient mode: balanced selection plus health-aware candidate filtering,
     * bounded retries with exponential backoff + jitter, and config-order fallback
     * chains. Validation mirrors {@link #balanced} plus a non-null {@code config}; see
     * the class javadoc for the attempt loop and the streaming boundary. Arity differs
     * from {@code balanced}, so there is no type-erasure trap — do NOT add any
     * {@code Map}/{@code Map} overload variants.
     *
     * <p>Delegates to the 4-arg overload with {@link CircuitBreaker#disabled}, so the
     * balanced behavior is preserved byte-for-byte.
     *
     * @param routes non-null alias → non-empty candidate list map (config order)
     * @param loadBalancer non-null strategy owning selection + observation
     * @param config non-null retry policy + health state + retry classifier
     */
    public static Router resilient(
            Map<String, List<ChatBackend>> routes, LoadBalancer loadBalancer, ResilienceConfig config) {
        return resilient(routes, loadBalancer, config, CircuitBreaker.disabled());
    }

    /**
     * Resilient + breaker mode: the loop plus the hard per-upstream
     * {@link CircuitBreaker} — OPEN/denied backends are dropped from every attempt's
     * candidate list (after the health filter), all-blocked fails open to a single
     * probe against the health-filtered list, per-attempt failures record signal 1
     * ({@link CircuitBreaker#recordConnectFailure}) alongside the health hook, and
     * the stream wrap records terminal outcomes per the streaming-safe contract. See
     * the class javadoc for the full semantics.
     *
     * @param routes non-null alias → non-empty candidate list map (config order)
     * @param loadBalancer non-null strategy owning selection + observation
     * @param config non-null retry policy + health state + retry classifier
     * @param breaker non-null per-upstream breaker ({@link CircuitBreaker#disabled}
     * reproduces the 3-arg path)
     */
    public static Router resilient(
            Map<String, List<ChatBackend>> routes,
            LoadBalancer loadBalancer,
            ResilienceConfig config,
            CircuitBreaker breaker) {
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(loadBalancer, "loadBalancer");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(breaker, "breaker");
        Map<String, List<ChatBackend>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<ChatBackend>> entry : routes.entrySet()) {
            String alias = Objects.requireNonNull(entry.getKey(), "model alias");
            if (alias.isBlank()) {
                throw new IllegalArgumentException("model alias must not be blank: " + alias);
            }
            List<ChatBackend> candidates = Objects.requireNonNull(entry.getValue(), "candidates for alias " + alias);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("candidate list must not be empty for alias " + alias);
            }
            List<ChatBackend> candidatesCopy = new ArrayList<>(candidates.size());
            Set<ChatBackend> seenInstances = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<String> seenNames = new LinkedHashSet<>();
            for (ChatBackend backend : candidates) {
                ChatBackend candidate = Objects.requireNonNull(backend, "backend for alias " + alias);
                // Duplicates within one alias are rejected here (the gateway's
                // ModelListFactory already rejects them at boot, but a directly
                // constructed Router must not silently accept them): strategies key
                // state on backend identity and name — SessionAffinity's HRW scoring
                // and the weighted pool key on name, so two same-named candidates
                // (or the same instance listed twice) collapse onto the
                // config-order-first backend and half the configured pool never
                // receives traffic. The same backend under DIFFERENT aliases stays
                // legal (identity keying is the documented multi-alias contract).
                if (!seenInstances.add(candidate)) {
                    throw new IllegalArgumentException("duplicate backend instance for model alias \"" + alias
                            + "\" (list each backend once per alias)");
                }
                if (!seenNames.add(candidate.name())) {
                    throw new IllegalArgumentException(
                            "duplicate backend \"" + candidate.name() + "\" for model alias \"" + alias
                                    + "\" (two candidates for one alias must be different backends — "
                                    + "session-affinity/weighted selection keys on the backend name)");
                }
                candidatesCopy.add(candidate);
            }
            copy.put(alias, Collections.unmodifiableList(candidatesCopy));
        }
        return new Router(Collections.unmodifiableMap(copy), loadBalancer, config, breaker);
    }

    /**
     * Exact, case-sensitive lookup of the backend registered for {@code model}. In
     * balanced/resilient mode this is the <i>first</i> candidate (stable config order),
     * not the load-balancer pick. Throws {@link UnknownModelException} for unknown
     * aliases; null/blank {@code model} is a caller bug → {@link IllegalArgumentException}.
     */
    public ChatBackend route(String model) {
        requireModel(model);
        if (balancedRoutes != null) {
            List<ChatBackend> candidates = balancedRoutes.get(model);
            if (candidates == null) {
                throw new UnknownModelException(model);
            }
            return candidates.getFirst();
        }
        ChatBackend backend = routes.get(model);
        if (backend == null) {
            throw new UnknownModelException(model);
        }
        return backend;
    }

    /**
     * Non-streaming completion: validate the request's model, resolve the route, delegate
     * (single mode), or run the resilient attempt loop (balanced/resilient mode, see the
     * class javadoc).
     */
    public ChatResponse complete(ChatRequest request) {
        return complete(request, NO_DISPATCH_OBSERVER);
    }

    /**
     * Non-streaming completion with a <b>dispatch observer</b>: the consumer is invoked
     * with every backend the router <i>actually dispatches to</i> — the single-mode
     * backend, or the load-balancer pick (and, on retry/failover, each subsequent
     * attempt's backend — last delivery wins in a per-request holder). This is the seam
     * callers need for per-provider accounting ({@code Router#route} is deliberately
     * <b>not</b> the pick in balanced/resilient mode — see the class javadoc — so
     * re-resolving through it misattributes the ledger in exactly the multi-provider
     * configs balancing exists for). The observer is contained exactly like the
     * resilience hooks: a throwing observer is logged and dropped, never masking the
     * dispatch. Not invoked when no dispatch happens (unknown model, every probe slot
     * busy).
     */
    public ChatResponse complete(ChatRequest request, Consumer<ChatBackend> onDispatch) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onDispatch, "onDispatch");
        if (balancedRoutes == null) {
            ChatBackend backend = route(request.model());
            reportDispatch(onDispatch, backend);
            return backend.complete(request);
        }
        return resilientComplete(request, onDispatch);
    }

    /**
     * Streaming completion: validate the request's model, resolve the route eagerly, then
     * delegate — unwrapped in single mode (a pass-through by identity), the resilient
     * connect loop in balanced/resilient mode (retry/fallback only while opening the
     * stream; once open, the wrap is preserved: first-element TTFT sample + chained
     * {@code onClose}; closing the returned stream still releases the underlying
     * connection). The caller must close the returned stream (the gateway owns the
     * lifecycle).
     */
    public Stream<StreamChunk> stream(ChatRequest request) {
        return stream(request, NO_DISPATCH_OBSERVER);
    }

    /**
     * Streaming completion with a <b>dispatch observer</b> (see {@link
     * #complete(ChatRequest, Consumer)}): invoked with the backend whose stream was
     * actually opened — the load-balancer pick, or the failover target when connect
     * retries walked the chain. Fired <i>before</i> the stream is returned, so a caller
     * reading a per-request holder after {@code stream(...)} returns sees the serving
     * backend. Contained like every hook: a throwing observer never masks the dispatch.
     */
    public Stream<StreamChunk> stream(ChatRequest request, Consumer<ChatBackend> onDispatch) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onDispatch, "onDispatch");
        if (balancedRoutes == null) {
            ChatBackend backend = route(request.model());
            reportDispatch(onDispatch, backend);
            return backend.stream(request);
        }
        return resilientStream(request, onDispatch);
    }

    /**
     * Unmodifiable set of the configured model aliases ( {@code GET /v1/models}).
     * Insertion order is preserved (config order) so the listing is deterministic.
     */
    public Set<String> models() {
        if (balancedRoutes != null) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(balancedRoutes.keySet()));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(routes.keySet()));
    }

    private List<ChatBackend> resolveCandidates(String model) {
        requireModel(model);
        List<ChatBackend> candidates = balancedRoutes.get(model);
        if (candidates == null) {
            throw new UnknownModelException(model);
        }
        return candidates;
    }

    private ChatResponse resilientComplete(ChatRequest request, Consumer<ChatBackend> onDispatch) {
        String model = request.model();
        List<ChatBackend> candidates = resolveCandidates(model);
        RetryPolicy retryPolicy = resilienceConfig.retryPolicy();
        UpstreamHealth health = resilienceConfig.health();
        RetryClassifier classifier = resilienceConfig.classifier();
        int maxRetries = retryPolicy.maxRetries();
        List<Throwable> failures = new ArrayList<>();
        Set<ChatBackend> tried = Collections.newSetFromMap(new IdentityHashMap<>());
        int attempt = 0;
        while (true) {
            List<ChatBackend> healthyCandidates = health.healthy(resolveCandidates(model));
            if (healthyCandidates.isEmpty()) {
                healthyCandidates = candidates; // fail-open: stale health must not hard-fail the request
            }
            BreakerPool breakerPool = breakerCandidates(healthyCandidates);
            ChatBackend backend = null;
            // The LB end hook fires at most once per attempt: the success path claims the
            // slot; a hook failure there must not trigger a second end(false, null)
            // delivery from the catch path (double delivery corrupts least-inflight
            // counters and masks the backend result).
            AtomicBoolean endFired = new AtomicBoolean();
            try {
                backend = pickAndClaim(model, breakerPool, tried, attempt, request);
                if (backend == null) {
                    // Every candidate's dispatch slot is busy (concurrent probes or health
                    // trials in flight on all backends): dispatch is impossible without
                    // double-dispatching onto a claimed slot. This is transient contention
                    // — probes and trials complete momentarily — so it rides the same
                    // retry budget as a dispatch failure; the next iteration recomputes
                    // the candidate pool and likely finds a free slot. No backend was
                    // dispatched, so nothing is recorded to health/breaker/LB for this
                    // attempt.
                    throw new BackendException(
                            BackendException.TYPE_NETWORK,
                            "no upstream available: every dispatch slot is busy (health trial or "
                                    + "circuit-breaker probe) (model " + LogSafe.text(model) + ")");
                }
                tried.add(backend);
                ChatBackend picked = backend; // effectively final for the hook lambdas
                // The dispatch observer fires when the backend is claimed for dispatch —
                // the slot is taken and the request is about to be sent, so a per-request
                // holder ends up holding the backend that actually received it (the
                // failover target on retries: last delivery wins).
                reportDispatch(onDispatch, picked);
                runHook("onRequestStart", () -> loadBalancer.onRequestStart(model, picked));
                long startNanos = System.nanoTime();
                ChatResponse response = picked.complete(request);
                runHook("health.recordSuccess", () -> health.recordSuccess(picked));
                runHook("breaker.recordSuccess", () -> circuitBreaker.recordSuccess(picked));
                runHook(
                        "onLatencySample",
                        () -> loadBalancer.onLatencySample(model, picked, System.nanoTime() - startNanos));
                runEndHook(model, picked, true, response, endFired);
                return response;
            } catch (Throwable e) {
                // Any throwable — an Error must release the slot and feed health too.
                // The classifier verdict is evaluated exactly once: the LB end hook fires for
                // any failure, but non-retryable client errors (4xx/auth/bad-payload) never
                // count toward health or the breaker — they are the client's fault, not an
                // upstream degradation.
                boolean retryable = isRetryable(e, classifier);
                if (backend != null) {
                    ChatBackend failed = backend; // effectively final for the hook lambdas
                    runEndHook(model, failed, false, null, endFired);
                    if (retryable) {
                        runHook("health.recordFailure", () -> health.recordFailure(failed));
                        runHook("breaker.recordConnectFailure", () -> circuitBreaker.recordConnectFailure(failed));
                    } else {
                        // Non-retryable outcomes deliberately never count toward the
                        // breaker — but the attempt still TERMINATED, and
                        // a claimed half-open probe must be released on any terminal
                        // outcome (the breaker's own contract) or the slot leaks busy for
                        // the whole cooldown (found by the cross-format smoke: an upstream 401
                        // after a 5xx retry storm wedged every later request).
                        circuitBreaker.releaseProbe(failed);
                        // Same terminal-outcome discipline for a claimed health trial:
                        // a client-driven 4xx is neither a trial success (must not
                        // recover the backend) nor an upstream failure (must not
                        // re-cooldown it) — release the claimed trial so the
                        // single-trial slot does not stay busy for a full extra
                        // cooldown window.
                        runHook("health.releaseTrial", () -> health.releaseTrial(failed));
                    }
                }
                if (!retryable || attempt >= maxRetries) {
                    for (Throwable earlier : failures) {
                        e.addSuppressed(earlier);
                    }
                    throw e;
                }
                failures.add(e);
                try {
                    retryPolicy.sleepBackoff(attempt);
                } catch (RuntimeException interrupted) {
                    // Interruption during backoff is a shutdown signal, not a retry
                    // condition: abort the chain, but keep the failures attached.
                    for (Throwable earlier : failures) {
                        interrupted.addSuppressed(earlier);
                    }
                    throw interrupted;
                }
                attempt++;
            }
        }
    }

    private Stream<StreamChunk> resilientStream(ChatRequest request, Consumer<ChatBackend> onDispatch) {
        String model = request.model();
        List<ChatBackend> candidates = resolveCandidates(model);
        RetryPolicy retryPolicy = resilienceConfig.retryPolicy();
        UpstreamHealth health = resilienceConfig.health();
        RetryClassifier classifier = resilienceConfig.classifier();
        int maxRetries = retryPolicy.maxRetries();
        List<Throwable> failures = new ArrayList<>();
        Set<ChatBackend> tried = Collections.newSetFromMap(new IdentityHashMap<>());
        int attempt = 0;
        while (true) {
            List<ChatBackend> healthyCandidates = health.healthy(resolveCandidates(model));
            if (healthyCandidates.isEmpty()) {
                healthyCandidates = candidates; // fail-open: stale health must not hard-fail the request
            }
            BreakerPool breakerPool = breakerCandidates(healthyCandidates);
            ChatBackend backend = null;
            AtomicBoolean endFired = new AtomicBoolean();
            try {
                backend = pickAndClaim(model, breakerPool, tried, attempt, request);
                if (backend == null) {
                    // Same contention handling as the complete path: every dispatch slot
                    // (probe or health trial) is busy — transient, retried within the
                    // same budget.
                    throw new BackendException(
                            BackendException.TYPE_NETWORK,
                            "no upstream available: every dispatch slot is busy (health trial or "
                                    + "circuit-breaker probe) (model " + LogSafe.text(model) + ")");
                }
                tried.add(backend);
                ChatBackend picked = backend; // effectively final for the hook lambdas
                // Same dispatch-observer contract as the complete path (see there).
                reportDispatch(onDispatch, picked);
                long startNanos = System.nanoTime();
                runHook("onRequestStart", () -> loadBalancer.onRequestStart(model, picked));
                Stream<StreamChunk> underlying = picked.stream(request);
                // NOTE: health success is NOT recorded here, at stream open. A connect-only
                // success used to reset the consecutive-failure counter before the
                // zero-chunk death incremented it, so a connect-then-die backend never
                // accumulated enough failures to flip unhealthy when allowedFails > 1
                // (docs/routing.md: the connect-path success is not allowed to mask a
                // zero-chunk death). The health success fires on the first CONSUMED chunk,
                // next to the TTFT sample, inside the wrap below — and on clean
                // exhaustion of an empty stream.
                return wrapStream(underlying, model, picked, startNanos, health);
            } catch (Throwable e) {
                // Connect failure: no bytes were delivered, so a retry is transparent
                // .
                // Non-retryable client errors never count toward health/breaker.
                boolean retryable = isRetryable(e, classifier);
                if (backend != null) {
                    ChatBackend failed = backend; // effectively final for the hook lambdas
                    runEndHook(model, failed, false, null, endFired);
                    if (retryable) {
                        runHook("health.recordFailure", () -> health.recordFailure(failed));
                        runHook("breaker.recordConnectFailure", () -> circuitBreaker.recordConnectFailure(failed));
                    } else {
                        // Same probe-release contract as the complete path: the connect
                        // attempt terminated non-retryably; the claimed half-open probe
                        // must not leak busy for the cooldown window.
                        circuitBreaker.releaseProbe(failed);
                        // ...and the same trial-release contract: a claimed health
                        // trial is freed (neither recovered nor re-cooldowned by a
                        // client-driven error).
                        runHook("health.releaseTrial", () -> health.releaseTrial(failed));
                    }
                }
                if (!retryable || attempt >= maxRetries) {
                    for (Throwable earlier : failures) {
                        e.addSuppressed(earlier);
                    }
                    throw e;
                }
                failures.add(e);
                try {
                    retryPolicy.sleepBackoff(attempt);
                } catch (RuntimeException interrupted) {
                    // Interruption during backoff is a shutdown signal, not a retry
                    // condition: abort the chain, but keep the failures attached.
                    for (Throwable earlier : failures) {
                        interrupted.addSuppressed(earlier);
                    }
                    throw interrupted;
                }
                attempt++;
            }
        }
    }

    /**
     * First untried healthy backend in config order; every candidate tried → re-pick.
     * The re-pick goes through the request-aware {@link LoadBalancer#pick(String, List,
     * ChatRequest)} — the router's documented entry point, threading the request the
     * router is dispatching — and therefore advances the strategy's own distribution
     * counters (a round-robin re-pick consumes an extra cycle position for one
     * logical request) and, for request-aware strategies (session-affinity), re-decides
     * on the same request deterministically. Accepted by design (see the class
     * javadoc): re-picks are bounded by the retry budget, rare, and the offset
     * self-corrects.
     */
    private ChatBackend nextUntried(
            List<ChatBackend> candidates, Set<ChatBackend> tried, String model, ChatRequest request) {
        for (ChatBackend backend : candidates) {
            if (!tried.contains(backend)) {
                return backend;
            }
        }
        return loadBalancer.pick(model, candidates, request);
    }

    /**
     * attempt-loop filter: drop breaker-blocked backends (OPEN with cooldown
     * pending, or a busy half-open probe slot — {@link CircuitBreaker#canTry}, a pure
     * gate: no claims happen here). When every candidate is blocked, fail open to the
     * (health-filtered) input list — the request goes out as a single probe whose
     * outcome re-trips or recovers the breaker (consistency with health's all-unhealthy
     * fail-open; re-checked by the chaos drill). The probe slot is claimed at dispatch
     * time on the picked backend only ({@link CircuitBreaker#claimProbe}).
     *
     * <p>The returned {@link BreakerPool#failOpen} rides to the claim: the all-open
     * fallback may claim a cooldown-pending OPEN backend (the documented divergence);
     * the normal path may not — closing the canTry→claimProbe TOCTOU window in which a
     * backend tripped OPEN mid-flight was admitted as if it were the fallback.
     */
    private record BreakerPool(List<ChatBackend> candidates, boolean failOpen) {}

    private BreakerPool breakerCandidates(List<ChatBackend> healthyCandidates) {
        List<ChatBackend> allowed = new ArrayList<>(healthyCandidates.size());
        for (ChatBackend backend : healthyCandidates) {
            if (circuitBreaker.canTry(backend)) {
                allowed.add(backend);
            }
        }
        return allowed.isEmpty() ? new BreakerPool(healthyCandidates, true) : new BreakerPool(allowed, false);
    }

    /**
     * Pick a dispatchable backend: the load-balancer pick at attempt 0 (the
     * request-aware {@link LoadBalancer#pick(String, List, ChatRequest)}), the
     * first-untried config-order candidate on retries ({@link #nextUntried}), then
     * atomically claim everything the dispatch needs on the picked backend — the
     * half-open probe slot ({@link CircuitBreaker#claimProbe}) and the health layer's
     * single trial for a cooldown-elapsed unhealthy backend
     * ({@link UpstreamHealth#claimTrial}; the health filter, like {@code canTry}, is a
     * pure gate — an admitted-but-unpicked candidate never burns its trial). A pick
     * whose slot is already claimed (a concurrent probe or trial in flight) is excluded
     * and the pick repeats — bounded by the candidate count — so filtering never leaks
     * a claim onto a candidate that was not dispatched and no backend is
     * double-dispatched onto a busy slot. The health claim is skipped on the <i>last</i>
     * remaining candidate: health is a soft filter that must never hard-fail a request
     * (the fail-open contract — stale health still sends the request as a probe,
     * without consuming anyone's trial), while the breaker claim has no such escape
     * (the exactly-one-probe discipline is hard). Returns {@code null} when no candidate
     * is claimable (every breaker probe slot busy); the caller must not dispatch then.
     */
    private ChatBackend pickAndClaim(
            String model, BreakerPool breakerPool, Set<ChatBackend> tried, int attempt, ChatRequest request) {
        List<ChatBackend> pool = breakerPool.candidates();
        while (true) {
            // Attempt 0 and the re-pick alike go through the request-aware 3-arg pick —
            // the router's documented entry point (a request-aware strategy such as
            // session-affinity must see every pick, including the narrowed-pool repeats
            // below; the request-independent strategies ignore the extra parameter).
            ChatBackend picked =
                    attempt == 0 ? loadBalancer.pick(model, pool, request) : nextUntried(pool, tried, model, request);
            if (claimForDispatch(picked, breakerPool.failOpen(), pool.size() == 1)) {
                return picked;
            }
            if (pool.size() == 1) {
                return null;
            }
            List<ChatBackend> narrowed = new ArrayList<>(pool.size() - 1);
            for (ChatBackend candidate : pool) {
                if (candidate != picked) {
                    narrowed.add(candidate);
                }
            }
            pool = narrowed;
        }
    }

    /**
     * Atomically claims both dispatch slots on the <i>picked</i> backend: the breaker's
     * half-open probe first (releasable via {@link CircuitBreaker#releaseProbe}), then
     * the health layer's single trial ({@link UpstreamHealth#claimTrial}). The trial
     * claim is not itself releasable, so it comes second — a trial lost to a concurrent
     * dispatch between filter and claim releases the just-claimed probe instead of
     * leaking it busy for the cooldown window. {@code healthFailOpen} (the last
     * remaining candidate) skips the trial claim: an exhausted pool dispatches anyway
     * (health's fail-open — a request still goes out as a probe), exactly like the
     * breaker's all-open fail-open probe claims a cooldown-pending OPEN backend.
     */
    private boolean claimForDispatch(ChatBackend backend, boolean breakerFailOpen, boolean healthFailOpen) {
        if (!circuitBreaker.claimProbe(backend, breakerFailOpen)) {
            return false;
        }
        if (healthFailOpen || resilienceConfig.health().claimTrial(backend)) {
            return true;
        }
        // The health trial was lost to a concurrent dispatch after the probe was
        // claimed — give the probe back so it cannot leak busy for the cooldown.
        circuitBreaker.releaseProbe(backend);
        return false;
    }

    /**
     * The stream wrap extended for the {@link CircuitBreaker} (see the class javadoc):
     * TTFT sample on the first consumed element, chained {@code onClose} → end hook +
     * underlying close (unchanged), plus terminal-outcome recording at chunk boundaries.
     * Only exceptions thrown by the delegate's {@code tryAdvance} count as signal 2 and
     * are rethrown untouched; exceptions from the consumer's own action are deliberately
     * not recorded (a client-side abort is not a provider failure); an early close with
     * no exception records nothing but releases a claimed half-open probe. The close-time
     * end hook bills the cost-based LB on a mid-stream delegate failure only when a
     * terminal usage chunk was already observed (that usage was delivered to the
     * client, so it counts; a connect-then-die failure with no usage bills nothing). A
     * failure that happens downstream of this wrap — e.g. the gateway's SSE publisher
     * failing to encode a chunk — is invisible here and bills as a clean close (
     * deferred: threading the publisher's failure signal into this close decision needs a
     * router/gateway API change).
     */
    private Stream<StreamChunk> wrapStream(
            Stream<StreamChunk> underlying, String model, ChatBackend backend, long startNanos, UpstreamHealth health) {
        Spliterator<StreamChunk> delegate = underlying.spliterator();
        AtomicBoolean sampled = new AtomicBoolean();
        AtomicBoolean outcomeRecorded = new AtomicBoolean();
        AtomicBoolean streamFailed = new AtomicBoolean();
        AtomicBoolean healthSuccessRecorded = new AtomicBoolean();
        AtomicInteger yielded = new AtomicInteger();
        // Last usage-bearing chunk — feeds cost-based LB on clean stream close (synthetic
        // ChatResponse). Client-aborted streams with no usage leave this null → $0.
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        AtomicReference<String> lastChunkId = new AtomicReference<>();
        AtomicReference<String> lastChunkModel = new AtomicReference<>();
        Spliterator<StreamChunk> wrapped = new Spliterator<>() {
            @Override
            public boolean tryAdvance(Consumer<? super StreamChunk> action) {
                StreamChunk[] delivered = new StreamChunk[1];
                boolean advanced;
                try {
                    advanced = delegate.tryAdvance(chunk -> delivered[0] = chunk);
                } catch (Throwable t) {
                    // Delegate failed during iteration: signal 2 at a chunk boundary. A
                    // zero-chunk failure (beforeFirstChunk) is a connect-then-die backend —
                    // it feeds health too, mirroring the connect path so health and the
                    // breaker consume the same per-attempt failure events (the
                    // docs promise this and the breaker-disabled operator disable relies on
                    // it). Mid-stream (>= 1 chunk) failures stay health-neutral (m2 design
                    // note): the backend demonstrably delivered bytes.
                    streamFailed.set(true);
                    if (outcomeRecorded.compareAndSet(false, true)) {
                        boolean beforeFirstChunk = yielded.get() == 0;
                        runHook(
                                "breaker.recordStreamFailure",
                                () -> circuitBreaker.recordStreamFailure(backend, beforeFirstChunk));
                        if (beforeFirstChunk) {
                            runHook("health.recordFailure", () -> health.recordFailure(backend));
                        }
                    }
                    throw t; // rethrown untouched
                }
                if (advanced) {
                    StreamChunk chunk = delivered[0];
                    if (chunk != null && chunk.usage() != null) {
                        lastUsage.set(chunk.usage());
                        if (chunk.id() != null) {
                            lastChunkId.set(chunk.id());
                        }
                        if (chunk.model() != null) {
                            lastChunkModel.set(chunk.model());
                        }
                    }
                    if (sampled.compareAndSet(false, true)) {
                        runHook(
                                "onLatencySample",
                                () -> loadBalancer.onLatencySample(model, backend, System.nanoTime() - startNanos));
                        // The health success fires with the TTFT sample — on the first
                        // CONSUMED chunk, never at stream open: a connect-only success
                        // used to reset the consecutive-failure counter and mask the
                        // zero-chunk death that followed (docs/routing.md — with
                        // allowed-fails > 1 such a backend never flipped unhealthy).
                        if (healthSuccessRecorded.compareAndSet(false, true)) {
                            runHook("health.recordSuccess", () -> health.recordSuccess(backend));
                        }
                    }
                    action.accept(chunk);
                    yielded.incrementAndGet();
                } else if (outcomeRecorded.compareAndSet(false, true)) {
                    runHook(
                            "breaker.recordSuccess",
                            () -> circuitBreaker.recordSuccess(backend)); // clean exhaustion, exactly once
                    // An empty-but-clean stream still served the request (no failure
                    // signal, no chunk to carry the success above) — it recovers health
                    // too, exactly once.
                    if (healthSuccessRecorded.compareAndSet(false, true)) {
                        runHook("health.recordSuccess", () -> health.recordSuccess(backend));
                    }
                }
                return advanced;
            }

            @Override
            public Spliterator<StreamChunk> trySplit() {
                return null;
            }

            @Override
            public long estimateSize() {
                return delegate.estimateSize();
            }

            @Override
            public int characteristics() {
                // Mask SUBSIZED: trySplit above always returns null, so the wrapped
                // spliterator can never produce exact-size splits — advertising SUBSIZED
                // (which the underlying stream may claim) would be a contract lie for any
                // downstream that parallelizes or reads characteristics. SIZED stays:
                // estimateSize delegates consistently.
                return delegate.characteristics() & ~Spliterator.SUBSIZED;
            }
        };
        return StreamSupport.stream(wrapped, false).onClose(() -> {
            try {
                circuitBreaker.releaseProbe(backend); // closed/abandoned stream: free a claimed probe
                if (!outcomeRecorded.get() && !healthSuccessRecorded.get()) {
                    // Abandoned before the first consumed chunk: neither
                    // recordSuccess (first-chunk, or clean exhaustion) nor
                    // recordFailure (delegate error before the first chunk) will ever
                    // fire, so the claimed health trial is released here — the same
                    // terminal-outcome settlement the probe just got. Without this the
                    // claimed trial's extended probation kept the backend excluded for
                    // a full extra cooldown window.
                    runHook("health.releaseTrial", () -> health.releaseTrial(backend));
                }
                Usage usage = lastUsage.get();
                // A mid-stream delegate failure is only unbilled when no terminal
                // usage chunk was observed — a usage chunk that already passed through
                // tryAdvance was delivered to the client (and governance settled it), so the
                // cost-based LB must count it too; a zero-chunk (connect-then-die) failure
                // or a usage-less abort bills nothing.
                ChatResponse streamResponse = usage == null
                        ? null
                        : syntheticStreamResponse(model, usage, lastChunkId.get(), lastChunkModel.get());
                if (streamFailed.get() && streamResponse == null) {
                    runHook("onRequestEnd", () -> loadBalancer.onRequestEnd(model, backend, false, null));
                } else {
                    runHook("onRequestEnd", () -> loadBalancer.onRequestEnd(model, backend, true, streamResponse));
                }
            } finally {
                // The end hook is contained, so it can never skip the
                // close-releases-connection contract — the upstream connection is always
                // released even when a misbehaving strategy throws on close.
                underlying.close();
            }
        });
    }

    /** The synthetic {@link ChatResponse} the LB hook consumes (only usage is read; never reaches the wire). */
    private ChatResponse syntheticStreamResponse(String model, Usage usage, String chunkId, String chunkModel) {
        return new ChatResponse(
                chunkId != null ? chunkId : "stream",
                "chat.completion",
                0L,
                chunkModel != null ? chunkModel : model,
                List.of(),
                usage,
                null,
                Map.of(),
                Map.of());
    }

    /**
     * Delivers the dispatch observer's {@code accept} inside the same containment the
     * resilience hooks get: the observer exists so a caller can attribute the dispatch
     * (the gateway's call ledger), so a throwing observer must never mask or preempt the
     * dispatch it is observing — logged and dropped, like {@link #runHook}.
     */
    private static void reportDispatch(Consumer<ChatBackend> onDispatch, ChatBackend backend) {
        try {
            onDispatch.accept(backend);
        } catch (RuntimeException | Error t) {
            LOG.log(Level.WARNING, "dispatch observer threw; ignoring so it cannot mask the dispatch", t);
        }
    }

    /**
     * Runs one resilience observation hook — a {@link LoadBalancer} delivery or a
     * {@link UpstreamHealth}/{@code CircuitBreaker} record call — inside a containment
     * guard (extended to the health/breaker seams): a
     * misbehaving implementation must never mask the backend result it was observing —
     * the hook's {@code RuntimeException}/{@code Error} is logged and dropped, and the
     * attempt continues with the backend's actual outcome (a 500 for a
     * successfully-served request would be a lie, and a hook throwing after a stream
     * opened would orphan the upstream connection). Applied to every start/sample/end
     * delivery and every health/breaker record in {@code complete}, {@code stream}
     * and the stream wrap. The backend name is operator config, never client input, so
     * it is safe to log.
     */
    private void runHook(String hook, Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException | Error t) {
            LOG.log(
                    Level.WARNING,
                    "resilience hook " + hook + " threw; ignoring so it cannot mask the backend result",
                    t);
        }
    }

    /**
     * Delivers the per-attempt end hook at most once: a throwing end hook on
     * the success path must not cause a second {@code end(false, null)} delivery from the
     * catch path — strategies like least-inflight decrement on every delivery, so a double
     * delivery would undercount their in-flight state. The success path claims the slot
     * first; a failure-path delivery is skipped once it is claimed. Hook exceptions are
     * contained (see {@link #runHook}).
     */
    private void runEndHook(
            String model, ChatBackend backend, boolean success, ChatResponse response, AtomicBoolean endFired) {
        if (!endFired.compareAndSet(false, true)) {
            return;
        }
        runHook("onRequestEnd", () -> loadBalancer.onRequestEnd(model, backend, success, response));
    }

    /**
     * Classifier call guarded against a misbehaving (throwing) implementation: the
     * classifier must not mask the backend failure it was asked to classify — the error
     * is already recorded to health and the LB end hook. {@code Error} is caught too
     * (the same containment as {@link #runHook}/{@link #reportDispatch}): this call runs
     * <i>inside</i> the attempt's catch block, so a classifier {@code Error} escaping
     * here would skip the end hook (leaking a least-inflight slot) and the probe release
     * below it. Fall back to not-retryable and attach the classifier failure for
     * debuggability.
     */
    private static boolean isRetryable(Throwable error, RetryClassifier classifier) {
        try {
            return classifier.isRetryable(error);
        } catch (RuntimeException | Error classifierFailure) {
            error.addSuppressed(classifierFailure);
            return false;
        }
    }

    private static void requireModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be null or blank");
        }
    }
}
