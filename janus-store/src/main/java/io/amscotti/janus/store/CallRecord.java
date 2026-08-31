package io.amscotti.janus.store;

import io.amscotti.janus.core.model.Usage;
import java.util.Objects;

/**
 * A Tier-1 per-request usage record (; the Usage.UsageEvent
 * analogue, trimmed to Janus's axes — no {@code team_id}/{@code app_label}/
 * {@code model_family}/{@code reasoning_tokens}: deliberately omitted (YAGNI per the
 * repo working agreement). One record per request for every reported outcome —
 * success, failure, streaming settle; <b>aborted streams record nothing</b> (the
 * decision: a client-disconnected stream before exhaustion is not reported and
 * the closed {@link CallStatus} set has no canceled variant — matching the
 * metrics side and the reference recorder); it is the {@link InMemoryCallStore} call
 * ring's payload and the shape of the Postgres {@code calls} table (the LiteLLM
 * per-request spend-log table is the product-semantics reference: key, model,
 * tokens, cost, latency, status).
 *
 * <p><b>Tier-1 privacy contract.</b> Contains <b>no user content</b>:
 * no prompt/response bodies, no request/response text — only ids, token counts,
 * costs, durations and a status. Like {@link KeyRecordView}, the field set is
 * structurally non-content: a caller cannot accidentally serialize bodies (or
 * hash/salt/secret key material) through this type.
 *
 * <p><b>Nullable fields.</b> {@code keyId} is null for auth-off requests (the
 * auth-off default — records with no key exist; {@link InMemoryCallStore} rings them
 * under a sentinel key). {@code model}/{@code provider} are null for a request that
 * failed before model resolution/routing (the reference implementation's nil-able {@code model}/
 * {@code provider}). The cache-token fields are null when the upstream omitted them
 * (Anthropic cache fields or OpenAI {@code prompt_tokens_details.cached_tokens};
 * absent ⇒ 0 pricing — the {@link Usage} precedent).
 *
 * <p><b>Units.</b> {@code costMicroUsd} is the {@link CostCalculator} integer
 * micro-USD output (1 USD = 1_000_000); {@code durationMillis} is the wall-clock
 * request duration; {@code atEpochMillis} is the record timestamp taken from the
 * store's injected {@link java.time.Clock} by the writer ( discipline —
 * deterministic tests pin a fixed clock).
 *
 * @param requestId the request id (non-null; the reference recorder's ring key)
 * @param keyId the owning {@link KeyRecord#id}; null for auth-off requests
 * @param model the client alias ( scope-by-alias semantics — what the client
 * sent); null when the request failed before resolution
 * @param provider the upstream provider that actually received the dispatch (the
 * load-balancer pick / retry-failover target threaded from the dispatch seam — never
 * the config-first candidate a model-alias re-resolve would answer); null when no
 * backend was dispatched (pre-dispatch denial, decode failure, unknown model)
 * @param promptTokens input tokens (Usage-aligned)
 * @param completionTokens output tokens
 * @param totalTokens the wire-reported total — a stored field, not derived (Usage
 * semantics)
 * @param cacheCreationInputTokens nullable (Anthropic-only — no OpenAI wire equivalent)
 * @param cacheReadInputTokens nullable (Anthropic cache fields / OpenAI cached tokens)
 * @param costMicroUsd exact cost in integer micro-USD (CostCalculator output)
 * @param durationMillis request duration in milliseconds
 * @param stream true for streaming (SSE) requests
 * @param status the closed-set outcome ({@link CallStatus})
 * @param atEpochMillis record timestamp from the store clock (epoch millis)
 */
public record CallRecord(
        String requestId,
        String keyId,
        String model,
        String provider,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        Long cacheCreationInputTokens,
        Long cacheReadInputTokens,
        long costMicroUsd,
        long durationMillis,
        boolean stream,
        CallStatus status,
        long atEpochMillis) {

    public CallRecord {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0 || costMicroUsd < 0 || durationMillis < 0) {
            throw new IllegalArgumentException("tokens/cost/duration must be non-negative (got prompt=" + promptTokens
                    + ", completion=" + completionTokens + ", total=" + totalTokens + ", cost=" + costMicroUsd
                    + ", duration=" + durationMillis + ")");
        }
        if (cacheCreationInputTokens != null && cacheCreationInputTokens < 0) {
            throw new IllegalArgumentException(
                    "cacheCreationInputTokens must be non-negative (got " + cacheCreationInputTokens + ")");
        }
        if (cacheReadInputTokens != null && cacheReadInputTokens < 0) {
            throw new IllegalArgumentException(
                    "cacheReadInputTokens must be non-negative (got " + cacheReadInputTokens + ")");
        }
    }

    /**
     * Convenience form from a {@link Usage} (the writer's source): maps the
     * token fields including the nullable cache-token fields verbatim.
     *
     * @param usage the canonical usage (may carry null cache fields)
     */
    public CallRecord(
            String requestId,
            String keyId,
            String model,
            String provider,
            Usage usage,
            long costMicroUsd,
            long durationMillis,
            boolean stream,
            CallStatus status,
            long atEpochMillis) {
        this(
                requestId,
                keyId,
                model,
                provider,
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens(),
                usage.cacheCreationInputTokens(),
                usage.cacheReadInputTokens(),
                costMicroUsd,
                durationMillis,
                stream,
                status,
                atEpochMillis);
    }

    /**
     * Is {@code totalTokens} consistent with the two component counts (prompt + completion)?
     * Literal arithmetic only: a usage with cache-read tokens (OpenAI cached tokens
     * are subtracted from {@code promptTokens}) has {@code totalTokens = prompt + completion
     * + cacheRead}, so this returns false there by design (the wire total is stored, not
     * derived — see the class javadoc).
     */
    public boolean totalTokensConsistent() {
        return totalTokens == promptTokens + completionTokens;
    }
}
