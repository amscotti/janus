package io.amscotti.janus.router;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import java.util.List;

/**
 * Load-balancing selection + observation seam. A strategy
 * instance owns whatever state it needs (round-robin counters, in-flight counts, latency
 * EMAs, cumulative costs, weights) and is thread-safe: concurrent {@link #pick} calls and
 * hook deliveries are supported.
 *
 * <p><b>Contract.</b>
 *
 * <ul>
 * <li>{@link #name} returns the config key the gateway's {@code [router]} TOML binds:
 * {@code "round-robin"}, {@code "least-inflight"}, {@code "latency-based"},
 * {@code "cost-based"}, {@code "weighted"} or {@code "session-affinity"}.
 * <li>{@link #pick(String, List, ChatRequest)} — the <b>router's entry point</b> —
 * selects one backend from the non-empty candidate list (config order preserved by
 * {@link Router#balanced}), optionally consulting the request (e.g. the
 * session-affinity strategy reads the {@code janus.session-id} meta entry the
 * gateway folds from the inbound {@code x-janus-session-id} header). It never
 * returns null; the router guarantees non-empty candidate lists by construction.
 * <li>{@link #pick(String, List)} is the request-blind form: the default the
 * 3-arg method delegates to, and the form direct/test callers may use. A strategy
 * that wants request-aware selection must override the <b>3-arg</b> form — the
 * router never calls the 2-arg form, so a strategy overriding only the 2-arg form
 * would be silently bypassed (this interface originally anticipated exactly this
 * additive growth: the five request-independent strategies ignore the request).
 * <li>The three observation hooks ({@link #onRequestStart}, {@link #onLatencySample},
 * {@link #onRequestEnd}) are {@code default} no-ops; each strategy overrides only
 * what it needs (least-inflight: start/end; latency: sample; cost: end). This
 * mirrors LiteLLM's pre-call vs success/failure logging separation and lets the
 * gateway add hooks (retry counts, breaker state) without breaking strategies.
 * </ul>
 *
 * <p><b>Hook wiring (balanced {@link Router} mode only).</b> {@code complete} fires
 * start → delegate → latency-sample(elapsed) → end(true, response); on exception — any
 * {@code Throwable}, including an {@link Error} — → end(false, null) and the exception
 * propagates untouched, so every strategy's end-hook state (e.g. least-inflight slots)
 * is released regardless of failure type. {@code stream} fires start → delegate →
 * latency-sample (time-to-first-chunk, on the first consumed element) → end(true, …)
 * when the stream is closed; a failure to <i>open</i> the stream → end(false, null) +
 * rethrow. On stream close the router passes a <b>synthetic</b> {@code ChatResponse}
 * carrying the terminal usage chunk observed during consumption (if any) — see
 * {@link #onRequestEnd} — so usage-billing strategies (e.g. cost-based) account for
 * streamed tokens too; a stream close with no terminal usage chunk ends with
 * {@code end(true, null)}. A stream that opens and closes cleanly is
 * {@code success=true} even if the consumer later hits a chunk-level error —
 * consumption-time errors, retries and breaker mid-stream behavior are the router's
 * concern and deliberately outside this contract.
 *
 * <p><b>State-keying rule.</b> In-flight, latency and cost state is keyed by backend
 * <i>identity</i> (a backend is a singleton per provider entry) so one backend
 * instance serving several aliases shares a single counter/EMA/cost total; {@code
 * WeightedLoadBalancer} keys by {@link ChatBackend#name} because weights are operator
 * config per provider entry.
 */
public interface LoadBalancer {

    /** Config key for {@code [router]} TOML binding. */
    String name();

    /**
     * Select one backend from the non-empty candidate list (config order). The
     * request-blind form — direct/test use; the router calls the {@linkplain
     * #pick(String, List, ChatRequest) 3-arg form} instead. Never null. An empty
     * candidate list is a caller bug → {@link IllegalArgumentException} with the
     * strategy's contract message (every shipped strategy guards this — never a bare
     * {@code NoSuchElementException} from {@code getFirst}).
     */
    ChatBackend pick(String model, List<ChatBackend> candidates);

    /**
     * Request-aware selection — <b>the router's entry point</b>. Every pick the
     * router makes (the attempt-0 pick and the all-tried re-pick) goes through this
     * form with the request the router is dispatching, so a request-aware strategy
     * (session-affinity reads the {@code janus.session-id} {@code ChatRequest.meta}
     * entry) sees each request exactly once per pick. The default delegates to the
     * {@linkplain #pick(String, List) 2-arg form}, so the request-independent
     * strategies are unchanged. <b>Override this form, not the 2-arg one</b> — the
     * router never calls the 2-arg form, so an override there would be silently
     * bypassed. Never null.
     */
    default ChatBackend pick(String model, List<ChatBackend> candidates, ChatRequest request) {
        return pick(model, candidates);
    }

    /** Called immediately before delegating to the picked backend. */
    default void onRequestStart(String model, ChatBackend backend) {}

    /** Called with a latency sample: total duration for complete(); time-to-first-chunk
     * for stream. Success samples only. */
    default void onLatencySample(String model, ChatBackend backend, long elapsedNanos) {}

    /** Called exactly once per request when it finishes: after complete() returns or
     * throws; when the stream is closed. {@code response} is non-null for successful
     * calls that carry usage: non-streaming completions (the backend's response), and
     * <b>streams whose consumption observed a terminal usage chunk</b> — the router
     * synthesizes a {@code ChatResponse} from the stream's last usage-bearing chunk
     * (only {@code usage} — with the chunk's id/model when present — is meaningful;
     * choices are empty and it never reaches the wire), exactly what
     * {@code CostBasedLoadBalancer} bills streams on. It is {@code null} for failures,
     * connect failures, and stream closes with no terminal usage observed (e.g. a
     * client-aborted stream, or an OpenAI-face stream without
     * {@code stream_options.include_usage}) — a custom strategy that ignores the
     * synthetic stream response under-bills streamed traffic. */
    default void onRequestEnd(String model, ChatBackend backend, boolean success, ChatResponse response) {}
}
