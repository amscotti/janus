package io.amscotti.janus.gateway;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Brute-force defense for the master-key check (review H3): a fixed-window counter of
 * <b>failed</b> master-key attempts on {@code /key/*}. Once {@code maxFailures}
 * wrong-key attempts land inside {@code window}, every further attempt is denied 429
 * (with {@code Retry-After}) until the window rolls — the check is otherwise a single
 * constant-time string compare with no cost, so an attacker could otherwise try
 * candidate master keys at line rate against {@code POST /key/generate}.
 *
 * <p><b>Scope.</b> Only {@link KeyAuthException.Reason#BAD_MASTER} attempts (a
 * presented-but-wrong key) count: missing-token requests carry no key material and
 * brute-force nothing, and counting them would let any scanner lock the admin plane.
 * A <b>successful</b> master-key auth resets the counter, so an operator's occasional
 * typos never accumulate into a lockout. The lockout is global (not per-IP): the
 * gateway may sit behind a proxy whose forwarded address is not trustworthy, and a
 * brief admin-plane lockout is the accepted trade-off against key-space search.
 *
 * <p>Thread-safe via a monitor: the admin plane is low-QPS, so contention is moot.
 * The {@link Clock} is injected ({@code /} discipline — no sleeping, no real time).
 */
final class MasterKeyThrottle {

    /** The production default: 10 failed attempts per 60 s window. */
    static MasterKeyThrottle create() {
        return new MasterKeyThrottle(10, Duration.ofSeconds(60), Clock.systemUTC());
    }

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;

    private static final long NO_WINDOW = -1L;

    private long windowStartMillis = NO_WINDOW;
    private int failures;

    MasterKeyThrottle(int maxFailures, Duration window, Clock clock) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be >= 1");
        }
        this.maxFailures = maxFailures;
        this.window = Objects.requireNonNull(window, "window");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Whether the admin plane is locked out for the remainder of the window. */
    synchronized boolean blocked() {
        rollIfElapsed();
        return failures >= maxFailures;
    }

    /** Seconds until the window reopens (0 when not blocked). */
    synchronized long retryAfterSeconds() {
        rollIfElapsed();
        if (failures < maxFailures) {
            return 0;
        }
        long elapsed = clock.millis() - windowStartMillis;
        long remaining = window.toMillis() - elapsed;
        return remaining <= 0 ? 0 : (remaining + 999) / 1000; // ceil — never advertise 0
    }

    /** A presented-but-wrong master key ({@code BAD_MASTER}) — the only counted attempt. */
    synchronized void recordFailure() {
        rollIfElapsed();
        if (windowStartMillis == NO_WINDOW) {
            windowStartMillis = clock.millis();
        }
        failures++;
    }

    /** A successful master-key auth — resets the counter (typos never accumulate). */
    synchronized void recordSuccess() {
        failures = 0;
        windowStartMillis = NO_WINDOW;
    }

    private void rollIfElapsed() {
        if (windowStartMillis == NO_WINDOW) {
            return;
        }
        if (clock.millis() - windowStartMillis >= window.toMillis()) {
            failures = 0;
            windowStartMillis = NO_WINDOW;
        }
    }
}
