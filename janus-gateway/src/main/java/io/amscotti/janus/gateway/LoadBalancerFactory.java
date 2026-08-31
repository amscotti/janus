package io.amscotti.janus.gateway;

import io.amscotti.janus.JanusConfig.RouterConfig;
import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.CostBasedLoadBalancer;
import io.amscotti.janus.router.CostFunction;
import io.amscotti.janus.router.LatencyBasedLoadBalancer;
import io.amscotti.janus.router.LeastInflightLoadBalancer;
import io.amscotti.janus.router.LoadBalancer;
import io.amscotti.janus.router.RoundRobinLoadBalancer;
import io.amscotti.janus.router.SessionAffinityLoadBalancer;
import io.amscotti.janus.router.WeightedLoadBalancer;
import io.amscotti.janus.store.CostCalculator;
import io.amscotti.janus.store.PriceTable;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code [router]} strategy/weights/alpha → {@link LoadBalancer} mapping — the
 * config-name switch that roots all six strategy classes in native-image. Pure logic,
 * package-private, unit-testable without Micronaut or network.
 *
 * <p><b>Strategy switch (explicit — no reflection).</b> {@code "round-robin"} →
 * {@link RoundRobinLoadBalancer}, {@code "least-inflight"} →
 * {@link LeastInflightLoadBalancer}, {@code "latency-based"} →
 * {@link LatencyBasedLoadBalancer}, {@code "cost-based"} → {@link CostBasedLoadBalancer}
 * with a {@link CostFunction} priced from the operator {@link PriceTable} (integer
 * micro-USD via {@link CostCalculator}; empty table ⇒ zero-cost ties → config order),
 * {@code "weighted"} → {@link WeightedLoadBalancer}, {@code "session-affinity"} →
 * {@link SessionAffinityLoadBalancer}. Unknown strategy →
 * {@link IllegalStateException} listing the six config names.
 */
final class LoadBalancerFactory {

    private static final Logger LOG = System.getLogger("io.amscotti.janus.gateway.LoadBalancerFactory");

    private static final String STRATEGY_ROUND_ROBIN = "round-robin";
    private static final String STRATEGY_LEAST_INFLIGHT = "least-inflight";
    private static final String STRATEGY_LATENCY_BASED = "latency-based";
    private static final String STRATEGY_COST_BASED = "cost-based";
    private static final String STRATEGY_WEIGHTED = "weighted";
    private static final String STRATEGY_SESSION_AFFINITY = "session-affinity";

    private LoadBalancerFactory() {}

    /**
     * Real pricing for {@code cost-based}: micro-USD via {@link CostCalculator}, priced
     * through {@code PriceTable.rateFor(model, backend.name)} — a row keyed by the
     * backend (provider) name wins as a per-backend override, else the client-alias row
     * (two providers serving one alias at two prices must compare at their
     * own rates, not a shared alias rate). Empty/missing rows price at 0 → ties to
     * config order until the operator adds {@code [[janus.pricing.models]]} rows.
     */
    static CostFunction pricingCost(PriceTable priceTable) {
        PriceTable table = Objects.requireNonNull(priceTable, "priceTable");
        return (model, backend, response) -> {
            if (response == null || response.usage() == null) {
                return 0.0;
            }
            var usage = response.usage();
            return (double) CostCalculator.costMicroUsd(
                    usage, table.rateFor(model, backend.name()).forPromptTokens(usage.billedPromptTokens()));
        };
    }

    /**
     * Resolve an (possibly absent/all-null) {@code [router]} config to a fully-defaulted
     * one: {@code null} → {@link RouterConfig#DEFAULTS}; a present section fills only null
     * components from defaults. Never returns null.
     */
    static RouterConfig resolve(RouterConfig r) {
        if (r == null) {
            return RouterConfig.DEFAULTS;
        }
        RouterConfig d = RouterConfig.DEFAULTS;
        return new RouterConfig(
                r.strategy() == null ? d.strategy() : r.strategy(),
                r.latencyAlpha() == null ? d.latencyAlpha() : r.latencyAlpha(),
                r.weights() == null ? d.weights() : r.weights(),
                r.maxRetries() == null ? d.maxRetries() : r.maxRetries(),
                r.backoffBaseMs() == null ? d.backoffBaseMs() : r.backoffBaseMs(),
                r.backoffMaxMs() == null ? d.backoffMaxMs() : r.backoffMaxMs(),
                r.jitter() == null ? d.jitter() : r.jitter(),
                r.allowedFails() == null ? d.allowedFails() : r.allowedFails(),
                r.cooldownTime() == null ? d.cooldownTime() : r.cooldownTime(),
                r.breakerFailureThreshold() == null ? d.breakerFailureThreshold() : r.breakerFailureThreshold(),
                r.breakerWindowSeconds() == null ? d.breakerWindowSeconds() : r.breakerWindowSeconds(),
                r.breakerCooldownSeconds() == null ? d.breakerCooldownSeconds() : r.breakerCooldownSeconds());
    }

