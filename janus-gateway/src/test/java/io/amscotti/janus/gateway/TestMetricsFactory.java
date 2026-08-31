package io.amscotti.janus.gateway;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;

/**
 * shared gateway test composition root (the {@link TestGovernanceFactory}
 * pattern): replaces the production {@link MetricsFactory}'s recorder bean with
 * either {@link MetricsRecorder#noop} (the default — every existing suite that
 * does not opt in gets the no-op, so default behavior is byte-identical)
 * or a recorder backed by the shared real {@link PrometheusMeterRegistry} when a
 * class opts in with {@value #ENABLED_PROPERTY}:
 *
 * <ul>
 * <li>the shared {@link #REGISTRY} — tests clear it in {@code @BeforeEach} and
 * scrape the exposition text (JUnit executes test methods sequentially
 * within a class, and classes share this singleton bean);
 * <li>the {@link MicrometerMetricsRecorder} built on it — the same class the
 * production {@link MetricsFactory} wires, so the scrape text is the real
 * artifact, not a test double.
 * </ul>
 */
@Factory
@Requires(property = "janus.test.production-factories", notEquals = "true")
final class TestMetricsFactory {

    /** Opt-in property: absent/false ⇒ {@code MetricsRecorder.noop()}; "true" ⇒ real recorder. */
    static final String ENABLED_PROPERTY = "janus.test.metrics";

    /** Shared real registry; metrics tests clear() it in @BeforeEach. */
    static final PrometheusMeterRegistry REGISTRY = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @Singleton
    @Replaces(factory = MetricsFactory.class)
    MetricsRecorder metricsRecorder(Environment environment) {
        boolean enabled = environment.getProperty(ENABLED_PROPERTY, Boolean.class, false);
        if (!enabled) {
            return MetricsRecorder.noop(); // every non-opting suite keeps the no-op behavior
        }
        REGISTRY.clear();
        return new MicrometerMetricsRecorder(REGISTRY);
    }
}
