package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.HostedToolDefinition;
import io.amscotti.janus.core.model.ImageSourceContent;
import io.amscotti.janus.core.model.ImageUrlContent;
import io.amscotti.janus.core.model.SystemMessage;
import io.amscotti.janus.core.model.TextContent;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.core.model.ToolDefinition;
import io.amscotti.janus.core.model.ToolMessage;
import io.amscotti.janus.core.model.UserMessage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Request direction of {@link AnthropicMessageCodec} ( steps 2–3): byte-level
 * snake_case wire-shape assertions on encode, validation + extras pass-through on
 * decode. Inline JSON strings only — committed golden fixtures cover that.
 */
class AnthropicRequestCodecTest {

    private final AnthropicMessageCodec codec = new AnthropicMessageCodec(JsonSupport.mapper());
    private final ObjectMapper mapper = JsonSupport.mapper();

    // ------------------------------------------------------------------ encode

    @Test
    void minimalRequestEncodesExactWireShape() {
        assertEquals(
                "{\"model\":\"model-1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":4096}",
                codec.encodeRequest(minimalRequest(false)));
    }

    @Test
    void streamTrueRequestEmitsStream() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"max_tokens\":4096"), json);
        assertTrue(json.contains("\"stream\":true"), json);
    }

    @Test
    void fullFeaturedRequestEncodesSnakeCaseWireShape() throws Exception {
        String json = codec.encodeRequest(fullRequest());
        assertFalse(json.contains("\"role\":\"system\""), json); // SystemMessage dropped from messages
        assertTrue(json.contains("\"role\":\"user\""), json);
        assertTrue(json.contains("\"role\":\"assistant\""), json);
        JsonNode node = mapper.readTree(json);
        JsonNode system = node.get("system");
        assertTrue(system.isArray(), json);
        assertEquals("be brief\n\nbe concise", system.get(0).get("text").asString());
        assertEquals("ephemeral", system.get(0).get("cache_control").get("type").asString());
        assertEquals(2, node.get("messages").size());
        assertEquals("user", node.get("messages").get(0).get("role").asString());
        assertEquals("assistant", node.get("messages").get(1).get("role").asString());
        assertEquals(512, node.get("max_tokens").asInt());
        assertEquals(0.7, node.get("temperature").asDouble());
        assertEquals(0.9, node.get("top_p").asDouble());
        assertEquals(50, node.get("top_k").asInt());
        assertEquals("END", node.get("stop_sequences").get(0).asString());
        assertTrue(node.get("stream").asBoolean());
        // D1 (blessed fix): Anthropic has no stream_options — the canonical's
        // streamOptions must NOT reach the Anthropic wire (real Anthropic rejects the
        // field; the corpus pinned the passthrough as the red hand-off test).
        assertFalse(node.has("stream_options"), "Anthropic-outbound must not carry stream_options");
        assertEquals("enabled", node.get("thinking").get("type").asString());
        assertEquals(1024, node.get("thinking").get("budget_tokens").asInt());
        assertEquals("ephemeral", node.get("cache_control").get("type").asString());
        assertEquals("x", node.get("custom").asString());
        assertFalse(node.has("meta"));
    }

    @Test
    void encodeDropsOpenAiOnlyFields() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                42L,
                1,
                0.0,
                0.1,
                Map.of("50256", -100),
                Map.of("type", "json_object"),
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        assertFalse(json.contains("\"seed\""), json);
        assertFalse(json.contains("\"n\""), json);
        assertFalse(json.contains("\"frequency_penalty\""), json);
        assertFalse(json.contains("\"presence_penalty\""), json);
        assertFalse(json.contains("\"logit_bias\""), json);
        assertFalse(json.contains("\"response_format\""), json);
    }

    @Test
    void metaIsNeverEmitted() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                Map.of("custom", "x"),
                Map.of("request_id", "abc"));
        String json = codec.encodeRequest(request);
        assertFalse(json.contains("request_id"), json);
        assertTrue(json.contains("\"custom\":\"x\""), json);
    }

    @Test
    void extrasMergeBaseWinsOnCollision() {
        // extras "max_tokens" collides with the mapped field: the mapped (gateway) value wins.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                extrasWithNull("max_tokens", 999, "custom", "x"),
                null);
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"max_tokens\":4096"), json);
        assertFalse(json.contains("\"max_tokens\":999"), json);
        assertTrue(json.contains("\"custom\":\"x\""), json);
    }

    @Test
    void extrasNeverReintroduceAnAbsentMappedField() {
        // An extras entry
        // colliding with a DECLARED-but-absent DTO component must be dropped, not
        // merged back — a hand-built/gateway-produced canonical whose extras carry an
        // Anthropic-mapped key (temperature, stop_sequences, …) while the mapped
        // component is null would otherwise re-introduce the field on the wire,
        // overriding the codec's own mapping decision (and for keys like stream_options
        // real Anthropic rejects unknown fields outright).
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null, // system
                null, // tools
                null, // toolChoice
                null, // temperature absent
                null, // topP
                null, // topK
                null, // maxTokens
                null, // stop absent
                null, // seed
                null, // n
                null, // frequencyPenalty
                null, // presencePenalty
                null, // logitBias
                null, // responseFormat
                false, // stream
                null, // streamOptions
                null, // reasoning
                null, // cacheControl
                extrasWithNull("temperature", 2.5, "stop_sequences", List.of("END"), "custom", "x"),
                null); // meta
        String json = codec.encodeRequest(request);
        assertFalse(json.contains("temperature"), json);
        assertFalse(json.contains("stop_sequences"), json);
        assertTrue(json.contains("\"custom\":\"x\""), json);
    }

    @Test
    void anthropicOriginatedMetadataUserIdRoundTripsUnchanged() {
        // The Anthropic wire's OWN metadata ({user_id}) must keep round-
        // tripping through this codec while the OpenAI shape is filtered away.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                extrasWithNull("metadata", Map.of("user_id", "anthropic-set")),
                null);
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"metadata\":{\"user_id\":\"anthropic-set\"}"), json);
    }

    @Test
    void chatWireUserIsRemappedToAnthropicMetadataUserId() {
        // The OpenAI chat-wire `user` field has an Anthropic-legal home —
        // metadata.user_id — so it is remapped, not dropped (the one row with a remap).
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                extrasWithNull("user", "u1", "custom", "x"),
                null);
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"metadata\":{\"user_id\":\"u1\"}"), json);
        assertFalse(json.contains("\"user\":"), "the chat-wire user field never reaches the Anthropic wire: " + json);
        assertTrue(
                json.contains("\"custom\":\"x\""), "genuinely unknown extras keep their documented tolerance: " + json);
    }

    @Test
    void explicitAnthropicMetadataUserIdWinsOverTheUserRemap() {
        // Deterministic precedence when both spellings ride one canonical: the explicit
        // Anthropic metadata.user_id (set deliberately on that wire, round-tripping)
        // wins over the remapped chat-wire user.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                extrasWithNull("user", "remapped", "metadata", Map.of("user_id", "kept")),
                null);
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"user_id\":\"kept\""), json);
        assertFalse(json.contains("remapped"), json);
    }

    @Test
    void openAiOnlyChatFieldsAreDroppedOnTheAnthropicEgress() {
        // Known OpenAI-only chat fields (SDK defaults among them) previously merged
        // top-level into Anthropic requests, which real Anthropic rejects with a 400 —
        // a live chat-face bug. The scoped drop-list removes exactly these; the OpenAI
        // `metadata` object (a different shape from Anthropic's) is dropped with them.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                extrasWithNull(
                        "service_tier", "auto",
                        "parallel_tool_calls", false,
                        "prompt_cache_key", "k",
                        "prompt_cache_options", Map.of("mode", "explicit"),
                        "safety_identifier", "s",
                        "metadata", Map.of("foo", "bar"),
                        "custom", "x"),
                null);
        String json = codec.encodeRequest(request);
        assertFalse(json.contains("service_tier"), json);
        assertFalse(json.contains("parallel_tool_calls"), json);
        assertFalse(json.contains("prompt_cache_key"), json);
        assertFalse(json.contains("prompt_cache_options"), json);
        assertFalse(json.contains("safety_identifier"), json);
        assertFalse(json.contains("foo"), "the OpenAI metadata shape is dropped: " + json);
        assertTrue(json.contains("\"custom\":\"x\""), json);
    }

    @Test
    void effortShapedReasoningTranslatesToAdaptiveThinkingWithOutputConfig() {
        // {effort} -> the modern Anthropic spelling — thinking
        // {type:"adaptive"} + top-level output_config {effort} — which preserves the
        // effort end-to-end (claude-sonnet-5 rejects thinking.type:"enabled"; live-
        // verified 2026-08). minimal maps to low; none omits both fields entirely.
        for (String effort : new String[] {"low", "medium", "high"}) {
            ChatRequest request = new ChatRequest(
                    "model-1",
                    List.of(new UserMessage("hi")),
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
                    null,
                    null,
                    false,
                    null,
                    Map.of("effort", effort),
                    null,
                    null,
                    null);
            String json = codec.encodeRequest(request);
            assertTrue(json.contains("\"thinking\":{\"type\":\"adaptive\"}"), effort + ": " + json);
            assertTrue(json.contains("\"output_config\":{\"effort\":\"" + effort + "\"}"), effort + ": " + json);
        }
        ChatRequest minimal = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                Map.of("effort", "minimal"),
                null,
                null,
                null);
        String minimalJson = codec.encodeRequest(minimal);
        assertTrue(minimalJson.contains("\"output_config\":{\"effort\":\"low\"}"), minimalJson);
        ChatRequest none = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                Map.of("effort", "none"),
                null,
                null,
                null);
        String noneJson = codec.encodeRequest(none);
        assertFalse(noneJson.contains("thinking"), "effort none omits thinking");
        assertFalse(noneJson.contains("output_config"), noneJson);
    }

    @Test
    void anthropicShapedThinkingPassesThroughUntouchedAndUnknownEffortIs400() {
        // The structural distinction: already-Anthropic-shaped maps round-trip; an
        // unknown effort spelling is a typed 400 (never a silent wrong budget).
        ChatRequest shaped = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                Map.of("type", "enabled", "budget_tokens", 8192),
                null,
                null,
                null);
        assertTrue(
                codec.encodeRequest(shaped).contains("\"budget_tokens\":8192"),
                "Anthropic-shaped reasoning passes verbatim");

        ChatRequest bogus = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                Map.of("effort", "ultra"),
                null,
                null,
                null);
        AnthropicCodecException e = org.junit.jupiter.api.Assertions.assertThrows(
                AnthropicCodecException.class, () -> codec.encodeRequest(bogus));
        assertEquals(AnthropicCodecException.TYPE_INVALID_REQUEST, e.type());
        assertTrue(e.getMessage().contains("ultra"), e.getMessage());
    }

    @Test
    void toolCacheControlRoundTripsOnTheLastTool() throws Exception {
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "claude-sonnet-5",
                  "max_tokens": 16,
                  "messages": [{"role": "user", "content": "hi"}],
                  "tools": [
                    {"name": "a", "input_schema": {"type": "object"}},
                    {"name": "b", "input_schema": {"type": "object"},
                     "cache_control": {"type": "ephemeral"}}
                  ]
                }
                """);
        assertEquals(2, decoded.tools().size());
        assertEquals(PromptCache.EPHEMERAL, decoded.tools().get(1).cacheControl());
        assertNull(decoded.tools().get(0).cacheControl());
        String json = codec.encodeRequest(decoded);
        JsonNode tools = mapper.readTree(json).get("tools");
        assertFalse(tools.get(0).has("cache_control"), json);
        assertEquals("ephemeral", tools.get(1).get("cache_control").get("type").asString());
    }

    @Test
    void inboundWebSearch20250305BecomesHostedToolAndRoundTrips() throws Exception {
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "claude-sonnet-5",
                  "max_tokens": 64,
                  "messages": [{"role": "user", "content": "search please"}],
                  "tools": [{
                    "type": "web_search_20250305",
                    "name": "web_search",
                    "max_uses": 1
                  }]
                }
                """);
        assertTrue(decoded.tools() == null || decoded.tools().isEmpty(), "must not become a function tool");
        assertNotNull(decoded.hostedTools());
        assertEquals(1, decoded.hostedTools().size());
        HostedToolDefinition.WebSearch search =
                (HostedToolDefinition.WebSearch) decoded.hostedTools().getFirst();
        assertEquals("low", search.searchContextSize());

        String json = codec.encodeRequest(decoded);
        JsonNode tools = mapper.readTree(json).get("tools");
        assertEquals(1, tools.size(), json);
        assertEquals("web_search_20250305", tools.get(0).get("type").asString());
        assertEquals("web_search", tools.get(0).get("name").asString());
        assertEquals(1, tools.get(0).get("max_uses").asInt());
    }

    @Test
    void openAiJsonSchemaIsDroppedOnAnthropicEncode() {
        ChatRequest request = new ChatRequest(
                "claude-sonnet-5",
                List.of(new UserMessage("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                16,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(
                        "type",
                        "json_schema",
                        "json_schema",
                        Map.of("name", "answer", "schema", Map.of("type", "object"))),
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        assertFalse(json.contains("response_format"), json);
        assertFalse(json.contains("json_schema"), json);
    }

    @Test
    void hostedWebSearchEncodesAsTheServerToolWithMaxUses() {
        // search_context_size -> max_uses (low 1 / medium 5 / high 10); a null size
        // omits max_uses (provider default); user_location passes through.
        for (Object[] row : new Object[][] {{"low", 1}, {"medium", 5}, {"high", 10}}) {
            ChatRequest request = new ChatRequest(
                    "model-1",
                    List.of(new UserMessage("hi")),
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
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    List.of(new HostedToolDefinition.WebSearch((String) row[0], null)),
                    null,
                    null);
            String json = codec.encodeRequest(request);
            assertTrue(
                    json.contains("\"name\":\"web_search\",\"type\":\"web_search_20250305\",\"max_uses\":" + row[1]),
                    row[0] + ": " + json);
        }
        ChatRequest noSize = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                List.of(new HostedToolDefinition.WebSearch(null, Map.of("city", "Berlin"))),
                null,
                null);
        String json = codec.encodeRequest(noSize);
        assertTrue(json.contains("\"web_search_20250305\""), json);
        assertFalse(json.contains("max_uses"), "null size omits max_uses: " + json);
        assertTrue(json.contains("\"city\":\"Berlin\""), json);
    }

    @Test
    void toolStrictIsDroppedOnTheAnthropicEncode() {
        // The Anthropic wire has no `strict` (structured outputs are OpenAI-leg
        // only) — the canonical slot is documented-dropped on this egress.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                List.of(new ToolDefinition(
                        "function",
                        "get_weather",
                        "current weather in a city",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}",
                        Boolean.TRUE)),
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
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"name\":\"get_weather\""), json);
        assertFalse(json.contains("strict"), "Anthropic encode drops strict: " + json);
    }

    @Test
    void encodeToolsToInputSchemaAndToolChoice() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                List.of(new ToolDefinition(
                        "function",
                        "get_weather",
                        "current weather in a city",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")),
                "auto",
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
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        // tools → {name, description, input_schema} (input_schema from the raw-JSON schema)
        assertTrue(
                json.contains("\"tools\":[{\"name\":\"get_weather\",\"description\":\"current weather in a city\","
                        + "\"input_schema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}]"),
                json);
        // toolChoice "auto" → {"type":"auto"}
        assertTrue(json.contains("\"tool_choice\":{\"type\":\"auto\"}"), json);
    }

    @Test
    void encodeToolWithoutSchemaUsesDefaultInputSchema() {
        // A tool definition with null/blank arguments must encode the
        // the reference default {"type":"object","properties":{}} — the previous implementation
        // emitted {"object":"object"} (missing the "type" key), which Anthropic rejects.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                List.of(new ToolDefinition("function", "get_weather", null, null)),
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
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"input_schema\":{\"type\":\"object\",\"properties\":{}}"), json);
        assertFalse(json.contains("\"object\":\"object\""), json);
    }

    @Test
    void encodeToolChoiceFormsViaMapper() {
        for (String[] pair : new String[][] {
            {"none", "{\"type\":\"none\"}"},
            {"required", "{\"type\":\"any\"}"},
            {"auto", "{\"type\":\"auto\"}"},
        }) {
            ChatRequest request = new ChatRequest(
                    "model-1",
                    List.of(new UserMessage("hi")),
                    null,
                    null,
                    pair[0],
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
                    false,
                    null,
                    null,
                    null,
                    null,
                    null);
            String json = codec.encodeRequest(request);
            assertTrue(json.contains("\"tool_choice\":" + pair[1]), json);
        }
        // OpenAI function object → {"type":"tool","name":N}
        Map<String, Object> functionObject = new HashMap<>();
        functionObject.put("type", "function");
        functionObject.put("function", Map.of("name", "get_weather"));
        ChatRequest withFunction = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                null,
                functionObject,
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
                false,
                null,
                null,
                null,
                null,
                null);
        String functionJson = codec.encodeRequest(withFunction);
        assertTrue(functionJson.contains("\"tool_choice\":{\"type\":\"tool\",\"name\":\"get_weather\"}"), functionJson);
    }

    @Test
    void encodeDisableParallelToolUseExtrasIsConsumedIntoToolChoice() {
        // The Anthropic-only disable_parallel_tool_use flag (captured into extras on
        // decode) is re-emitted inside tool_choice on encode and must NOT ride the
        // top-level extras merge.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                null,
                "auto",
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
                false,
                null,
                null,
                null,
                Map.of(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL, true),
                null);
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"tool_choice\":{\"type\":\"auto\",\"disable_parallel_tool_use\":true}"), json);
        assertFalse(json.contains(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL), json);
    }

    @Test
    void encodeToolMessageToUserRoleToolResultBlock() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new ToolMessage("call_1", "{\"temp\":18}")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        // ToolMessage → user-role message with a tool_result block (tool_use_id from toolCallId)
        assertTrue(json.contains("\"role\":\"user\""), json);
        assertTrue(
                json.contains(
                        "\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\",\"content\":\"{\\\"temp\\\":18}\"}]"),
                json);
    }

    @Test
    void encodeToolMessageWithoutToolCallIdIsRejected() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new ToolMessage(null, "ok")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        AnthropicCodecException e = assertThrows(AnthropicCodecException.class, () -> codec.encodeRequest(request));
        assertEquals(AnthropicCodecException.TYPE_INVALID_REQUEST, e.type());
        assertTrue(e.getMessage().contains("tool_call_id"), e.getMessage());
    }

    @Test
    void encodeUserMessageWithNullOrBlankContentIsRejected() {
        // A canonical UserMessage(null) — constructible via the codec's
        // own decode of a wire message with "content":null, or a content-less user message
        // from an OpenAI client — used to encode to {"role":"user"} with no content, which
        // real Anthropic rejects with a 400. The codec now catches it with
        // TYPE_INVALID_REQUEST (mirroring the tool-call-id check) instead of emitting an
        // invalid wire body.
        for (String content : new String[] {null, ""}) {
            ChatRequest request = new ChatRequest(
                    "model-1",
                    List.of(new UserMessage(content)),
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
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null);
            AnthropicCodecException e = assertThrows(AnthropicCodecException.class, () -> codec.encodeRequest(request));
            assertEquals(AnthropicCodecException.TYPE_INVALID_REQUEST, e.type());
            assertTrue(e.getMessage().contains("content"), e.getMessage());
        }
    }

    @Test
    void decodeContentNullWireMessageThenEncodeRejects() {
        // Round-trip view: decode stays tolerant ({"content":null}
        // becomes UserMessage(null)), but re-encoding that canonical to the Anthropic wire
        // is rejected — real Anthropic has no content-less user message, so the failure is
        // surfaced as a typed codec error rather than an upstream 400.
        ChatRequest decoded =
                codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":null}]}");
        assertEquals(List.of(new UserMessage(null)), decoded.messages());
        AnthropicCodecException e = assertThrows(AnthropicCodecException.class, () -> codec.encodeRequest(decoded));
        assertEquals(AnthropicCodecException.TYPE_INVALID_REQUEST, e.type());
        assertTrue(e.getMessage().contains("content"), e.getMessage());
    }

    @Test
    void encodeAssistantToolCallsToToolUseBlocks() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(
                        new AssistantMessage(
                                null,
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        new UserMessage("ok")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"role\":\"assistant\""), json);
        // arguments (raw JSON string) → input (decoded object)
        assertTrue(
                json.contains(
                        "\"content\":[{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"Paris\"}}]"),
                json);
    }

    @Test
    void encodeAssistantToolCallWithBlankArgumentsEmitsEmptyInput() {
        // The tool-call path must mirror the streaming encoder's
        // empty-input behavior — blank arguments → "input":{} (not the input_schema
        // default, and not the old malformed {"object":"object"}).
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(
                        new UserMessage("hi"),
                        new AssistantMessage(
                                null,
                                List.of(new ToolCall("call_1", "function", new FunctionCall("get_weather", null))))),
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
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"input\":{}"), json);
        assertFalse(json.contains("\"input\":{\"type\":\"object\""), json);
    }

    @Test
    void encodeMixedTextAndToolCallsToContentArray() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(
                        new AssistantMessage(
                                "let me check",
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        new UserMessage("ok")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        // text + tool-call assistant message → mixed content array
        assertTrue(
                json.contains(
                        "\"content\":[{\"type\":\"text\",\"text\":\"let me check\"},"
                                + "{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"Paris\"}}]"),
                json);
    }

    @Test
    void encodeInvalidToolArgumentsAreRejectedNamingTheTool() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                List.of(new ToolDefinition("function", "get_weather", null, "{not json")),
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
                false,
                null,
                null,
                null,
                null,
                null);
        AnthropicCodecException e = assertThrows(AnthropicCodecException.class, () -> codec.encodeRequest(request));
        assertEquals(AnthropicCodecException.TYPE_INVALID_REQUEST, e.type());
        assertTrue(e.getMessage().contains("get_weather"), e.getMessage());
    }

    // ------------------------------------------------------------------ decode

    @Test
    void minimalWireRequestDecodes() throws Exception {
        ChatRequest request = codec.decodeRequest(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":100}");
        assertEquals("m", request.model());
        assertEquals(List.of(new UserMessage("hi")), request.messages());
        assertEquals(100, request.maxTokens());
        assertNull(request.system());
        assertFalse(request.stream());
    }

    @Test
    void blankModelIsRejected() {
        AnthropicCodecException e = assertThrows(
                AnthropicCodecException.class,
                () -> codec.decodeRequest("{\"model\":\" \",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));
        assertEquals(AnthropicCodecException.TYPE_INVALID_REQUEST, e.type());
    }

    @Test
    void emptyOrMissingMessagesAreRejected() {
        assertThrows(AnthropicCodecException.class, () -> codec.decodeRequest("{\"model\":\"m\",\"messages\":[]}"));
        assertThrows(AnthropicCodecException.class, () -> codec.decodeRequest("{\"model\":\"m\"}"));
    }

    @Test
    void unknownRoleIsRejected() {
        AnthropicCodecException e = assertThrows(
                AnthropicCodecException.class,
                () -> codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"system\",\"content\":\"hi\"}]}"));
        assertEquals(AnthropicCodecException.TYPE_INVALID_REQUEST, e.type());
        assertTrue(e.getMessage().contains("system"), e.getMessage());
    }

    @Test
    void systemStringFormDecodes() throws Exception {
        ChatRequest request = codec.decodeRequest(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"system\":\"be brief\"}");
        assertEquals("be brief", request.system());
    }

    @Test
    void systemTextBlockArrayFormJoins() throws Exception {
        ChatRequest request =
                codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"system\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}");
        assertEquals("ab", request.system());
    }

    @Test
    void systemWithNonTextBlockIsRejected() {
        // Anthropic system accepts text blocks only — the rejection stays but no longer
        // names (multimodal/thinking system content is out of scope, documented).
        AnthropicCodecException e = assertThrows(
                AnthropicCodecException.class,
                () -> codec.decodeRequest(
                        "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"system\":["
                                + "{\"type\":\"text\",\"text\":\"a\"},"
                                + "{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"f\",\"input\":{}}]}"));
        assertTrue(e.getMessage().contains("text"), e.getMessage());
        assertFalse(e.getMessage().contains("unused-marker"), e.getMessage());
        assertEquals(AnthropicCodecException.TYPE_INVALID_REQUEST, e.type());
    }

    @Test
    void contentStringFormDecodes() throws Exception {
        ChatRequest request =
                codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"assistant\",\"content\":\"hello\"}]}");
        assertEquals(List.of(new AssistantMessage("hello", null)), request.messages());
    }

    @Test
    void contentTextBlockArrayFormJoinsAndDropsThinking() throws Exception {
        ChatRequest request = codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"a\"},"
                + "{\"type\":\"thinking\",\"thinking\":\"x\",\"signature\":\"s\"},"
                + "{\"type\":\"text\",\"text\":\"b\"}]}]}");
        assertEquals(List.of(new UserMessage("ab")), request.messages());
    }

    @Test
    void contentToolUseBlockDecodesToToolCalls() {
        ChatRequest request = codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"let me\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"f\",\"input\":{\"city\":\"Paris\"}}]}]}");
        // mixed text + tool content → AssistantMessage(content, toolCalls); input → raw-JSON arguments
        assertEquals(
                List.of(new AssistantMessage(
                        "let me",
                        List.of(new ToolCall("t", "function", new FunctionCall("f", "{\"city\":\"Paris\"}"))))),
                request.messages());
    }

    @Test
    void contentToolResultBlockDecodesToToolMessage() {
        ChatRequest request = codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\",\"content\":\"{\\\"temp\\\":18}\"}]}]}");
        assertEquals(List.of(new ToolMessage("call_1", "{\"temp\":18}")), request.messages());
    }

    @Test
    void contentMixedTextAndToolResultDecodesToUserThenToolMessages() {
        ChatRequest request = codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"done\"},"
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\",\"content\":\"ok\"}]}]}");
        assertEquals(List.of(new UserMessage("done"), new ToolMessage("call_1", "ok")), request.messages());
    }

    @Test
    void wireToolsAndToolChoiceDecodeToCanonical() throws Exception {
        ChatRequest request =
                codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"tools\":[{\"name\":\"get_weather\",\"description\":\"current weather in a city\","
                        + "\"input_schema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}],"
                        + "\"tool_choice\":{\"type\":\"any\"}}");
        assertEquals(
                List.of(new ToolDefinition(
                        "function",
                        "get_weather",
                        "current weather in a city",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")),
                request.tools());
        assertEquals("required", request.toolChoice()); // {"type":"any"} → "required"
    }

    @Test
    void wireToolChoiceDisableParallelToolUseFoldsIntoExtras() throws Exception {
        ChatRequest request =
                codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"tool_choice\":{\"type\":\"auto\",\"disable_parallel_tool_use\":true}}");
        assertEquals("auto", request.toolChoice());
        assertEquals(Boolean.TRUE, request.extras().get(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL));
    }

    @Test
    void thinkingCacheControlAndStreamOptionsMapToCanonicalReservedFields() throws Exception {
        ChatRequest request =
                codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":1024},"
                        + "\"cache_control\":{\"type\":\"ephemeral\"},"
                        + "\"stream_options\":{\"include_usage\":true},\"stream\":true}");
        assertEquals(Map.of("type", "enabled", "budget_tokens", 1024), request.reasoning());
        assertEquals(Map.of("type", "ephemeral"), request.cacheControl());
        assertEquals(Map.of("include_usage", true), request.streamOptions());
        assertTrue(request.stream());
    }

    @Test
    void claudeCodeAdaptiveThinkingAndOutputConfigRoundTrip() throws Exception {
        // Claude Code 2.1.x: thinking.type=adaptive + display (unknown to Anthropic)
        // + sibling output_config.effort. Encode must emit adaptive thinking without
        // display, and keep output_config.effort.
        ChatRequest request = codec.decodeRequest("{\"model\":\"claude-sonnet-5\",\"max_tokens\":64,\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                + "\"thinking\":{\"type\":\"adaptive\",\"display\":\"omitted\"},"
                + "\"output_config\":{\"effort\":\"high\"}}");
        assertEquals("adaptive", request.reasoning().get("type"));
        assertEquals("high", request.reasoning().get("effort"));
        String json = codec.encodeRequest(request);
        JsonNode node = mapper.readTree(json);
        assertEquals("adaptive", node.get("thinking").get("type").asString(), json);
        assertFalse(node.get("thinking").has("display"), json);
        assertEquals("high", node.get("output_config").get("effort").asString(), json);
    }

    @Test
    void nullValuedThinkingDecodesWithoutNpeAndRoundTrips() throws Exception {
        // {"thinking":{...,"x":null}} previously NPE'd — ChatRequest's
        // Map.copyOf(reasoning) threw on the null value, escaping decodeRequest as a raw
        // NullPointerException instead of an AnthropicCodecException. The pass-through
        // contract (mirror of extras) makes null-valued wire fields legitimate: the
        // decode now succeeds and the value survives a decode → encode round trip.
        ChatRequest request =
                codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":1024,\"x\":null}}");
        assertEquals(3, request.reasoning().size());
        assertTrue(request.reasoning().containsKey("x"));
        assertNull(request.reasoning().get("x"));
        assertEquals(1024, request.reasoning().get("budget_tokens"));

        String json = codec.encodeRequest(request);
        JsonNode node = mapper.readTree(json);
        assertTrue(node.get("thinking").has("x"), json);
        assertTrue(node.get("thinking").get("x").isNull(), json);
    }

    @Test
    void stopSequencesAndMaxTokensDecode() throws Exception {
        ChatRequest request =
                codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"stop_sequences\":[\"END\"],\"max_tokens\":200}");
        assertEquals(List.of("END"), request.stop());
        assertEquals(200, request.maxTokens());
    }

    @Test
    void stopSequencesWithNullElementsDecodeWithoutNpe() throws Exception {
        // A client "stop_sequences":[null] is malformed, but it is wire input — the
        // decode must not fail with a raw NullPointerException escaping List.copyOf in
        // the DTO/canonical constructors (the same NPE-escape-on-wire-input class the
        // null-valued maps fixed). The element pass-through-survives so the upstream
        // rejects it with a typed error.
        ChatRequest request =
                codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"stop_sequences\":[\"END\",null],\"max_tokens\":200}");
        assertEquals(Arrays.asList("END", null), request.stop());
        assertNull(request.stop().get(1));
    }

    @Test
    void unknownTopLevelFieldsFoldIntoExtras() throws Exception {
        ChatRequest request =
                codec.decodeRequest("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"custom\":\"x\",\"metadata\":{\"user\":\"u\"}}");
        assertEquals(Map.of("custom", "x", "metadata", Map.of("user", "u")), request.extras());
    }

    @Test
    void unknownMessageFieldsFoldIntoRequestExtras() throws Exception {
        ChatRequest request = codec.decodeRequest(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\",\"name\":\"alice\"}]}");
        assertEquals(Map.of("name", "alice"), request.extras());
    }

    @Test
    void namedSystemMessageContentFlattensAndNameNeverLeaksTopLevel() throws Exception {
        // A named system message in the canonical messages list (OpenAI-legal
        // per-message name) must not leak as a top-level "name" on the Anthropic wire
        // (real Anthropic rejects unknown request fields). Its content flattens into the
        // top-level system string; the name has no Anthropic home and is dropped.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new SystemMessage("be brief", "guardian"), new UserMessage("hi")),
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
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        assertFalse(json.contains("\"name\""), json);
        assertTrue(json.contains("\"system\":\"be brief\""), json);
        assertTrue(json.contains("\"role\":\"user\""), json);
    }

    // ------------------------------------------------------------------ multimodal

    @Test
    void multimodalUserImageBlocksRoundTripOnAnthropicLeg() {
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "claude-sonnet",
                  "max_tokens": 64,
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "what is in this image?"},
                      {"type": "image", "source": {
                        "type": "base64",
                        "media_type": "image/png",
                        "data": "abc123"
                      }}
                    ]
                  }]
                }
                """);
        assertEquals(1, decoded.messages().size());
        UserMessage user = (UserMessage) decoded.messages().getFirst();
        assertTrue(user.isMultimodal());
        assertEquals(2, user.parts().size());
        assertEquals("what is in this image?", ((TextContent) user.parts().get(0)).text());
        ImageSourceContent img = (ImageSourceContent) user.parts().get(1);
        assertEquals("base64", img.type());
        assertEquals("image/png", img.mediaType());
        assertEquals("abc123", img.data());

        String reencoded = codec.encodeRequest(decoded);
        assertTrue(reencoded.contains("\"type\":\"image\""), reencoded);
        assertTrue(reencoded.contains("\"type\":\"base64\""), reencoded);
        assertTrue(reencoded.contains("abc123"), reencoded);
        assertTrue(reencoded.contains("what is in this image?"), reencoded);
    }

    @Test
    void openAiDataUrlImageConvertsToAnthropicBase64Source() {
        // model, messages, system, tools, toolChoice, temp, topP, topK, maxTokens, …
        ChatRequest openAiStyle = new ChatRequest(
                "claude-sonnet",
                List.of(UserMessage.multimodal(List.of(
                        new TextContent("describe"), new ImageUrlContent("data:image/png;base64,iVBOR", "low")))),
                null,
                null,
                null,
                null,
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(openAiStyle);
        assertTrue(json.contains("\"type\":\"image\""), json);
        assertTrue(json.contains("\"type\":\"base64\""), json);
        assertTrue(json.contains("\"media_type\":\"image/png\""), json);
        assertTrue(json.contains("\"data\":\"iVBOR\""), json);
        assertFalse(json.contains("image_url"), json);
        assertFalse(json.contains("data:image"), json);
    }

    @Test
    void openAiHttpsImageConvertsToAnthropicUrlSource() {
        ChatRequest openAiStyle = new ChatRequest(
                "claude-sonnet",
                List.of(UserMessage.multimodal(
                        List.of(new TextContent("look"), new ImageUrlContent("https://example.com/a.png")))),
                null,
                null,
                null,
                null,
                null,
                null,
                64,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        String json = codec.encodeRequest(openAiStyle);
        assertTrue(json.contains("\"type\":\"url\""), json);
        assertTrue(json.contains("https://example.com/a.png"), json);
        ChatRequest again = codec.decodeRequest(json);
        UserMessage user = (UserMessage) again.messages().getFirst();
        assertTrue(user.isMultimodal());
        assertInstanceOf(ImageSourceContent.class, user.parts().get(1));
        assertEquals("url", ((ImageSourceContent) user.parts().get(1)).type());
    }

    // ------------------------------------------------------------------ helpers

    private static ChatRequest minimalRequest(boolean stream) {
        return new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                null,
                null,
                stream,
                null,
                null,
                null,
                null,
                null);
    }

    private static ChatRequest fullRequest() {
        return new ChatRequest(
                "model-1",
                List.of(new SystemMessage("be concise"), new UserMessage("hi"), new AssistantMessage("hello", null)),
                "be brief",
                null,
                null,
                0.7,
                0.9,
                50,
                512,
                List.of("END"),
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                Map.of("include_usage", true),
                Map.of("type", "enabled", "budget_tokens", 1024),
                Map.of("type", "ephemeral"),
                Map.of("custom", "x"),
                null);
    }

    /** {@link Map#of} forbids null values; build the extras map manually (m1 null-tolerance). */
    private static Map<String, Object> extrasWithNull(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
