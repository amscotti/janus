package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.Usage;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MicrometerMetricsRecorder} against a real
 * {@link PrometheusMeterRegistry} (no Micronaut context; the BOM-pinned registry
 * class is the native-image-clean {@code io.micrometer.prometheusmetrics} package,
 * Micrometer 1.13+): request counting bucketed by face × coarse status, the latency
 * histogram, unlabeled token/cost totals, and the per-key series — exact scrape text
 * per the step-3 plan. {@code MetricsRecorder.noop} touches no registry.
 */
class MetricsRecorderTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @BeforeEach
    void reset() {
        registry.clear();
    }

    @Test
    void recordRequestIncrementsStatusBucketAndHistogram() {
        recorder().recordRequest("openai", 200, 250, null);

        String scrape = registry.scrape();
        assertLine(scrape, "janus_requests_total{face=\"openai\",status=\"2xx\"} 1.0");
        assertLine(scrape, "janus_request_duration_seconds_count{face=\"openai\"} 1");
        assertLine(scrape, "janus_request_duration_seconds_sum{face=\"openai\"} 0.25");
    }

    @Test
    void recordRequestBucketsCoarseStatus() {
        MetricsRecorder recorder = recorder();
        recorder.recordRequest("anthropic", 429, 10, null); // gateway-originated 429
        recorder.recordRequest("anthropic", 502, 10, null); // provider 5xx passthrough

        String scrape = registry.scrape();
        assertLine(scrape, "janus_requests_total{face=\"anthropic\",status=\"4xx\"} 1.0");
        assertLine(scrape, "janus_requests_total{face=\"anthropic\",status=\"5xx\"} 1.0");
    }

    @Test
    void recordRequestBucketsOutOfBandStatuses() {
        // The (defensive) 3xx bucket — a gateway never sends redirects, so
        // a 3xx status is a client-visible anomaly folded into the 4xx class; 1xx and
        // ≥600 (unset/weird sentinels) stay 5xx.
        MetricsRecorder recorder = recorder();
        recorder.recordRequest("openai", 301, 1, null);
        recorder.recordRequest("openai", 302, 1, null);
        recorder.recordRequest("openai", 600, 1, null);
        recorder.recordRequest("openai", 99, 1, null);

        String scrape = registry.scrape();
        assertLine(scrape, "janus_requests_total{face=\"openai\",status=\"4xx\"} 2.0");
        assertLine(scrape, "janus_requests_total{face=\"openai\",status=\"5xx\"} 2.0");
    }

    @Test
    void recordUsageRecordsUnlabeledTotalsOnlyWithoutKey() {
        recorder().recordUsage(null, new Usage(10, 5, 15), 2800);

        String scrape = registry.scrape();
        assertLine(scrape, "janus_tokens_in_total 10.0");
        assertLine(scrape, "janus_tokens_out_total 5.0");
        assertLine(scrape, "janus_cost_micro_usd_total 2800.0");
        assertFalse(scrape.contains("janus_key_"), "auth-off must never create per-key series:\n" + scrape);
    }

    @Test
    void recordUsageWithKeyRecordsPerKeySeries() {
        recorder().recordUsage("k-1", new Usage(10, 5, 15), 2800);

        String scrape = registry.scrape();
        assertLine(scrape, "janus_key_tokens_in_total{key_id=\"k-1\"} 10.0");
        assertLine(scrape, "janus_key_tokens_out_total{key_id=\"k-1\"} 5.0");
        assertLine(scrape, "janus_key_cost_micro_usd_total{key_id=\"k-1\"} 2800.0");
        assertLine(scrape, "janus_tokens_in_total 10.0");
    }

    @Test
    void recordRequestWithKeyIncrementsKeyRequests() {
        recorder().recordRequest("openai", 200, 250, "k-1");

        String scrape = registry.scrape();
        assertLine(scrape, "janus_key_requests_total{key_id=\"k-1\"} 1.0");
        assertLine(scrape, "janus_requests_total{face=\"openai\",status=\"2xx\"} 1.0");
    }

    @Test
    void nullUsageRecordsZeroTokensAndCost() {
        recorder().recordUsage("k-1", null, 0);

        String scrape = registry.scrape();
        assertLine(scrape, "janus_tokens_in_total 0.0");
        assertLine(scrape, "janus_tokens_out_total 0.0");
        assertLine(scrape, "janus_cost_micro_usd_total 0.0");
        assertLine(scrape, "janus_key_tokens_in_total{key_id=\"k-1\"} 0.0");
        assertLine(scrape, "janus_key_cost_micro_usd_total{key_id=\"k-1\"} 0.0");
    }

    @Test
    void recordLedgerWritePublishesUnlabeledHistogram() {
        // the call-ledger store-write Timer — the
        // janus_request_duration_seconds shape (percentile-histogram bucket lines
        // + count/sum) with NO labels at all: the write is measured per
        // call-record, and the privacy contract allows no label dimension here
        // (key_id would make the hot-key contention visible, but that is
        // Tier-2 territory — the operator correlates with the store instead).
        recorder().recordLedgerWrite(250_000_000L); // 250 ms, nanos

        String scrape = registry.scrape();
        assertLine(scrape, "janus_ledger_write_seconds_count 1");
        assertLine(scrape, "janus_ledger_write_seconds_sum 0.25");
        assertLine(scrape, "janus_ledger_write_seconds_bucket{le=\"+Inf\"} 1");
        assertTrue(
                scrape.lines().anyMatch(line -> line.startsWith("janus_ledger_write_seconds_bucket{le=")),
                "the ledger-write histogram must publish _bucket lines (publishPercentileHistogram):\n" + scrape);
    }

    @Test
    void noopTouchesNoRegistry() {
        MetricsRecorder.noop().recordRequest("openai", 200, 1, "k-1");
        MetricsRecorder.noop().recordUsage("k-1", new Usage(1, 1, 2), 1);
        MetricsRecorder.noop().recordLedgerWrite(1);
        MetricsRecorder.noop().forgetKey("k-1");

        assertEquals("", registry.scrape(), "noop() must never reach a registry");
    }

    @Test
    void forgetKeyRemovesPerKeySeriesButKeepsUnlabeledTotals() {
        MetricsRecorder recorder = recorder();
        recorder.recordRequest("openai", 200, 50, "k-1");
        recorder.recordUsage("k-1", new Usage(10, 5, 15), 2800);

        String before = registry.scrape();
        assertLine(before, "janus_key_requests_total{key_id=\"k-1\"} 1.0");
        assertLine(before, "janus_key_tokens_in_total{key_id=\"k-1\"} 10.0");
        assertLine(before, "janus_key_tokens_out_total{key_id=\"k-1\"} 5.0");
        assertLine(before, "janus_key_cost_micro_usd_total{key_id=\"k-1\"} 2800.0");

        recorder.forgetKey("k-1");

        String after = registry.scrape();
        assertFalse(after.contains("key_id=\"k-1\""), "forgetKey must drop every per-key series for k-1:\n" + after);
        // The unlabeled totals and the face×status counter carry no key_id label —
        // they survive (a revoked key's past traffic still counts toward aggregates).
        assertLine(after, "janus_tokens_in_total 10.0");
        assertLine(after, "janus_tokens_out_total 5.0");
        assertLine(after, "janus_cost_micro_usd_total 2800.0");
        assertLine(after, "janus_requests_total{face=\"openai\",status=\"2xx\"} 1.0");
    }

    @Test
    void forgetKeyIsIdempotentAndSafeForUnknownKeys() {
        MetricsRecorder recorder = recorder();
        recorder.recordUsage("k-1", new Usage(1, 1, 2), 1);
        recorder.forgetKey("never-existed"); // no series — no exception
        recorder.forgetKey("k-1");
        recorder.forgetKey("k-1"); // second call is a no-op
        recorder.forgetKey(null); // null-safe
        String scrape = registry.scrape();
        assertFalse(scrape.contains("key_id=\"k-1\""), "repeated forgetKey must stay a no-op:\n" + scrape);
    }

    @Test
    void forgetKeyThenRecordRecreatesTheSeries() {
        // Documented race: recordUsage/recordRequest resolve their per-key
        // meters on demand, so a record landing after forgetKey re-creates the series —
        // the "series do not accumulate" contract is best-effort across the single
        // in-flight request window. Pins the current semantics: the re-created series
        // is live (the tokens were real) and scrapable.
        MetricsRecorder recorder = recorder();
        recorder.recordRequest("openai", 200, 50, "k-1");
        recorder.forgetKey("k-1");
        String afterForget = registry.scrape();
        assertFalse(afterForget.contains("key_id=\"k-1\""), "forgetKey must drop the series:\n" + afterForget);

        recorder.recordUsage("k-1", new Usage(10, 5, 15), 2800);

        String recreated = registry.scrape();
        assertLine(recreated, "janus_key_tokens_in_total{key_id=\"k-1\"} 10.0");
        assertLine(recreated, "janus_key_cost_micro_usd_total{key_id=\"k-1\"} 2800.0");
    }

    @Test
    void noopForgetKeyIsSafe() {
        // noop.forgetKey never throws and touches no registry.
        MetricsRecorder.noop().forgetKey("k-1");
        MetricsRecorder.noop().forgetKey(null);
    }

    private MicrometerMetricsRecorder recorder() {
        return new MicrometerMetricsRecorder(registry);
    }

    /** Line-exact scrape assertion (the step-3 plan's "scrape text exact" bar). */
    private static void assertLine(String scrape, String expected) {
        assertTrue(
                scrape.lines().anyMatch(line -> line.equals(expected)),
                "expected line: " + expected + "\nscrape:\n" + scrape);
    }
}
