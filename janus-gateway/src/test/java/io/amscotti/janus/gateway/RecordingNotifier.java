package io.amscotti.janus.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * test {@link Notifier}: records every {@code notify} call so the soft-cap
 * enforcement tests can assert the {@code :budget_exceeded} event shape
 * ({@code key_id}, {@code tier: :soft}, committed, cap — the reference event shape)
 * without a log capture or a webhook. Shared as a static (the
 * {@link TestGovernanceFactory} singleton Governance records into it); events are
 * key-scoped in assertions, and the factory clears the list when a real-governance
 * context is created.
 */
final class RecordingNotifier implements Notifier {

    /** One recorded dispatch. */
    record Event(String name, Map<String, Object> payload) {}

    private final List<Event> events = new ArrayList<>();
    private final List<String> forgottenKeys = new ArrayList<>();

    @Override
    public synchronized void notify(String event, Map<String, Object> payload) {
        events.add(new Event(event, payload));
    }

    @Override
    public synchronized void forgetKey(String keyId) {
        forgottenKeys.add(keyId);
    }

    synchronized void clear() {
        events.clear();
        forgottenKeys.clear();
    }

    /** An immutable snapshot of every dispatch so far. */
    synchronized List<Event> snapshot() {
        return List.copyOf(events);
    }

    /** An immutable snapshot of every {@code forgetKey} call so far. */
    synchronized List<String> forgottenKeys() {
        return List.copyOf(forgottenKeys);
    }
}
