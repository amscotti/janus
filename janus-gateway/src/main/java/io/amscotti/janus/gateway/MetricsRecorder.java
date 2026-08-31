package io.amscotti.janus.gateway;

import io.amscotti.janus.core.model.Usage;

/**
 * Tier-1 metrics seam (the {@link Governance} / {@link Notifier} pattern): the
 * Prometheus series the gateway records, deliberately separated from the Micrometer
 * implementation so controllers and {@link Governance} never touch a registry and
 * tests inject {@link #noop}. The production instance is
 * {@link MicrometerMetricsRecorder} (produced by {@link MetricsFactory} from the
 * auto-configured {@code MeterRegistry}); {@link #noop} is the
 * byte-identical no-op every pre- suite uses.
 *
 * <p><b>Tier-1 privacy contract (the Tier-1 privacy contract — blunt,
 * always on).</b> The recorded series carry <b>no user content ever</b>: labels are
 * only {@code face} ({@code openai}|{@code anthropic}|{@code responses}|{@code admin}
 * — the four values of {@link Face#label}, the last for master-key/auth rejections
 * on non-model routes such as {@code /key/*}), a coarse
 * {@code status} bucket ({@code 2xx}|{@code 4xx}|{@code 5xx} — bounds cardinality),
 * and {@code key_id}; never prompt text, response text, model alias, or request id.
 * The {@code key_id} label is a <b>deliberate, documented divergence</b> from the reference implementation's
 * "never label by {@code key_id}" rule (the reference implementation semantics uses {@code team_id}): the
 * the design demands per-key usage, and the JVM key model has no team concept.
 * It is mitigated because {@code KeyRecord.id} is an opaque, non-secret,
 * operator-created id and the key set is finite (bounded cardinality) — and every
 * other label is coarse-bucketed. {@code MetricsPrivacyContractTest} pins the
 * no-bodies guarantee by planting a marker in the prompt and the fake response and
 * asserting the scraped exposition never contains it.
 *
 * <p><b>Where recording happens (controllers for HTTP shape, Governance for
 * usage).</b> {@link #recordRequest} is called by every face controller (after
 * dispatch, all three faces and both modes — streaming duration measured to
 * stream close via the publisher's close hook; a stream that ends with a
 * mid-stream error frame / stall records its mapped 5xx, not 200 — see the
 * publisher javadocs); {@link #recordUsage} is called by {@link Governance} at
 * finalize / stream-settle, the one place {@link Usage} + exact micro-USD cost +
 * the governing key all exist. A null {@code keyId} means auth-off: only the
 * unlabeled totals are recorded (no per-key series). Streams that exhaust without a
 * terminal usage chunk record a zero entry (documented limitation — Janus never
 * forces {@code include_usage}); aborted streams record nothing.
 *
 * <p>Thread-safe: the Micrometer registry is; {@link #noop} is a stateless
 * singleton. Native-image clean: explicit meter names/tags only, no reflection, no
 * {@code @Timed} AOP (the whole codebase's explicit-surface discipline).
 */
interface MetricsRecorder {

    /**
     * Record one completed (or failed) request: the {@code janus_requests_total}
     * counter bucketed by {@code face} × coarse {@code status} (429s and provider
     * errors land in their 4xx/5xx buckets), the {@code janus_request_duration_seconds}
     * latency histogram (Timer, default buckets) by {@code face}, and — when a key
     * governed the request — the {@code janus_key_requests_total} counter by
     * {@code key_id}.
     *
     * @param face the ingress face: {@code "openai"}, {@code "anthropic"},
     * {@code "responses"} (the {@code /v1/responses*} stateless face — {@link
     * Face#RESPONSES}), or {@code "admin"} (master-key rejections on non-model
     * routes and the {@code /key/*} operations themselves — success and failure
     * alike, so the admin series is complete). Four values, one per {@link
     * Face#label}; a dashboard builder reading this seam must chart all four
     * series.
     * @param status the HTTP status the response carries (or would carry on failure)
     * @param durationMillis request latency — non-streaming: dispatch → response;
     * streaming: dispatch → stream close
     * @param keyId the governing {@code KeyRecord.id} (opaque, non-secret), or
     * {@code null} for auth-off (no per-key series)
     */
    void recordRequest(String face, int status, long durationMillis, String keyId);

