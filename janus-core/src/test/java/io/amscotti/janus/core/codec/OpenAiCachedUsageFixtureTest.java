package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.Usage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The OpenAI cached-token decode path: a response carrying
 * {@code prompt_tokens_details.cached_tokens} must map to {@link Usage#cacheReadInputTokens}
 * with the cached count <b>subtracted</b> from {@code promptTokens} (the canonical
 * regular-input convention both faces share, matching the reference
 * {@code Pricing.normalize_tokens} reference), so {@code CostCalculator} prices cached
 * tokens at the cache rate — never a double bill at the full input rate.
 */
class OpenAiCachedUsageFixtureTest {

    private static final String FIXTURE = "/fixtures/openai/chat.response.cached.json";

    private final OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());

    @Test
    void cachedTokensPopulateCacheReadAndSplitFromPrompt() throws Exception {
        ChatResponse response = codec.decodeResponse(read());

        // prompt_tokens 14 = 9 regular + 5 cached; completion 12; total 26 (full input + output).
        assertEquals(new Usage(9, 12, 26, null, 5L), response.usage());
    }

    @Test
    void reencodeRestoresFullPromptSoTheLedgerStaysConsistent() throws Exception {
        // Re-emitting the canonical's regular (cache-split) prompt_tokens
        // verbatim broke the OpenAI wire contract prompt + completion == total (a 9/12/26
        // passthrough). The full input count is restored on the OpenAI wire and the cache
        // split re-emits as prompt_tokens_details / prompt_cache_hit_tokens.
        ChatResponse response = codec.decodeResponse(read());
        String json = codec.encodeResponse(response);

        assertTrue(json.contains("\"prompt_tokens\":14"), json);
        assertTrue(json.contains("\"completion_tokens\":12"), json);
        assertTrue(json.contains("\"total_tokens\":26"), json);
        assertTrue(json.contains("\"cached_tokens\":5"), json);
        assertTrue(json.contains("\"prompt_cache_hit_tokens\":5"), json);
        assertTrue(json.contains("\"prompt_tokens\":14,\"completion_tokens\":12,\"total_tokens\":26"), json);
    }

    @Test
    void dualCacheSpellingPrefersPromptTokensDetailsOnDisagreement() throws Exception {
        // An endpoint emitting both spellings with different values must not
        // silently credit the smaller delta to regular input — prompt_tokens_details
        // cached_tokens is the primary spelling and wins (documented choice).
        ChatResponse response = codec.decodeResponse("""
                {
                  "id": "chatcmpl-1", "object": "chat.completion", "created": 1, "model": "deepseek-v4-flash",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "x"}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 14, "completion_tokens": 12, "total_tokens": 26,
                            "prompt_tokens_details": {"cached_tokens": 5},
                            "prompt_cache_hit_tokens": 3}
                }
                """);
        // details (5) wins over the alias (3): regular = 14 - 5 = 9, never 11.
        assertEquals(new Usage(9, 12, 26, null, 5L), response.usage());
    }

    @Test
    void cachedExceedingPromptClampsToPromptAndPreservesInputAccounting() throws Exception {
        // A malformed upstream reporting cached_tokens > prompt_tokens must
        // not over-report cache beyond the real input (the old max-based split billed 10
        // cached for a 5-token prompt). The cache claim clamps to the prompt count, so
        // regular + cacheRead == prompt (the input is never zeroed by bad data).
        ChatResponse response = codec.decodeResponse("""
                {
                  "id": "chatcmpl-1", "object": "chat.completion", "created": 1, "model": "deepseek-v4-flash",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "x"}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7,
                            "prompt_tokens_details": {"cached_tokens": 10}}
                }
                """);
        Usage usage = response.usage();
        // cache clamped to prompt (5), regular = 5 - 5 = 0 — cache never exceeds input.
        assertEquals(0L, usage.promptTokens());
        assertEquals(5L, usage.cacheReadInputTokens(), "cacheRead must clamp to the prompt count");
        assertEquals(5L, usage.promptTokens() + usage.cacheReadInputTokens(), "regular + cacheRead == prompt");

        // the re-encode restores a consistent ledger (5 + 2 == 7)
        String json = codec.encodeResponse(response);
        assertTrue(json.contains("\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7"), json);
    }

    @Test
    void deepSeekTopLevelCacheHitAliasIsMappedTheSameWay() throws Exception {
        // DeepSeek/Kimi report prompt_cache_hit_tokens as a top-level usage member (no
        // prompt_tokens_details); both spellings must split prompt_tokens identically.
        ChatResponse response = codec.decodeResponse("""
                {
                  "id": "chatcmpl-1", "object": "chat.completion", "created": 1, "model": "deepseek-v4-flash",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "x"}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 14, "completion_tokens": 12, "total_tokens": 26,
                            "prompt_cache_hit_tokens": 5}
                }
                """);
        assertEquals(new Usage(9, 12, 26, null, 5L), response.usage());
    }

    @Test
    void cacheWriteTokensSplitAsCacheCreationAndAreRestoredOnEncode() {
        ChatResponse response = codec.decodeResponse("""
                {
                  "id": "chatcmpl-1", "object": "chat.completion", "created": 1, "model": "gpt-5.6-luna",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "x"}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 14, "completion_tokens": 12, "total_tokens": 26,
                            "prompt_tokens_details": {"cached_tokens": 3, "cache_write_tokens": 5}}
                }
                """);
        assertEquals(new Usage(6, 12, 26, 5L, 3L), response.usage());
        String json = codec.encodeResponse(response);
        assertTrue(json.contains("\"prompt_tokens\":14,\"completion_tokens\":12,\"total_tokens\":26"), json);
        assertTrue(json.contains("\"cached_tokens\":3"), json);
        assertTrue(json.contains("\"cache_write_tokens\":5"), json);
        assertTrue(json.contains("\"prompt_cache_hit_tokens\":3"), json);
    }

    @Test
    void absentCacheMembersLeavePromptUnsplit() throws Exception {
        ChatResponse response = codec.decodeResponse("""
                {
                  "id": "chatcmpl-1", "object": "chat.completion", "created": 1, "model": "deepseek-v4-flash",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "x"}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 14, "completion_tokens": 12, "total_tokens": 26}
                }
                """);
        assertEquals(new Usage(14, 12, 26), response.usage());
        assertNull(response.usage().cacheReadInputTokens(), "no cached tokens ⇒ null cache field");
    }

    @Test
    void handBuiltCanonicalViolatingTheRestoreInvariantNeverRestoresPrompt() throws Exception {
        // The restore heuristic fires only on the exact invariant codec-produced
        // canonicals satisfy (prompt + completion + cached == total). A hand-built
        // canonical that violates the equality (7 + 5 + 3 != 10) must NOT have its prompt
        // bumped on the OpenAI wire — the split is left verbatim.
        // The emit-side triple is still reconciled — total is recomputed as
        // prompt + completion (12), never the invariant-violating 10 the canonical
        // carried, so a strict SDK's prompt + completion == total validation holds even
        // for a canonical produced by a contract-violating upstream (cached counted
        // OUTSIDE prompt_tokens).
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("x", null), "stop")),
                new Usage(7, 5, 10, null, 3L),
                "stop",
                Map.of(),
                null);
        String json = codec.encodeResponse(response);
        assertTrue(json.contains("\"prompt_tokens\":7,\"completion_tokens\":5,\"total_tokens\":12"), json);
    }

    @Test
    void handBuiltFullPromptCanonicalIsNeverDoubleCountedOnTheOpenAiWire() throws Exception {
        // A HAND-BUILT canonical whose promptTokens already includes the cached count
        // keeps prompt + completion == total (the Usage hand-built contract), so the
        // restore equality prompt + completion + cached == total fails (10 + 5 + 3 != 15)
        // and the full prompt is never double-counted (a regression here would emit 13
        // prompt_tokens for a 10-token input and corrupt the ledger). Codec-produced
        // usage never has this shape: the Anthropic decode stores REGULAR input with
        // the additive total (see CanonicalRoundTripPropertyTest
        // .anthropicSourcedCacheUsageRestoresFullInputOnTheOpenAiWire).
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("x", null), "stop")),
                new Usage(10, 5, 15, null, 3L),
                "stop",
                Map.of(),
                null);
        String json = codec.encodeResponse(response);
        assertTrue(json.contains("\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15"), json);
        assertFalse(json.contains("prompt_tokens\":13"), json);
    }

    private static String read() throws IOException {
        try (InputStream in = OpenAiCachedUsageFixtureTest.class.getResourceAsStream(FIXTURE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
