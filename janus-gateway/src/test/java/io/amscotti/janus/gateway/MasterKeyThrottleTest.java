package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Review H3 — the master-key brute-force defense: a fixed window of {@code BAD_MASTER}
 * attempts, 429 lockout at the threshold, window roll, and the success reset that keeps
 * an operator's typos from accumulating into a lockout. Fixed/mutable clock only ({@code
 * /} discipline — no sleeping, no real time).
 */
class MasterKeyThrottleTest {

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long deltaMillis) {
            millis += deltaMillis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("fixed-zone");
        }
    }

    @Test
    void blocksAtThresholdWithinTheWindow() {
        MutableClock clock = new MutableClock(0);
        MasterKeyThrottle throttle = new MasterKeyThrottle(3, Duration.ofSeconds(60), clock);

        throttle.recordFailure();
        throttle.recordFailure();
        assertFalse(throttle.blocked(), "two failures of three — still open");

        throttle.recordFailure();
        assertTrue(throttle.blocked(), "threshold reached — locked out");
        assertTrue(throttle.retryAfterSeconds() > 0, "a blocked throttle must advertise a positive Retry-After");
        assertTrue(throttle.retryAfterSeconds() <= 60, "Retry-After never exceeds the window");
    }

    @Test
    void windowRollReopensTheAdminPlane() {
        MutableClock clock = new MutableClock(0);
        MasterKeyThrottle throttle = new MasterKeyThrottle(2, Duration.ofSeconds(60), clock);

        throttle.recordFailure();
        throttle.recordFailure();
        assertTrue(throttle.blocked());

        clock.advance(60_000);
        assertFalse(throttle.blocked(), "window elapsed — open again");
        assertEquals(0, throttle.retryAfterSeconds());
    }

    @Test
    void successResetsTheCounterSoTyposNeverAccumulate() {
        MutableClock clock = new MutableClock(0);
        MasterKeyThrottle throttle = new MasterKeyThrottle(2, Duration.ofSeconds(60), clock);

        throttle.recordFailure();
        throttle.recordSuccess(); // the operator logs in
        throttle.recordFailure(); // a later typo starts from zero
        assertFalse(throttle.blocked(), "success reset the counter — one failure of two");
    }

    @Test
    void failuresInSuccessiveWindowsDoNotAccumulate() {
        MutableClock clock = new MutableClock(0);
        MasterKeyThrottle throttle = new MasterKeyThrottle(2, Duration.ofSeconds(60), clock);

        throttle.recordFailure();
        clock.advance(60_000); // window rolls
        throttle.recordFailure();
        assertFalse(throttle.blocked(), "each window counts only its own failures");
    }

    @Test
    void retryAfterIsCeiledNeverZeroWhenBlocked() {
        MutableClock clock = new MutableClock(0);
        MasterKeyThrottle throttle = new MasterKeyThrottle(1, Duration.ofSeconds(60), clock);

        throttle.recordFailure();
        clock.advance(59_400); // 600 ms remain
        assertTrue(throttle.blocked());
        assertEquals(1, throttle.retryAfterSeconds(), "600 ms ceils to 1 s, never advertises 0 while blocked");
    }

    @Test
    void lockoutReEngagesAfterAWindowRoll() {
        MutableClock clock = new MutableClock(0);
        MasterKeyThrottle throttle = new MasterKeyThrottle(2, Duration.ofSeconds(60), clock);

        throttle.recordFailure();
        throttle.recordFailure();
        assertTrue(throttle.blocked());

        clock.advance(60_000);
        assertFalse(throttle.blocked(), "window elapsed — open again");

        // After the roll, failures must re-anchor a new window (regression:
        // windowStartMillis was reset to 0 instead of NO_WINDOW, so every later
        // call rolled immediately and the lockout could never engage again).
        throttle.recordFailure();
        throttle.recordFailure();
        assertTrue(throttle.blocked(), "a fresh burst in the new window locks out again");
        assertEquals(60, throttle.retryAfterSeconds(), "the new window is measured from its own start");
    }

    @Test
    void rejectedMaxFailuresConfiguration() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MasterKeyThrottle(0, Duration.ofSeconds(60), Clock.systemUTC()));
    }
}
