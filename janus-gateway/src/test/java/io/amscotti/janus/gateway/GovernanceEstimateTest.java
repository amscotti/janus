package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.DecisionQuestion;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.Message;
import io.amscotti.janus.core.model.SystemMessage;
import io.amscotti.janus.core.model.ToolMessage;
import io.amscotti.janus.core.model.UserMessage;
import io.amscotti.janus.store.PricingRate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The estimate precedence chain and its fallback constant: the
 * conservative pre-dispatch token estimate is {@code request.maxTokens} when present,
 * else the pricing row's {@code defaultMaxTokens}, else {@link Governance#DEFAULT_MAX_TOKENS}
 * (aligned with the reference implementation semantics {@code @default_max_tokens 4096}).
 */
class GovernanceEstimateTest {

    private static final PricingRate ROW_DEFAULT = new PricingRate(0.14, 0.28, 0.0, 0.0, 2048);
    private static final PricingRate NO_ROW_DEFAULT = new PricingRate(0.14, 0.28, 0.0, 0.0, 0);

    @Test
    void estimatePrecedenceIsRequestThenRowThenReferenceFallback() {
        assertEquals(512, Governance.estimateTokens(request(512), ROW_DEFAULT), "the request's max_tokens wins");
        assertEquals(
                2048,
                Governance.estimateTokens(request(null), ROW_DEFAULT),
                "the row default wins when the request omits max_tokens");
        assertEquals(
                Governance.DEFAULT_MAX_TOKENS,
                Governance.estimateTokens(request(null), NO_ROW_DEFAULT),
                "the gateway fallback wins when neither the request nor the row supplies one");
        assertEquals(
                Governance.DEFAULT_MAX_TOKENS,
                Governance.estimateTokens(request(0), NO_ROW_DEFAULT),
                "a non-positive max_tokens is treated as absent");
    }

    @Test
    void fallbackPinsTheReferenceDefault() {
        assertEquals(
                4096,
                Governance.DEFAULT_MAX_TOKENS,
                "the reference default (4096) — aligned with the gateway default, not 1024");
    }

    // -------------------------------------- prompt-token estimate (budget reserve)

