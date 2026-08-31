package io.amscotti.janus.gateway;

import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.CircuitBreaker;
import io.amscotti.janus.router.UpstreamHealth;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * The router's resilience state as its own {@code @Singleton}
 * bean: {@link RouterFactory#router} <b>populates</b> this bean (the same
 * {@code UpstreamHealth} + {@code CircuitBreaker} instances the router consumes, plus
 * the distinct provider backends), and {@link MetricsFactory} <b>hard-depends</b> on it
 * for the per-provider {@code janus_upstream_healthy} /
 * {@code janus_upstream_breaker_state} gauges. This removes the bean-ordering
 * hazard: before, {@code MetricsFactory} read the state off a {@code @Nullable
 * RouterFactory} whose fields were populated only when {@code router} ran, so a bean
 * that resolved {@code MetricsRecorder} before the {@code Router} silently skipped the
 * gauges forever — with no boot error and no test coverage (every {@code @MicronautTest}
 * replaced the factory). Now the dependency edge is explicit (see {@link
 * MetricsFactory}); the values themselves are read through state suppliers, so nothing
 * re-registers on a state flip.
 *
 * <p>Behavior-neutral: the retained instances are the router's own (no {@code
 * janus-router} change); an empty/absent router state (no model list, or a test context
 * that replaces {@link RouterFactory}) leaves an empty backends list, which
 * {@link MetricsFactory#registerHealthGauges} treats as a no-op (null health/breaker
 * likewise). {@code populate} is idempotent — {@code router} runs once per context,
 * and a re-run (test context rebuild) simply overwrites with fresh instances.
 *
 * <p>Not thread-safe by design: {@code populate} runs during bean creation, before any
 * scrape can observe the gauges; the gauge <i>values</i> re-read the live router state
 * on every scrape (never this bean's snapshot).
 */
@Singleton
final class RouterResilience {

    private UpstreamHealth health;
    private CircuitBreaker breaker;
    private List<ChatBackend> backends = List.of();

    /** Called by {@link RouterFactory#router()} after the resilience bundle is built. */
    void populate(UpstreamHealth health, CircuitBreaker breaker, List<ChatBackend> backends) {
        this.health = health;
        this.breaker = breaker;
        this.backends = backends;
    }

    /** The router's per-upstream health, or null before {@code router()} ran. */
    UpstreamHealth health() {
        return health;
    }

    /** The router's per-upstream breaker, or null before {@code router()} ran. */
    CircuitBreaker breaker() {
        return breaker;
    }

    /** The distinct provider backends the gauges are labeled by (empty before populate). */
    List<ChatBackend> backends() {
        return backends;
    }
}
