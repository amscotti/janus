package io.amscotti.janus.store;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * test clock: a mutable {@link Clock} so rate-limit windows, token-bucket refills
 * and ledger period rollovers are pinned deterministically ( no-real-time
 * discipline — the store types take an injectable {@code Clock}, and this is the
 * test double that advances it). Package-private: janus-store tests only.
 */
final class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    MutableClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    MutableClock(Instant instant, ZoneId zone) {
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
}
