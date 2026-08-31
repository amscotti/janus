package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ImageSourceContent;
import io.amscotti.janus.core.model.ImageUrlContent;
import io.amscotti.janus.core.model.TextContent;
import io.amscotti.janus.core.model.UserMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Official OpenAI GPT-5.6 / Anthropic cache-marker translation: object breakpoints,
 * request-level key/options, and both cross-format directions.
 */
class PromptCacheCodecTest {

    private final OpenAiMessageCodec openAi = OpenAiMessageCodec.create();
    private final AnthropicMessageCodec anthropic = AnthropicMessageCodec.create();
    private final OpenAiResponsesCodec responses = OpenAiResponsesCodec.create();
    private final ObjectMapper mapper = JsonSupport.mapper();

    @Test
    void openAiObjectBreakpointOnSystemDecodesAndReEmitsOnGpt56() throws Exception {
        ChatRequest decoded = openAi.decodeRequest("""
                {
                  "model": "gpt-5.6-luna",
                  "prompt_cache_key": "support:kb-v1",
                  "prompt_cache_options": {"mode": "explicit", "ttl": "30m"},
                  "messages": [
                    {"role": "system", "content": [
                      {"type": "text", "text": "stable prefix",
                       "prompt_cache_breakpoint": {"mode": "explicit"}}
                    ]},
                    {"role": "user", "content": "what next?"}
                  ]
                }
                """);
        assertEquals("stable prefix", decoded.system());
        assertEquals(PromptCache.EPHEMERAL, decoded.cacheControl());
        assertEquals("support:kb-v1", decoded.extras().get("prompt_cache_key"));
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) decoded.extras().get("prompt_cache_options");
        assertEquals("explicit", options.get("mode"));

