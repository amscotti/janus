package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.HostedToolCall;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.store.CostCalculator;
import io.amscotti.janus.store.FixedWindowRateLimiter;
import io.amscotti.janus.store.InMemoryKeyStore;
import io.amscotti.janus.store.InMemorySpendLedger;
import io.amscotti.janus.store.KeyRecord;
import io.amscotti.janus.store.KeyStore;
import io.amscotti.janus.store.PriceTable;
import io.amscotti.janus.store.PricingRate;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * the {@link Governance} recorder hook (direct unit tests, no Micronaut
 * context): {@link #finalize} records the exact {@link CostCalculator} micro-USD
 * (pinned against the DeepSeek fixtures: 10×0.14 + 5×0.28 = 2 800) plus the
 * per-key series; auth-off finalize records the unlabeled totals only; the stream
 * wrap records from the terminal usage-bearing chunk, records a zero entry on clean
 * exhaustion without usage, and records <b>nothing</b> on the abort/release path;
 * and the 6-arg constructor (the pre- form) delegates to
 * {@link MetricsRecorder#noop} — the suites' byte-identical guarantee.
 */
class GovernanceMetricsTest {

    private static final java.time.Clock CLOCK = TestKeyAuthFactory.CLOCK;

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final InMemorySpendLedger ledger = new InMemorySpendLedger(CLOCK, 1000);
    private final RecordingNotifier notifier = new RecordingNotifier();
    private final InMemoryKeyStore keyStore = new InMemoryKeyStore(CLOCK);

    @BeforeEach
    void reset() {
        registry.clear();
    }

    @Test
    void finalizeRecordsExactMicroUsdCostAndPerKeySeries() {
        KeyRecord key = key();

        governance()
                .finalize(null, request("deepseek-v4-flash"), response(new Usage(10, 5, 15)), preflight(key), 0, null);

        String scrape = registry.scrape();
        assertLine(scrape, "janus_tokens_in_total 10.0");
        assertLine(scrape, "janus_tokens_out_total 5.0");
        assertLine(scrape, "janus_cost_micro_usd_total 2800.0");
        assertLine(scrape, "janus_key_tokens_in_total{key_id=\"" + key.id() + "\"} 10.0");
        assertLine(scrape, "janus_key_tokens_out_total{key_id=\"" + key.id() + "\"} 5.0");
        assertLine(scrape, "janus_key_cost_micro_usd_total{key_id=\"" + key.id() + "\"} 2800.0");
    }

    @Test
    void authOffFinalizeRecordsUnlabeledTotalsOnly() {
        governance()
                .finalize(
                        null,
                        request("deepseek-v4-flash"),
                        response(new Usage(10, 5, 15)),
                        Governance.Preflight.NONE,
                        0,
                        null);

        String scrape = registry.scrape();
        assertLine(scrape, "janus_tokens_in_total 10.0");
        assertLine(scrape, "janus_cost_micro_usd_total 2800.0");
        assertFalse(scrape.contains("janus_key_"), "auth-off must never create per-key series:\n" + scrape);
    }

    @Test
    void authOffStreamWrapRecordsUnlabeledTotalsOnly() {
        Stream<StreamChunk> stream = Stream.of(contentChunk("Hello"), usageChunk(new Usage(10, 5, 15)));

        try (Stream<StreamChunk> wrapped =
                governance().wrapStream(Governance.Preflight.NONE, request("deepseek-v4-flash"), stream, null)) {
            wrapped.forEach(chunk -> {});
        }

        String scrape = registry.scrape();
        assertLine(scrape, "janus_tokens_in_total 10.0");
        assertLine(scrape, "janus_tokens_out_total 5.0");
        assertLine(scrape, "janus_cost_micro_usd_total 2800.0");
        assertFalse(scrape.contains("janus_key_"), "auth-off streams must never create per-key series:\n" + scrape);
    }

    @Test
    void streamWrapRecordsFromTerminalUsageChunk() {
        KeyRecord key = key();
        Stream<StreamChunk> stream = Stream.of(contentChunk("Hello"), usageChunk(new Usage(10, 5, 15)));

        try (Stream<StreamChunk> wrapped =
                governance().wrapStream(preflight(key), request("deepseek-v4-flash"), stream, null)) {
            wrapped.forEach(chunk -> {});
        }

        String scrape = registry.scrape();
        assertLine(scrape, "janus_tokens_in_total 10.0");
        assertLine(scrape, "janus_key_tokens_in_total{key_id=\"" + key.id() + "\"} 10.0");
        assertLine(scrape, "janus_key_cost_micro_usd_total{key_id=\"" + key.id() + "\"} 2800.0");
    }

    @Test
    void streamExhaustionWithoutUsageChunkRecordsZero() {
        KeyRecord key = key();
        Stream<StreamChunk> stream = Stream.of(contentChunk("Hello"), contentChunk(" world"));

        try (Stream<StreamChunk> wrapped = governance().wrapStream(preflight(key), null, stream, null)) {
            wrapped.forEach(chunk -> {});
        }

        String scrape = registry.scrape();
        assertLine(scrape, "janus_tokens_in_total 0.0");
        assertLine(scrape, "janus_tokens_out_total 0.0");
        assertLine(scrape, "janus_cost_micro_usd_total 0.0");
        assertLine(scrape, "janus_key_tokens_in_total{key_id=\"" + key.id() + "\"} 0.0");
        assertLine(scrape, "janus_key_cost_micro_usd_total{key_id=\"" + key.id() + "\"} 0.0");
    }

    @Test
    void streamAbortRecordsNothing() {
        KeyRecord key = key();
        Governance.Preflight preflight = preflight(key);
        Stream<StreamChunk> wrapped = governance().wrapStream(preflight, null, Stream.of(contentChunk("Hello")), null);

        var iterator = wrapped.iterator();
        assertTrue(iterator.hasNext(), "one chunk arrives");
        iterator.next();
        wrapped.close(); // abort/cancel before exhaustion: release, never record

        String scrape = registry.scrape();
        assertFalse(scrape.contains("janus_tokens_in_total 10"), "an abort must not record tokens:\n" + scrape);
        assertFalse(scrape.contains("janus_key_tokens_in_total"), "an abort must not create key series:\n" + scrape);
        assertFalse(scrape.contains("janus_key_cost_micro_usd_total"), scrape);
    }

    @Test
    void finalizeBillsHostedWebSearchesBesidesTokens() {
        // Anthropic bills each hosted web_search at web-search-per-1k besides
        // result tokens. searchCount used to be dead — 10 searches at $10/1k
        // = $0.10 = 100_000 micro, plus the 2_800 token pin.
        PriceTable withSearch =
                PriceTable.of(Map.of("deepseek-v4-flash", new PricingRate(0.14, 0.28, 0.0, 0.0, 4096, 10.0)));
        Governance gov = new Governance(
                new FixedWindowRateLimiter(CLOCK),
                withSearch,
                ledger,
                notifier,
                0.8,
                CLOCK,
                new MicrometerMetricsRecorder(registry));
        KeyRecord key = key();
        PricingRate rate = withSearch.rateFor("deepseek-v4-flash");
        ChatResponse res = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                java.util.Collections.nCopies(10, new HostedToolCall.WebSearchCall("q")),
                Map.of(),
                Map.of());
        gov.finalize(
                null,
                request("deepseek-v4-flash"),
                res,
                new Governance.Preflight(key, rate, 1024, CostCalculator.estimateMicroUsd(1024, rate), true, false, 0),
                0,
                null);
        assertLine(registry.scrape(), "janus_cost_micro_usd_total 102800.0");
    }

    @Test
    void sixArgConstructorDelegatesToNoopRecorder() {
        KeyRecord key = key();
        Governance sixArg = new Governance(
                new FixedWindowRateLimiter(CLOCK), TestGovernanceFactory.PRICES, ledger, notifier, 0.8, CLOCK);

        sixArg.finalize(null, null, response(new Usage(10, 5, 15)), preflight(key), 0, null);

        assertFalse(
                registry.scrape().contains("janus_"),
                "the 6-arg form delegates to MetricsRecorder.noop() — behavior byte-identical");
    }

    // ------------------------------------------------------------------ helpers

    private Governance governance() {
        return new Governance(
                new FixedWindowRateLimiter(CLOCK),
                TestGovernanceFactory.PRICES,
                ledger,
                notifier,
                0.8,
                CLOCK,
                new MicrometerMetricsRecorder(registry));
    }

    private KeyRecord key() {
        return keyStore.create(new KeyStore.KeyCreateRequest(
                        "metrics", List.of("deepseek-v4-flash"), null, 1.0, null, null, null))
                .record();
    }

    private Governance.Preflight preflight(KeyRecord key) {
        PricingRate rate = TestGovernanceFactory.PRICES.rateFor("deepseek-v4-flash");
        return new Governance.Preflight(key, rate, 1024, CostCalculator.estimateMicroUsd(1024, rate), true, false, 0);
    }

    private static ChatRequest request(String model) {
        return new ChatRequest(
                model, List.of(), null, null, null, null, null, null, null, null, null, null, null, null, Map.of(),
                Map.of(), false, Map.of(), Map.of(), null, Map.of(), Map.of());
    }

    private static ChatResponse response(Usage usage) {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                usage,
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static StreamChunk contentChunk(String text) {
        return new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, text, null), null)),
                null,
                Map.of());
    }

    private static StreamChunk usageChunk(Usage usage) {
        return new StreamChunk(
                "chatcmpl-1", "chat.completion.chunk", 1_700_000_000L, "deepseek-v4-flash", List.of(), usage, Map.of());
    }

    private static void assertLine(String scrape, String expected) {
        assertTrue(
                scrape.lines().anyMatch(line -> line.equals(expected)),
                "expected line: " + expected + "\nscrape:\n" + scrape);
    }
}
