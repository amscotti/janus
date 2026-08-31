package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.core.model.Usage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SSE delta chunk direction of {@link OpenAiMessageCodec} : nullable
 * finish_reason, tool-call deltas (position-based index), include_usage chunks, and the
 * encode defaults mirroring the reference face.
 */
class OpenAiChunkCodecTest {

    private final OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());

    // ------------------------------------------------------------------ decode

    @Test
    void decodeChunkMapsDeltaChunkToCanonical() {
        StreamChunk decoded = codec.decodeChunk("""
                {"id": "chatcmpl-123", "object": "chat.completion.chunk", "created": 1700000000, "model": "deepseek-v4-flash",
                 "choices": [{"index": 0, "delta": {"role": "assistant", "content": "Hel"}, "finish_reason": null}]}
                """);
        assertEquals("chatcmpl-123", decoded.id());
        assertEquals("chat.completion.chunk", decoded.object());
        assertEquals(1700000000L, decoded.created());
        assertEquals("deepseek-v4-flash", decoded.model());
        assertEquals(List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "Hel", null), null)), decoded.choices());
        assertNull(decoded.usage());
        assertEquals(Map.of(), decoded.extras());
    }

    @Test
    void decodeToolCallDeltaPreservesIndexAndMapsFunction() {
        // The wire index round-trips through ToolCall.index (was dropped, corrupting
        // multi-tool-call streams on decode→re-encode).
        StreamChunk decoded = codec.decodeChunk("""
                {"id": "chatcmpl-123", "object": "chat.completion.chunk", "created": 1, "model": "m",
                 "choices": [{"index": 0, "delta": {"tool_calls": [
                   {"index": 0, "id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": ""}},
                   {"index": 1, "type": "function", "function": {"arguments": "{\\"city\\":\\"Paris\\"}"}}
                 ]}, "finish_reason": null}]}
                """);
        assertEquals(
                List.of(
                        new ToolCall("call_1", "function", new FunctionCall("get_weather", ""), 0),
                        new ToolCall(null, "function", new FunctionCall(null, "{\"city\":\"Paris\"}"), 1)),
                decoded.choices().get(0).delta().toolCalls());
    }

    @Test
    void multiIndexToolCallDeltaSurvivesDecodeReencodeWithoutRenumbering() {
        // Guard: a fragment belonging to call index 1 must not come back as index 0
        // after a passthrough decode → re-encode cycle.
        String upstream = """
                {"id": "i", "object": "chat.completion.chunk", "created": 1, "model": "m",
                 "choices": [{"index": 0, "delta": {"tool_calls": [
                   {"index": 1, "type": "function", "function": {"arguments": "{\\"b\\":2}"}}
                 ]}, "finish_reason": null}]}
                """;
        StreamChunk decoded = codec.decodeChunk(upstream);
        assertEquals(1, decoded.choices().get(0).delta().toolCalls().get(0).index());
        String reencoded = codec.encodeChunk(decoded);
        // the tool-call fragment must keep wire index 1, not be renumbered to 0
        assertTrue(reencoded.contains("\"tool_calls\":[{\"index\":1"), reencoded);
        assertFalse(reencoded.contains("\"tool_calls\":[{\"index\":0"), reencoded);
    }

    @Test
    void decodeUsageChunkMapsIncludeUsageTerminalChunk() {
        StreamChunk decoded = codec.decodeChunk("""
                {"id": "chatcmpl-123", "object": "chat.completion.chunk", "created": 1, "model": "m",
                 "choices": [], "usage": {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3}}
                """);
        assertEquals(List.of(), decoded.choices());
        assertEquals(new Usage(1, 2, 3), decoded.usage());
    }

    @Test
    void unknownDeltaRoleIsRejectedAsApiError() {
        OpenAiCodecException ex = assertThrows(OpenAiCodecException.class, () -> codec.decodeChunk("""
                {"id": "i", "object": "chat.completion.chunk", "created": 1, "model": "m",
                 "choices": [{"index": 0, "delta": {"role": "bogus", "content": "x"}, "finish_reason": null}]}
                """));
        assertEquals(OpenAiCodecException.TYPE_API_ERROR, ex.type());
        assertTrue(ex.getMessage().contains("bogus"));
    }

    @Test
    void unknownTopLevelChunkFieldsGoToExtras() {
        StreamChunk decoded = codec.decodeChunk("""
                {"id": "i", "object": "chat.completion.chunk", "created": 1, "model": "m",
                 "choices": [], "server_sent_at": 123, "delta_extra": {"x": 1}}
                """);
        assertEquals(Map.of("server_sent_at", 123, "delta_extra", Map.of("x", 1)), decoded.extras());
    }

    @Test
    void deepSeekReasoningContentStaysInsideTheDeltaOnDecodeAndReencode() {
        // DeepSeek streaming sends delta.reasoning_content — it must keep its
        // position inside the delta (Delta.reasoning), so a DeepSeek SDK client consuming
        // Janus's stream sees the reasoning text and a strict OpenAI upstream never
        // receives a bogus top-level reasoning_content field.
        String upstream = """
                {"id": "chatcmpl-123", "object": "chat.completion.chunk", "created": 1, "model": "deepseek-v4-flash",
                 "choices": [{"index": 0, "delta": {"role": "assistant", "reasoning_content": "think", "content": "hi"}, "finish_reason": null}]}
                """;
        StreamChunk decoded = codec.decodeChunk(upstream);
        assertEquals(
                Map.of("reasoning_content", "think"),
                decoded.choices().get(0).delta().reasoning());
        // the delta-level unknown must NOT be hoisted to the chunk top-level extras
        assertEquals(Map.of(), decoded.extras());

        String reencoded = codec.encodeChunk(decoded);
        assertTrue(
                reencoded.contains(
                        "\"delta\":{\"role\":\"assistant\",\"content\":\"hi\",\"reasoning_content\":\"think\"}"),
                reencoded);
        // reasoning_content must not re-emerge as a top-level chunk field
        assertFalse(reencoded.matches("(?s).*\\}\"reasoning_content\":\"think\".*"), reencoded);
    }

    @Test
    void toolCallDeltaWithNullFunctionEmitsNoFunctionMember() {
        // A tool-call fragment whose function has neither name nor arguments
        // must omit the function member instead of emitting the invalid OpenAI shape
        // "function":{}.
        StreamChunk chunk = new StreamChunk(
                "i",
                "chat.completion.chunk",
                1L,
                "m",
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null,
                                null,
                                List.of(
                                        new ToolCall("call_1", "function", null, 0),
                                        new ToolCall("call_2", "function", new FunctionCall(null, null), 1))),
                        null)),
                null,
                Map.of());
        String json = codec.encodeChunk(chunk);
        assertTrue(json.contains("\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\"}"), json);
        assertFalse(json.contains("\"function\":{"), json);
        assertFalse(json.contains("\"function\":"), json);
    }

    @Test
    void encodeChunkRejectsNullChoicesWithTypedApiError() {
        // encodeChunk must not iterate canonical.choices without a null guard (unlike
        // the response path) — a hand-built canonical with null choices escaped the
        // codec's typed-error contract as a raw NPE. Now a typed api_error; an empty
        // choices list stays valid (the usage-only terminal chunk).
        OpenAiCodecException ex = assertThrows(
                OpenAiCodecException.class,
                () -> codec.encodeChunk(new StreamChunk("i", "chat.completion.chunk", 1L, "m", null, null, Map.of())));
        assertEquals(OpenAiCodecException.TYPE_API_ERROR, ex.type());
        assertTrue(ex.getMessage().contains("choices"), ex.getMessage());

        StreamChunk emptyChoices = new StreamChunk("i", "chat.completion.chunk", 1L, "m", List.of(), null, Map.of());
        assertTrue(codec.encodeChunk(emptyChoices).contains("\"choices\":[]"), codec.encodeChunk(emptyChoices));
    }

    @Test
    void deltaRoleToolOrSystemOrDeveloperIsRejectedAsApiError() {
        // "tool"/"system" are known ChatRole values but invalid in a
        // chat.completion.chunk delta — the decode previously accepted them and the
        // re-encode emitted a provably invalid delta role verbatim. Reject at decode
        // (consistent with the unknown-role rejection). "developer" is likewise
        // system-ish and never legal in a streaming delta — kept out of the whitelist.
        for (String role : new String[] {"tool", "system", "developer"}) {
            OpenAiCodecException ex =
                    assertThrows(OpenAiCodecException.class, () -> codec.decodeChunk("""
                            {"id": "i", "object": "chat.completion.chunk", "created": 1, "model": "m",
                             "choices": [{"index": 0, "delta": {"role": "%s", "content": "x"}, "finish_reason": null}]}
                            """.formatted(role)));
            assertEquals(OpenAiCodecException.TYPE_API_ERROR, ex.type(), role);
            assertTrue(ex.getMessage().contains("delta role"), ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ encode

    @Test
    void encodeChunkProducesWireShape() {
        StreamChunk chunk = new StreamChunk(
                "chatcmpl-123",
                "chat.completion.chunk",
                1700000000L,
                "model-1",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "Hel", null), ChatResponse.STOP_REASON_STOP)),
                new Usage(1, 2, 3),
                Map.of("custom", "x"));
        String json = codec.encodeChunk(chunk);
        assertTrue(json.contains("\"id\":\"chatcmpl-123\""), json);
        assertTrue(json.contains("\"object\":\"chat.completion.chunk\""), json);
        assertTrue(json.contains("\"created\":1700000000"), json);
        assertTrue(json.contains("\"model\":\"model-1\""), json);
        assertTrue(json.contains("\"choices\":[{\"index\":0"), json);
        assertTrue(json.contains("\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"}"), json);
        assertTrue(json.contains("\"finish_reason\":\"stop\""), json);
        assertTrue(json.contains("\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}"), json);
        assertTrue(json.contains("\"custom\":\"x\""), json);
    }

    @Test
    void encodeChunkDefaultsIdObjectAndModel() {
        StreamChunk chunk = new StreamChunk(null, null, 1L, null, List.of(), null, Map.of());
        String json = codec.encodeChunk(chunk);
        assertTrue(json.contains("\"id\":\"chatcmpl-janus\""), json);
        assertTrue(json.contains("\"object\":\"chat.completion.chunk\""), json);
        assertTrue(json.contains("\"model\":\"unknown\""), json);
    }

    @Test
    void encodeChunkNormalizesObjectForAnthropicDerivedCanonicalChunks() {
        // D2 (blessed fix): an Anthropic-derived canonical chunk carries object
        // "message" (the message_start wire type) — re-emitted verbatim on the OpenAI
        // face, the pinned SDK rejects it (Literal["chat.completion.chunk"]). The
        // OpenAI chunk encode path must emit the OpenAI-conformant constant.
        StreamChunk anthropicShaped = new StreamChunk(
                "msg_9d8c7b6a5f4e3d2c1b0a9f8e7d6c5b4a",
                "message",
                0L,
                "claude-3-5-sonnet",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "The", null), null)),
                null,
                Map.of());
        String json = codec.encodeChunk(anthropicShaped);
        assertTrue(json.contains("\"object\":\"chat.completion.chunk\""), json);
        assertFalse(json.contains("\"object\":\"message\""), json);
        assertTrue(json.contains("\"created\":0"), json);
    }

    @Test
    void encodeToolCallDeltaSynthesizesIndexAndDefaultsEmptyArguments() {
        StreamChunk chunk = new StreamChunk(
                "i",
                "chat.completion.chunk",
                1L,
                "m",
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null,
                                null,
                                List.of(new ToolCall("call_1", "function", new FunctionCall("get_weather", null)))),
                        null)),
                null,
                Map.of());
        String json = codec.encodeChunk(chunk);
        assertTrue(
                json.contains(
                        "\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}"),
                json);
    }

    @Test
    void encodeChunkDefaultsTypeToFunction() {
        StreamChunk chunk = new StreamChunk(
                "i",
                "chat.completion.chunk",
                1L,
                "m",
                List.of(new ChunkChoice(
                        0,
                        new Delta(null, null, List.of(new ToolCall("call_1", null, new FunctionCall("f", null)))),
                        null)),
                null,
                Map.of());
        assertTrue(codec.encodeChunk(chunk).contains("\"type\":\"function\""));
    }

    @Test
    void nullableFinishReasonIsOmittedOnEncode() {
        StreamChunk chunk = new StreamChunk(
                "i",
                "chat.completion.chunk",
                1L,
                "m",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "x", null), null)),
                null,
                Map.of());
        assertFalse(codec.encodeChunk(chunk).contains("finish_reason"));
    }
}
