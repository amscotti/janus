package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.amscotti.janus.core.model.UserMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hazard guard (binding): Jackson 3 drops interface-level
 * {@code @JsonTypeInfo} when serializing a *bare* {@code List<Message>}. The codec never
 * serializes canonical types at all (DTO mapping) and every DTO list is declared-typed —
 * so the per-message {@code "role"} and per-choice {@code "index"} discriminators must
 * always appear in encode output. A future refactor into bare-list or map-based
 * serialization fails here in CI.
 */
class CodecWireShapeGuardTest {

    private final OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());

    @Test
    void requestEncodeEmitsRolePerMessage() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("a"), new UserMessage("b")),
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
        assertEquals(2, countOccurrences(json, "\"role\""));
        assertTrue(json.contains("\"role\":\"user\""), json);
    }

    @Test
    void responseEncodeEmitsIndexPerChoice() {
        ChatResponse response = new ChatResponse(
                "i",
                "chat.completion",
                1L,
                "m",
                List.of(
                        new ChatChoice(0, new AssistantMessage("a", null), null),
                        new ChatChoice(1, new AssistantMessage("b", null), null)),
                null,
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                null);
        String json = codec.encodeResponse(response);
        assertEquals(2, countOccurrences(json, "\"index\""));
        assertTrue(json.contains("\"index\":0"), json);
        assertTrue(json.contains("\"index\":1"), json);
    }

    @Test
    void requestEncodeEmitsToolWireShapes() {
        // The OpenAI guard also covers tool shapes —
        // tools/tool_calls carry the wire discriminator "type":"function", tool messages
        // carry "role":"tool" + "tool_call_id", and no Anthropic-idiomatic shape leaks
        // into OpenAI output (the canonical-type leak invariant, tool side).
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(
                        new UserMessage("what's the weather?"),
                        new AssistantMessage(
                                null,
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        new ToolMessage("call_1", "{\"temp\":18}")),
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
        // per-message role discriminators: user + assistant + tool
        assertTrue(json.contains("\"role\":\"user\""), json);
        assertTrue(json.contains("\"role\":\"assistant\""), json);
        assertTrue(json.contains("\"role\":\"tool\""), json);
        // tool definition carries the function discriminator, description and parameters
        assertTrue(
                json.contains("\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                        + "\"description\":\"current weather in a city\","
                        + "\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}}"),
                json);
        // assistant tool_calls carry id + function discriminator; tool message carries tool_call_id
        assertTrue(
                json.contains("\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                        + "\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}]"),
                json);
        assertTrue(
                json.contains("\"role\":\"tool\",\"content\":\"{\\\"temp\\\":18}\",\"tool_call_id\":\"call_1\""), json);
        assertTrue(json.contains("\"tool_choice\":\"auto\""), json);
        // no Anthropic-idiomatic shape ever leaks into the OpenAI wire
        assertFalse(json.contains("input_schema"), json);
        assertFalse(json.contains("tool_use"), json);
        assertFalse(json.contains("tool_result"), json);
        assertFalse(json.contains("content_block"), json);
        assertFalse(json.contains("stop_reason"), json);
    }

    @Test
    void chunkEncodeEmitsIndexPerChoice() {
        StreamChunk chunk = new StreamChunk(
                "i",
                "chat.completion.chunk",
                1L,
                "m",
                List.of(
                        new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "a", null), null),
                        new ChunkChoice(1, new Delta(null, "b", null), null)),
                null,
                Map.of());
        String json = codec.encodeChunk(chunk);
        assertEquals(2, countOccurrences(json, "\"index\""));
        assertTrue(json.contains("\"index\":0"), json);
        assertTrue(json.contains("\"index\":1"), json);
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
}
