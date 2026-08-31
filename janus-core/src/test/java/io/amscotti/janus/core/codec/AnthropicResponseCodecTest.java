package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.HostedToolCall;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.core.model.ToolMessage;
import io.amscotti.janus.core.model.Usage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Response direction of {@link AnthropicMessageCodec} ( step 4 + tool response
 * translation): text-block content ↔ assistant message, stop-reason table (shared,
 * unknown-verbatim), usage mapping incl. cache tokens, extras round-trip, and
 * {@code tool_use} blocks ↔ assistant {@code ToolCall}s.
 */
class AnthropicResponseCodecTest {

    private final AnthropicMessageCodec codec = new AnthropicMessageCodec(JsonSupport.mapper());

    // ------------------------------------------------------------------ encode

    @Test
    void extrasNeverReintroduceAnAbsentMappedField() {
        // A response
        // canonical whose extras carry an Anthropic-mapped key while the mapped member
        // is absent must not re-emit that key — the codec's own mapping decision wins
        // (stop_sequence is never emitted by encode, so an extras entry must not add it).
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChatChoice(0, new AssistantMessage("Hello", null), "stop")),
                new Usage(10, 5, 15),
                null,
                Map.of("stop_sequence", "END", "custom", "x"),
                null);
        String json = codec.encodeResponse(response);
        assertFalse(json.contains("stop_sequence"), json);
        assertTrue(json.contains("\"custom\":\"x\""), json);
    }

    @Test
    void encodeExactWireShape() {
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChatChoice(0, new AssistantMessage("Hello", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                null);
        assertEquals(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}],\"stop_reason\":\"end_turn\","
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}",
                codec.encodeResponse(response));
    }

    @Test
    void encodeStopReasonReverseTable() {
        for (String[] pair : new String[][] {
            {ChatResponse.STOP_REASON_STOP, "end_turn"},
            {ChatResponse.STOP_REASON_LENGTH, "max_tokens"},
            {ChatResponse.STOP_REASON_TOOL_CALLS, "tool_use"},
            {ChatResponse.STOP_REASON_CONTENT_FILTER, "content_filter"},
            {ChatResponse.STOP_REASON_ERROR, "end_turn"},
            {"custom_reason", "custom_reason"}, // unknown passes through verbatim (tolerant)
        }) {
            ChatResponse response = new ChatResponse(
                    "msg_1",
                    "message",
                    0L,
                    "claude-3",
                    List.of(new ChatChoice(0, new AssistantMessage("x", null), null)),
                    null,
                    pair[0],
                    Map.of(),
                    null);
            String json = codec.encodeResponse(response);
            assertTrue(json.contains("\"stop_reason\":\"" + pair[1] + "\""), json);
        }
    }

    @Test
    void encodeNullUsageAndStopReasonAreOmitted() {
        // The per-choice finish reason is the canonical source (response-level is
        // the fallback), so both spellings must be null for the wire to omit stop_reason.
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChatChoice(0, new AssistantMessage("x", null), null)),
                null,
                null,
                Map.of(),
                null);
        String json = codec.encodeResponse(response);
        assertFalse(json.contains("\"usage\""), json);
        assertFalse(json.contains("\"stop_reason\""), json);
        assertFalse(json.contains("\"stop_sequence\""), json);
    }

    @Test
    void encodeAssistantToolCallsToToolUseBlocksAndStopReason() {
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChatChoice(
                        0,
                        new AssistantMessage(
                                "checking",
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        null)),
                new Usage(1, 1, 2, 0L, 0L),
                ChatResponse.STOP_REASON_TOOL_CALLS,
                Map.of(),
                null);
        String json = codec.encodeResponse(response);
        // assistant toolCalls → tool_use blocks; STOP_REASON_TOOL_CALLS → stop_reason "tool_use"
        assertTrue(
                json.contains(
                        "\"content\":[{\"type\":\"text\",\"text\":\"checking\"},"
                                + "{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"Paris\"}}]"),
                json);
        assertTrue(json.contains("\"stop_reason\":\"tool_use\""), json);
    }

    @Test
    void encodeToolCallsOnlyResponseEmitsToolUseBlocksWithoutText() {
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChatChoice(
                        0,
                        new AssistantMessage(
                                null,
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        null)),
                null,
                ChatResponse.STOP_REASON_TOOL_CALLS,
                Map.of(),
                null);
        String json = codec.encodeResponse(response);
        assertTrue(
                json.contains(
                        "\"content\":[{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"Paris\"}}]"),
                json);
        assertFalse(json.contains("\"type\":\"text\""), json);
    }

    @Test
    void encodeRejectsToolMessageInChoice() {
        // Responses carry only assistant content — a tool message in a response choice has
        // no Anthropic home (rejection stays; no longer names ).
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChatChoice(0, new ToolMessage("call_1", "ok"), "stop")),
                null,
                null,
                Map.of(),
                null);
        AnthropicCodecException e = assertThrows(AnthropicCodecException.class, () -> codec.encodeResponse(response));
        assertFalse(e.getMessage().contains("unused-marker"), e.getMessage());
        assertEquals(AnthropicCodecException.TYPE_API_ERROR, e.type());
    }

    // ------------------------------------------------------------------ decode

    @Test
    void encodeResponseReEmitsHostedWebSearchCalls() throws Exception {
        // hostedToolCalls are response-level output: they must survive the encode as
        // server_tool_use blocks (placed ahead of the assistant text), not be dropped.
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChatChoice(0, new AssistantMessage("Sunny", null), "stop")),
                new Usage(10, 5, 15),
                null,
                List.of(new HostedToolCall.WebSearchCall("berlin weather")),
                Map.of(),
                null);
        String json = codec.encodeResponse(response);
        assertTrue(json.contains("\"type\":\"server_tool_use\""), json);
        assertTrue(json.contains("\"name\":\"web_search\""), json);
        assertTrue(json.contains("\"query\":\"berlin weather\""), json);
        assertTrue(json.indexOf("server_tool_use") < json.indexOf("\"text\":\"Sunny\""), json);
    }

    @Test
    void webSearchServerToolUseBlocksMapToHostedCalls() throws Exception {
        // server_tool_use (web_search) becomes a response-level hosted call; the
        // text still rides the choice; web_search_tool_result blocks keep the unknown
        // tolerance (dropped) — the query is what the Responses face needs.
        ChatResponse response = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":["
                        + "{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\","
                        + "\"input\":{\"query\":\"berlin weather\"}},"
                        + "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srv_1\",\"content\":[]},"
                        + "{\"type\":\"text\",\"text\":\"Sunny\"}],"
                        + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}");
        assertEquals("Sunny", ((AssistantMessage) response.choices().getFirst().message()).content());
        assertEquals(List.of(new HostedToolCall.WebSearchCall("berlin weather")), response.hostedToolCalls());
    }

    @Test
    void decodeJoinsTextBlocksAndMapsStopReasonUsage() throws Exception {
        ChatResponse response = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"Hel\"},{\"type\":\"text\",\"text\":\"lo\"}],"
                        + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                        + "\"cache_creation_input_tokens\":2,\"cache_read_input_tokens\":3}}");
        assertEquals(
                new ChatResponse(
                        "msg_1",
                        "message",
                        0L,
                        "claude-3",
                        List.of(new ChatChoice(0, new AssistantMessage("Hello", null), "stop")),
                        // cache tokens map to the canonical fields; total is the FULL
                        // input (regular + cache) + output — Anthropic's input_tokens
                        // excludes the additive cache counts
                        new Usage(10, 5, 20, 2L, 3L),
                        ChatResponse.STOP_REASON_STOP,
                        Map.of(),
                        null),
                response);
    }

    @Test
    void decodeDropsThinkingBlocks() throws Exception {
        ChatResponse response = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\",\"signature\":\"s\"},"
                        + "{\"type\":\"text\",\"text\":\"a\"}]}");
        assertEquals(List.of(new ChatChoice(0, new AssistantMessage("a", null), null)), response.choices());
    }

    @Test
    void decodeDropsUnknownAndImageBlocksLikeTheStreamingPath() throws Exception {
        // The streaming path tolerates and drops unknown content
        // blocks (server_tool_use, web_search_tool_result, fallback — Anthropic's
        // versioning contract) and image blocks, but the non-streaming response decode
        // used to throw TYPE_API_ERROR (a 500) on the same payload. The two paths must
        // agree: a payload that streams must also decode. The surrounding text survives;
        // the server_tool_use and image blocks are dropped.
        ChatResponse response = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"server_tool_use\",\"id\":\"toolu_1\",\"name\":\"web_search\","
                        + "\"input\":{\"query\":\"x\"}},"
                        + "{\"type\":\"text\",\"text\":\"a\"},"
                        + "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"x\"}}],"
                        + "\"stop_reason\":\"end_turn\"}");
        assertEquals(List.of(new ChatChoice(0, new AssistantMessage("a", null), "stop")), response.choices());
    }

    @Test
    void decodeOfAllUnknownOrImageBlocksYieldsSingleEmptyChoice() throws Exception {
        // Mirror of the thinking-only case: a response whose content
        // is entirely dropped blocks (server_tool_use + image) must still yield exactly one
        // canonical choice with empty content — zero choices would be a broken 200 for
        // OpenAI-face clients (an empty choices array).
        ChatResponse response = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"server_tool_use\",\"id\":\"toolu_1\",\"name\":\"web_search\","
                        + "\"input\":{\"query\":\"x\"}},"
                        + "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"x\"}}],"
                        + "\"stop_reason\":\"end_turn\"}");
        assertEquals(List.of(new ChatChoice(0, new AssistantMessage("", null), "stop")), response.choices());
    }

    @Test
    void decodeToolResultBlockInResponseIsStillRejected() {
        // Only the tolerable-to-drop blocks are dropped. A
        // tool_result block is request-side (user-role) — it has no place in a response, so
        // it stays a hard error (genuinely malformed, never produced by a real upstream).
        AnthropicCodecException e = assertThrows(
                AnthropicCodecException.class,
                () -> codec.decodeResponse(
                        "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                                + "\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":\"ok\"}]}"));
        assertEquals(AnthropicCodecException.TYPE_API_ERROR, e.type());
        assertTrue(e.getMessage().contains("tool_result"), e.getMessage());
    }

    @Test
    void decodeToolUseWithNullOrEmptyInputProducesEmptyArguments() throws Exception {
        // A tool_use block with input:null (non-conformant but not
        // impossible from a sloppy upstream) used to decode to the 4-char JSON-literal
        // string "null" — JSON-poisoned arguments that re-encode as arguments:"null". The
        // streaming path treats null/empty input as "" (isEmptyInput); the non-streaming
        // response path must agree. Empty-object input behaves the same way.
        ChatResponse nullInput = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"f\",\"input\":null}]}");
        assertEquals(
                List.of(new ChatChoice(
                        0,
                        new AssistantMessage(null, List.of(new ToolCall("t", "function", new FunctionCall("f", "")))),
                        null)),
                nullInput.choices());

        ChatResponse emptyObjectInput = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"f\",\"input\":{}}]}");
        assertEquals(
                List.of(new ChatChoice(
                        0,
                        new AssistantMessage(null, List.of(new ToolCall("t", "function", new FunctionCall("f", "")))),
                        null)),
                emptyObjectInput.choices());
    }

    @Test
    void decodeToolUseWithNullInputDoesNotPoisonReencodedArguments() throws Exception {
        // Round-trip view: the empty-arguments convention means a
        // null-input tool_use decodes and re-encodes as "input":{} (toToolUseBlock's
        // Map.of encode convention) — never as the string "null" on the Anthropic wire
        // or as arguments:"null" on the OpenAI face.
        ChatResponse decoded = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"f\",\"input\":null}]}");
        String json = codec.encodeResponse(decoded);
        assertTrue(json.contains("\"input\":{}"), json);
        assertFalse(json.contains("\"input\":\"null\""), json);
    }

    @Test
    void decodeThinkingOnlyResponseToSingleEmptyChoice() throws Exception {
        // A response whose content is entirely thinking blocks (all dropped) must
        // still yield exactly one canonical choice with empty content — zero choices would
        // be a broken 200 for OpenAI-face clients (an empty choices array), even though the
        // upstream is billed for real output tokens.
        ChatResponse response = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"reasoning\",\"signature\":\"s\"}],"
                        + "\"stop_reason\":\"end_turn\"}");
        assertEquals(
                List.of(new ChatChoice(0, new AssistantMessage("", null), "stop")),
                response.choices(),
                "one choice with empty content, never zero choices");
        // Re-encode to the OpenAI face: a valid choices:[...] with empty content.
        OpenAiMessageCodec openAi = new OpenAiMessageCodec(JsonSupport.mapper());
        String openAiJson = openAi.encodeResponse(response);
        assertTrue(
                openAiJson.contains("\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\"}"),
                openAiJson);
    }

    @Test
    void decodeEmptyContentResponseToSingleEmptyChoice() throws Exception {
        ChatResponse response = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[],\"stop_reason\":\"end_turn\"}");
        assertEquals(List.of(new ChatChoice(0, new AssistantMessage("", null), "stop")), response.choices());
    }

    @Test
    void decodeToolUseBlocksToAssistantToolCalls() throws Exception {
        ChatResponse response = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"a\"},"
                        + "{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"get_weather\",\"input\":{\"city\":\"Paris\"}}],"
                        + "\"stop_reason\":\"tool_use\"}");
        assertEquals(
                new ChatResponse(
                        "msg_1",
                        "message",
                        0L,
                        "claude-3",
                        List.of(new ChatChoice(
                                0,
                                new AssistantMessage(
                                        "a",
                                        List.of(new ToolCall(
                                                "t",
                                                "function",
                                                new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                                ChatResponse.STOP_REASON_TOOL_CALLS)),
                        null,
                        ChatResponse.STOP_REASON_TOOL_CALLS,
                        Map.of(),
                        null),
                response);
    }

    @Test
    void encodeRoundTripsToolCallsWithCacheUsage() {
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChatChoice(
                        0,
                        new AssistantMessage(
                                "checking",
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        ChatResponse.STOP_REASON_TOOL_CALLS)),
                new Usage(10, 5, 20, 2L, 3L),
                ChatResponse.STOP_REASON_TOOL_CALLS,
                Map.of(),
                null);
        ChatResponse decoded = codec.decodeResponse(codec.encodeResponse(response));
        assertEquals(response, decoded);
    }

    @Test
    void decodeUnknownStopReasonPassesThroughVerbatim() throws Exception {
        ChatResponse response = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"a\"}],\"stop_reason\":\"custom_reason\"}");
        assertEquals("custom_reason", response.stopReason());
        assertEquals("custom_reason", response.choices().get(0).finishReason());
    }

    @Test
    void decodeFoldsUnknownTopLevelFieldsIntoExtras() throws Exception {
        ChatResponse response = codec.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"a\"}],\"custom\":\"x\"}");
        assertEquals(Map.of("custom", "x"), response.extras());
    }

    @Test
    void encodeDefaultsIdModelAndObjectWhenAbsent() throws Exception {
        ChatResponse response = codec.decodeResponse("{\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}");
        assertEquals("unknown", response.id());
        assertEquals("message", response.object());
        assertEquals("unknown", response.model());
        assertEquals(0L, response.created());
        assertEquals(List.of(new ChatChoice(0, new AssistantMessage("a", null), null)), response.choices());
    }

    @Test
    void encodeConcatenatesAllChoicesIntoOneContentArray() {
        // Documented non-idempotence (4)'s encode side: Anthropic has a single content
        // array, so a multi-choice canonical response encodes every choice's content into
        // one concatenated array — the decode side collapses choices (round-trip tests pin
        // that); this pins the encode-side concatenation the corpus otherwise leaves untested.
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(
                        new ChatChoice(0, new AssistantMessage("first", null), null),
                        new ChatChoice(1, new AssistantMessage("second", null), null)),
                null,
                null,
                Map.of(),
                null);
        String json = codec.encodeResponse(response);
        // two choices → one content array with two text blocks (Anthropic has a single
        // content array; every choice's content concatenates into it)
        assertEquals(2, JsonSupport.mapper().readTree(json).get("content").size());
        assertTrue(
                json.contains(
                        "\"content\":[{\"type\":\"text\",\"text\":\"first\"},{\"type\":\"text\",\"text\":\"second\"}]"),
                json);
    }
}
