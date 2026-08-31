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
import io.amscotti.janus.core.model.DeveloperMessage;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.ImageUrlContent;
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

/**
 * Request direction of {@link OpenAiMessageCodec} ( steps 2–3): byte-level snake_case
 * wire-shape assertions on encode, validation + extras pass-through on decode. Inline
 * JSON strings only — committed golden fixtures cover that.
 */
class OpenAiRequestCodecTest {

    private final OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());

    // ------------------------------------------------------------------ encode

    @Test
    void minimalRequestEncodesExactWireShape() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null, // system
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
        assertEquals(
                "{\"model\":\"model-1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}",
                codec.encodeRequest(request));
    }

    @Test
    void fullFeaturedRequestEncodesSnakeCaseWireShape() {
        String json = codec.encodeRequest(fullRequest());
        assertTrue(json.contains("\"model\":\"model-1\""), json);
        assertTrue(json.contains("\"role\":\"system\",\"content\":\"be brief\""), json);
        assertTrue(json.contains("\"role\":\"user\""), json);
        assertTrue(json.contains("\"role\":\"assistant\""), json);
        // Tool message wire shape: role + content + tool_call_id (component order).
        assertTrue(json.contains("\"role\":\"tool\""), json);
        assertTrue(json.contains("\"tool_call_id\":\"call_1\""), json);
        assertTrue(json.contains("\"temperature\":0.7"), json);
        assertTrue(json.contains("\"top_p\":0.9"), json);
        // top_k is deliberately dropped on the OpenAI egress (strict OpenAI 400s it);
        // pinned as ABSENT even when the canonical request carries it.
        assertTrue(!json.contains("\"top_k\""), json);
        assertTrue(json.contains("\"max_completion_tokens\":512"), json);
        assertTrue(json.contains("\"stop\":[\"END\"]"), json);
        assertTrue(json.contains("\"seed\":42"), json);
        assertTrue(json.contains("\"n\":1"), json);
        assertTrue(json.contains("\"frequency_penalty\":0.0"), json);
        assertTrue(json.contains("\"presence_penalty\":0.1"), json);
        assertTrue(json.contains("\"logit_bias\":{\"50256\":-100}"), json);
        assertTrue(json.contains("\"response_format\":{\"type\":\"json_object\"}"), json);
        assertTrue(json.contains("\"stream\":true"), json);
        assertTrue(json.contains("\"stream_options\":{\"include_usage\":true}"), json);
        assertTrue(json.contains("\"tool_choice\":\"auto\""), json);
        assertTrue(
                json.contains("\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                        + "\"description\":\"current weather in a city\","
                        + "\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}}"),
                json);
    }

    @Test
    void toolChoiceNormalizesAnthropicIdiomaticCanonicalValuesOnEncode() {
        // canonical convention: toolChoice is OpenAI-idiomatic. A hand-built
        // Anthropic-idiomatic canonical value ({"type":"auto"}) normalizes to "auto".
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                null,
                Map.of("type", "auto"), // Anthropic-idiomatic → must normalize
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
        assertTrue(json.contains("\"tool_choice\":\"auto\""), json);
        assertFalse(json.contains("\"tool_choice\":{\"type\":\"auto\"}"), json);
    }

    @Test
    void systemFieldAndSystemMessageMergeIntoOneLeadingSystemMessage() {
        // Dedupe rule (pinned): canonical system + SystemMessage in messages must not
        // duplicate; they merge into a single leading system message, system content first.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new io.amscotti.janus.core.model.SystemMessage("be concise"), new UserMessage("hi")),
                "be brief",
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
        assertTrue(json.contains("\"role\":\"system\",\"content\":\"be brief\\n\\nbe concise\""), json);
        assertEquals(1, countOccurrences(json, "\"role\":\"system\""));
    }

    @Test
    void systemFieldPlusSystemMessageIsNotRoundTripIdempotent() {
        // Documented consequence of the pinned dedupe rule: canonical → encode →
        // decode yields the joined text in `system` and the SystemMessage gone from
        // `messages` — the adapter must not rely on idempotence for this combination.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new io.amscotti.janus.core.model.SystemMessage("be concise"), new UserMessage("hi")),
                "be brief",
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
        ChatRequest decoded = codec.decodeRequest(codec.encodeRequest(request));
        assertEquals("be brief\n\nbe concise", decoded.system());
        assertEquals(List.of(new UserMessage("hi")), decoded.messages());
    }

    @Test
    void streamFalseIsOmittedFromTheWire() {
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
                null,
                null);
        assertFalse(codec.encodeRequest(request).contains("\"stream\""));
    }

    @Test
    void toolArgumentsSchemaIsParsedIntoParametersObject() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                List.of(new ToolDefinition("function", "get_weather", null, "{\"type\":\"object\"}")),
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
        // parameters must be an object, not the raw JSON string
        assertTrue(json.contains("\"parameters\":{\"type\":\"object\"}"), json);
        assertFalse(json.contains("\"parameters\":\"{\\\"type"), json);
    }

    @Test
    void extrasAreMergedTopLevelButMappedFieldsWinOnCollision() {
        // The reference's merge_extras keeps the gateway/mapped value on collision
        // (Map.merge(extras, base, fn _, _, gateway -> gateway end)) — the mapped
        // "model" must win over the extras copy.
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
                Map.of("custom", "pass-through", "model", "overridden"),
                null);
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"custom\":\"pass-through\""), json);
        assertTrue(json.contains("\"model\":\"model-1\""), json);
        assertFalse(json.contains("\"model\":\"overridden\""), json);
    }

    @Test
    void stopListWithNullElementsDecodesWithoutNpeAndRoundTrip() {
        // A client "stop":[null] is malformed, but it is wire input — the decode must
        // not fail with a raw NullPointerException escaping List.copyOf (the same
        // NPE-escape-on-wire-input class as the null-valued maps above). The element
        // pass-through-survives and re-encodes, so the upstream (not the proxy) rejects
        // it with a typed error.
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "stop": ["END", null]
                }
                """);
        assertEquals(Arrays.asList("END", null), decoded.stop());
        assertNull(decoded.stop().get(1));
        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"stop\":[\"END\",null]"), json);
    }

    @Test
    void nullValuedPayloadMapsDecodeWithoutNpeAndRoundTrip() {
        // Null-valued fields inside logit_bias/stream_options/response_format
        // are legitimate pass-through payloads (mirror of extras). The OpenAI wire decode
        // previously failed with a wrapped ValueInstantiationException; it now decodes and
        // re-encodes the value without a raw NullPointerException escaping.
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "logit_bias": {"50256": null},
                  "response_format": {"type": null},
                  "stream_options": {"include_usage": null}
                }
                """);
        assertTrue(decoded.logitBias().containsKey("50256"));
        assertNull(decoded.logitBias().get("50256"));
        assertNull(decoded.responseFormat().get("type"));
        assertNull(decoded.streamOptions().get("include_usage"));

        // the null-valued fields survive the encode without crashing
        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"logit_bias\":{\"50256\":null}"), json);
        assertTrue(json.contains("\"stream_options\":{\"include_usage\":null}"), json);
    }

    @Test
    void metaAndReservedPhaseTwoFieldsAreNeverEmitted() {
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
                Map.of("effort", "high"), // reasoning — effort-shaped maps emit reasoning_effort
                "disable", // cacheControl — still reserved (no oo home)
                null,
                Map.of("key_id", "k1", "attempt", 2));
        String json = codec.encodeRequest(request);
        assertFalse(json.contains("key_id"));
        assertFalse(json.contains("\"meta\""));
        // An effort-shaped canonical reasoning map IS
        // serialized on the oo wire — as the chat-wire reasoning_effort string.
        assertTrue(json.contains("\"reasoning_effort\":\"high\""), json);
        assertFalse(json.contains("cache_control"));
        assertFalse(json.contains("\"reasoning\""), "no literal reasoning object leaks: " + json);
    }

    @Test
    void encodeValidatesModelMessagesAndToolMessageIds() {
        ChatRequest blankModel = new ChatRequest(
                " ",
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
                null,
                null);
        OpenAiCodecException modelEx = assertThrows(OpenAiCodecException.class, () -> codec.encodeRequest(blankModel));
        assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, modelEx.type());
        assertTrue(modelEx.getMessage().contains("model"));

        ChatRequest emptyMessages = new ChatRequest(
                "model-1", List.of(), null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, false, null, null, null, null, null);
        OpenAiCodecException messagesEx =
                assertThrows(OpenAiCodecException.class, () -> codec.encodeRequest(emptyMessages));
        assertTrue(messagesEx.getMessage().contains("messages"));

        ChatRequest toolWithoutId = new ChatRequest(
                "model-1",
                List.of(new ToolMessage(null, "x")),
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
        OpenAiCodecException toolEx =
                assertThrows(OpenAiCodecException.class, () -> codec.encodeRequest(toolWithoutId));
        assertTrue(toolEx.getMessage().contains("tool_call_id"));
    }

    // ------------------------------------------------------------------ decode

    @Test
    void maxCompletionTokensDecodesAndEncodes() {
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "max_completion_tokens": 256
                }
                """);
        assertEquals(256, decoded.maxTokens());
        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"max_completion_tokens\":256"), json);
        assertFalse(json.contains("\"max_tokens\""), json);
    }

    @Test
    void maxTokensPreferredOverMaxCompletionTokensOnDecode() {
        // Dual-key body (clients mid-migration): Jackson applies properties in document
        // order (last wins). Pin that the legacy max_tokens alias is accepted and, when
        // it appears after the primary key, is the value that sticks.
        ChatRequest dual = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "max_completion_tokens": 100,
                  "max_tokens": 200
                }
                """);
        assertEquals(200, dual.maxTokens());

        ChatRequest legacyOnly = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "max_tokens": 512
                }
                """);
        assertEquals(512, legacyOnly.maxTokens());
    }

    @Test
    void decodeRequestMapsFullWireShapeToCanonical() {
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "deepseek-v4-flash",
                  "messages": [
                    {"role": "system", "content": "be brief"},
                    {"role": "user", "content": "what is the weather?"},
                    {"role": "assistant", "content": null, "tool_calls": [
                      {"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Paris\\"}"}}
                    ]},
                    {"role": "tool", "tool_call_id": "call_1", "content": "{\\"temp\\":18}"}
                  ],
                  "stream": true,
                  "temperature": 0.7,
                  "max_tokens": 512,
                  "stop": ["END"],
                  "tool_choice": "auto"
                }
                """);
        assertEquals("deepseek-v4-flash", decoded.model());
        assertEquals("be brief", decoded.system());
        assertEquals(
                List.of(
                        new UserMessage("what is the weather?"),
                        new AssistantMessage(
                                null,
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        new ToolMessage("call_1", "{\"temp\":18}")),
                decoded.messages());
        assertTrue(decoded.stream());
        assertEquals(0.7, decoded.temperature());
        assertEquals(512, decoded.maxTokens());
        assertEquals(List.of("END"), decoded.stop());
        assertEquals("auto", decoded.toolChoice());
        assertEquals(Map.of(), decoded.extras());
        assertEquals(Map.of(), decoded.meta());
    }

    @Test
    void unknownTopLevelFieldsGoToExtrasUnmodified() {
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "future_field": {"a": 1},
                  "another": null
                }
                """);
        Map<String, Object> extras = decoded.extras();
        assertEquals(Map.of("a", 1), extras.get("future_field"));
        assertTrue(extras.containsKey("another"));
        assertNull(extras.get("another"));
    }

    @Test
    void unknownMessageFieldsFoldIntoRequestExtrasButNameStaysOnTheMessage() {
        // The wire `name` is a legal per-message field with a canonical home
        // (UserMessage.name) — it must NOT fold into the request extras (the pre-fix
        // behavior re-emitted it as a bogus top-level request field strict OpenAI
        // upstreams reject). Unknown message fields still fold as before.
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi", "name": "alice", "custom_msg": 42}]
                }
                """);
        assertEquals(Map.of("custom_msg", 42), decoded.extras());
        assertEquals(List.of(new UserMessage("hi", "alice")), decoded.messages());
    }

    @Test
    void messageNameRoundTripsInsideTheMessageOnEncode() {
        // A named message must re-encode with "name" inside the message
        // object, never as a bogus top-level field.
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [
                    {"role": "user", "content": "hi", "name": "alice"},
                    {"role": "assistant", "content": "yo", "name": "bob", "tool_calls": [
                      {"id": "call_1", "type": "function", "function": {"name": "f", "arguments": "{}"}}
                    ]},
                    {"role": "tool", "content": "42", "tool_call_id": "call_1", "name": "tool0"}
                  ]
                }
                """);
        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"role\":\"user\",\"content\":\"hi\",\"name\":\"alice\""), json);
        assertTrue(json.contains("\"role\":\"assistant\",\"content\":\"yo\",\"name\":\"bob\""), json);
        // tool message: name sits between content and tool_call_id (OpenAiMessage component order)
        assertTrue(
                json.contains("\"role\":\"tool\",\"content\":\"42\",\"name\":\"tool0\",\"tool_call_id\":\"call_1\""),
                json);
        // the name never leaks to the top level of the payload
        assertFalse(json.matches("(?s).*\\}\"name\":\"alice\".*"), json);
        assertFalse(json.contains("\"custom_msg\""), json);
        // full decode → encode → decode round trip: the names survive
        ChatRequest roundTripped = codec.decodeRequest(json);
        assertEquals(decoded, roundTripped);
    }

    @Test
    void toolDescriptionMapsToCanonicalToolDefinitionDescription() {
        // descriptions live on ToolDefinition.description — the extras hack is deleted.
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "tools": [{"type": "function", "function": {"name": "f", "description": "does stuff", "parameters": {"type": "object"}}}]
                }
                """);
        assertFalse(decoded.extras().containsKey("description"));
        assertEquals(
                List.of(new ToolDefinition("function", "f", "does stuff", "{\"type\":\"object\"}")), decoded.tools());
    }

    @Test
    void reasoningObjectEffortIsPromotedLikeReasoningEffort() {
        // Clients (and OpenRouter) often send the Responses-shaped object
        // {"reasoning":{"effort":"none"}} on the chat face. That must not stay a
        // silent extras passthrough: encode should emit the chat-wire
        // reasoning_effort string so a reasoning model actually sees the knob.
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "reasoning": {"effort": "none"}
                }
                """);
        assertEquals(Map.of("effort", "none"), decoded.reasoning());
        assertFalse(decoded.extras().containsKey("reasoning"), "promoted out of extras: " + decoded.extras());
        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"reasoning_effort\":\"none\""), json);
        assertFalse(json.contains("\"reasoning\""), "object form must not leak on encode: " + json);
    }

    @Test
    void nonEffortShapedReasoningRidesTheExtrasPassThrough() {
        // A `reasoning` value that is NOT effort-shaped (e.g. {"enabled":true} or the
        // string "high") was never translated, so it must stay in extras and survive
        // the oo round trip verbatim — never be silently deleted by the effort lift.
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "reasoning": {"enabled": true}
                }
                """);
        assertNull(decoded.reasoning(), "no effort found — nothing to lift: " + decoded.reasoning());
        assertEquals(Map.of("enabled", true), decoded.extras().get("reasoning"), String.valueOf(decoded.extras()));
        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"reasoning\":{\"enabled\":true}"), json);
        assertFalse(json.contains("reasoning_effort"), "nothing fabricated: " + json);
        assertEquals(decoded, codec.decodeRequest(json), "oo round trip is lossless");

        ChatRequest stringSpelling = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "reasoning": "high"
                }
                """);
        assertNull(stringSpelling.reasoning());
        assertEquals("high", stringSpelling.extras().get("reasoning"), String.valueOf(stringSpelling.extras()));
        assertTrue(codec.encodeRequest(stringSpelling).contains("\"reasoning\":\"high\""));
    }

    @Test
    void reasoningEffortStringWinsOverReasoningObject() {
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "reasoning_effort": "low",
                  "reasoning": {"effort": "none"}
                }
                """);
        assertEquals(Map.of("effort", "low"), decoded.reasoning());
        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"reasoning_effort\":\"low\""), json);
    }

    @Test
    void reasoningEffortRoundTripsThroughTheCanonicalReasoningMap() {
        // The wire's reasoning_effort string maps to the canonical reasoning
        // map's "effort" entry — both directions. (The canonical slot was previously
        // never serialized on this leg (silently dropped).
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "reasoning_effort": "low"
                }
                """);
        assertEquals(Map.of("effort", "low"), decoded.reasoning());
        assertFalse(decoded.extras().containsKey("reasoning_effort"), "mapped key, never extras: ");
        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"reasoning_effort\":\"low\""), json);
    }

    @Test
    void anthropicShapedReasoningNeverEmitsABogusReasoningEffort() {
        // The ao decode stores raw thinking ({type, budget_tokens}) in the canonical
        // reasoning map; the oo encode must not fabricate reasoning_effort from it —
        // only the "effort" key is read, structurally.
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
                Map.of("type", "enabled", "budget_tokens", 4096),
                null,
                null,
                null);
        String json = codec.encodeRequest(request);
        assertFalse(json.contains("reasoning_effort"), json);
        assertFalse(json.contains("budget_tokens"), "the Anthropic shape has no oo home (documented drop): " + json);
    }

    @Test
    void hostedWebSearchOnTheOpenAiEgressIsATyped400() {
        // Chat completions cannot HOST tools — real OpenAI rejects
        // the bridge-derived web_search_options param ("Unknown parameter", live-
        // verified 2026-08). Emitting it would only become that upstream 400, so the
        // oo encode throws the face's own named invalid-request error instead
        // (translate-or-throw); the Anthropic leg serves web_search.
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
                List.of(new io.amscotti.janus.core.model.HostedToolDefinition.WebSearch(
                        "medium", Map.of("type", "approximate", "city", "Berlin"))),
                null,
                null);
        OpenAiCodecException e = org.junit.jupiter.api.Assertions.assertThrows(
                OpenAiCodecException.class, () -> codec.encodeRequest(request));
        assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, e.type());
        assertTrue(e.getMessage().contains("unsupported_hosted_tool: web_search"), e.getMessage());
        assertTrue(
                e.getMessage().contains("Anthropic-format upstreams"),
                "the error names where the tool IS served: " + e.getMessage());
    }

    @Test
    void requestsWithoutHostedToolsOmitWebSearchOptions() {
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
                null,
                null,
                null);
        assertFalse(codec.encodeRequest(request).contains("web_search_options"));
    }

    @Test
    void toolStrictRoundTripsThroughTheCanonicalSlot() {
        // OpenAI's structured-outputs `strict` flag maps
        // to a first-class ToolDefinition slot — nested in `function` on the wire, never
        // a top-level extras hack (tools is a mapped key; the merge is top-level only).
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "tools": [{"type": "function", "function": {"name": "f", "parameters": {"type": "object"}, "strict": true}}]
                }
                """);
        assertEquals(Boolean.TRUE, decoded.tools().getFirst().strict());
        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"strict\":true"), "strict re-emits nested in function: " + json);
        assertTrue(
                json.contains("\"function\":{\"name\":\"f\""),
                "strict rides inside the function object, not the tool: " + json);
    }

    @Test
    void absentToolStrictStaysOmittedOnTheWire() {
        // The canonical default is null = omit — existing behavior is byte-identical
        // for every request that never sets it (the compatibility-constructor default).
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                List.of(new ToolDefinition("function", "f", null, "{\"type\":\"object\"}")),
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
        assertFalse(codec.encodeRequest(request).contains("strict"));
    }

    @Test
    void blankOrMissingModelIsRejected() {
        OpenAiCodecException blank = assertThrows(OpenAiCodecException.class, () -> codec.decodeRequest("""
                {"model": "  ", "messages": [{"role": "user", "content": "hi"}]}
                """));
        assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, blank.type());
        assertTrue(blank.getMessage().contains("model"));

        OpenAiCodecException missing = assertThrows(OpenAiCodecException.class, () -> codec.decodeRequest("""
                {"messages": [{"role": "user", "content": "hi"}]}
                """));
        assertTrue(missing.getMessage().contains("model"));
    }

    @Test
    void emptyOrMissingMessagesAreRejected() {
        OpenAiCodecException empty = assertThrows(OpenAiCodecException.class, () -> codec.decodeRequest("""
                {"model": "model-1", "messages": []}
                """));
        assertTrue(empty.getMessage().contains("messages"));

        OpenAiCodecException missing = assertThrows(OpenAiCodecException.class, () -> codec.decodeRequest("""
                {"model": "model-1"}
                """));
        assertTrue(missing.getMessage().contains("messages"));
    }

    @Test
    void unknownRoleIsRejectedWithTypedException() {
        OpenAiCodecException ex = assertThrows(OpenAiCodecException.class, () -> codec.decodeRequest("""
                {"model": "model-1", "messages": [{"role": "bogus", "content": "hi"}]}
                """));
        assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, ex.type());
        assertTrue(ex.getMessage().contains("bogus"));
    }

    @Test
    void toolMessageWithoutToolCallIdIsRejected() {
        OpenAiCodecException ex = assertThrows(OpenAiCodecException.class, () -> codec.decodeRequest("""
                {"model": "model-1", "messages": [{"role": "tool", "content": "x"}]}
                """));
        assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, ex.type());
        assertTrue(ex.getMessage().contains("tool_call_id"));
    }

    @Test
    void multimodalUserContentRoundTripsTextAndImageUrl() {
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "what is in this image?"},
                      {"type": "image_url", "image_url": {"url": "https://example.com/x.png", "detail": "low"}}
                    ]
                  }]
                }
                """);
        assertEquals(1, decoded.messages().size());
        UserMessage user = (UserMessage) decoded.messages().getFirst();
        assertTrue(user.isMultimodal(), user::toString);
        assertEquals(2, user.parts().size());
        assertInstanceOf(TextContent.class, user.parts().get(0));
        assertEquals("what is in this image?", ((TextContent) user.parts().get(0)).text());
        assertInstanceOf(ImageUrlContent.class, user.parts().get(1));
        ImageUrlContent img = (ImageUrlContent) user.parts().get(1);
        assertEquals("https://example.com/x.png", img.url());
        assertEquals("low", img.detail());

        String reencoded = codec.encodeRequest(decoded);
        assertTrue(reencoded.contains("image_url"), reencoded);
        assertTrue(reencoded.contains("https://example.com/x.png"), reencoded);
        assertTrue(reencoded.contains("what is in this image?"), reencoded);
        ChatRequest again = codec.decodeRequest(reencoded);
        assertTrue(((UserMessage) again.messages().getFirst()).isMultimodal());
    }

    @Test
    void responseFormatJsonSchemaRoundTripsOnOpenAiLeg() {
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "return json"}],
                  "response_format": {
                    "type": "json_schema",
                    "json_schema": {
                      "name": "answer",
                      "schema": {"type": "object", "properties": {"ok": {"type": "boolean"}}}
                    }
                  }
                }
                """);
        assertNotNull(decoded.responseFormat());
        assertEquals("json_schema", decoded.responseFormat().get("type"));
        String reencoded = codec.encodeRequest(decoded);
        assertTrue(reencoded.contains("response_format"), reencoded);
        assertTrue(reencoded.contains("json_schema"), reencoded);
        ChatRequest again = codec.decodeRequest(reencoded);
        assertEquals("json_schema", again.responseFormat().get("type"));
    }

    @Test
    void nonUserArrayContentIsStillRejected() {
        OpenAiCodecException ex = assertThrows(OpenAiCodecException.class, () -> codec.decodeRequest("""
                {"model": "model-1", "messages": [{"role": "assistant", "content": [{"type": "text", "text": "hi"}]}]}
                """));
        assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, ex.type());
        assertTrue(ex.getMessage().contains("user, system, and developer"), ex.getMessage());
    }

    @Test
    void malformedJsonIsRejectedWithTypedException() {
        OpenAiCodecException ex = assertThrows(OpenAiCodecException.class, () -> codec.decodeRequest("{not json"));
        assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, ex.type());
    }

    @Test
    void streamDefaultsToFalse() {
        ChatRequest absent = codec.decodeRequest("""
                {"model": "model-1", "messages": [{"role": "user", "content": "hi"}]}
                """);
        assertFalse(absent.stream());

        ChatRequest explicitNull = codec.decodeRequest("""
                {"model": "model-1", "messages": [{"role": "user", "content": "hi"}], "stream": null}
                """);
        assertFalse(explicitNull.stream());
    }

    @Test
    void stopAcceptsStringOrArray() {
        ChatRequest asString = codec.decodeRequest("""
                {"model": "model-1", "messages": [{"role": "user", "content": "hi"}], "stop": "END"}
                """);
        assertEquals(List.of("END"), asString.stop());

        ChatRequest asArray = codec.decodeRequest("""
                {"model": "model-1", "messages": [{"role": "user", "content": "hi"}], "stop": ["A", "B"]}
                """);
        assertEquals(List.of("A", "B"), asArray.stop());
    }

    @Test
    void systemMessageContentIsExtractedFromAnyPosition() {
        ChatRequest decoded = codec.decodeRequest("""
                {"model": "model-1", "messages": [
                  {"role": "user", "content": "hi"},
                  {"role": "system", "content": "A"},
                  {"role": "system", "content": "B"}
                ]}
                """);
        assertEquals("A\n\nB", decoded.system());
        assertEquals(List.of(new UserMessage("hi")), decoded.messages());
    }

    @Test
    void systemOnlyRequestIsRejectedAtDecodeTime() {
        // A request whose messages are *only* system messages decodes to an
        // empty `messages` canonical that every provider encode then rejects — decode and
        // encode must agree on validity, so the system-only request is rejected here with
        // the same typed error instead of producing the empty-messages footgun.
        OpenAiCodecException ex = assertThrows(OpenAiCodecException.class, () -> codec.decodeRequest("""
                {"model": "model-1", "messages": [{"role": "system", "content": "be brief"}]}
                """));
        assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, ex.type());
        assertTrue(ex.getMessage().contains("messages"), ex.getMessage());
    }

    @Test
    void systemMessageNameStaysInsideTheMessageAndRoundTrips() {
        // "name" is schema-legal on OpenAI system messages ("the name of the
        // author of this message"). A named system message keeps its per-message canonical
        // home (SystemMessage.name) instead of folding into request extras, so re-encoding
        // emits "name" inside the message object — never as a bogus top-level field.
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [
                    {"role": "system", "content": "be brief", "name": "guardian"},
                    {"role": "user", "content": "hi"}
                  ]
                }
                """);
        // the named system message stays in messages (not flattened into ChatRequest.system)
        assertNull(decoded.system());
        assertEquals(
                List.of(new io.amscotti.janus.core.model.SystemMessage("be brief", "guardian"), new UserMessage("hi")),
                decoded.messages());

        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"role\":\"system\",\"content\":\"be brief\",\"name\":\"guardian\""), json);
        // name must not leak to the payload top level (strict OpenAI rejects unknown
        // top-level parameters): parse and assert the message holds it, the root doesn't.
        var root = JsonSupport.mapper().readTree(json);
        assertNull(root.get("name"), json);
        assertEquals("system", root.get("messages").get(0).get("role").asString());
        assertEquals("guardian", root.get("messages").get(0).get("name").asString());

        // round-trip idempotent: decode → encode → decode preserves the named system message
        assertEquals(decoded, codec.decodeRequest(json));
    }

    @Test
    void unnamedSystemMessagesStillFlattenIntoSystemField() {
        // Regression guard for the named-system change: unnamed system messages must
        // keep flattening into ChatRequest.system (the pinned pre-existing behavior).
        ChatRequest decoded = codec.decodeRequest("""
                {"model": "model-1", "messages": [
                  {"role": "system", "content": "be brief"},
                  {"role": "user", "content": "hi"}]}
                """);
        assertEquals("be brief", decoded.system());
        assertEquals(List.of(new UserMessage("hi")), decoded.messages());
    }

    @Test
    void anthropicOnlyDisableParallelToolUseExtrasKeyIsStrippedOnEncode() {
        // An Anthropic-sourced request carries the Anthropic-only
        // disable_parallel_tool_use flag in canonical extras; the OpenAI encode must not
        // leak it as a top-level field (OpenAI rejects unknown top-level parameters).
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
        assertFalse(json.contains(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL), json);
        assertFalse(json.contains("disable_parallel"), json);
        assertTrue(json.contains("\"tool_choice\":\"auto\""), json);
        // other extras still pass through top-level
        ChatRequest withOtherExtras = new ChatRequest(
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
                Map.of("custom", "pass-through", ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL, true),
                null);
        String json2 = codec.encodeRequest(withOtherExtras);
        assertTrue(json2.contains("\"custom\":\"pass-through\""), json2);
        assertFalse(json2.contains(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL), json2);
    }

    @Test
    void functionLessToolEmitsNoFunctionMemberOnEncode() {
        // The tool-DEFINITION path must never emit the invalid OpenAI shape
        // "function":{} for a function-less/nameless tool (strict upstreams reject it with
        // "function contains empty value"), asymmetric with the fixed streaming path. A
        // wire tool without a function member decodes to a null FunctionCall and re-encodes
        // as {"type":"function"} — decode → encode stays idempotent.
        ChatRequest decoded = codec.decodeRequest("""
                {"model": "model-1", "messages": [{"role": "user", "content": "hi"}],
                 "tools": [{"type": "function"}]}
                """);
        assertEquals(List.of(new ToolDefinition("function", null, null, null)), decoded.tools());
        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"tools\":[{\"type\":\"function\"}]"), json);
        assertFalse(json.contains("\"function\":{"), json);

        // a hand-built canonical with all-null definition fields must behave identically
        ChatRequest handBuilt = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                List.of(new ToolDefinition("function", null, null, null)),
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
        String json2 = codec.encodeRequest(handBuilt);
        assertTrue(json2.contains("\"tools\":[{\"type\":\"function\"}]"), json2);
        assertFalse(json2.contains("\"function\":{"), json2);
    }

    @Test
    void extrasCannotOverrideAbsentOptionalMappedFields() {
        // The mapped-field-wins rule must protect fields the DTO omitted too —
        // an absent-optional mapped field like stream:false (omitted by NON_NULL) let an
        // extras entry flip it ("stream":true on a canonical the gateway says is
        // non-streaming, or an extras temperature on a null-temperature canonical).
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
                false, // stream: false — must not be flipped by extras
                null,
                null,
                null,
                Map.of("stream", true, "temperature", 0.5, "custom", "pass-through"),
                null);
        String json = codec.encodeRequest(request);
        assertFalse(json.contains("\"stream\""), json);
        assertFalse(json.contains("\"temperature\""), json);
        assertTrue(json.contains("\"custom\":\"pass-through\""), json);
    }

    private static ChatRequest fullRequest() {
        return new ChatRequest(
                "model-1",
                List.of(
                        new UserMessage("what is the weather in Paris?"),
                        new AssistantMessage(
                                null,
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        new ToolMessage("call_1", "{\"temp\":18}")),
                "be brief", // system
                List.of(new ToolDefinition(
                        "function",
                        "get_weather",
                        "current weather in a city",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")),
                "auto", // toolChoice — canonical form is OpenAI-idiomatic
                0.7, // temperature
                0.9, // topP
                50, // topK
                512, // maxTokens
                List.of("END"), // stop
                42L, // seed
                1, // n
                0.0, // frequencyPenalty
                0.1, // presencePenalty
                Map.of("50256", -100), // logitBias
                Map.of("type", "json_object"), // responseFormat
                true, // stream
                Map.of("include_usage", true), // streamOptions
                null, // reasoning
                null, // cacheControl
                extrasWithNull("custom", "pass-through", "null_field", null), // extras
                Map.of("key_id", "k1")); // meta
    }

    @Test
    void promptCacheBreakpointObjectRoundTripsOnGpt56AndIsDroppedOnOtherModels() {
        ChatRequest gpt = codec.decodeRequest("""
                {
                  "model": "gpt-5.6-luna",
                  "messages": [{"role":"user","content":[
                    {"type":"text","text":"hi","prompt_cache_breakpoint":{"mode":"explicit"}}
                  ]}]
                }
                """);
        assertEquals(Map.of("type", "ephemeral"), gpt.cacheControl());
        String gptJson = codec.encodeRequest(gpt);
        assertTrue(gptJson.contains("\"prompt_cache_breakpoint\""), gptJson);
        assertTrue(gptJson.contains("\"mode\":\"explicit\""), gptJson);
        assertFalse(gptJson.contains("cache_control"), gptJson);

        ChatRequest other = codec.decodeRequest("""
                {
                  "model": "deepseek-v4-flash",
                  "messages": [{"role":"user","content":[
                    {"type":"text","text":"hi","prompt_cache_breakpoint":{"mode":"explicit"}}
                  ]}]
                }
                """);
        String otherJson = codec.encodeRequest(other);
        assertFalse(otherJson.contains("prompt_cache_breakpoint"), otherJson);
        assertFalse(otherJson.contains("cache_control"), otherJson);
    }

    @Test
    void reasoningEffortXhighMaxUltraPassThroughOnOpenAiWire() {
        for (String effort : List.of("xhigh", "max", "ultra")) {
            ChatRequest decoded = codec.decodeRequest("""
                    {
                      "model": "grok-4.6",
                      "messages": [{"role":"user","content":"hi"}],
                      "reasoning_effort": "%s"
                    }
                    """.formatted(effort));
            assertEquals(Map.of("effort", effort), decoded.reasoning());
            String json = codec.encodeRequest(decoded);
            assertTrue(json.contains("\"reasoning_effort\":\"" + effort + "\""), json);
        }
    }

    @Test
    void reasoningContentExtrasAreDroppedAndChatTemplateKwargsPassThrough() {
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
                Map.of(
                        "thinking",
                        Map.of("type", "disabled"),
                        "reasoning_content",
                        "secret",
                        "chat_template_kwargs",
                        Map.of("enable_thinking", false)),
                Map.of());
        String json = codec.encodeRequest(request);
        assertTrue(json.contains("\"thinking\""), json);
        assertFalse(json.contains("reasoning_content"), json);
        assertTrue(json.contains("chat_template_kwargs"), json);
        assertTrue(json.contains("enable_thinking"), json);
    }

    @Test
    void developerRoleRoundTripsOnOpenAiWireAndFlattensToSystemOnAnthropic() {
        // OpenAI's "developer" role was previously unhandled (fromWire threw,
        // surfacing as a typed invalid_request_error on the OpenAI face). It now decodes
        // to a system-ish DeveloperMessage, re-encodes as "developer" on the OpenAI wire
        // (never flattened into ChatRequest.system), and merges into the top-level system
        // field on the Anthropic leg — LiteLLM's map_developer_role_to_system_role.
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [
                    {"role": "developer", "content": "be correct", "name": "author"},
                    {"role": "user", "content": "hi"}
                  ]
                }
                """);
        assertEquals(List.of(new DeveloperMessage("be correct", "author"), new UserMessage("hi")), decoded.messages());
        assertNull(decoded.system(), "developer content must NOT flatten into ChatRequest.system");

        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"role\":\"developer\""), json);
        assertTrue(json.contains("\"content\":\"be correct\""), json);
        assertTrue(json.contains("\"name\":\"author\""), json);
        assertFalse(json.contains("\"system\""), json);

        AnthropicMessageCodec anthropic = new AnthropicMessageCodec(JsonSupport.mapper());
        String anthropicJson = anthropic.encodeRequest(decoded);
        assertTrue(anthropicJson.contains("\"system\":\"be correct\""), anthropicJson);
        assertFalse(anthropicJson.contains("developer"), anthropicJson);
        assertTrue(anthropicJson.contains("\"role\":\"user\""), anthropicJson);
    }

    @Test
    void developerRoleWithoutNameRoundTrips() {
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [
                    {"role": "developer", "content": "be concise"},
                    {"role": "user", "content": "hi"}
                  ]
                }
                """);
        assertEquals(List.of(new DeveloperMessage("be concise"), new UserMessage("hi")), decoded.messages());
        String json = codec.encodeRequest(decoded);
        assertTrue(json.contains("\"role\":\"developer\""), json);
        assertFalse(json.contains("\"name\""), json);
    }

    @Test
    void toolDefinitionSchemaNeverLeaksAsToolCallArguments() {
        // Definitions (ChatRequest.tools) and invocations (assistant
        // tool_calls) are distinct types — a definition's schema string can only reach
        // the wire as tools[].function.parameters, and a call's real arguments only as
        // tool_calls[].function.arguments. A conflation regression would put the schema
        // string (or the call args) in the wrong slot.
        ChatRequest decoded = codec.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [
                    {"role": "user", "content": "hi"},
                    {"role": "assistant", "content": "checking", "tool_calls": [
                      {"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Paris\\"}"}}
                    ]}
                  ],
                  "tools": [{"type": "function", "function": {"name": "get_weather", "description": "weather", "parameters": {"type": "object"}}}]
                }
                """);
        assertEquals(
                List.of(new ToolDefinition("function", "get_weather", "weather", "{\"type\":\"object\"}")),
                decoded.tools());
        String json = codec.encodeRequest(decoded);
        // definition schema → object under tools[].function.parameters
        assertTrue(json.contains("\"parameters\":{\"type\":\"object\"}"), json);
        // invocation real arguments → string under tool_calls[].function.arguments
        assertTrue(json.contains("\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\""), json);
        // the schema never appears as a tool-call argument
        assertFalse(json.contains("arguments\":\"{\\\"type\\\":\\\"object\\\"}\""), json);
        // the call arguments never appear as a definition parameter schema (parameters is
        // always an object on the wire, never a raw JSON string)
        assertFalse(json.contains("parameters\":\"{\\\"city"), json);
    }

    @Test
    void encodeUserMessageWithNullOrBlankContentIsRejected() {
        // Mirrors the Anthropic-leg check — a content-less UserMessage(null)
        // (constructible via decode of a wire message with "content":null) used to encode
        // to {"role":"user"} with content omitted and fail upstream with a generic 400.
        // Both faces now reject it with the codec's typed invalid_request_error.
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
            OpenAiCodecException e = assertThrows(OpenAiCodecException.class, () -> codec.encodeRequest(request));
            assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, e.type());
            assertTrue(e.getMessage().contains("content"), e.getMessage());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
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
