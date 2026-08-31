package io.amscotti.janus.router;

import io.amscotti.janus.core.model.ChatResponse;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Least-in-flight selection (LiteLLM {@code least-busy}): pick the backend with the
 * fewest requests currently being served. Config key: {@code "least-inflight"}.
 *
 * <p>State: one {@link AtomicInteger} per backend, keyed by <b>identity</b> — the same
 * backend instance serving several aliases shares one counter (the point of the
 * strategy). {@link #onRequestStart} increments; {@link #onRequestEnd} decrements
 * <b>regardless of success</b> (end-in-finally semantics — a failed call still releases
 * its slot). Ties go to the lowest index in config order. Thread-safe.
 */
public final class LeastInflightLoadBalancer implements LoadBalancer {

    private final ConcurrentHashMap<ChatBackend, AtomicInteger> inflight = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "least-inflight";
    }

    @Override
    public ChatBackend pick(String model, List<ChatBackend> candidates) {
        // Same contract guard as RoundRobinLoadBalancer (Review L2): the Router
        // guarantees non-empty candidate lists, but pick is a public method — fail with
        // the contract message instead of a bare NoSuchElementException from getFirst.
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "least-inflight pick requires a non-empty candidate list (model " + model + ")");
        }
        ChatBackend best = candidates.getFirst();
        int bestCount = count(best);
        for (int i = 1; i < candidates.size(); i++) {
            ChatBackend candidate = candidates.get(i);
            int count = count(candidate);
            if (count < bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    @Override
    public void onRequestStart(String model, ChatBackend backend) {
        inflight.computeIfAbsent(backend, b -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void onRequestEnd(String model, ChatBackend backend, boolean success, ChatResponse response) {
        AtomicInteger counter = inflight.get(backend);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    /** Package-private test seam: current in-flight count for {@code backend} (0 if never started). */
    int inflightOf(ChatBackend backend) {
        return count(backend);
    }

    private int count(ChatBackend backend) {
        AtomicInteger counter = inflight.get(backend);
        return counter == null ? 0 : counter.get();
    }
}