    /**
     * Record one request's usage: the unlabeled {@code janus_tokens_in_total} /
     * {@code janus_tokens_out_total} / {@code janus_cost_micro_usd_total} counters
     * (exact integer micro-USD, {@code CostCalculator} output) and — when
     * {@code keyId} is non-null — the {@code janus_key_*} counterparts. A null
     * {@code usage} (streams that exhausted without a terminal usage chunk) records
     * zeros.
     *
     * @param keyId the governing {@code KeyRecord.id}, or {@code null} for auth-off
     * (unlabeled totals only)
     * @param usage the settled token counts, or {@code null} for a zero entry
     * @param costMicroUsd the exact cost in micro-USD
     */
    void recordUsage(String keyId, Usage usage, long costMicroUsd);

    /**
     * Drop the per-key series for {@code keyId} (the four {@code janus_key_*}
     * counters labelled {@code key_id}). Called by {@link AdminKeysController}
     * on a successful {@code POST /key/delete} so a revoked key's series do not
     * accumulate in the registry over the process lifetime — the {@code key_id}
     * label is otherwise unbounded across key churn (the documented divergence
     * from the reference implementation's team_id rule). The unlabeled totals ({@code janus_tokens_*},
     * {@code janus_cost_*}, {@code janus_requests_total}) and the per-provider
     * gauges carry no {@code key_id} label and are never matched. A no-op when
     * no per-key series exist for {@code keyId} (never-created or
     * already-forgotten); idempotent and null-safe. {@link #noop} is a no-op.
     *
     * @param keyId the opaque, non-secret {@code KeyRecord.id} to forget
     */
    void forgetKey(String keyId);

    /**
     * Record one call-ledger store write's duration: the <b>unlabeled</b>
     * {@code janus_ledger_write_seconds} Timer (percentile-histogram buckets +
     * count/sum — the {@code janus_request_duration_seconds} shape). Called by
     * {@link Governance}'s one {@code CallRecord} writer around every
     * {@code CallStore.recordCall} — success and contained failure alike (a
     * pool-exhaustion timeout is exactly the tail this timer exists to see) —
     * through the same best-effort posture as the write itself, so a recording
     * failure never reaches the request path.
     *
     * <p><b>No labels — the Tier-1 privacy contract.</b> The series carries no
     * {@code key_id}/{@code face}/{@code provider} dimension: it measures one
     * store, not one caller, and keeps the label cardinality contract blunt.
     * Per-key write contention is Tier-2 territory (correlate with the store).
     *
     * <p><b>Default no-op.</b> Only {@link MicrometerMetricsRecorder} overrides
     * it; {@link #noop} and the anonymous test fakes inherit the no-op, so
     * existing constructions stay byte-identical.
     *
     * @param durationNanos the store write's wall-clock duration in nanoseconds
     * (nanos, not the {@link #recordRequest} millis convention: an in-memory
     * write is sub-millisecond and the PG-leg decision gate reads the p95
     * against a ~2 ms threshold)
     */
    default void recordLedgerWrite(long durationNanos) {}

    /** The no-op: every method is a no-op; tests and noop-governance use it. */
    static MetricsRecorder noop() {
        return Noop.INSTANCE;
    }

    /** Stateless singleton backing {@link #noop()}. */
    final class Noop implements MetricsRecorder {

        private static final MetricsRecorder INSTANCE = new Noop();

        private Noop() {}

        @Override
        public void recordRequest(String face, int status, long durationMillis, String keyId) {}

        @Override
        public void recordUsage(String keyId, Usage usage, long costMicroUsd) {}

        @Override
        public void forgetKey(String keyId) {}
    }
}
