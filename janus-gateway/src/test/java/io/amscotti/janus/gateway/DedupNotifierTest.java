package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link DedupNotifier} once-per-key-per-window
 * semantics with a fixed clock: the first {@code :budget_exceeded} dispatch for a key
 * in window N fires; subsequent dispatches for the same key in the same window are
 * suppressed; a dispatch in the next window fires again; a different key fires
 * independently; non-budget events (and events without a key_id) pass through
 * undeduped.
 */
class DedupNotifierTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:30Z"), ZoneOffset.UTC); // window 0

    private final RecordingNotifier sink = new RecordingNotifier();
    private final DedupNotifier notifier = new DedupNotifier(sink, CLOCK);

    @Test
    void firstCrossingFiresAndSameWindowCrossingsAreSuppressed() {
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));

        assertEquals(1, eventsFor("key-a").size(), "one event per key per window");
        assertEquals("soft", eventsFor("key-a").getFirst().payload().get("tier"));
    }

    @Test
    void nextWindowFiresAgain() {
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));
        assertEquals(1, eventsFor("key-a").size());

        DedupNotifier nextWindow =
                new DedupNotifier(sink, Clock.fixed(Instant.parse("2026-08-03T00:01:00Z"), ZoneOffset.UTC)); // window 1
        nextWindow.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));

        assertEquals(2, eventsFor("key-a").size(), "the next window crosses again");
    }

    @Test
    void differentKeysAreIndependent() {
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-b"));
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));

        assertEquals(1, eventsFor("key-a").size());
        assertEquals(1, eventsFor("key-b").size());
    }

    @Test
    void nonBudgetEventsAndKeylessEventsPassThrough() {
        notifier.notify("lifecycle.created", Map.of("key_id", "key-a"));
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, Map.of("tier", "soft"));

        assertEquals(2, sink.snapshot().size());
    }

    @Test
    void forgetKeyPrunesTheDedupWindowSoSameWindowRefires() {
        // DedupNotifier state used to grow unboundedly across key churn
        // with no prune hook. forgetKey (called by AdminKeysController on a successful
        // POST /key/delete) drops a key's window entry, so a deleted-and-re-created
        // key fires budget_exceeded again in the SAME window.
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));
        assertEquals(1, eventsFor("key-a").size(), "first window crossing fires once");
        assertTrue(notifier.remembers("key-a"), "the key holds a dedup window entry");

        notifier.forgetKey("key-a");
        assertFalse(notifier.remembers("key-a"), "forgetKey prunes the entry");

        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));
        assertEquals(2, eventsFor("key-a").size(), "the same-window re-fire is no longer suppressed");
    }

    @Test
    void forgetKeyIsIdempotentAndNullSafeAndLeavesOthersUntouched() {
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-a"));
        notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, payload("key-b"));
        notifier.forgetKey("key-a");
        notifier.forgetKey("key-a");
        notifier.forgetKey(null);
        assertFalse(notifier.remembers("key-a"), "pruned");
        assertTrue(notifier.remembers("key-b"), "other keys are untouched");
    }

    private static Map<String, Object> payload(String keyId) {
        return Map.of("key_id", keyId, "tier", "soft");
    }

    private List<RecordingNotifier.Event> eventsFor(String keyId) {
        return sink.snapshot().stream()
                .filter(e -> keyId.equals(e.payload().get("key_id")))
                .toList();
    }
}