    @Test
    void promptEstimateSumsMessageContentAndSystemCharsDividedByFour() {
        // The prompt half of the budget admission estimate is a
        // content-length heuristic — (system + all message contents) UTF-16 chars / 4.
        ChatRequest request = new ChatRequest(
                "deepseek-v4-flash",
                List.<Message>of(
                        new SystemMessage("sys "),
                        new UserMessage("hello"),
                        new AssistantMessage("world!", null),
                        new ToolMessage("tool-1", "result")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                false,
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                Map.of());
        // chars: 4 (sys) + 5 (hello) + 6 (world!) + 6 (result) = 21 → 21 / 4 = 5.
        assertEquals(5, Governance.estimatePromptTokens(request));
    }

    @Test
    void promptEstimateToleratesNullContentAndNullRequest() {
        assertEquals(
                0,
                Governance.estimatePromptTokens(null),
                "a null request estimates no prompt (defensive — enforce never passes one)");
        ChatRequest blank = new ChatRequest(
                "deepseek-v4-flash",
                List.of(new AssistantMessage(null, List.of(), null), new UserMessage("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                false,
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                Map.of());
        // "hi" = 2 chars → 0 tokens (integer division); null contents count nothing.
        assertEquals(0, Governance.estimatePromptTokens(blank));
    }

    @Test
    void promptEstimateFeedsTheReserveCostWithInputAndCacheRates() {
        // The two halves compose: estimatePromptTokens → estimatePromptMicroUsd prices
        // at max(input, cacheRead, cacheCreation) per token, so a 40-char prompt on
        // DeepSeek reserves 10 × 140 = 1400 micro-USD of input cost up front.
        ChatRequest request = new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("x".repeat(40))),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                false,
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                Map.of());
        long promptTokens = Governance.estimatePromptTokens(request);
        assertEquals(10, promptTokens, "40 chars / 4");
        assertEquals(
                1_400L,
                io.amscotti.janus.store.CostCalculator.estimatePromptMicroUsd(promptTokens, ROW_DEFAULT),
                "10 × (0.14 + 0.0) × 1000 = 1400 micro-USD");
    }

    @Test
    void promptEstimateCoversTheCacheCreationPath() {
        // For Anthropic-class rows the cache-write rate is the most
        // expensive per-token rate — the composed admission estimate prices the prompt
        // at max(input, read, creation), so a prompt entirely written to cache on that
        // request is covered by the reserve (the old input + cacheRead form under-priced
        // it and let a single prompt-heavy cache-write request cross the hard cap).
        PricingRate sonnet = new PricingRate(3.0, 15.0, 0.30, 3.75, 4096);
        ChatRequest request = new ChatRequest(
                "claude-sonnet",
                List.of(new UserMessage("x".repeat(40))),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                false,
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                Map.of());
        long promptTokens = Governance.estimatePromptTokens(request);
        assertEquals(10, promptTokens, "40 chars / 4");
        long estimate = io.amscotti.janus.store.CostCalculator.estimatePromptMicroUsd(promptTokens, sonnet);
        assertEquals(37_500L, estimate, "10 × max(3.0, 0.30, 3.75) × 1000 = 37_500 micro-USD");
        // The actual worst case it must cover: all 10 prompt tokens billed at the
        // creation rate (Anthropic input_tokens is regular-only after the breakpoint).
        long fullyCreated = io.amscotti.janus.store.CostCalculator.costMicroUsd(
                new io.amscotti.janus.core.model.Usage(0, 0, 0, 10L, null), sonnet);
        assertEquals(37_500L, fullyCreated, "10 × 3.75 × 1000 = 37_500 micro-USD");
        assertTrue(estimate >= fullyCreated, "the admission estimate covers the cache-creation path");
    }

    @Test
    void decisionEstimateSumsStateQuestionsAndImages() {
        DecisionRequest request = new DecisionRequest(
                "d3",
                "12345678901234567890",
                List.of("aGVsbG8="),
                Map.of("q", new DecisionQuestion.NoulQuestion("12345678", Map.of("true", "yes!", "false", "no!!!"))),
                Map.of(),
                Map.of());
        // The estimate is the compact serialized request (state/questions/criteria,
        // JSON structure included) / 4, plus 1024 per image. Pinned so a return to
        // the content-only sum (which missed JSON structure and extras) fails.
        assertEquals(37 + 1024, Governance.estimateDecisionTokens(request));
        assertEquals(0, Governance.estimateDecisionTokens(null));
    }

    @Test
    void decisionEstimateWalksStructuredStateTreesAndScoreLevels() {
        // A structured state (array of strings/maps/numbers/booleans) and a score
        // question exercise plainLength's recursion (map keys + leaves, non-string
        // leaves via String.valueOf) and the level-list branch.
        DecisionRequest request = new DecisionRequest(
                "d3",
                List.of("ab", Map.of("k", "v"), 42, true),
                null,
                Map.of("q", new DecisionQuestion.ScoreQuestion("1234", List.of("lvl-a", "lvl-b"))),
                Map.of(),
                Map.of());
        // 132 serialized bytes / 4 = 33. Structured state and score levels are
        // serialized, not walked as a content-length sum.
        assertEquals(33, Governance.estimateDecisionTokens(request));
    }

    @Test
    void decisionEstimateChargesTypeSafeAssetsAsImagesNotBase64Text() {
        DecisionRequest request = new DecisionRequest(
                "jev-latest",
                Map.of("photo", "p1"),
                null,
                Map.of("locate", new DecisionQuestion.BoxQuestion("Find the mug.")),
                Map.of(
                        "assets",
                        Map.of(
                                "p1",
                                Map.of(
                                        "type", "image",
                                        "format", "png",
                                        "data_base64", "a".repeat(20_000)))),
                Map.of());
        long estimate = Governance.estimateDecisionTokens(request);
        assertTrue(estimate >= 1024, "each asset is charged the 1024-token image estimate: " + estimate);
        assertTrue(estimate < 1100, "the 20k base64 payload must not be priced as text: " + estimate);
    }

    @Test
    void settleChunkAccumulatesCachedTokensInTpm() {
        java.time.Clock clock =
                java.time.Clock.fixed(java.time.Instant.ofEpochSecond(1_700_000_000L), java.time.ZoneOffset.UTC);
        io.amscotti.janus.store.FixedWindowRateLimiter limiter =
                new io.amscotti.janus.store.FixedWindowRateLimiter(clock);
        io.amscotti.janus.store.InMemorySpendLedger ledger =
                new io.amscotti.janus.store.InMemorySpendLedger(clock, 100);
        io.amscotti.janus.store.PriceTable table = io.amscotti.janus.store.PriceTable.of(
                Map.of("deepseek-v4-flash", new PricingRate(0.14, 0.28, 0.014, 0.14, 4096)));
        Governance governance = new Governance(
                limiter,
                table,
                ledger,
                new LoggingNotifier(),
                0.8,
                clock,
                MetricsRecorder.noop(),
                new io.amscotti.janus.store.InMemoryCallStore(clock, 100));
        io.amscotti.janus.store.KeyStore.CreatedKey created = new io.amscotti.janus.store.InMemoryKeyStore(clock)
                .create(new io.amscotti.janus.store.KeyStore.KeyCreateRequest(
                        "tpm-cache", List.of("deepseek-v4-flash"), null, null, null, null, 100));
        ChatRequest chatRequest = new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                false,
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                Map.of());
        io.micronaut.http.HttpRequest<?> http = io.micronaut.http.HttpRequest.POST("/v1/chat/completions", "")
                .setAttribute(KeyAuthFilter.KEY_ATTRIBUTE, created.record());
        Governance.Preflight preflight = governance.enforce(http, chatRequest);
        // 10 prompt + 20 cacheCreation + 30 cacheRead + 5 completion = 65 billed tokens.
        io.amscotti.janus.core.model.Usage usage = new io.amscotti.janus.core.model.Usage(10, 5, 15, 20L, 30L);
        io.amscotti.janus.core.model.StreamChunk usageChunk = new io.amscotti.janus.core.model.StreamChunk(
                "id-1", "chat.completion.chunk", 1_700_000_000L, "deepseek-v4-flash", List.of(), usage, Map.of());

        try (java.util.stream.Stream<io.amscotti.janus.core.model.StreamChunk> wrapped =
                governance.wrapStream(preflight, chatRequest, java.util.stream.Stream.of(usageChunk), null)) {
            wrapped.forEach(ignored -> {});
        }

        String keyId = created.record().id();
        assertTrue(!limiter.wouldExceed(keyId, 100, 35), "65 settled + 35 next == 100 (within cap)");
        assertTrue(limiter.wouldExceed(keyId, 100, 36), "65 settled + 36 next == 101 (must count cached tokens)");
    }