    /**
     * Strategy switch with the operator's {@link PriceTable} wired into cost-based
     * selection (production path from {@link RouterFactory}). The one-arg
     * {@code create(RouterConfig)} overload (a {@link PriceTable#EMPTY} convenience for
     * tests) was removed — main code carries no test-only surface, and tests pass
     * {@code PriceTable.EMPTY} explicitly so the production pricing path is the only one.
     */
    static LoadBalancer create(RouterConfig r, PriceTable priceTable) {
        return switch (r.strategy()) {
            case STRATEGY_ROUND_ROBIN -> new RoundRobinLoadBalancer();
            case STRATEGY_LEAST_INFLIGHT -> new LeastInflightLoadBalancer();
            case STRATEGY_LATENCY_BASED ->
                // Staleness window = the health cooldown (SECONDS → millis): a backend that
                // spent a cooldown out of rotation must be re-sampled before it competes on
                // its stale EMA — the same cooldown that re-admits it.
                new LatencyBasedLoadBalancer(r.latencyAlpha(), r.cooldownTime() * 1000L);
            case STRATEGY_COST_BASED -> new CostBasedLoadBalancer(pricingCost(priceTable));
            case STRATEGY_WEIGHTED -> new WeightedLoadBalancer(r.weights());
            case STRATEGY_SESSION_AFFINITY -> new SessionAffinityLoadBalancer();
            default ->
                throw new IllegalStateException(
                        "unknown load-balancing strategy \"" + r.strategy() + "\" (expected one of: "
                                + STRATEGY_ROUND_ROBIN + ", " + STRATEGY_LEAST_INFLIGHT + ", "
                                + STRATEGY_LATENCY_BASED + ", " + STRATEGY_COST_BASED + ", "
                                + STRATEGY_WEIGHTED + ", " + STRATEGY_SESSION_AFFINITY + ")");
        };
    }

    /**
     * Weighted-strategy boot warnings: logs each warning and returns the texts so tests
     * can pin them. No-op for non-weighted strategies when not called.
     */
    static List<String> warnAboutWeights(Map<String, List<ChatBackend>> routes, Map<String, Integer> weights) {
        List<String> warnings = new ArrayList<>();
        Set<String> listedBackends = new HashSet<>();
        for (Map.Entry<String, List<ChatBackend>> entry : routes.entrySet()) {
            for (ChatBackend backend : entry.getValue()) {
                listedBackends.add(backend.name());
                Integer weight = weights.get(backend.name());
                if (weight == null || weight <= 0) {
                    warnings.add("weighted strategy: backend \"" + backend.name() + "\" (alias \""
                            + entry.getKey() + "\") has no positive weight — it will be excluded from the pool"
                            + " (all excluded → first-available fallback)");
                }
            }
        }
        for (String key : weights.keySet()) {
            if (!listedBackends.contains(key)) {
                warnings.add("weighted strategy: weight key \"" + key + "\" matches no listed backend"
                        + " (typo? the backend will never be selected)");
            }
        }
        for (String warning : warnings) {
            LOG.log(Level.WARNING, warning);
        }
        return Collections.unmodifiableList(warnings);
    }

    /**
     * Cost-based-strategy boot warning: with an empty price table every
     * response prices at 0, so {@code CostBasedLoadBalancer#pick} sees a 0-vs-0 tie and
     * permanently returns the first candidate — strictly worse than round-robin. Mirrors
     * {@link #warnAboutWeights}: logs the warning and returns its text so tests can pin
     * it. Quiet when the table has at least one row (alias or per-backend override) —
     * real prices are then possible even if a specific alias still lacks a row (that
     * alias prices at 0 until the operator adds one, logged per unknown alias at
     * request time).
     */
    static List<String> warnAboutCosts(PriceTable priceTable) {
        if (!priceTable.isEmpty()) {
            return List.of();
        }
        String warning = "cost-based strategy: the pricing table has no rows — every response prices at $0, so"
                + " cost-based selection ties to config order (the first candidate wins every pick, backend"
                + " 2 is never served). Add [[janus.pricing.models]] rows (a backend/provider-name row is a"
                + " per-backend override) to enable real cost-based routing, or switch strategy to"
                + " \"round-robin\" until rows exist";
        LOG.log(Level.WARNING, warning);
        return List.of(warning);
    }

    /**
     * Session-affinity boot warning: {@code weights} are a {@code weighted}-strategy
     * knob and are ignored under {@code session-affinity} (the HRW hash decides every
     * pick) — an operator who configures both must hear about it at boot, not wonder
     * later why the split never matches the weights. Mirrors {@link #warnAboutWeights}
     * and {@link #warnAboutCosts}: logs the warning and returns its text so tests can
     * pin it. Quiet when no weights are configured.
     */
    static List<String> warnAboutIgnoredWeights(Map<String, Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            return List.of();
        }
        String warning = "session-affinity strategy: the configured [janus.router] weights are ignored —"
                + " session-affinity picks by rendezvous hash of the x-janus-session-id header"
                + " (remove the weights, or switch strategy to \"weighted\" if the split was the intent)";
        LOG.log(Level.WARNING, warning);
        return List.of(warning);
    }
}
