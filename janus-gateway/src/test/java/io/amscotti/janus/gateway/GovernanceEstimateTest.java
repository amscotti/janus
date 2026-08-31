package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatRequest;
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
