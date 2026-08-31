package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.CircuitBreaker;
import io.amscotti.janus.router.Router;
import io.amscotti.janus.router.UpstreamHealth;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The <b>production</b>-DI resilience-clock test: the one {@code @MicronautTest}
 * that opts into the real factories ({@code janus.test.production-factories=true}, the
 * {@code ProductionMetricsExpositionTest} pattern) AND replaces the {@link Clock} bean
 * with a mutable fixed clock, then asserts the {@link RouterFactory} threads that bean
 * into the resilience bundle. (Previously {@code router} built the health/breaker with
 * {@code toResilience(r)} / {@code toBreaker(r)} (private {@code Clock.systemUTC}), a
 * seam inconsistency with the store/ledger/limiter/governance all running on the one
 * {@code CallStoreFactory} {@code Clock} bean. The bundle's cooldowns here advance
 * <b>exactly</b> with the bean clock — if {@code router} regressed to a system clock,
 * advancing the fixed (past) bean clock would move nothing: the health/breaker would
 * stay frozen on the pre-cooldown answer, and the final {@code assertTrue} (cooldown
 * elapsed) would fail. Pins the seam behaviorally, without the static test overloads.
 */
@MicronautTest
@Property(name = "janus.test.production-factories", value = "true")
// The fixed-clock factory below is gated on its own property (NOT the production-
// factories one): every production-factories context — RouterFactoryClockTest,
// ProductionMetricsExpositionTest and the two-node integration test — must run the
// REAL Clock.systemUTC unless it explicitly opts into the frozen clock. A leak of
// the fixed clock froze the shared Postgres RPM windows at epoch+16min in the two-node
// test (breaking real-window assertions).
@Property(name = "janus.test.router-clock", value = "true")
@Property(name = "janus.model-list[0].name", value = "deepseek-v4-flash")
@Property(name = "janus.model-list[0].provider", value = "deepseek")
@Property(name = "janus.limits.window", value = "fixed")
class RouterFactoryClockTest {

    @Inject
    Clock clock;

    // Injecting the Router forces router to run, which populates RouterResilience
    // (the MetricsFactory hard-dependency trick — same creation-order guarantee).
    @Inject
    Router router;

    @Inject
    RouterResilience resilience;

    @Test
    void resilienceBundleRunsOnTheBeanClock() {
        assertSame(FixedClockFactory.CLOCK, clock, "the injected Clock bean is the fixed clock this test drives");
        assertFalse(
                resilience.backends().isEmpty(), "router() populated the resilience bean with the model-list backend");

        UpstreamHealth health = resilience.health();
        CircuitBreaker breaker = resilience.breaker();
        ChatBackend backend = resilience.backends().getFirst();

        // Health: 3 consecutive failures (allowed-fails default 3) → unhealthy with a
        // 10s cooldown measured on the bean clock.
        health.recordFailure(backend);
        health.recordFailure(backend);
        health.recordFailure(backend);
        assertFalse(health.passivelyHealthy(backend), "probation pending at T0 on the bean clock");
        FixedClockFactory.CLOCK.advance(9_999);
        assertFalse(health.passivelyHealthy(backend), "cooldown (10s) not elapsed at +9.999s on the bean clock");
        FixedClockFactory.CLOCK.advance(1);
        assertTrue(health.passivelyHealthy(backend), "cooldown elapsed at exactly +10s on the bean clock");

        // Breaker: 5 failures (breaker-failure-threshold default 5) → OPEN with a 30s
        // cooldown; canTry flips exactly when the bean clock crosses it.
        for (int i = 0; i < 5; i++) {
            breaker.recordConnectFailure(backend);
        }
        assertSame(CircuitBreaker.State.OPEN, breaker.state(backend), "threshold reached → OPEN on the bean clock");
        FixedClockFactory.CLOCK.advance(29_999);
        assertTrue(!breaker.canTry(backend), "breaker cooldown (30s) not elapsed at +29.999s");
        FixedClockFactory.CLOCK.advance(1);
        assertTrue(breaker.canTry(backend), "breaker cooldown elapsed at exactly +30s on the bean clock");
    }

    /**
     * Replaces the {@code CallStoreFactory} {@code Clock} bean with a mutable fixed
     * clock. Gated on this test's own property ({@code janus.test.router-clock}) so it
     * activates ONLY here — a broader gate (e.g. {@code janus.test.production-factories})
     * would also leak the frozen 1970 clock into {@code ProductionMetricsExpositionTest}
     * and {@code TwoNodeIntegrationTest}, whose real-window/real-timestamp assertions
     * depend on the production {@code Clock.systemUTC}.
     */
    @Factory
    @Requires(property = "janus.test.router-clock", value = "true")
    static final class FixedClockFactory {

        static final MutableClock CLOCK = new MutableClock(1_000_000L);

        @Singleton
        @Replaces(factory = CallStoreFactory.class)
        Clock clock() {
            return CLOCK;
        }
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
