package io.amscotti.janus.gateway;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * test clock: a mutable {@link Clock} so budget-reset windows (and any other
 * time-derived behavior) can cross a window boundary deterministically inside a
 * gateway test context — the janus-store {@code MutableClock} pattern, given its
 * own tiny copy here because test classes are not shared across modules (
 * no-real-time discipline; the fixed start is the shared
 * {@link TestKeyAuthFactory#CLOCK} instant every suite already assumes).
 *
 * <p><b>Shared-state discipline.</b> The shared {@link TestKeyAuthFactory#CLOCK}
 * bean is ONE instance per JVM — a test that advances it must {@link #reset} in
 * the same test method (the windowed e2e suite does), or every later test in the
 * JVM would read a moved clock and the fixed-clock {@code Retry-After} assertions
 * would drift.
 */
final class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    MutableClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = java.util.Objects.requireNonNull(instant, "instant");
        this.zone = java.util.Objects.requireNonNull(zone, "zone");
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    void advanceSeconds(long seconds) {
        instant = instant.plusSeconds(seconds);
    }

    void advanceMillis(long millis) {
        instant = instant.plusMillis(millis);
    }

    /** Back to the shared fixed start — a mutating test restores the JVM-wide clock. */
    void reset() {
        instant = TestKeyAuthFactory.CLOCK_START;
    }
}
