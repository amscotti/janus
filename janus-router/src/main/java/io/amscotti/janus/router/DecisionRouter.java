package io.amscotti.janus.router;

import io.amscotti.janus.core.codec.DecisionCodecException;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The decision-plane router: a model alias resolves to an ordered candidate
 * list of {@link DecisionBackend}s (same name in config = one pool, the chat
 * rule); the first attempt round-robins across the pool and retryable
 * failures walk the remaining candidates in config order with the shared
 * {@link RetryPolicy} backoff (docs/system-one.md).
 *
 * <p>Deliberately smaller than the chat {@link Router}: decisions never
 * stream, so there is no stream wrap, no first-chunk boundary, and no
 * usage-carrying load-balancer hook surface. Passive health and the circuit
 * breaker are keyed by {@link ChatBackend} identity and stay chat-only for
 * now (the architectural-review spike verdict) — decision calls are short
 * and idempotent, and the retry/fallback walk covers transient outages;
 * health/breaker generalization is future work if decision traffic needs it.
 *
 * <p>Image-bearing requests route to {@link DecisionBackend#supportsImages()
 * image-capable} candidates only: a mixed pool degrades to its capable
 * members deterministically (never a round-robin-position coin flip), and a
 * pool with no capable member is a typed client error before any dispatch.
 *
 * <p>Earlier retryable failures ride as suppressed exceptions on the final
 * failure, interruption during backoff aborts the chain, and non-retryable
 * failures propagate immediately — the chat router's discipline.
 */
public final class DecisionRouter {

    private static final System.Logger LOG = System.getLogger("io.amscotti.janus.router.DecisionRouter");

    private final Map<String, List<DecisionBackend>> routes;
    private final RetryPolicy retryPolicy;
    private final RetryClassifier classifier;
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /** Single-attempt form (direct/test use): no retries. */
    public DecisionRouter(Map<String, List<DecisionBackend>> routes) {
        this(routes, new RetryPolicy(0, 1, 1, 0.0), DefaultRetryClassifier.INSTANCE);
    }

