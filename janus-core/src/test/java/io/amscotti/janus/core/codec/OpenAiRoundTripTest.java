package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.core.model.ToolDefinition;
import io.amscotti.janus.core.model.ToolMessage;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.core.model.UserMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Full round-trip idempotence : canonical → wire → canonical for request,
 * response and chunk — all four message roles, tools, and extras with null-valued
 * entries (regression guard).
 */
class OpenAiRoundTripTest {

    private final OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());

    @Test
    void requestRoundTripsIdempotently() {
        ChatRequest request = new ChatRequest(
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
                "be brief", // system lives in the canonical system field (its home)
                List.of(new ToolDefinition(
                        "function",
                        "get_weather",
                        "current weather in a city",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")),
                "auto", // toolChoice — canonical OpenAI-idiomatic form
                0.7, // temperature
                0.9, // topP
                // topK: deliberately NOT round-tripped on the OpenAI leg — the
                // egress drops top_k (strict OpenAI 400s it), so a canonical topK
                // comes back null; encoded as null here to pin the symmetric shape.
                null, // topK
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
                null,
                null, // reasoning / cacheControl
                extrasWithNull("custom", "pass-through", "null_field", null), // extras (m1: null value)
                Map.of()); // meta — never emitted, never read back

        ChatRequest decoded = codec.decodeRequest(codec.encodeRequest(request));
        assertEquals(request, decoded);
    }

    @Test
    void responseRoundTripsIdempotently() {
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
                        "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                extrasWithNull("provider", "deepseek", "null_field", null),
                Map.of());

        ChatResponse decoded = codec.decodeResponse(codec.encodeResponse(response));
        assertEquals(response, decoded);
    }

    @Test
    void chunkRoundTripsIdempotently() {
        StreamChunk chunk = new StreamChunk(
                "chatcmpl-123",
                "chat.completion.chunk",
                1700000000L,
                "model-1",
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                ChatRole.ASSISTANT,
                                "Hel",
                                List.of(new ToolCall(
                                        "call_1", "function", new FunctionCall("get_weather", "{\"x\":1}"), 0))),
                        "stop")),
                new Usage(1, 2, 3),
                extrasWithNull("custom", "x", "null_field", null));

        StreamChunk decoded = codec.decodeChunk(codec.encodeChunk(chunk));
        assertEquals(chunk, decoded);
    }

    @Test
    void extrasSurviveFullRoundTripsUnmodified() {
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
                extrasWithNull("opaque", Map.of("nested", List.of(1, 2)), "nil_value", null, "str", "v"),
                null);
        ChatRequest decoded = codec.decodeRequest(codec.encodeRequest(request));
        assertEquals(request.extras(), decoded.extras());
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
