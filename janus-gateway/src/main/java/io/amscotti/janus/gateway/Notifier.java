package io.amscotti.janus.gateway;

import java.util.Map;

/**
 * Governance event dispatch (; the Notifier behaviour, ported):
 * {@link Governance} fires {@code :budget_exceeded} events (soft/hard tier crossings)
 * into an external sink. The contract is the reference one — <b>dispatch is
 * fire-and-forget, never raises, and never propagates into the request path</b>; the
 * built-in adapters are non-blocking ({@link LoggingNotifier} returns immediately,
 * {@link WebhookNotifier} uses {@code java.net.http.HttpClient.sendAsync}) and a
 * failing adapter is swallowed by the {@link Governance} call site regardless.
 *
 * <p>The event set is deliberately small for {@code "budget_exceeded"} with a
 * payload of {@code {"key_id", "tier" ("soft"|"hard"), "committed_micro_usd",
 * "cap_micro_usd"}} (the %{team_id, tier, committed, cap, period}
 * shape, key id instead of team id; / add lifecycle events). The payload is a
 * plain {@code Map} so the webhook adapter serializes it without new reflection
 * config (native-image clean — no gateway reflect-config entries per the Design
 * notes).
 */
interface Notifier {

    /** The {@code :budget_exceeded} event name (vocabulary). */
    String EVENT_BUDGET_EXCEEDED = "budget_exceeded";

    /**
     * Dispatch {@code event} with {@code payload} to the sink. Must never throw — a
     * notifier failure must not tear down the request that triggered it.
     */
    void notify(String event, Map<String, Object> payload);

    /**
     * Drop any per-key state for {@code keyId} (the {@link DedupNotifier} prune
     * hook — the {@code MetricsRecorder.forgetKey} analogue). Called by
     * {@link AdminKeysController} on a successful {@code POST /key/delete} so a
     * revoked key's dedup window is not remembered forever: a key that was deleted and
     * re-created with the same id fires {@code :budget_exceeded} again in the same
     * window instead of being silently suppressed. Stateless adapters
     * ({@link LoggingNotifier}, {@link WebhookNotifier}) are no-ops; idempotent and
     * null-safe; {@code notify} for an unknown key is unaffected.
     */
    void forgetKey(String keyId);
}
