package io.amscotti.janus.router;

import io.amscotti.janus.core.model.ChatResponse;

/**
 * Pluggable cost seam for {@link CostBasedLoadBalancer}. The gateway wires real
 * pricing tables ({@code PriceTable} + micro-USD calculator); unit tests supply fixed
 * price maps. Implementations must be thread-safe and side-effect free — the strategy
 * invokes the function once per successful response that carries a non-null
 * {@link ChatResponse} (non-streaming completions and clean stream closes with terminal
 * usage).
 */
@FunctionalInterface
public interface CostFunction {

    /**
     * Cost of one successful response served by {@code backend} for client-alias
     * {@code model}. {@code response} is non-null when the strategy calls this; usage
     * may still be null (then cost should typically be 0). The gateway's pricing table
     * prices a row keyed by {@code backend.name} (a per-backend override) above the
     * {@code model} row, so multi-provider aliases compare at their own rates.
     */
    double costOf(String model, ChatBackend backend, ChatResponse response);
}