        String json = openAi.encodeRequest(decoded);
        JsonNode node = mapper.readTree(json);
        JsonNode systemContent = node.get("messages").get(0).get("content");
        assertTrue(systemContent.isArray(), json);
        assertEquals("stable prefix", systemContent.get(0).get("text").asString());
        assertEquals(
                "explicit",
                systemContent.get(0).get("prompt_cache_breakpoint").get("mode").asString());
        assertEquals("support:kb-v1", node.get("prompt_cache_key").asString());
        assertEquals("explicit", node.get("prompt_cache_options").get("mode").asString());
        assertFalse(json.contains("\"cache_control\""), json);
    }

    @Test
    void booleanBreakpointStillDecodesButGpt56EncodeEmitsObject() throws Exception {
        ChatRequest decoded = openAi.decodeRequest("""
                {
                  "model": "gpt-5.6-luna",
                  "messages": [{"role":"user","content":[
                    {"type":"text","text":"hi","prompt_cache_breakpoint":true}
                  ]}]
                }
                """);
        assertEquals(PromptCache.EPHEMERAL, decoded.cacheControl());
        UserMessage user =
                assertInstanceOf(UserMessage.class, decoded.messages().get(0));
        assertTrue(user.isMultimodal());
        TextContent part = assertInstanceOf(TextContent.class, user.parts().get(0));
        assertEquals(PromptCache.EPHEMERAL, part.cacheControl());

        String json = openAi.encodeRequest(decoded);
        JsonNode node = mapper.readTree(json);
        JsonNode content = node.get("messages").get(0).get("content");
        assertTrue(content.isArray(), json);
        assertEquals(
                "explicit",
                content.get(0).get("prompt_cache_breakpoint").get("mode").asString());
        assertFalse(json.contains("true"), "boolean true must not be re-emitted: " + json);
    }

    @Test
    void nonGpt56EncodeDropsBreakpointAndOptions() {
        ChatRequest decoded = openAi.decodeRequest("""
                {
                  "model": "deepseek-v4-flash",
                  "prompt_cache_key": "k",
                  "prompt_cache_options": {"mode": "explicit"},
                  "messages": [{"role":"system","content":[
                    {"type":"text","text":"stable","prompt_cache_breakpoint":{"mode":"explicit"}}
                  ]},{"role":"user","content":"hi"}]
                }
                """);
        String json = openAi.encodeRequest(decoded);
        assertFalse(json.contains("prompt_cache_breakpoint"), json);
        assertFalse(json.contains("prompt_cache_options"), json);
        assertTrue(json.contains("\"prompt_cache_key\":\"k\""), json);
        assertTrue(json.contains("\"content\":\"stable\"") || json.contains("\"content\":\"hi\""), json);
    }

    @Test
    void openAiToAnthropicEmitsCacheControlOnSystemBlock() throws Exception {
        ChatRequest decoded = openAi.decodeRequest("""
                {
                  "model": "claude-sonnet-5",
                  "prompt_cache_key": "k",
                  "prompt_cache_options": {"mode": "explicit", "ttl": "30m"},
                  "messages": [
                    {"role": "system", "content": [
                      {"type": "text", "text": "stable prefix",
                       "prompt_cache_breakpoint": {"mode": "explicit"}}
                    ]},
                    {"role": "user", "content": "what next?"}
                  ]
                }
                """);
        String json = anthropic.encodeRequest(decoded);
        JsonNode node = mapper.readTree(json);
        JsonNode system = node.get("system");
        assertTrue(system.isArray(), json);
        assertEquals("stable prefix", system.get(0).get("text").asString());
        assertEquals("ephemeral", system.get(0).get("cache_control").get("type").asString());
        assertFalse(json.contains("prompt_cache_key"), json);
        assertFalse(json.contains("prompt_cache_options"), json);
        assertFalse(json.contains("prompt_cache_breakpoint"), json);
    }

    @Test
    void anthropicSystemBlockToOpenAiGpt56EmitsObjectBreakpoint() throws Exception {
        ChatRequest decoded = anthropic.decodeRequest("""
                {
                  "model": "gpt-5.6-luna",
                  "max_tokens": 16,
                  "system": [
                    {"type": "text", "text": "stable prefix",
                     "cache_control": {"type": "ephemeral"}}
                  ],
                  "messages": [{"role": "user", "content": "what next?"}]
                }
                """);
        assertEquals("stable prefix", decoded.system());
        assertEquals(PromptCache.EPHEMERAL, decoded.cacheControl());

        String json = openAi.encodeRequest(decoded);
        JsonNode node = mapper.readTree(json);
        JsonNode systemContent = node.get("messages").get(0).get("content");
        assertTrue(systemContent.isArray(), json);
        assertEquals(
                "explicit",
                systemContent.get(0).get("prompt_cache_breakpoint").get("mode").asString());
        assertEquals("explicit", node.get("prompt_cache_options").get("mode").asString());
        assertEquals("30m", node.get("prompt_cache_options").get("ttl").asString());
    }

    @Test
    void anthropicRequestLevelCacheControlStillRoundTrips() throws Exception {
        ChatRequest decoded = anthropic.decodeRequest("""
                {
                  "model": "claude-sonnet-5",
                  "max_tokens": 16,
                  "cache_control": {"type": "ephemeral"},
                  "system": "be brief",
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """);
        assertEquals(PromptCache.EPHEMERAL, decoded.cacheControl());
        String json = anthropic.encodeRequest(decoded);
        JsonNode node = mapper.readTree(json);
        assertEquals("ephemeral", node.get("cache_control").get("type").asString());
        JsonNode system = node.get("system");
        assertTrue(system.isArray(), json);
        assertEquals("be brief", system.get(0).get("text").asString());
        assertEquals("ephemeral", system.get(0).get("cache_control").get("type").asString());
    }

    @Test
    void responsesDeveloperBreakpointDecodesToCacheControl() {
        ChatRequest decoded = responses.decodeRequest("""
                {
                  "model": "gpt-5.6-luna",
                  "store": false,
                  "prompt_cache_key": "support:kb-v1",
                  "prompt_cache_options": {"mode": "explicit", "ttl": "30m"},
                  "input": [
                    {
                      "type": "message",
                      "role": "developer",
                      "content": [
                        {
                          "type": "input_text",
                          "text": "stable prefix",
                          "prompt_cache_breakpoint": {"mode": "explicit"}
                        }
                      ]
                    },
                    {
                      "type": "message",
                      "role": "user",
                      "content": [{"type": "input_text", "text": "where?"}]
                    }
                  ]
                }
                """);
        assertEquals(PromptCache.EPHEMERAL, decoded.cacheControl());
        assertEquals("support:kb-v1", decoded.extras().get("prompt_cache_key"));
        String chat = openAi.encodeRequest(decoded);
        assertTrue(chat.contains("\"prompt_cache_breakpoint\""), chat);
        assertTrue(chat.contains("\"mode\":\"explicit\""), chat);
        assertTrue(chat.contains("stable prefix"), chat);
    }

    @Test
    void supportsExplicitOpenAiBreakpointsMatchesGpt56FamilyOnly() {
        assertTrue(PromptCache.supportsExplicitOpenAiBreakpoints("gpt-5.6-luna"));
        assertTrue(PromptCache.supportsExplicitOpenAiBreakpoints("gpt-5.6"));
        assertTrue(PromptCache.supportsExplicitOpenAiBreakpoints("openai/gpt-5.6-luna"));
        assertTrue(PromptCache.supportsExplicitOpenAiBreakpoints("gpt-5.7"));
        assertFalse(PromptCache.supportsExplicitOpenAiBreakpoints("gpt-5.5"));
        assertFalse(PromptCache.supportsExplicitOpenAiBreakpoints("deepseek-v4-flash"));
        assertFalse(PromptCache.supportsExplicitOpenAiBreakpoints("claude-sonnet-5"));
        assertFalse(PromptCache.supportsExplicitOpenAiBreakpoints("qwen/qwen3.8-max"));
        assertTrue(PromptCache.supportsOpenAiWireCacheControl("qwen/qwen3.8-max"));
        assertTrue(PromptCache.supportsOpenAiWireCacheControl("qwen3.8-max"));
        assertFalse(PromptCache.supportsOpenAiWireCacheControl("gpt-5.6-luna"));
        assertFalse(PromptCache.supportsOpenAiWireCacheControl("deepseek-v4-flash"));
    }

    @Test
    void userPartBreakpointCrossesToAnthropicBlock() throws Exception {
        ChatRequest decoded = openAi.decodeRequest("""
                {
                  "model": "claude-sonnet-5",
                  "messages": [{"role":"user","content":[
                    {"type":"text","text":"long prefix","prompt_cache_breakpoint":{"mode":"explicit"}},
                    {"type":"text","text":"variable question"}
                  ]}]
                }
                """);
        UserMessage user =
                assertInstanceOf(UserMessage.class, decoded.messages().get(0));
        TextContent first = assertInstanceOf(TextContent.class, user.parts().get(0));
        assertEquals(PromptCache.EPHEMERAL, first.cacheControl());

        String json = anthropic.encodeRequest(decoded);
        JsonNode content = mapper.readTree(json).get("messages").get(0).get("content");
        assertTrue(content.isArray(), json);
        assertEquals(
                "ephemeral", content.get(0).get("cache_control").get("type").asString());
        assertFalse(content.get(1).has("cache_control"), json);
    }

    @Test
    void anthropicOneHourTtlRoundTripsOnTheSystemBlock() throws Exception {
        ChatRequest decoded = anthropic.decodeRequest("""
                {
                  "model": "claude-sonnet-5",
                  "max_tokens": 16,
                  "system": [
                    {"type": "text", "text": "stable prefix",
                     "cache_control": {"type": "ephemeral", "ttl": "1h"}}
                  ],
                  "messages": [{"role": "user", "content": "what next?"}]
                }
                """);
        assertTrue(PromptCache.isEphemeral(decoded.cacheControl()));
        @SuppressWarnings("unchecked")
        Map<String, Object> marker = (Map<String, Object>) decoded.cacheControl();
        assertEquals("1h", String.valueOf(marker.get("ttl")));

        String json = anthropic.encodeRequest(decoded);
        JsonNode system = mapper.readTree(json).get("system");
        assertTrue(system.isArray(), json);
        assertEquals("ephemeral", system.get(0).get("cache_control").get("type").asString());
        assertEquals("1h", system.get(0).get("cache_control").get("ttl").asString());
    }

    @Test
    void assistantToolUseBlockMarkerSurvivesTheAnthropicRoundTrip() throws Exception {
        // The standard agent-loop caching pattern puts the breakpoint on the assistant
        // tool_use block (cache the whole replayed prefix). The canonical model has no
        // per-block home for assistant content, so decode captures the marker into the
        // request-level cacheControl fallback and encode re-emits it — with a system
        // prompt present that is the system block; without one, the last user message.
        // Never a silent drop to cache misses.
        ChatRequest decoded = anthropic.decodeRequest("""
                {
                  "model": "claude-sonnet-5",
                  "max_tokens": 16,
                  "system": "be brief",
                  "messages": [
                    {"role": "user", "content": "task"},
                    {"role": "assistant", "content": [
                      {"type": "text", "text": "calling"},
                      {"type": "tool_use", "id": "t1", "name": "get", "input": {},
                       "cache_control": {"type": "ephemeral", "ttl": "1h"}}
                    ]},
                    {"role": "user", "content": [
                      {"type": "tool_result", "tool_use_id": "t1", "content": "42"}
                    ]}
                  ]
                }
                """);
        assertTrue(PromptCache.isEphemeral(decoded.cacheControl()), "marker captured, not dropped");
        String json = anthropic.encodeRequest(decoded);
        JsonNode system = mapper.readTree(json).get("system");
        assertTrue(system.isArray(), json);
        assertEquals("ephemeral", system.get(0).get("cache_control").get("type").asString());
        assertEquals("1h", system.get(0).get("cache_control").get("ttl").asString());
        // idempotence: the re-placed marker decodes back to the same canonical slot
        assertEquals(decoded.cacheControl(), anthropic.decodeRequest(json).cacheControl());
    }

    @Test
    void assistantTextBlockMarkerSurvivesViaTheLastUserFallback() throws Exception {
        // No system prompt: the captured assistant-text marker re-emits on the last
        // user message's text block (the single-slot placement rule).
        ChatRequest decoded = anthropic.decodeRequest("""
                {
                  "model": "claude-sonnet-5",
                  "max_tokens": 16,
                  "messages": [
                    {"role": "assistant", "content": [
                      {"type": "text", "text": "prior turn", "cache_control": {"type": "ephemeral"}}
                    ]},
                    {"role": "user", "content": "next"}
                  ]
                }
                """);
        assertEquals(PromptCache.EPHEMERAL, decoded.cacheControl());
        String json = anthropic.encodeRequest(decoded);
        JsonNode content = mapper.readTree(json).get("messages").get(1).get("content");
        assertTrue(content.isArray(), json);
        assertEquals(
                "ephemeral", content.get(0).get("cache_control").get("type").asString());
    }

    @Test
    void twoUserPartBreakpointsBothSurviveAnthropicEncode() throws Exception {
        ChatRequest decoded = anthropic.decodeRequest("""
                {
                  "model": "claude-sonnet-5",
                  "max_tokens": 16,
                  "messages": [{"role":"user","content":[
                    {"type":"text","text":"prefix-a","cache_control":{"type":"ephemeral"}},
                    {"type":"text","text":"prefix-b","cache_control":{"type":"ephemeral"}},
                    {"type":"text","text":"variable question"}
                  ]}]
                }
                """);
        UserMessage user =
                assertInstanceOf(UserMessage.class, decoded.messages().get(0));
        assertEquals(3, user.parts().size());
        TextContent first = assertInstanceOf(TextContent.class, user.parts().get(0));
        TextContent second = assertInstanceOf(TextContent.class, user.parts().get(1));
        TextContent third = assertInstanceOf(TextContent.class, user.parts().get(2));
        assertEquals(PromptCache.EPHEMERAL, first.cacheControl());
        assertEquals(PromptCache.EPHEMERAL, second.cacheControl());
        assertNull(third.cacheControl());

        String json = anthropic.encodeRequest(decoded);
        JsonNode content = mapper.readTree(json).get("messages").get(0).get("content");
        assertEquals(
                "ephemeral", content.get(0).get("cache_control").get("type").asString());
        assertEquals(
                "ephemeral", content.get(1).get("cache_control").get("type").asString());
        assertFalse(content.get(2).has("cache_control"), json);
    }

    @Test
    void openAiImageUrlBreakpointCrossesToAnthropicImageCacheControl() throws Exception {
        ChatRequest decoded = openAi.decodeRequest("""
                {
                  "model": "claude-sonnet-5",
                  "messages": [{"role":"user","content":[
                    {"type":"text","text":"look"},
                    {"type":"image_url","image_url":{"url":"https://example.com/a.png"},
                     "prompt_cache_breakpoint":{"mode":"explicit"}}
                  ]}]
                }
                """);
        UserMessage user =
                assertInstanceOf(UserMessage.class, decoded.messages().get(0));
        ImageUrlContent image =
                assertInstanceOf(ImageUrlContent.class, user.parts().get(1));
        assertEquals(PromptCache.EPHEMERAL, image.cacheControl());

        String json = anthropic.encodeRequest(decoded);
        JsonNode content = mapper.readTree(json).get("messages").get(0).get("content");
        assertEquals("image", content.get(1).get("type").asString());
        assertEquals("url", content.get(1).get("source").get("type").asString());
        assertEquals(
                "ephemeral", content.get(1).get("cache_control").get("type").asString());
        assertFalse(content.get(0).has("cache_control"), json);
    }

    @Test
    void anthropicImageCacheControlCrossesToOpenAiGpt56ImageBreakpoint() throws Exception {
        ChatRequest decoded = anthropic.decodeRequest("""
                {
                  "model": "gpt-5.6-luna",
                  "max_tokens": 16,
                  "messages": [{"role":"user","content":[
                    {"type":"text","text":"look"},
                    {"type":"image","source":{"type":"url","url":"https://example.com/a.png"},
                     "cache_control":{"type":"ephemeral"}}
                  ]}]
                }
                """);
        UserMessage user =
                assertInstanceOf(UserMessage.class, decoded.messages().get(0));
        ImageSourceContent image =
                assertInstanceOf(ImageSourceContent.class, user.parts().get(1));
        assertEquals(PromptCache.EPHEMERAL, image.cacheControl());

        String json = openAi.encodeRequest(decoded);
        JsonNode content = mapper.readTree(json).get("messages").get(0).get("content");
        assertEquals("image_url", content.get(1).get("type").asString());
        assertEquals(
                "explicit",
                content.get(1).get("prompt_cache_breakpoint").get("mode").asString());
        assertFalse(content.get(0).has("prompt_cache_breakpoint"), json);
    }

    @Test
    void responsesInputImageBreakpointDecodesAndReEmitsOnChat() {
        ChatRequest decoded = responses.decodeRequest("""
                {
                  "model": "gpt-5.6-luna",
                  "store": false,
                  "input": [{
                    "type": "message",
                    "role": "user",
                    "content": [
                      {"type": "input_text", "text": "look"},
                      {"type": "input_image", "image_url": "https://example.com/a.png",
                       "prompt_cache_breakpoint": {"mode": "explicit"}}
                    ]
                  }]
                }
                """);
        assertEquals(PromptCache.EPHEMERAL, decoded.cacheControl());
        UserMessage user =
                assertInstanceOf(UserMessage.class, decoded.messages().get(0));
        ImageUrlContent image =
                assertInstanceOf(ImageUrlContent.class, user.parts().get(1));
        assertEquals(PromptCache.EPHEMERAL, image.cacheControl());
        String chat = openAi.encodeRequest(decoded);
        assertTrue(chat.contains("\"prompt_cache_breakpoint\""), chat);
        assertTrue(chat.contains("https://example.com/a.png"), chat);
    }

    @Test
    void openAiCacheControlOnQwenDecodesAndReEmits() throws Exception {
        ChatRequest decoded = openAi.decodeRequest("""
                {
                  "model": "qwen/qwen3.8-max",
                  "messages": [{"role":"user","content":[
                    {"type":"text","text":"long prefix","cache_control":{"type":"ephemeral"}},
                    {"type":"text","text":"variable question"}
                  ]}]
                }
                """);
        UserMessage user =
                assertInstanceOf(UserMessage.class, decoded.messages().get(0));
        TextContent first = assertInstanceOf(TextContent.class, user.parts().get(0));
        assertEquals(PromptCache.EPHEMERAL, first.cacheControl());

        String json = openAi.encodeRequest(decoded);
        JsonNode content = mapper.readTree(json).get("messages").get(0).get("content");
        assertEquals(
                "ephemeral", content.get(0).get("cache_control").get("type").asString());
        assertFalse(content.get(0).has("prompt_cache_breakpoint"), json);
        assertFalse(content.get(1).has("cache_control"), json);
        assertFalse(json.contains("prompt_cache_options"), json);
    }

    @Test
    void anthropicCacheControlEncodesAsCacheControlOnQwen() throws Exception {
        ChatRequest decoded = anthropic.decodeRequest("""
                {
                  "model": "qwen/qwen3.8-max",
                  "max_tokens": 16,
                  "system": [
                    {"type": "text", "text": "stable prefix",
                     "cache_control": {"type": "ephemeral"}}
                  ],
                  "messages": [{"role": "user", "content": "what next?"}]
                }
                """);
        String json = openAi.encodeRequest(decoded);
        JsonNode systemContent = mapper.readTree(json).get("messages").get(0).get("content");
        assertTrue(systemContent.isArray(), json);
        assertEquals(
                "ephemeral",
                systemContent.get(0).get("cache_control").get("type").asString());
        assertFalse(json.contains("prompt_cache_breakpoint"), json);
    }
}
