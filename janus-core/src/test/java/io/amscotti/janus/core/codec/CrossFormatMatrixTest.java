package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.core.model.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The cross-format matrix — composition through the canonical
 * model, inline JSON byte-shape assertions only (committed real-upstream golden fixtures
 * cover that; no network in CI):
 *
 * <ul>
 * <li>OpenAI request wire → {@link OpenAiMessageCodec#decodeRequest} →
 * {@link AnthropicMessageCodec#encodeRequest} → Anthropic JSON
 * ({@code input_schema}, {@code tool_choice}, user-role {@code tool_result},
 * top-level {@code system});
 * <li>Anthropic request wire → decode → OpenAI JSON ({@code functions},
 * {@code "required"} tool_choice, {@code role:"tool"} with {@code tool_call_id},
 * system message at index 0);
 * <li>response both directions (OpenAI {@code tool_calls} +
 * {@code finish_reason:"tool_calls"} ↔ Anthropic {@code tool_use} +
 * {@code stop_reason:"tool_use"}; usage incl. cache tokens on both sides —
 * Anthropic {@code cache_*_input_tokens}, OpenAI {@code prompt_tokens_details});
 * <li>streaming both directions (OpenAI chunk sequence with tool fragments ↔ Anthropic
 * event sequence via the encoder; Anthropic events ↔ OpenAI chunk frames);
 * <li>round-trip idempotence: canonical → OpenAI wire → canonical and canonical →
 * Anthropic wire → canonical for every message kind incl. tools, tool_choice,
 * usage (with cache tokens on the Anthropic side), system.
 * </ul>
 */
class CrossFormatMatrixTest {

    private final OpenAiMessageCodec openAi = new OpenAiMessageCodec(JsonSupport.mapper());
    private final AnthropicMessageCodec anthropic = new AnthropicMessageCodec(JsonSupport.mapper());

    // ------------------------------------------------------- OpenAI wire → Anthropic

    @Test
    void openAiRequestWireComposesToAnthropicWire() {
        ChatRequest canonical = openAi.decodeRequest("""
                {
                  "model": "model-1",
                  "messages": [
                    {"role": "system", "content": "be brief"},
                    {"role": "user", "content": "what is the weather in Paris?"},
                    {"role": "assistant", "content": null, "tool_calls": [
                      {"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Paris\\"}"}}
                    ]},
                    {"role": "tool", "content": "{\\"temp\\":18}", "tool_call_id": "call_1"}
                  ],
                  "tools": [{"type": "function", "function": {
                    "name": "get_weather", "description": "current weather in a city",
                    "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}
                  }}],
                  "tool_choice": {"type": "function", "function": {"name": "get_weather"}}
                }
                """);
        // system message extracted into the canonical system field (dropped from messages)
        assertEquals("be brief", canonical.system());
        assertEquals(3, canonical.messages().size());
        assertEquals(
                new ToolMessage("call_1", "{\"temp\":18}"), canonical.messages().get(2));

        String json = anthropic.encodeRequest(canonical);
        // top-level system (merged from the OpenAI system message)
        assertTrue(json.contains("\"system\":\"be brief\""), json);
        // assistant tool_calls → tool_use content block
        assertTrue(
                json.contains("\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call_1\","
                        + "\"name\":\"get_weather\",\"input\":{\"city\":\"Paris\"}}]"),
                json);
        // ToolMessage → user-role message with a tool_result block
        assertTrue(
                json.contains("\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\","
                        + "\"content\":\"{\\\"temp\\\":18}\"}]"),
                json);
        // tools → input_schema (description preserved)
        assertTrue(
                json.contains("\"tools\":[{\"name\":\"get_weather\",\"description\":\"current weather in a city\","
                        + "\"input_schema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}]"),
                json);
        // OpenAI function-object tool_choice → {"type":"tool","name":...}
        assertTrue(json.contains("\"tool_choice\":{\"type\":\"tool\",\"name\":\"get_weather\"}"), json);
    }

    // ---------------------------------------------------- Anthropic wire → OpenAI

    @Test
    void anthropicRequestWireComposesToOpenAiWire() {
        ChatRequest canonical = anthropic.decodeRequest("""
                {
                  "model": "claude-3",
                  "max_tokens": 4096,
                  "system": "be brief",
                  "messages": [
                    {"role": "user", "content": "what is the weather in Paris?"},
                    {"role": "assistant", "content": [
                      {"type": "tool_use", "id": "call_1", "name": "get_weather", "input": {"city": "Paris"}}
                    ]},
                    {"role": "user", "content": [
                      {"type": "tool_result", "tool_use_id": "call_1", "content": "{\\"temp\\":18}"}
                    ]}
                  ],
                  "tools": [{"name": "get_weather", "description": "current weather in a city",
                             "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}}}],
                  "tool_choice": {"type": "any"}
                }
                """);
        assertEquals("be brief", canonical.system());
        // Anthropic-idiomatic tool_choice {"type":"any"} → canonical "required"
        assertEquals("required", canonical.toolChoice());
        assertEquals(
                new ToolMessage("call_1", "{\"temp\":18}"), canonical.messages().get(2));

        String json = openAi.encodeRequest(canonical);
        // system message at index 0 (Anthropic's top-level system → OpenAI role:system)
        assertTrue(json.contains("\"role\":\"system\",\"content\":\"be brief\""), json);
        assertTrue(json.indexOf("\"role\":\"system\"") < json.indexOf("\"role\":\"user\""), json);
        // tool_use → tool_calls with type:"function" and raw-JSON arguments (null content
        // is omitted by @JsonInclude(NON_NULL))
        assertTrue(
                json.contains("\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                        + "\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}]"),
                json);
        // tool_result → role:"tool" message with tool_call_id
        assertTrue(
                json.contains("\"role\":\"tool\",\"content\":\"{\\\"temp\\\":18}\",\"tool_call_id\":\"call_1\""), json);
        // input_schema → functions with parameters + description
        assertTrue(
                json.contains("\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                        + "\"description\":\"current weather in a city\","
                        + "\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}}"),
                json);
        // canonical "required" → OpenAI "required"
        assertTrue(json.contains("\"tool_choice\":\"required\""), json);
    }

    // ---------------------------------------------------- response both directions

    @Test
    void openAiResponseWireComposesToAnthropicWire() {
        ChatResponse canonical = openAi.decodeResponse("""
                {"id": "chatcmpl-123", "object": "chat.completion", "created": 1700000000, "model": "model-1",
                 "choices": [{"index": 0, "message": {"role": "assistant", "content": "checking", "tool_calls": [
                   {"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Paris\\"}"}}
                 ]}, "finish_reason": "tool_calls"}],
                 "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}
                """);
        assertEquals(ChatResponse.STOP_REASON_TOOL_CALLS, canonical.stopReason());
        assertEquals(
                List.of(new ToolCall("call_1", "function", new FunctionCall("get_weather", "{\"city\":\"Paris\"}"))),
                ((AssistantMessage) canonical.choices().get(0).message()).toolCalls());

        String json = anthropic.encodeResponse(canonical);
        // tool_calls → tool_use blocks; finish_reason "tool_calls" → stop_reason "tool_use"
        assertTrue(
                json.contains("\"content\":[{\"type\":\"text\",\"text\":\"checking\"},{\"type\":\"tool_use\","
                        + "\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"Paris\"}}]"),
                json);
        assertTrue(json.contains("\"stop_reason\":\"tool_use\""), json);
        // usage: prompt/completion → input/output; total derived, never emitted
        assertTrue(json.contains("\"usage\":{\"input_tokens\":10,\"output_tokens\":5}"), json);
        assertFalse(json.contains("\"total"), json);
    }

    @Test
    void anthropicResponseWireComposesToOpenAiWireWithCacheTokensInDetails() {
        ChatResponse canonical = anthropic.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\","
                        + "\"input\":{\"city\":\"Paris\"}}],\"stop_reason\":\"tool_use\","
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                        + "\"cache_creation_input_tokens\":2,\"cache_read_input_tokens\":3}}");
        assertEquals(ChatResponse.STOP_REASON_TOOL_CALLS, canonical.stopReason());
        // cache tokens land on the canonical Usage; total is the full
        // input (regular 10 + cache 2 + 3) + output 5 (Anthropic's input_tokens
        // excludes the additive cache counts)
        assertEquals(new Usage(10, 5, 20, 2L, 3L), canonical.usage());
        assertEquals(
                List.of(new ToolCall("call_1", "function", new FunctionCall("get_weather", "{\"city\":\"Paris\"}"))),
                ((AssistantMessage) canonical.choices().get(0).message()).toolCalls());

        String json = openAi.encodeResponse(canonical);
        // tool_use → tool_calls; stop_reason "tool_use" → finish_reason "tool_calls"
        assertTrue(
                json.contains("\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                        + "\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}]}"),
                json);
        assertTrue(json.contains("\"finish_reason\":\"tool_calls\""), json);
        // the restore heuristic fires (prompt 10 + completion 5 + cache 5 == total 20),
        // so the OpenAI wire counts the FULL input inside prompt_tokens with the split
        // alongside it — cached_tokens/cache_write_tokens are subsets of prompt_tokens
        assertTrue(json.contains("\"prompt_tokens\":15"), json);
        assertTrue(json.contains("\"completion_tokens\":5"), json);
        assertTrue(json.contains("\"total_tokens\":20"), json);
        assertTrue(json.contains("\"cached_tokens\":3"), json);
        assertTrue(json.contains("\"cache_write_tokens\":2"), json);
    }

    @Test
    void anthropicRefusalStopReasonMapsToValidOpenAiFinishReasonOnTheAoLeg() {
        // Anthropic's "refusal"/"pause_turn" are real stop reasons; on the ao
        // cross-format leg they must NOT leak to the OpenAI wire as finish_reason values
        // outside the SDK vocabulary most clients validate. The canonical keeps the
        // verbatim value (Anthropic round trip idempotent) and the OpenAI encode side maps
        // it to "stop".
        ChatResponse canonical = anthropic.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"I cannot help with that\"}],"
                        + "\"stop_reason\":\"refusal\"}");
        assertEquals("refusal", canonical.stopReason(), "the canonical keeps the verbatim value");
        String json = openAi.encodeResponse(canonical);
        assertTrue(json.contains("\"finish_reason\":\"stop\""), json);
        assertFalse(json.contains("refusal"), json);
    }

    @Test
    void anthropicRefusalFinishReasonMapsToValidValueOnTheAoStreamingLeg() {
        // The same leak existed on the ao streaming leg — the Anthropic message_delta
        // finish reason "refusal" would re-emit as an invalid OpenAI chunk finish_reason.
        StreamChunk terminal = anthropic.decodeChunk(
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"},\"usage\":{\"output_tokens\":4}}");
        assertEquals("refusal", terminal.choices().get(0).finishReason(), "canonical keeps the verbatim value");
        String json = openAi.encodeChunk(terminal);
        assertTrue(json.contains("\"finish_reason\":\"stop\""), json);
        assertFalse(json.contains("refusal"), json);
    }

    // --------------------------------------------------- streaming both directions

    @Test
    void openAiToolStreamComposesToAnthropicEvents() {
        String[] openAiChunks = {
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":["
                    + "{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}"
                    + "]},\"finish_reason\":null}]}",
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                    + "{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\"}}"
                    + "]},\"finish_reason\":null}]}",
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                    + "{\"index\":0,\"function\":{\"arguments\":\"\\\"Paris\\\"}\"}}"
                    + "]},\"finish_reason\":null}]}",
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"
        };
        AnthropicStreamEncoder encoder = anthropic.newStreamEncoder();
        List<AnthropicSseEvent> events = new ArrayList<>();
        for (String chunk : openAiChunks) {
            StreamChunk canonical = openAi.decodeChunk(chunk);
            events.addAll(encoder.feed(canonical));
        }
        events.addAll(encoder.finish());

        // message_start → content_block_start(tool_use, index 0) → input_json_delta* →
        // content_block_stop(0) → message_delta(stop_reason "tool_use") → message_stop
        List<String> types = events.stream().map(AnthropicSseEvent::event).toList();
        assertEquals(
                List.of(
                        "message_start",
                        "content_block_start",
                        "content_block_delta",
                        "content_block_delta",
                        "content_block_delta",
                        "content_block_stop",
                        "message_delta",
                        "message_stop"),
                types);
        assertTrue(
                events.get(1)
                        .dataJson()
                        .contains(
                                "\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{}}"),
                events.get(1).dataJson());
        // fragments pass through verbatim (never accumulated or validated)
        assertTrue(
                events.get(2).dataJson().contains("\"partial_json\":\"\""),
                events.get(2).dataJson());
        assertTrue(
                events.get(3).dataJson().contains("\"partial_json\":\"{\\\"city\\\":\""),
                events.get(3).dataJson());
        assertTrue(
                events.get(4).dataJson().contains("\"partial_json\":\"\\\"Paris\\\"}\""),
                events.get(4).dataJson());
        assertTrue(
                events.get(6).dataJson().contains("\"stop_reason\":\"tool_use\""),
                events.get(6).dataJson());
    }

    @Test
    void anthropicToolEventsComposeToOpenAiChunkFrames() {
        List<StreamChunk> chunks = List.of(
                anthropic.decodeChunk(
                        "content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\","
                                + "\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{}}}"),
                anthropic.decodeChunk(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\","
                                + "\"partial_json\":\"{\\\"city\\\":\"}}"),
                anthropic.decodeChunk(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\","
                                + "\"partial_json\":\"\\\"Paris\\\"}\"}}"),
                anthropic.decodeChunk(
                        "message_delta",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},"
                                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}"));

        // the Anthropic block index rides ToolCall.index verbatim (documented: Anthropic
        // indexes content blocks, OpenAI indexes tool calls only)
        assertEquals(
                0, chunks.get(0).choices().get(0).delta().toolCalls().get(0).index());

        List<String> frames = chunks.stream().map(openAi::encodeChunk).toList();
        // first fragment carries id + name + empty arguments
        assertTrue(
                frames.get(0)
                        .contains("\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"toolu_1\","
                                + "\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]"),
                frames.get(0));
        // argument fragments pass through verbatim, keyed by the block index (the encode
        // normalizes the canonical null type to "function" — pinned OpenAI behavior)
        assertTrue(
                frames.get(1)
                        .contains(
                                "\"tool_calls\":[{\"index\":0,\"type\":\"function\",\"function\":{\"arguments\":\"{\\\"city\\\":\"}}]"),
                frames.get(1));
        assertTrue(
                frames.get(2)
                        .contains(
                                "\"tool_calls\":[{\"index\":0,\"type\":\"function\",\"function\":{\"arguments\":\"\\\"Paris\\\"}\"}}]"),
                frames.get(2));
        // message_delta → finish_reason "tool_calls" + usage
        assertTrue(frames.get(3).contains("\"finish_reason\":\"tool_calls\""), frames.get(3));
        assertTrue(
                frames.get(3).contains("\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}"),
                frames.get(3));
    }

    @Test
    void aoStreamRenumbersContentBlockIndicesToToolOnlyIndices() {
        // Anthropic indexes content blocks (text and tools share one
        // counter); OpenAI indexes tool calls only. The stateful per-stream decoder must
        // renumber tool-using blocks into a tool-only 0-based space so an ao passthrough
        // emits contiguous OpenAI tool_calls indices — a leading text block (0) then two
        // tool blocks (1, 2) must surface as tool indices 0, 1, never 1, 2.
        AnthropicStreamDecoder decoder = anthropic.newStreamDecoder();
        decoder.decodeChunk(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}");
        // leading text block occupies Anthropic block index 0 (no canonical chunk)
        assertNull(decoder.decodeChunk(
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        decoder.decodeChunk(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Let me\"}}");
        // first tool block (Anthropic index 1) → tool-only index 0
        StreamChunk toolStart = decoder.decodeChunk(
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{}}}");
        assertEquals(0, toolStart.choices().get(0).delta().toolCalls().get(0).index());
        // second tool block (Anthropic index 2) → tool-only index 1
        StreamChunk toolStart2 = decoder.decodeChunk(
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"toolu_2\",\"name\":\"get_weather\",\"input\":{}}}");
        assertEquals(1, toolStart2.choices().get(0).delta().toolCalls().get(0).index());
        // fragments route through the renumbering table: block 1 → 0, block 2 → 1
        StreamChunk frag0 = decoder.decodeChunk(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\","
                        + "\"partial_json\":\"{\\\"city\\\":\"}}");
        assertEquals(0, frag0.choices().get(0).delta().toolCalls().get(0).index());
        StreamChunk frag1 = decoder.decodeChunk(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\","
                        + "\"partial_json\":\"\\\"Paris\\\"}\"}}");
        assertEquals(1, frag1.choices().get(0).delta().toolCalls().get(0).index());

        // outbound OpenAI wire carries contiguous 0-based tool_calls indices
        assertTrue(
                openAi.encodeChunk(toolStart).contains("\"tool_calls\":[{\"index\":0,\"id\":\"toolu_1\""),
                openAi.encodeChunk(toolStart));
        assertTrue(
                openAi.encodeChunk(toolStart2).contains("\"tool_calls\":[{\"index\":1,\"id\":\"toolu_2\""),
                openAi.encodeChunk(toolStart2));
        assertTrue(
                openAi.encodeChunk(frag0).contains("\"tool_calls\":[{\"index\":0,\"type\":\"function\""),
                openAi.encodeChunk(frag0));
        assertTrue(
                openAi.encodeChunk(frag1).contains("\"tool_calls\":[{\"index\":1,\"type\":\"function\""),
                openAi.encodeChunk(frag1));
    }

    @Test
    void legacyFunctionCallFinishReasonMapsToToolUseOnTheOaStreamingLeg() {
        // The legacy OpenAI streaming finish_reason "function_call" is
        // normalized to the canonical tool_calls value at the decode boundary — the
        // Anthropic stream encoder then emits stop_reason "tool_use", never the
        // Anthropic-invalid verbatim "function_call".
        StreamChunk terminal =
                openAi.decodeChunk("{\"id\":\"i\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"function_call\"}]}");
        assertEquals(
                ChatResponse.STOP_REASON_TOOL_CALLS, terminal.choices().get(0).finishReason());

        AnthropicStreamEncoder encoder = anthropic.newStreamEncoder();
        encoder.feed(new StreamChunk(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                null,
                Map.of()));
        encoder.feed(terminal);
        List<AnthropicSseEvent> events = encoder.finish();
        AnthropicSseEvent messageDelta = events.stream()
                .filter(e -> e.event().equals("message_delta"))
                .findFirst()
                .orElseThrow();
        assertTrue(messageDelta.dataJson().contains("\"stop_reason\":\"tool_use\""), messageDelta.dataJson());
        assertFalse(messageDelta.dataJson().contains("function_call"), messageDelta.dataJson());
    }

    // ------------------------------------------------------ round-trip idempotence

    @Test
    void canonicalRoundTripsThroughOpenAiWire() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(
                        new UserMessage("hi"),
                        new AssistantMessage(
                                null,
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        new ToolMessage("call_1", "{\"temp\":18}")),
                "be brief",
                List.of(new ToolDefinition(
                        "function",
                        "get_weather",
                        "current weather in a city",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")),
                Map.of("type", "function", "function", Map.of("name", "get_weather")),
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
                Map.of(),
                Map.of());
        assertEquals(request, openAi.decodeRequest(openAi.encodeRequest(request)));

        ChatResponse response = new ChatResponse(
                "chatcmpl-123",
                "chat.completion",
                1700000000L,
                "model-1",
                List.of(new ChatChoice(
                        0,
                        new AssistantMessage(
                                "checking",
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        ChatResponse.STOP_REASON_TOOL_CALLS)),
                new Usage(10, 5, 15), // no cache tokens: the OpenAI wire has no home for them
                ChatResponse.STOP_REASON_TOOL_CALLS,
                Map.of(),
                Map.of());
        assertEquals(response, openAi.decodeResponse(openAi.encodeResponse(response)));
    }

    @Test
    void canonicalRoundTripsThroughAnthropicWire() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(
                        new UserMessage("hi"),
                        new AssistantMessage(
                                null,
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        new ToolMessage("call_1", "{\"temp\":18}")),
                "be brief",
                List.of(new ToolDefinition(
                        "function",
                        "get_weather",
                        "current weather in a city",
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")),
                "required", // canonical form; Anthropic {"type":"any"} maps back
                null,
                null,
                null,
                4096, // a missing canonical maxTokens defaults to 4096 on the wire
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
                Map.of(),
                Map.of());
        assertEquals(request, anthropic.decodeRequest(anthropic.encodeRequest(request)));

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
                new Usage(10, 5, 20, 2L, 3L), // cache tokens round-trip on the Anthropic wire
                ChatResponse.STOP_REASON_TOOL_CALLS,
                Map.of(),
                Map.of());
        assertEquals(response, anthropic.decodeResponse(anthropic.encodeResponse(response)));
    }

    @Test
    void divergingStopReasonSpellingsEncodeConsistentlyOnBothFaces() throws Exception {
        // The canonical stores the same fact twice (per-choice
        // ChatChoice.finishReason and response-level ChatResponse.stopReason), kept in sync
        // by convention on decode. A hand-built canonical whose spellings diverge must not
        // emit inconsistent wire output across the two faces. Both codecs now read the
        // per-choice spelling first (Anthropic encode mirrors the OpenAI finishReasonFor),
        // so the divergent canonical encodes "length"/"max_tokens" on both wires.
        ChatResponse diverging = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1L,
                "model-1",
                List.of(new ChatChoice(0, new AssistantMessage("x", null), ChatResponse.STOP_REASON_LENGTH)),
                new Usage(1, 2, 3),
                ChatResponse.STOP_REASON_STOP, // disagrees with the per-choice reason
                Map.of(),
                null);

        String openAiJson = openAi.encodeResponse(diverging);
        assertTrue(openAiJson.contains("\"finish_reason\":\"length\""), openAiJson);
        assertFalse(openAiJson.contains("\"finish_reason\":\"stop\""), openAiJson);

        String anthropicJson = anthropic.encodeResponse(diverging);
        assertTrue(anthropicJson.contains("\"stop_reason\":\"max_tokens\""), anthropicJson);
        assertFalse(anthropicJson.contains("\"stop_reason\":\"end_turn\""), anthropicJson);
    }

    @Test
    void nullPerChoiceFinishReasonFallsBackToResponseLevel() throws Exception {
        // When the per-choice spelling is absent the response-level stopReason
        // is the fallback on both faces (a decode-produced canonical always carries both,
        // but a hand-built one may carry only the response-level fact).
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1L,
                "model-1",
                List.of(new ChatChoice(0, new AssistantMessage("x", null), null)),
                new Usage(1, 2, 3),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                null);
        assertTrue(
                openAi.encodeResponse(response).contains("\"finish_reason\":\"stop\""),
                openAi.encodeResponse(response));
        assertTrue(
                anthropic.encodeResponse(response).contains("\"stop_reason\":\"end_turn\""),
                anthropic.encodeResponse(response));
    }
}
