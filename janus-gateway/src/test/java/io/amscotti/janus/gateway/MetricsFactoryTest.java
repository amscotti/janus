package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.amscotti.janus.router.CircuitBreaker;
import io.amscotti.janus.router.CircuitBreakerConfig;
import io.amscotti.janus.router.PassiveUpstreamHealth;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link MetricsFactory} per-provider health/breaker gauges (fixed clock,
 * fixture states; no Micronaut context): a healthy provider reads 1, a provider past
 * {@code allowed-fails} reads 0, and the breaker gauge tracks CLOSED (0) → OPEN (2) →
 * HALF_OPEN (1). The gauges are registered with <b>state suppliers</b> — the same
 * registered gauge reflects the state flips on re-scrape, never a registration-time
 * snapshot (the plan's "register with supplier, not snapshot" bar).
 */
class MetricsFactoryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final FakeBackend backend = new FakeBackend("deepseek");

    /** The label set: provider + base_url (per-instance identity), labels sorted A-Z. */
    private static String line(String metric, String name, String baseUrl, double value) {
        return metric + "{base_url=\"" + baseUrl + "\",provider=\"" + name + "\"} " + value;
    }

    @BeforeEach
    void reset() {
        registry.clear();
    }

    @Test
    void healthyProviderGaugeReadsOne() {
        MetricsFactory.registerHealthGauges(registry, health(2), breaker(2), List.of(backend));

        assertLine(registry.scrape(), line("janus_upstream_healthy", "deepseek", backend.baseUrl(), 1.0));
        assertLine(registry.scrape(), line("janus_upstream_breaker_state", "deepseek", backend.baseUrl(), 0.0));
    }

    @Test
    void providerPastAllowedFailsGaugeReadsZero() {
        PassiveUpstreamHealth health = health(2);
        health.recordFailure(backend);
        health.recordFailure(backend); // 2 consecutive ≥ allowedFails → unhealthy

        MetricsFactory.registerHealthGauges(registry, health, breaker(2), List.of(backend));
        assertLine(registry.scrape(), line("janus_upstream_healthy", "deepseek", backend.baseUrl(), 0.0));
    }

    @Test
    void successfulRecoveryFlipsGaugeBackToOne() {
        PassiveUpstreamHealth health = health(2);
        health.recordFailure(backend);
        health.recordFailure(backend);
        MetricsFactory.registerHealthGauges(registry, health, breaker(2), List.of(backend));
        assertLine(registry.scrape(), line("janus_upstream_healthy", "deepseek", backend.baseUrl(), 0.0));

        // Passive recovery: the supplier re-reads live state — no re-registration.
        health.recordSuccess(backend);
        assertLine(registry.scrape(), line("janus_upstream_healthy", "deepseek", backend.baseUrl(), 1.0));
    }

    @Test
    void breakerClosedReadsZero() {
        MetricsFactory.registerHealthGauges(registry, health(2), breaker(2), List.of(backend));
        assertLine(registry.scrape(), line("janus_upstream_breaker_state", "deepseek", backend.baseUrl(), 0.0));
    }

    @Test
    void breakerOpenReadsTwoThenHalfOpenReadsOne() {
        MutableClock clock = new MutableClock(CLOCK.millis());
        CircuitBreaker breaker = CircuitBreaker.create(
                new CircuitBreakerConfig(2, Duration.ofSeconds(60), Duration.ofSeconds(30)), clock);
        breaker.recordConnectFailure(backend);
        breaker.recordConnectFailure(backend); // threshold reached → OPEN

        MetricsFactory.registerHealthGauges(registry, health(2), breaker, List.of(backend));
        assertLine(registry.scrape(), line("janus_upstream_breaker_state", "deepseek", backend.baseUrl(), 2.0));

        // Cooldown (30s) elapses; the router's dispatch-time claim moves OPEN → HALF_OPEN.
        clock.advance(31_000);
        assertTrue(breaker.canTry(backend), "cooldown elapsed ⇒ the gate admits a probe");
        assertTrue(breaker.claimProbe(backend), "the probe slot is claimed");
        assertLine(registry.scrape(), line("janus_upstream_breaker_state", "deepseek", backend.baseUrl(), 1.0));
    }

    @Test
    void nullHealthOrBreakerIsANoOp() {
        MetricsFactory.registerHealthGauges(registry, null, breaker(2), List.of(backend));
        MetricsFactory.registerHealthGauges(registry, health(2), null, List.of(backend));
        MetricsFactory.registerHealthGauges(registry, health(2), breaker(2), List.of());

        String scrape = registry.scrape();
        assertFalse(scrape.contains("janus_upstream_"), scrape);
        assertEquals("", scrape);
    }

    @Test
    void gaugesAreLabeledByProviderName() {
        FakeBackend other = new FakeBackend("anthropic");
        MetricsFactory.registerHealthGauges(registry, health(2), breaker(2), List.of(backend, other));

        String scrape = registry.scrape();
        assertLine(scrape, line("janus_upstream_healthy", "deepseek", backend.baseUrl(), 1.0));
        assertLine(scrape, line("janus_upstream_healthy", "anthropic", other.baseUrl(), 1.0));
        assertLine(scrape, line("janus_upstream_breaker_state", "deepseek", backend.baseUrl(), 0.0));
        assertLine(scrape, line("janus_upstream_breaker_state", "anthropic", other.baseUrl(), 0.0));
    }

    @Test
    void sameNameBackendsWithDistinctBaseUrlsRegisterDistinctSeries() {
        // One provider name backs two distinct backend instances (two entries
        // for the same provider under different aliases). The base_url label keeps the
        // two gauge series distinguishable — a provider-only label would collapse them
        // last-wins, reporting one upstream's state as another's.
        FakeBackend prod = new FakeBackend("deepseek", "https://prod.deepseek");
        FakeBackend dev = new FakeBackend("deepseek", "https://dev.deepseek");
        PassiveUpstreamHealth health = health(1);
        health.recordFailure(prod); // prod unhealthy, dev healthy
        health.recordSuccess(dev);

        MetricsFactory.registerHealthGauges(registry, health, breaker(1), List.of(prod, dev));

        String scrape = registry.scrape();
        assertLine(scrape, line("janus_upstream_healthy", "deepseek", "https://prod.deepseek", 0.0));
        assertLine(scrape, line("janus_upstream_healthy", "deepseek", "https://dev.deepseek", 1.0));
        assertEquals(2, count(scrape, "janus_upstream_healthy"), "two distinct series, not one collapsed");
    }

    @Test
    void sameNameAndBaseUrlBackendsCollapseToOneSeriesWithAWarning() {
        // Two model-list entries for the same provider under different aliases
        // produce two distinct backends whose name AND base URL are identical (the canonical
        // deepseek-v4-flash + deepseek-v4-pro shape — both provider "deepseek", both omitting
        // base-url → the adapter's default). Micrometer treats a meter id (name + tags) as
        // unique, so the second register was a silent no-op returning the first gauge —
        // the second instance's state was never published and the first's was reported under
        // a label set covering both. The fix de-duplicates at registration: the FIRST backend
        // wins (its state supplier owns the series), the second is skipped with a boot
        // warning, so one backend's state can never be reported as another's.
        FakeBackend first = new FakeBackend("deepseek");
        FakeBackend second = new FakeBackend("deepseek", first.baseUrl());
        PassiveUpstreamHealth health = health(1);
        health.recordFailure(first); // first unhealthy, second healthy — must not bleed through
        health.recordSuccess(second);

        ListAppender<ILoggingEvent> logs = captureLogs(MetricsFactory.class);
        MetricsFactory.registerHealthGauges(registry, health, breaker(1), List.of(first, second));

        String scrape = registry.scrape();
        assertLine(scrape, line("janus_upstream_healthy", "deepseek", first.baseUrl(), 0.0));
        assertEquals(1, count(scrape, "janus_upstream_healthy"), "one series — the second backend is deduped");
        assertTrue(
                logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("deepseek")),
                "the dedup must log a boot warning:\n" + logs.list);
    }

    @Test
    void gaugeScrapeDoesNotInvokeTheActiveHealthProbe() {
        // The gauge supplier consults passive state only — a wired active probe
        // (contractually able to perform network I/O) must never fire from a /metrics
        // scrape, even for a backend on cooldown probation where healthy would consult it.
        AtomicInteger probeCalls = new AtomicInteger();
        PassiveUpstreamHealth health =
                new PassiveUpstreamHealth(1, 0, Clock.offset(CLOCK, Duration.ofSeconds(2)), b -> {
                    probeCalls.incrementAndGet();
                    return false; // a vetoing probe: the old healthy() path would consult it
                });
        health.recordFailure(backend); // allowedFails=1 → unhealthy; cooldown 0 ⇒ instantly probation-elapsed

        MetricsFactory.registerHealthGauges(registry, health, breaker(1), List.of(backend));
        // Passive trial-eligibility reads 1 (cooldown elapsed = dispatch-eligible) with
        // no probe side effect — the observability answer, minus the probe veto.
        assertLine(registry.scrape(), line("janus_upstream_healthy", "deepseek", backend.baseUrl(), 1.0));
        assertEquals(0, probeCalls.get(), "a /metrics scrape must never trigger active probe calls");
    }

    @Test
    void baseUrlLabelStripsQueryAndUserinfo() {
        // Tier-1 hardening (review H2): OpenAI-compatible providers commonly accept keys
        // as a query parameter (…/v1?api-key=…), and URLs can carry userinfo credentials
        // (https://user:pass@host). The base_url label is published on the unauthenticated
        // /metrics endpoint — it must carry scheme://host[:port]/path ONLY, never the
        // query or userinfo, or an operator's embedded credential is published verbatim.
        FakeBackend withQuery =
                new FakeBackend("openai-compatible", "https://api.example.com/v1?api-key=sk-super-secret");
        FakeBackend withUserinfo = new FakeBackend("openai-compatible", "https://user:pass@internal.example.com/v1");
        PassiveUpstreamHealth health = health(1);
        health.recordSuccess(withQuery);
        health.recordSuccess(withUserinfo);

        MetricsFactory.registerHealthGauges(registry, health, breaker(1), List.of(withQuery, withUserinfo));

        String scrape = registry.scrape();
        assertFalse(scrape.contains("sk-super-secret"), "query credentials must never appear in a scrape:\n" + scrape);
        assertFalse(scrape.contains("user:pass"), "userinfo credentials must never appear in a scrape:\n" + scrape);
        assertLine(scrape, line("janus_upstream_healthy", "openai-compatible", "https://api.example.com/v1", 1.0));
        assertLine(scrape, line("janus_upstream_healthy", "openai-compatible", "https://internal.example.com/v1", 1.0));
    }

    @Test
    void backendsWithSameSanitizedUrlCollapseToOneSeries() {
        // Two instances of one provider whose URLs differ ONLY by query collapse onto the
        // sanitized identity — the dedup warning names the sanitized URL too (a raw-URL
        // warning would leak the credential into logs).
        FakeBackend first = new FakeBackend("deepseek", "https://api.deepseek.com?key-one=aaa");
        FakeBackend second = new FakeBackend("deepseek", "https://api.deepseek.com?key-two=bbb");
        PassiveUpstreamHealth health = health(1);
        health.recordSuccess(first);
        health.recordSuccess(second);

        ListAppender<ILoggingEvent> logs = captureLogs(MetricsFactory.class);
        MetricsFactory.registerHealthGauges(registry, health, breaker(1), List.of(first, second));

        String scrape = registry.scrape();
        assertFalse(scrape.contains("key-one"), scrape);
        assertFalse(scrape.contains("key-two"), scrape);
        assertEquals(1, count(scrape, "janus_upstream_healthy"), "one sanitized series");
        assertTrue(
                logs.list.stream()
                        .noneMatch(e -> e.getFormattedMessage().contains("key-one")
                                || e.getFormattedMessage().contains("key-two")),
                "the dedup warning must print the sanitized URL only:\n" + logs.list);
    }

    private PassiveUpstreamHealth health(int allowedFails) {
        return new PassiveUpstreamHealth(allowedFails, 60_000, CLOCK);
    }

    /** Attach a recording logback appender to {@code loggerClass}'s logger. */
    private static ListAppender<ILoggingEvent> captureLogs(Class<?> loggerClass) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private CircuitBreaker breaker(int threshold) {
        return CircuitBreaker.create(
                new CircuitBreakerConfig(threshold, Duration.ofSeconds(60), Duration.ofSeconds(30)), CLOCK);
    }

    private static void assertLine(String scrape, String expected) {
        assertTrue(
                scrape.lines().anyMatch(line -> line.equals(expected)),
                "expected line: " + expected + "\nscrape:\n" + scrape);
    }

    /** How many label sets a series currently publishes (distinct-series assertion). */
    private static long count(String scrape, String series) {
        return scrape.lines().filter(line -> line.startsWith(series + "{")).count();
    }

    /** Fixed-zone mutable clock (the / discipline — no sleeping, no real time). */
    private static final class MutableClock extends Clock {

        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long deltaMillis) {
            millis += deltaMillis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("MutableClock is fixed-zone");
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
