package io.amscotti.janus.router;

import java.time.Duration;
import java.util.Objects;

/**
 * circuit-breaker configuration: the per-upstream breaker
 * knobs handed to {@link CircuitBreaker#create(CircuitBreakerConfig)}. Config keys the
 * gateway's {@code [router]} TOML binds: {@code breaker_failure_threshold},
 * {@code breaker_window_seconds}, {@code breaker_cooldown_seconds} — matching the reference
 * Janus defaults 5 / 60s / 30s.
 *
 * <p>Fail-fast validation (the router's discipline): {@code failureThreshold >= 1} and both
 * {@code window} and {@code cooldown} strictly positive — a misconfigured breaker fails
 * at startup, not on first request. The single exception is a <b>disabled</b> breaker:
 * {@code failureThreshold == 0} disables the breaker entirely (the health layer stays on
 * — the operator-facing "set {@code breaker-failure-threshold = 0} to disable the
 * breaker" knob), and {@link CircuitBreaker#create(CircuitBreakerConfig)} maps <i>any</i>
 * {@code (0, *, *)} config to the {@link CircuitBreaker#disabled} no-op singleton.
 * The canonical disabled form is {@link #disabled} {@code (0, zero, zero)}; a threshold
 * zero with a nonzero window/cooldown is accepted and those two knobs are simply ignored
 * (their values cannot matter once the breaker is off). Negative durations are always a
 * config error.
 *
 * <p>Immutable data holder (record); thread-safe by construction.
 */
public record CircuitBreakerConfig(int failureThreshold, Duration window, Duration cooldown) {

    public CircuitBreakerConfig {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(cooldown, "cooldown");
        if (failureThreshold < 0) {
            throw new IllegalArgumentException("failureThreshold must be >= 0: " + failureThreshold);
        }
        if (failureThreshold == 0) {
            // Disabled (see the class javadoc): window/cooldown are ignored, so zero OR
            // positive are both legal — but negative durations are always nonsense.
            if (window.isNegative()) {
                throw new IllegalArgumentException("window must not be negative: " + window);
            }
            if (cooldown.isNegative()) {
                throw new IllegalArgumentException("cooldown must not be negative: " + cooldown);
            }
        } else {
            if (window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("window must be > 0: " + window);
            }
            if (cooldown.isZero() || cooldown.isNegative()) {
                throw new IllegalArgumentException("cooldown must be > 0: " + cooldown);
            }
        }
    }

    /**
     * The disabled sentinel config {@code (0, zero, zero)} — the canonical disabled form
     * (see the class javadoc). {@link CircuitBreaker#create(CircuitBreakerConfig)} maps
     * it, and every other {@code (0, *, *)} config, to the {@link CircuitBreaker#disabled}
     * no-op singleton.
     */
    public static CircuitBreakerConfig disabled() {
        return new CircuitBreakerConfig(0, Duration.ZERO, Duration.ZERO);
    }
}
