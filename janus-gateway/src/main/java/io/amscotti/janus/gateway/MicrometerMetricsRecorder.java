package io.amscotti.janus.gateway;

import io.amscotti.janus.core.model.Usage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The {@link MetricsRecorder} implementation: hands each series to Micrometer
 * on the auto-configured {@code MeterRegistry} (the Prometheus exporter's registry
 * under the hood), with explicit meter names/tags — no {@code @Timed} AOP, no
 * reflection, so the native-image surface stays exactly what the tests scrape.
 *
 * <p><b>Series (names follow * LiteLLM vocabulary, {@code janus_} prefix).</b>
 *
 * <pre>
 * janus_requests_total Counter face × status (2xx|4xx|5xx)
 * janus_request_duration_seconds Timer face (Prometheus exposition: the
 * count/sum/max summary PLUS
 * percentile-histogram _bucket lines —
 * The
 * "latency histogram" and the bucket
 * lines deliver a histogram, so
 * publishPercentileHistogram is on)
 * janus_tokens_in_total Counter (unlabeled)
 * janus_tokens_out_total Counter (unlabeled)
 * janus_cost_micro_usd_total Counter (unlabeled, exact integer micro-USD)
 * janus_key_requests_total Counter key_id
 * janus_key_tokens_in_total Counter key_id
 * janus_key_tokens_out_total Counter key_id
 * janus_key_cost_micro_usd_total Counter key_id
 * janus_ledger_write_seconds Timer (unlabeled; percentile-histogram buckets
 * + count/sum — the duration-seconds shape, no label dimension)
 * </pre>
 *
 * <p>The per-provider health/breaker gauges are registered by {@link MetricsFactory}
 * (not here) because they read router state, not request/usage data. Every meter is
 * resolved by name/tags at record time (Micrometer reuses existing meters), so a
 * registry {@code clear}/{@code reset} between scrapes never orphans a held
 * meter — tests clear the shared registry per test without leaking state.
 *
 * <p>Tier-1 privacy: see {@link MetricsRecorder} — the only label values here are
 * {@code face}, coarse {@code status} and {@code key_id}; nothing derived from
 * request/response bodies ever reaches a meter.
 */
final class MicrometerMetricsRecorder implements MetricsRecorder {

    static final String SERIES_REQUESTS = "janus_requests_total";
    static final String SERIES_DURATION = "janus_request_duration_seconds";
    static final String SERIES_TOKENS_IN = "janus_tokens_in_total";
    static final String SERIES_TOKENS_OUT = "janus_tokens_out_total";
    static final String SERIES_COST = "janus_cost_micro_usd_total";
    static final String SERIES_KEY_REQUESTS = "janus_key_requests_total";
    static final String SERIES_KEY_TOKENS_IN = "janus_key_tokens_in_total";
    static final String SERIES_KEY_TOKENS_OUT = "janus_key_tokens_out_total";
    static final String SERIES_KEY_COST = "janus_key_cost_micro_usd_total";
    static final String SERIES_LEDGER_WRITE = "janus_ledger_write_seconds";

    private static final String TAG_FACE = "face";
    private static final String TAG_STATUS = "status";
    private static final String TAG_KEY = "key_id";

    private final MeterRegistry registry;

