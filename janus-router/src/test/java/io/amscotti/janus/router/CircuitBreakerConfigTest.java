package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * step 2: {@link CircuitBreakerConfig} — fail-fast validation of the rolling-window
 * breaker knobs (threshold ≥ 1, window/cooldown > 0), the {@code disabled} sentinel
 * representation (threshold 0 + zero durations) and accessor immutability. No TOML
 * parsing here — the {@code breaker_*} keys are bound elsewhere.
 */
class CircuitBreakerConfigTest {

    @Test
    void acceptsValidValuesAndExposesThem() {
        CircuitBreakerConfig config = new CircuitBreakerConfig(5, Duration.ofSeconds(60), Duration.ofSeconds(30));
        assertEquals(5, config.failureThreshold());
        assertEquals(Duration.ofSeconds(60), config.window());
        assertEquals(Duration.ofSeconds(30), config.cooldown());
    }

    @Test
    void rejectsThresholdBelowOne() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerConfig(-1, Duration.ofSeconds(60), Duration.ofSeconds(30)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerConfig(-5, Duration.ofSeconds(60), Duration.ofSeconds(30)));
    }

    @Test
    void rejectsZeroOrNegativeWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerConfig(5, Duration.ZERO, Duration.ofSeconds(30)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerConfig(5, Duration.ofSeconds(-1), Duration.ofSeconds(30)));
    }

    @Test
    void rejectsZeroOrNegativeCooldown() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerConfig(5, Duration.ofSeconds(60), Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerConfig(5, Duration.ofSeconds(60), Duration.ofSeconds(-1)));
    }

    @Test
    void rejectsNullWindowAndCooldown() {
        assertThrows(NullPointerException.class, () -> new CircuitBreakerConfig(5, null, Duration.ofSeconds(30)));
        assertThrows(NullPointerException.class, () -> new CircuitBreakerConfig(5, Duration.ofSeconds(60), null));
    }

    @Test
    void disabledFactoryReturnsTheSentinelConfig() {
        CircuitBreakerConfig disabled = CircuitBreakerConfig.disabled();
        assertEquals(0, disabled.failureThreshold());
        assertEquals(Duration.ZERO, disabled.window());
        assertEquals(Duration.ZERO, disabled.cooldown());
    }

    @Test
    void thresholdZeroDisablesTheBreakerRegardlessOfWindowAndCooldown() {
        // Operator disable: "set breaker-failure-threshold = 0 to disable the
        // breaker" — the natural operator action — must be a graceful disable, not a boot
        // crash. A zero threshold with any non-negative window/cooldown is accepted (the
        // two knobs are ignored once the breaker is off) and CircuitBreaker.create maps it
        // to the disabled singleton.
        new CircuitBreakerConfig(0, Duration.ofSeconds(60), Duration.ofSeconds(30));
        new CircuitBreakerConfig(0, Duration.ZERO, Duration.ofSeconds(30));
        new CircuitBreakerConfig(0, Duration.ofSeconds(60), Duration.ZERO);
        assertSame(
                CircuitBreaker.disabled(),
                CircuitBreaker.create(new CircuitBreakerConfig(0, Duration.ofSeconds(60), Duration.ofSeconds(30))));
        assertSame(
                CircuitBreaker.disabled(),
                CircuitBreaker.create(new CircuitBreakerConfig(0, Duration.ZERO, Duration.ZERO)));
    }

    @Test
    void negativeDurationsAreAlwaysRejected() {
        // Negative durations are nonsense whether or not the breaker is disabled.
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerConfig(0, Duration.ofSeconds(-1), Duration.ofSeconds(30)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerConfig(0, Duration.ofSeconds(60), Duration.ofSeconds(-1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerConfig(5, Duration.ofSeconds(-1), Duration.ofSeconds(30)));
    }

    @Test
    void partialZeroKnobsAreConfigErrorsOnlyForAnActiveBreaker() {
        // An active breaker needs strictly positive window and cooldown.
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerConfig(5, Duration.ZERO, Duration.ofSeconds(30)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CircuitBreakerConfig(5, Duration.ofSeconds(60), Duration.ZERO));
    }
}