    /** Resilient form: bounded retries with backoff over retryable failures. */
    public DecisionRouter(
            Map<String, List<DecisionBackend>> routes, RetryPolicy retryPolicy, RetryClassifier classifier) {
        Objects.requireNonNull(routes, "routes");
        Map<String, List<DecisionBackend>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<DecisionBackend>> e : routes.entrySet()) {
            List<DecisionBackend> candidates = List.copyOf(Objects.requireNonNull(e.getValue(), "candidates"));
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("decision alias \"" + e.getKey() + "\" has no backends");
            }
            Set<String> names = new HashSet<>();
            for (DecisionBackend backend : candidates) {
                if (!names.add(backend.name())) {
                    throw new IllegalArgumentException(
                            "duplicate backend name \"" + backend.name() + "\" for decision alias \"" + e.getKey()
                                    + "\" (two entries for one alias must resolve to different backend names)");
                }
            }
            copy.put(Objects.requireNonNull(e.getKey(), "alias"), candidates);
        }
        for (Map.Entry<String, List<DecisionBackend>> e : copy.entrySet()) {
            if (e.getKey().isBlank()) {
                throw new IllegalArgumentException("decision alias must not be blank: \"" + e.getKey() + "\"");
            }
        }
        this.routes = Collections.unmodifiableMap(copy);
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /** The configured decision aliases, config order. */
    public Set<String> models() {
        return routes.keySet();
    }

    /** Whether {@code model} is a configured decision alias (pre-pricing 404 guard). */
    public boolean knows(String model) {
        return model != null && routes.containsKey(model);
    }

    /** Decide with no dispatch observer. */
    public DecisionResponse decide(DecisionRequest request) {
        return decide(request, null);
    }

    /**
     * Decide, reporting each dispatched backend to {@code onDispatch} as it is
     * claimed (the gateway's call ledger reads the actually-serving provider
     * from the last delivery — the chat dispatch-observer contract).
     */
    public DecisionResponse decide(DecisionRequest request, Consumer<DecisionBackend> onDispatch) {
        Objects.requireNonNull(request, "request");
        String model = Objects.requireNonNull(request.model(), "request.model");
        List<DecisionBackend> candidates = routes.get(model);
        if (candidates == null) {
            throw new UnknownModelException(model);
        }
        if (request.hasImageEvidence()) {
            List<DecisionBackend> imageCapable = new ArrayList<>();
            for (DecisionBackend candidate : candidates) {
                if (candidate.supportsImages()) {
                    imageCapable.add(candidate);
                }
            }
            if (imageCapable.isEmpty()) {
                // Deterministic, before any dispatch, and free of backend
                // identity — the client learns the capability gap, not the
                // operator's pool topology.
                throw new DecisionCodecException(
                        DecisionCodecException.TYPE_INVALID_REQUEST,
                        "model \"" + model + "\" does not accept images — set vision = true on its"
                                + " decision-model-list entry to route image evidence");
            }
            candidates = imageCapable;
        }
        int maxRetries = retryPolicy.maxRetries();
        List<Throwable> failures = new ArrayList<>();
        Set<DecisionBackend> tried = Collections.newSetFromMap(new IdentityHashMap<>());
        int attempt = 0;
        while (true) {
            DecisionBackend backend = pick(model, candidates, tried, attempt);
            if (backend == null) {
                // Every candidate has had its one shot. Do not re-pick candidate 0
                // (that would hammer the primary and double-retry the same backend);
                // surface the last failure with the earlier attempts suppressed.
                Throwable last = failures.get(failures.size() - 1);
                for (Throwable earlier : failures) {
                    if (earlier != last) {
                        last.addSuppressed(earlier);
                    }
                }
                if (last instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (last instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("decision dispatch failed", last);
            }
            tried.add(backend);
            if (onDispatch != null) {
                // The chat dispatch-observer contract: accounting must never mask or
                // abort a dispatch.
                try {
                    onDispatch.accept(backend);
                } catch (RuntimeException | Error observerFailure) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "decision dispatch observer failed (dropped): {0}",
                            observerFailure.toString());
                }
            }
            try {
                return backend.decide(request);
            } catch (RuntimeException | Error e) {
                boolean retryable = e instanceof RuntimeException && isRetryable(e);
                if (!retryable || attempt >= maxRetries) {
                    for (Throwable earlier : failures) {
                        if (earlier != e) {
                            e.addSuppressed(earlier);
                        }
                    }
                    throw e;
                }
                failures.add(e);
                try {
                    retryPolicy.sleepBackoff(attempt);
                } catch (RuntimeException interrupted) {
                    for (Throwable earlier : failures) {
                        if (earlier != interrupted) {
                            interrupted.addSuppressed(earlier);
                        }
                    }
                    throw interrupted;
                }
                attempt++;
            }
        }
    }

    /**
     * Attempt 0 round-robins; retries walk the first untried candidate in config
     * order. Returns null once every candidate has been tried — the caller throws
     * the last failure instead of re-picking candidate 0.
     */
    private DecisionBackend pick(
            String model, List<DecisionBackend> candidates, Set<DecisionBackend> tried, int attempt) {
        if (attempt == 0) {
            int index = Math.floorMod(
                    counters.computeIfAbsent(model, m -> new AtomicInteger()).getAndIncrement(), candidates.size());
            return candidates.get(index);
        }
        for (DecisionBackend candidate : candidates) {
            if (!tried.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** A throwing classifier must not turn a failure retryable (the chat router's guard). */
    private boolean isRetryable(Throwable error) {
        try {
            return classifier.isRetryable(error);
        } catch (RuntimeException | Error classifierFailure) {
            if (classifierFailure != error) {
                error.addSuppressed(classifierFailure);
            }
            return false;
        }
    }
}
