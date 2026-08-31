package io.amscotti.janus.gateway;

import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.CircuitBreaker;
import io.amscotti.janus.router.Router;
import io.amscotti.janus.router.UpstreamHealth;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * composition root for the metrics beans (the {@link GovernanceFactory} pattern):
 * produces the {@link MetricsRecorder} on the auto-configured {@code MeterRegistry}
 * (the Prometheus exporter's registry — the same one {@code GET /metrics} scrapes)
 * and registers the per-provider health/breaker gauges from the {@code UpstreamHealth}
 * + {@code CircuitBreaker} instances {@link RouterFactory} retained via the
 * {@link RouterResilience} bean (the router's own instances — no {@code janus-router}
 * change). {@code MetricsFactory} <b>hard-depends</b> on
 * {@link RouterResilience} <em>and</em> the {@link Router} bean — Micronaut resolves
 * {@code router} (which populates the resilience bean) before this factory runs, so
 * the gauge registration can never silently skip because {@code router} "hasn't run
 * yet" (the bean-ordering hazard; pinned by {@code ProductionMetricsExposition
 * Test}). A context that replaces {@link RouterFactory} (or produces no router state)
 * simply registers nothing — the resilience bean's empty backends list is a no-op.
 *
 * <p><b>Gauges (per {@code provider} = {@code ChatBackend.name}).</b>
 *
 * <pre>
 * janus_upstream_healthy 1 = dispatch-eligible (healthy, or cooldown-elapsed
 * trial-eligible), 0 = unhealthy
 * janus_upstream_breaker_state 0 = CLOSED, 1 = HALF_OPEN, 2 = OPEN
 * </pre>
 *
 * Both are labeled {@code provider} + {@code base_url}: the same provider
 * name can back several distinct backend <em>instances</em> (two entries for one
 * provider under different aliases), and a provider-only label would collapse them
 * onto one series Prometheus resolves last-wins — reporting one upstream's state as
 * another's. The base URL is the coarse per-instance identity (Tier-1-permitted; never
 * request text, model alias or request id). When two backends share <b>both</b> the
 * provider name <b>and</b> the base URL (the reachable default shape: two aliases,
 * {@code provider = "deepseek"}, base URL omitted → the adapter's default), the
 * {@code (provider, base_url)} identity is <b>not</b> unique — Micrometer treats a
 * meter id (name + tags) as unique, so a second {@code Gauge.builder(...).register}
 * is a silent no-op returning the existing meter. Such a pair is
 * <b>de-duplicated at registration</b> — the first backend registers its gauge and
 * wins, the second is skipped with a boot warning (documented below), so one backend's
 * health/breaker state can never be reported under the other's identity. Both are
 * registered with <b>state
 * suppliers</b> (the value function re-reads the router's live state on every scrape —
 * never a snapshot), so a state flip after registration shows up on the next scrape
 * without any re-registration (pinned by {@code MetricsFactoryTest}). The healthy
 * supplier reads {@link UpstreamHealth#passivelyHealthy} per backend — exact, and free
 * of the fail-open {@code healthy} quirk (an all-unhealthy subset would return the
 * full input). The gauge path deliberately uses {@code passivelyHealthy}, never
 * {@code healthy} — the active {@link HealthProbe} seam is contractually able to
 * perform network I/O, and a {@code /metrics} scrape must never trigger synchronous
 * per-backend probe calls from the scrape thread (pinned by {@code MetricsFactoryTest}).
 */
@Factory
class MetricsFactory {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsFactory.class);

    static final String GAUGE_HEALTHY = "janus_upstream_healthy";
    static final String GAUGE_BREAKER_STATE = "janus_upstream_breaker_state";
    private static final String TAG_PROVIDER = "provider";
    private static final String TAG_BASE_URL = "base_url";

    /**
     * Hard-depends on {@link Router} (forces {@code router} to run —
     * which populates {@link RouterResilience}) and on {@link RouterResilience} itself
     * (the gauge state). The {@code Router} parameter is deliberately not named/used:
     * it exists only to make the creation order an explicit DI edge instead of a
     * constructor-parameter-order accident.
     */
    @Singleton
    MetricsRecorder metricsRecorder(MeterRegistry registry, RouterResilience resilience, Router router) {
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);
        registerHealthGauges(registry, resilience.health(), resilience.breaker(), resilience.backends());
        return recorder;
    }

    /**
     * Register the per-provider health/breaker gauges on {@code registry}. Static so
     * {@code MetricsFactoryTest} drives it directly with fixture state and a fixed
     * clock (no Micronaut context); the DI wiring calls it with the router-retained
     * instances. Null health/breaker (no router state) is a no-op.
     */
    static void registerHealthGauges(
            MeterRegistry registry, UpstreamHealth health, CircuitBreaker breaker, List<ChatBackend> backends) {
        if (health == null || breaker == null) {
            return;
        }
        Set<BackendIdentity> registered = new HashSet<>();
        for (ChatBackend backend : backends) {
            String sanitizedUrl = sanitizedBaseUrl(backend.baseUrl());
            BackendIdentity identity = new BackendIdentity(backend.name(), sanitizedUrl);
            if (!registered.add(identity)) {
                // The (provider, base_url) label set is the meter identity,
                // and Micrometer de-duplicates a re-registered id — a second register
                // is a silent no-op returning the first meter. The first backend's
                // state supplier wins, so the second's health/breaker state would never
                // be published (and a later flip of the first would show under a label
                // set that also covers the second). Skip with a boot warning (documented
                // behavior) instead of silently losing one upstream's series.
                // The warning prints the SANITIZED URL (review H2): the raw one may
                // carry query/userinfo credentials that must not reach the logs.
                LOG.warn(
                        "duplicate health/breaker gauge identity (provider=\"{}\", base_url=\"{}\") — another"
                                + " model-list entry already registers this identity, so this backend's state is"
                                + " not published as its own series (the first entry wins; per-instance identity is"
                                + " provider × base URL — see MetricsFactory)",
                        backend.name(),
                        sanitizedUrl);
                continue;
            }
            // strongReference(true): Micrometer's default Gauge holds only a WEAK
            // reference to the state object — the registry does not keep it alive, so
            // a collection between register and scrape silently turns the gauge into
            // NaN. Production wires the RouterResilience-retained singletons (always
            // strongly reachable), but the registry must not depend on that lifetime
            // accident: retain the reference at the meter — this is exactly the shape
            // MetricsFactoryTest drives (registerHealthGauges with temporaries), the
            // configuration that flaked the suite scrape.
            Gauge.builder(GAUGE_HEALTHY, health, h -> healthyValue(h, backend))
                    .tag(TAG_PROVIDER, backend.name())
                    .tag(TAG_BASE_URL, sanitizedUrl)
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(GAUGE_BREAKER_STATE, breaker, b -> breakerValue(b, backend))
                    .tag(TAG_PROVIDER, backend.name())
                    .tag(TAG_BASE_URL, sanitizedUrl)
                    .strongReference(true)
                    .register(registry);
        }
    }

    /**
     * The base_url label value: {@code scheme://host[:port][/path]} ONLY. The raw
     * operator-configured URL is never used as a label (review H2 — Tier-1 hardening):
     * OpenAI-compatible providers commonly accept keys as a query parameter
     * ({@code …/v1?api-key=…}) and URLs can carry userinfo credentials
     * ({@code https://user:pass@host}); {@code /metrics} is unauthenticated by design,
     * so an embedded credential would be published verbatim to any scraper. A URL that
     * does not parse down to scheme+host publishes {@code <redacted>} rather than any
     * raw fragment.
     */
    static String sanitizedBaseUrl(String baseUrl) {
        try {
            var uri = java.net.URI.create(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "<redacted>";
            }
            StringBuilder sanitized =
                    new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() != -1) {
                sanitized.append(':').append(uri.getPort());
            }
            if (uri.getPath() != null && !uri.getPath().isEmpty()) {
                sanitized.append(uri.getPath());
            }
            return sanitized.toString();
        } catch (RuntimeException e) {
            return "<redacted>";
        }
    }

    private static double healthyValue(UpstreamHealth health, ChatBackend backend) {
        return health.passivelyHealthy(backend) ? 1.0 : 0.0;
    }

    private static double breakerValue(CircuitBreaker breaker, ChatBackend backend) {
        return switch (breaker.state(backend)) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
        };
    }

    /** The gauge's meter identity: the {@code (provider, base_url)} label set. */
    private record BackendIdentity(String provider, String baseUrl) {}
}
