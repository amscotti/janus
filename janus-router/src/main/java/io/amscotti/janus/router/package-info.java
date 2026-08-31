/**
 * Router + load balancing: maps a caller-supplied model alias to one or more
 * provider backends.
 *
 * <p>This module depends on {@code janus-core} only. It never imports {@code
 * io.amscotti.janus.provider.*}. The provider-agnostic seam is {@link
 * ChatBackend}; the gateway adapts a {@code ProviderAdapter} and builds the
 * {@code Map<String, ChatBackend>} from the TOML {@code model-list}.
 *
 * <p>{@link Router#balanced} selects among backends for one alias through
 * {@link LoadBalancer} — {@link RoundRobinLoadBalancer}, {@link
 * LeastInflightLoadBalancer}, {@link LatencyBasedLoadBalancer}, {@link
 * CostBasedLoadBalancer}, {@link WeightedLoadBalancer}. {@link
 * Router#resilient} layers {@link PassiveUpstreamHealth}, {@link RetryPolicy},
 * {@link CircuitBreaker}, and config-order fallback. Streaming retries only on
 * connect-time failures, never after the first chunk. {@code balanced}
 * delegates to {@code resilient} with {@link ResilienceConfig#none}.
 *
 * <p>TOML binding and HTTP faces live in {@code janus-gateway}.
 */
package io.amscotti.janus.router;
