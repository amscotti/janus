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
import io.amscotti.janus.core.model.ToolMessage;
import io.amscotti.janus.core.model.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hazard guard (binding): Jackson 3 drops interface-level
 * {@code @JsonTypeInfo} when serializing a *bare* {@code List<Message>}. The codec never
 * serializes canonical types at all (DTO mapping) and every DTO list is declared-typed —
 * so the Anthropic discriminators ({@code "type"} per content block / SSE event / delta,
 * {@code "role"} per message) must always appear in encode output. A future refactor into
 * bare-list or map-based serialization fails here in CI.
 */
class AnthropicWireShapeGuardTest {

    private final AnthropicMessageCodec codec = new AnthropicMessageCodec(JsonSupport.mapper());

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
    void responseEncodeEmitsTypePerBlock() {
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                0L,
                "m",
                List.of(
                        new ChatChoice(0, new AssistantMessage("a", null), null),
                        new ChatChoice(1, new AssistantMessage("b", null), null)),
                null,
                null,
                Map.of(),
                null);
        String json = codec.encodeResponse(response);
        assertEquals(2, countOccurrences(json, "\"type\":\"text\""));
        assertEquals(1, countOccurrences(json, "\"type\":\"message\""));
    }

    @Test
    void encoderEventsCarryTypeAndRoleDiscriminators() {
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<AnthropicSseEvent> events = new ArrayList<>();
        events.addAll(encoder.feed(new StreamChunk(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                null,
                Map.of())));
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(0, new Delta(null, "Hel", null), null)),
                null,
                Map.of())));
        events.addAll(encoder.finish());
        assertEquals(6, events.size());
        for (AnthropicSseEvent event : events) {
            assertTrue(event.dataJson().contains("\"type\":\"" + event.event() + "\""), event.dataJson());
        }
        // Embedded discriminators: role per embedded message, type per block/delta.
        assertTrue(
                events.get(0).dataJson().contains("\"role\":\"assistant\""),
                events.get(0).dataJson());
        assertTrue(
                events.get(1).dataJson().contains("\"content_block\":{\"type\":\"text\""),
                events.get(1).dataJson());
        assertTrue(
                events.get(2).dataJson().contains("\"delta\":{\"type\":\"text_delta\""),
                events.get(2).dataJson());
    }

    @Test
    void encoderToolEventsCarryTypeDiscriminatorsPerBlockAndDelta() {
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<AnthropicSseEvent> events = new ArrayList<>();
        List<ToolCall> toolCalls =
                List.of(new ToolCall("call_1", "function", new FunctionCall("get_weather", "{\"city\":\"Paris\"}"), 0));
        events.addAll(encoder.feed(new StreamChunk(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChunkChoice(0, new Delta(null, null, toolCalls), null)),
                null,
                Map.of())));
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(0, new Delta(null, "done", null), null)),
                null,
                Map.of())));
        events.addAll(encoder.finish());
        for (AnthropicSseEvent event : events) {
            assertTrue(event.dataJson().contains("\"type\":\"" + event.event() + "\""), event.dataJson());
        }
        // tool_use block start carries the block discriminator and the tool identity
        assertTrue(
                events.get(1)
                        .dataJson()
                        .contains(
                                "\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{}}"),
                events.get(1).dataJson());
        // input_json_delta carries the delta discriminator; the tool_use block is then
        // closed (Anthropic protocol: content_block_stop before the terminal sequence)
        assertTrue(
                events.get(2).dataJson().contains("\"delta\":{\"type\":\"input_json_delta\""),
                events.get(2).dataJson());
        assertTrue(
                events.get(3).dataJson().contains("\"type\":\"content_block_stop\""),
                events.get(3).dataJson());
        assertTrue(
                events.get(3).dataJson().contains("\"index\":0"), events.get(3).dataJson());
        // text content after a tool block reopens a fresh text block (index 1)
        assertTrue(
                events.get(4).dataJson().contains("\"type\":\"content_block_start\""),
                events.get(4).dataJson());
        assertTrue(
                events.get(4).dataJson().contains("\"content_block\":{\"type\":\"text\",\"text\":\"\"}"),
                events.get(4).dataJson());
        assertTrue(
                events.get(5).dataJson().contains("\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}"),
                events.get(5).dataJson());
        // the text block is closed, then the terminal message_delta + message_stop
        assertTrue(
                events.get(6).dataJson().contains("\"type\":\"content_block_stop\""),
                events.get(6).dataJson());
        assertTrue(
                events.get(7).dataJson().contains("\"type\":\"message_delta\""),
                events.get(7).dataJson());
        assertTrue(
                events.get(7).dataJson().contains("\"stop_reason\":\"end_turn\""),
                events.get(7).dataJson());
        assertTrue(
                events.get(8).dataJson().contains("\"type\":\"message_stop\""),
                events.get(8).dataJson());
        // no canonical type ever leaks into the wire
        for (AnthropicSseEvent event : events) {
            assertFalse(event.dataJson().contains("\"type\":\"function\""), event.dataJson());
        }
    }

    @Test
    void requestEncodeEmitsToolBlockDiscriminators() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("a"), new ToolMessage("call_1", "ok")),
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
        // user-role tool_result block carries the block type discriminator
        assertTrue(json.contains("\"type\":\"tool_result\""), json);
        assertTrue(json.contains("\"tool_use_id\":\"call_1\""), json);
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
