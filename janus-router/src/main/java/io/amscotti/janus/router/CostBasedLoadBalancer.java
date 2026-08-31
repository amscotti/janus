package io.amscotti.janus.router;

import io.amscotti.janus.core.model.ChatResponse;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cost-based selection: pick the backend with the lowest cumulative cost accumulated from
 * actual usage. Config key: {@code "cost-based"}.
 *
 * <p><b>Spend-equalizing, not price-picking.</b> Despite a surface resemblance to
 * LiteLLM's {@code lowest-cost} (which picks the statically cheapest deployment on every
 * request, gated by TPM/RPM caps janus lacks), this strategy equalizes <b>cumulative
 * spend</b>: the cheaper backend is picked more often while the expensive one is still
 * served until its spend converges. The steady state is spend parity across backends, not
 * "always the cheapest" — a legitimate strategy for provider redundancy where each
 * upstream keeps a share of traffic.
 *
 * <p>State: one cumulative double per backend, keyed by <b>identity</b>, accumulated in
 * {@link #onRequestEnd} for successful responses with a non-null {@code response}
 * (non-streaming completions and clean stream closes that observed terminal usage)
 * via the pluggable {@link CostFunction}. A null {@code response.usage} contributes
 * 0; failed calls and client-aborted streams (no response) are not counted. The gateway
 * wires real pricing tables (a row keyed by the backend/provider name overrides the
 * client-alias row — the multi-provider-per-alias comparison); unit tests supply fixed
 * price maps. Ties go to the lowest index in config order. Thread-safe.
 *
 * <p><b>Identity-keyed, so aliases couple (documented {@link LoadBalancer} state-keying
 * rule).</b> A backend serving several aliases accumulates blended spend across them
 * (each response priced at its own alias's/backend's rate), so a candidate's total for
 * one alias includes spend incurred under every other alias it serves. For multi-alias
 * configs this silently couples aliases — selection for alias {@code Y} is biased toward
 * whichever backend did not serve the pricey alias {@code X}. Bounded and intentional
 * (the identity-keying contract), but operators with starkly different per-alias rates
 * should expect the coupling.
 *
 * <p><b>Streaming caveat.</b> On the OpenAI wire a terminal usage chunk appears
 * only when the client sent {@code stream_options.include_usage}; Janus never forces it
 * upstream (byte-golden), so OpenAI-face streams without it contribute $0 while
 * Anthropic streams always carry usage. For an alias backed by one of each, cost-based
 * selection is systematically biased toward the OpenAI backend until clients opt into
 * {@code include_usage} — a documented limitation shared with the governance ledger.
 */
public final class CostBasedLoadBalancer implements LoadBalancer {

    private final CostFunction costFunction;
    private final ConcurrentHashMap<ChatBackend, Double> costs = new ConcurrentHashMap<>();

    public CostBasedLoadBalancer(CostFunction costFunction) {
        this.costFunction = Objects.requireNonNull(costFunction, "costFunction");
    }

    @Override
    public String name() {
        return "cost-based";
    }

    @Override
    public ChatBackend pick(String model, List<ChatBackend> candidates) {
        // Same contract guard as RoundRobinLoadBalancer (Review L2): the Router
        // guarantees non-empty candidate lists, but pick is a public method — fail with
        // the contract message instead of a bare NoSuchElementException from getFirst.
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "cost-based pick requires a non-empty candidate list (model " + model + ")");
        }
        ChatBackend best = candidates.getFirst();
        double bestCost = total(best);
        for (int i = 1; i < candidates.size(); i++) {
            ChatBackend candidate = candidates.get(i);
            double cost = total(candidate);
            if (cost < bestCost) {
                best = candidate;
                bestCost = cost;
            }
        }
        return best;
    }

    @Override
    public void onRequestEnd(String model, ChatBackend backend, boolean success, ChatResponse response) {
        if (success && response != null) {
            double cost = response.usage() == null ? 0.0 : costFunction.costOf(model, backend, response);
            costs.compute(backend, (b, total) -> (total == null ? 0.0 : total) + cost);
        }
    }

    /** Package-private test seam: cumulative cost for {@code backend} (0.0 if never billed). */
    double cumulativeCostOf(ChatBackend backend) {
        return total(backend);
    }

    private double total(ChatBackend backend) {
        Double cost = costs.get(backend);
        return cost == null ? 0.0 : cost;
    }
}