    @Test
    void enforceSaturatesReserveWhenPromptAndOutputEstimatesBothNearLongMax() {
        java.time.Clock clock =
                java.time.Clock.fixed(java.time.Instant.ofEpochSecond(1_700_000_000L), java.time.ZoneOffset.UTC);
        io.amscotti.janus.store.FixedWindowRateLimiter limiter =
                new io.amscotti.janus.store.FixedWindowRateLimiter(clock);
        io.amscotti.janus.store.InMemorySpendLedger ledger =
                new io.amscotti.janus.store.InMemorySpendLedger(clock, 100);
        // Both estimateMicroUsd (4096) and estimatePromptMicroUsd (100) saturate at Long.MAX_VALUE
        // with rate 1e20; unsafely adding them wrapped negative (-2L) -> IllegalArgumentException.
        io.amscotti.janus.store.PriceTable table = io.amscotti.janus.store.PriceTable.of(
                Map.of("deepseek-v4-flash", new PricingRate(1e20, 1e20, 0.0, 0.0, 4096)));
        Governance governance = new Governance(
                limiter,
                table,
                ledger,
                new LoggingNotifier(),
                0.8,
                clock,
                MetricsRecorder.noop(),
                new io.amscotti.janus.store.InMemoryCallStore(clock, 100));
        io.amscotti.janus.store.KeyStore.CreatedKey created = new io.amscotti.janus.store.InMemoryKeyStore(clock)
                .create(new io.amscotti.janus.store.KeyStore.KeyCreateRequest(
                        "huge-reserve", List.of("deepseek-v4-flash"), null, 1.0, null, null, null));
        ChatRequest bigPrompt = new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("a".repeat(400))),
                null,
                null,
                null,
                null,
                null,
                null,
                4096,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                false,
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                Map.of());
        io.micronaut.http.HttpRequest<?> http = io.micronaut.http.HttpRequest.POST("/v1/chat/completions", "")
                .setAttribute(KeyAuthFilter.KEY_ATTRIBUTE, created.record());

        // Must deny cleanly with BUDGET_EXCEEDED_HARD (429), not throw IllegalArgumentException (500) on negative -2L.
        org.junit.jupiter.api.Assertions.assertThrows(
                RateLimitExceededException.class, () -> governance.enforce(http, bigPrompt));
    }

    private static ChatRequest request(Integer maxTokens) {
        return new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                maxTokens,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                false,
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                Map.of());
    }
}