    MicrometerMetricsRecorder(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void recordRequest(String face, int status, long durationMillis, String keyId) {
        String bucket = bucketOf(status);
        registry.counter(SERIES_REQUESTS, TAG_FACE, face, TAG_STATUS, bucket).increment();
        // The latency Timer publishes percentile-histogram
        // bucket lines (le="..." + le="+Inf") in the Prometheus exposition — the design's
        // "latency histogram" wording is satisfied literally, not just by the
        // count/sum/max summary form. Pinned by ProductionMetricsExpositionTest.
        // : find-then-create keeps the meter resolved by name/tags at record
        // time (the clear/reset invariant — a singleton recorder must never hold
        // a meter orphaned by a registry clear), while avoiding the per-request
        // Timer builder allocation on the hot path once the meter exists.
        Timer timer = registry.find(SERIES_DURATION).tags(TAG_FACE, face).timer();
        if (timer == null) {
            timer = Timer.builder(SERIES_DURATION)
                    .tags(TAG_FACE, face)
                    .publishPercentileHistogram(true)
                    .register(registry);
        }
        timer.record(durationMillis, TimeUnit.MILLISECONDS);
        if (keyId != null) {
            registry.counter(SERIES_KEY_REQUESTS, TAG_KEY, keyId).increment();
        }
    }

    @Override
    public void recordUsage(String keyId, Usage usage, long costMicroUsd) {
        long in = usage == null ? 0 : usage.promptTokens();
        long out = usage == null ? 0 : usage.completionTokens();
        // Name lookups (like the per-key series): the registry re-creates a meter
        // after a clear/reset, so a recorder instance never holds an orphaned meter.
        registry.counter(SERIES_TOKENS_IN).increment(in);
        registry.counter(SERIES_TOKENS_OUT).increment(out);
        registry.counter(SERIES_COST).increment(costMicroUsd);
        if (keyId != null) {
            registry.counter(SERIES_KEY_TOKENS_IN, TAG_KEY, keyId).increment(in);
            registry.counter(SERIES_KEY_TOKENS_OUT, TAG_KEY, keyId).increment(out);
            registry.counter(SERIES_KEY_COST, TAG_KEY, keyId).increment(costMicroUsd);
        }
    }

    @Override
    public void recordLedgerWrite(long durationNanos) {
        // The duration Timer's find-then-create pattern (the clear/reset
        // invariant) and its percentile-histogram shape — minus every label: the
        // series measures one store, not one caller (the privacy contract's
        // bluntest series).
        Timer timer = registry.find(SERIES_LEDGER_WRITE).timer();
        if (timer == null) {
            timer = Timer.builder(SERIES_LEDGER_WRITE)
                    .publishPercentileHistogram(true)
                    .register(registry);
        }
        timer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void forgetKey(String keyId) {
        if (keyId == null) {
            return;
        }
        // find is a read-only lookup (it never creates a meter, unlike
        // registry.counter(...)); remove drops each per-key counter so its
        // series does not outlive the key. The unlabeled totals and the
        // per-provider gauges carry no key_id label, so they are never matched.
        //
        // Documented race: recordRequest/recordUsage resolve their meters
        // with registry.counter(name, TAG_KEY, keyId), which re-creates a meter on
        // demand. A request that passed auth just before a POST /key/delete and
        // settles after this forgetKey re-creates the per-key series — it lingers
        // until the next delete. Accepted: the window is a single in-flight request
        // (the tokens were real, so the series is not wrong), and closing it would
        // require gating per-key recording on key lifecycle in Governance or a
        // tombstones approach — neither is worth the plumbing. Pinned by
        // MetricsRecorderTest.forgetKeyThenRecordRecreatesTheSeries.
        for (String series :
                new String[] {SERIES_KEY_REQUESTS, SERIES_KEY_TOKENS_IN, SERIES_KEY_TOKENS_OUT, SERIES_KEY_COST}) {
            Counter counter = registry.find(series).tag(TAG_KEY, keyId).counter();
            if (counter != null) {
                registry.remove(counter);
            }
        }
    }

    /**
     * Coarse status bucket (bounds cardinality — the plan's "no raw status codes"
     * rule): 2xx → {@code 2xx}, 4xx → {@code 4xx} (429s included), 3xx → {@code 4xx}
     * (a 3xx is a client-visible anomaly — a gateway never sends redirects, so
     * bucketing it 5xx would silently contradict the status the client saw; folding
     * it into the client-error class keeps the coarse bucket honest), everything
     * else (1xx, 5xx, ≥600) → {@code 5xx}.
     */
    private static String bucketOf(int status) {
        if (status >= 200 && status < 300) {
            return "2xx";
        }
        if (status >= 300 && status < 500) {
            return "4xx";
        }
        return "5xx";
    }
}
