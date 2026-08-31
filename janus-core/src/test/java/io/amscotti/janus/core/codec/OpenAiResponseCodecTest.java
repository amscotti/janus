package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.core.model.Usage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Response direction of {@link OpenAiMessageCodec} : finish_reason ↔
 * stopReason normalization (unknown values verbatim), usage mapping, extras round-trip.
 */
class OpenAiResponseCodecTest {

    private final OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());

    // ------------------------------------------------------------------ decode

    @Test
    void decodeResponseMapsFullWireShapeToCanonical() {
        ChatResponse decoded = codec.decodeResponse("""
                {
                  "id": "chatcmpl-123",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "deepseek-v4-flash",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "Hello!", "tool_calls": [
                      {"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Paris\\"}"}}
                    ]},
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
                  "system_fingerprint": "fp_abc"
                }
                """);
        assertEquals("chatcmpl-123", decoded.id());
        assertEquals("chat.completion", decoded.object());
        assertEquals(1700000000L, decoded.created());
        assertEquals("deepseek-v4-flash", decoded.model());
        assertEquals(
                List.of(new ChatChoice(
                        0,
                        new AssistantMessage(
                                "Hello!",
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        "tool_calls")),
                decoded.choices());
        assertEquals(ChatResponse.STOP_REASON_TOOL_CALLS, decoded.stopReason());
        assertEquals(new Usage(10, 5, 15), decoded.usage());
        assertEquals(Map.of("system_fingerprint", "fp_abc"), decoded.extras());
        assertEquals(Map.of(), decoded.meta());
    }

    @Test
    void finishReasonNormalizationCoversKnownAndUnknownValues() {
        ChatResponse toolCalls = codec.decodeResponse(responseWithFinishReason("tool_calls"));
        assertEquals(ChatResponse.STOP_REASON_TOOL_CALLS, toolCalls.stopReason());
        assertEquals("tool_calls", toolCalls.choices().get(0).finishReason());

        // OpenAI's legacy "function_call" maps to tool_calls (StopReason table)
        ChatResponse functionCall = codec.decodeResponse(responseWithFinishReason("function_call"));
        assertEquals(ChatResponse.STOP_REASON_TOOL_CALLS, functionCall.stopReason());
        // The per-choice finish reason is normalized at the decode boundary too,
        // so it always agrees with the response-level stopReason (was stored raw before).
        assertEquals("tool_calls", functionCall.choices().get(0).finishReason());

        // Unknown finish reasons pass through verbatim (tolerant, pinned)
        ChatResponse unknown = codec.decodeResponse(responseWithFinishReason("weird_reason"));
        assertEquals("weird_reason", unknown.stopReason());
        assertEquals("weird_reason", unknown.choices().get(0).finishReason());
    }

    @Test
    void usageTotalDefaultsToPromptPlusCompletion() {
        ChatResponse decoded = codec.decodeResponse("""
                {
                  "id": "i", "object": "chat.completion", "created": 1, "model": "m",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "x"}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 7, "completion_tokens": 3}
                }
                """);
        assertEquals(new Usage(7, 3, 10), decoded.usage());
    }

    @Test
    void usageCompatConstructorLeavesCompletionDetailsNull() {
        OpenAiUsage usage = new OpenAiUsage(1L, 2L, 3L, null, 4L);
        assertEquals(1L, usage.promptTokens());
        assertEquals(2L, usage.completionTokens());
        assertEquals(3L, usage.totalTokens());
        assertEquals(4L, usage.promptCacheHitTokens());
        assertNull(usage.completionTokensDetails());
    }

    @Test
    void reasoningTokensFromCompletionTokensDetailsRoundTrip() {
        ChatResponse decoded = codec.decodeResponse("""
                {
                  "id": "i", "object": "chat.completion", "created": 1, "model": "m",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}],
                  "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 50,
                    "total_tokens": 60,
                    "completion_tokens_details": {"reasoning_tokens": 40}
                  }
                }
                """);
        assertEquals(new Usage(10, 50, 60, null, null, 40L), decoded.usage());
        assertEquals(40L, decoded.usage().reasoningTokens());
        String reencoded = codec.encodeResponse(decoded);
        assertTrue(reencoded.contains("completion_tokens_details"), reencoded);
        assertTrue(reencoded.contains("\"reasoning_tokens\":40"), reencoded);
        ChatResponse again = codec.decodeResponse(reencoded);
        assertEquals(40L, again.usage().reasoningTokens());
    }

    @Test
    void missingUsageAndMissingRoleAreTolerated() {
        ChatResponse decoded = codec.decodeResponse("""
                {
                  "id": "i", "object": "chat.completion", "created": 1, "model": "m",
                  "choices": [{"index": 0, "message": {"content": "x"}, "finish_reason": null}]
                }
                """);
        assertNull(decoded.usage());
        assertEquals(List.of(new ChatChoice(0, new AssistantMessage("x", null), null)), decoded.choices());
        assertNull(decoded.stopReason());
    }

    @Test
    void nullChoiceMessageRaisesTypedApiErrorNeverANullPointer() {
        // A malformed upstream choice with message:null (or a missing
        // message member) must raise the codec's typed api_error — the pre-fix codec
        // threw a raw NPE on dto.role that escaped decodeResponse and surfaced to the
        // client as an api_error leaking internal class names.
        OpenAiCodecException explicitNull = assertThrows(OpenAiCodecException.class, () -> codec.decodeResponse("""
                {"id": "i", "object": "chat.completion", "created": 1, "model": "m",
                 "choices": [{"index": 0, "message": null, "finish_reason": "stop"}]}
                """));
        assertEquals(OpenAiCodecException.TYPE_API_ERROR, explicitNull.type());
        assertTrue(explicitNull.getMessage().contains("message"), explicitNull.getMessage());

        OpenAiCodecException omitted = assertThrows(OpenAiCodecException.class, () -> codec.decodeResponse("""
                {"id": "i", "object": "chat.completion", "created": 1, "model": "m",
                 "choices": [{"index": 0, "finish_reason": "stop"}]}
                """));
        assertEquals(OpenAiCodecException.TYPE_API_ERROR, omitted.type());
        assertTrue(omitted.getMessage().contains("message"), omitted.getMessage());

        // a well-formed multi-choice response still decodes (guard: the null check must
        // not reject valid choices)
        ChatResponse ok = codec.decodeResponse("""
                {"id": "i", "object": "chat.completion", "created": 1, "model": "m",
                 "choices": [{"index": 0, "message": {"role": "assistant", "content": "x"}, "finish_reason": "stop"}]}
                """);
        assertEquals(1, ok.choices().size());
    }

    @Test
    void choiceLevelUnknownFieldsFoldIntoResponseExtras() {
        ChatResponse decoded = codec.decodeResponse("""
                {
                  "id": "i", "object": "chat.completion", "created": 1, "model": "m",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "x"}, "finish_reason": "stop", "logprobs": {"tokens": ["x"]}}]
                }
                """);
        assertEquals(Map.of("logprobs", Map.of("tokens", List.of("x"))), decoded.extras());
    }

    @Test
    void messageLevelUnknownFieldsFoldIntoResponseExtras() {
        // Regression guard: OpenAiResponseMessage.extras must be folded into the
        // response extras on decode (was silently dropped), mirroring the request and
        // chunk directions.
        ChatResponse decoded = codec.decodeResponse("""
                {
                  "id": "i", "object": "chat.completion", "created": 1, "model": "m",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "x",
                    "annotations": [{"type": "url_citation", "url": "https://example.com"}]}, "finish_reason": "stop"}]
                }
                """);
        assertEquals(
                Map.of(
                        "annotations",
                        List.of(Map.of(
                                "type", "url_citation",
                                "url", "https://example.com"))),
                decoded.extras());
    }

    @Test
    void nestedUnknownFieldsReEmergeAtTopLevelOnEncode() {
        // Pinned: choice/message-level unknowns fold into the top-level extras and
        // re-emerge as top-level fields on encode — values survive, position does not.
        ChatResponse decoded = codec.decodeResponse("""
                {
                  "id": "i", "object": "chat.completion", "created": 1, "model": "m",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "x",
                    "annotations": ["a"]}, "finish_reason": "stop", "logprobs": {"tokens": ["x"]}}]
                }
                """);
        String json = codec.encodeResponse(decoded);
        assertTrue(json.contains("\"logprobs\":{\"tokens\":[\"x\"]}"), json);
        assertTrue(json.contains("\"annotations\":[\"a\"]"), json);
    }

    // ------------------------------------------------------------------ encode

    @Test
    void encodeResponseProducesWireShape() {
        ChatResponse response = new ChatResponse(
                "chatcmpl-123",
                "chat.completion",
                1700000000L,
                "model-1",
                List.of(new ChatChoice(
                        0,
                        new AssistantMessage(
                                "Hello!",
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        null)),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of("provider", "deepseek"),
                Map.of("key_id", "k1"));
        String json = codec.encodeResponse(response);
        assertTrue(json.contains("\"id\":\"chatcmpl-123\""), json);
        assertTrue(json.contains("\"object\":\"chat.completion\""), json);
        assertTrue(json.contains("\"created\":1700000000"), json);
        assertTrue(json.contains("\"model\":\"model-1\""), json);
        assertTrue(json.contains("\"choices\":[{\"index\":0"), json);
        assertTrue(json.contains("\"role\":\"assistant\""), json);
        assertTrue(
                json.contains(
                        "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}"),
                json);
        assertTrue(json.contains("\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}"), json);
        assertTrue(json.contains("\"provider\":\"deepseek\""), json);
        assertFalse(json.contains("key_id"));
        assertFalse(json.contains("\"meta\""));
    }

    @Test
    void encodeResponseNormalizesObjectToChatCompletionForAnthropicDerivedCanonicals() {
        // D2 (blessed fix): an Anthropic-derived canonical carries object "message"
        // (the wire type) and created 0 — re-emitted verbatim, the OpenAI wire shape
        // violates the pinned SDK's object Literal["chat.completion"]. The OpenAI face
        // encode path must emit OpenAI-conformant format fields; created 0 is a valid
        // epoch-second int for the SDK and stays deterministic (the codec's existing
        // handling — the matrix pins it).
        ChatResponse anthropicShaped = new ChatResponse(
                "msg_9d8c7b6a5f4e3d2c1b0a9f8e7d6c5b4a",
                "message",
                0L,
                "claude-3-5-sonnet",
                List.of(new ChatChoice(
                        0, new AssistantMessage("The weather in Paris is 18 degrees with light rain.", null), "stop")),
                new Usage(14, 12, 26),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
        String json = codec.encodeResponse(anthropicShaped);
        assertTrue(json.contains("\"object\":\"chat.completion\""), json);
        assertFalse(json.contains("\"object\":\"message\""), json);
        assertTrue(json.contains("\"created\":0"), json);
    }

    @Test
    void stopReasonMapsBackToFinishReasonWhenChoiceLacksOne() {
        ChatResponse response = new ChatResponse(
                "i",
                "chat.completion",
                1L,
                "m",
                List.of(new ChatChoice(0, new AssistantMessage("x", null), null)),
                null,
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                null);
        String json = codec.encodeResponse(response);
        assertTrue(json.contains("\"finish_reason\":\"stop\""), json);

        // canonical error → OpenAI "stop" (StopReason.to_openai table)
        ChatResponse error = new ChatResponse(
                "i",
                "chat.completion",
                1L,
                "m",
                List.of(new ChatChoice(0, new AssistantMessage("x", null), null)),
                null,
                ChatResponse.STOP_REASON_ERROR,
                Map.of(),
                null);
        String errorJson = codec.encodeResponse(error);
        assertTrue(errorJson.contains("\"finish_reason\":\"stop\""), errorJson);
    }

    @Test
    void unknownStopReasonPassesThroughVerbatim() {
        ChatResponse response = new ChatResponse(
                "i",
                "chat.completion",
                1L,
                "m",
                List.of(new ChatChoice(0, new AssistantMessage("x", null), null)),
                null,
                "weird_reason",
                Map.of(),
                null);
        assertTrue(codec.encodeResponse(response).contains("\"finish_reason\":\"weird_reason\""));
    }

    @Test
    void rawChoiceFinishReasonWinsOverStopReason() {
        ChatResponse response = new ChatResponse(
                "i",
                "chat.completion",
                1L,
                "m",
                List.of(new ChatChoice(0, new AssistantMessage("x", null), "length")),
                null,
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                null);
        assertTrue(codec.encodeResponse(response).contains("\"finish_reason\":\"length\""));
    }

    @Test
    void encodeValidatesIdModelAndChoices() {
        ChatResponse noId = new ChatResponse(
                null,
                "chat.completion",
                1L,
                "m",
                List.of(new ChatChoice(0, new AssistantMessage("x", null), null)),
                null,
                null,
                Map.of(),
                null);
        OpenAiCodecException idEx = assertThrows(OpenAiCodecException.class, () -> codec.encodeResponse(noId));
        assertEquals(OpenAiCodecException.TYPE_API_ERROR, idEx.type());
        assertTrue(idEx.getMessage().contains("id"));

        ChatResponse noChoices = new ChatResponse("i", "chat.completion", 1L, "m", null, null, null, Map.of(), null);
        OpenAiCodecException choicesEx =
                assertThrows(OpenAiCodecException.class, () -> codec.encodeResponse(noChoices));
        assertTrue(choicesEx.getMessage().contains("choices"));
    }

    private static String responseWithFinishReason(String finishReason) {
        return """
                {
                  "id": "i", "object": "chat.completion", "created": 1, "model": "m",
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "x"}, "finish_reason": "%s"}],
                  "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
                }
                """.formatted(finishReason);
    }
}
