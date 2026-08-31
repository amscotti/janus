package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * step 3: {@link RetryPolicy} — the exponential backoff curve (pinned exact values
 * with jitter off), the cap, jitter bounds, the {@link Sleeper} seam (tests never sleep)
 * and fail-fast constructor validation.
 */
class RetryPolicyTest {

    @Test
    void delayFollowsExponentialCurveUpToTheCap() {
        RetryPolicy policy = new RetryPolicy(5, 10, 100, 0.0);
        assertEquals(10, policy.delayMillis(0));
        assertEquals(20, policy.delayMillis(1));
        assertEquals(40, policy.delayMillis(2));
        assertEquals(80, policy.delayMillis(3));
        assertEquals(100, policy.delayMillis(4)); // capped
        assertEquals(100, policy.delayMillis(5)); // stays capped
        assertEquals(100, policy.delayMillis(100)); // no overflow, no growth past the cap
    }

    @Test
    void delayCapsImmediatelyWhenBaseIsAtTheMax() {
        RetryPolicy policy = new RetryPolicy(3, 500, 1000, 0.0);
        assertEquals(500, policy.delayMillis(0));
        assertEquals(1000, policy.delayMillis(1));
        assertEquals(1000, policy.delayMillis(2));
    }

    @Test
    void jitterStaysWithinDocumentedBounds() {
        RetryPolicy policy = new RetryPolicy(3, 10, 100, 0.5, new Random(42), millis -> {});
        for (int attempt = 0; attempt < 3; attempt++) {
            long capped = Math.min(10L << attempt, 100L);
            for (int i = 0; i < 200; i++) {
                long delay = policy.delayMillis(attempt);
                assertTrue(delay >= capped, "attempt " + attempt + " delay " + delay + " below " + capped);
                assertTrue(
                        delay <= capped + (long) (0.5 * capped),
                        "attempt " + attempt + " delay " + delay + " above " + (capped + (long) (0.5 * capped)));
            }
        }
    }

    @Test
    void zeroJitterIsFullyDeterministic() {
        RetryPolicy policy = new RetryPolicy(2, 10, 100, 0.0, new Random(1), millis -> {});
        for (int attempt = 0; attempt < 3; attempt++) {
            long first = policy.delayMillis(attempt);
            for (int i = 0; i < 50; i++) {
                assertEquals(first, policy.delayMillis(attempt));
            }
        }
    }

    @Test
    void sleepBackoffDelegatesToTheSleeperSeamWithComputedDelays() {
        List<Long> sleeps = new ArrayList<>();
        RetryPolicy policy = new RetryPolicy(2, 10, 100, 0.0, new Random(1), sleeps::add);
        policy.sleepBackoff(0);
        policy.sleepBackoff(1);
        policy.sleepBackoff(2);
        assertEquals(List.of(10L, 20L, 40L), sleeps); // delay values, never real time
    }

    @Test
    void rejectsNegativeMaxRetries() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(-1, 10, 100, 0.0));
    }

    @Test
    void rejectsNonPositiveBaseDelay() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 0, 100, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, -5, 100, 0.0));
    }

    @Test
    void rejectsMaxBelowBase() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 100, 10, 0.0));
    }

    @Test
    void rejectsOutOfRangeJitter() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 10, 100, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 10, 100, 1.1));
    }

    @Test
    void rejectsNegativeAttempt() {
        RetryPolicy policy = new RetryPolicy(1, 10, 100, 0.0);
        assertThrows(IllegalArgumentException.class, () -> policy.delayMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> policy.sleepBackoff(-1));
    }
}
