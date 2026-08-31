package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.core.model.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Streaming direction of {@link AnthropicMessageCodec} ( steps 5–6 + ): the SSE
 * event → {@code StreamChunk} mapping table ({@link AnthropicMessageCodec#decodeChunk})
 * and the stateful multi-block {@link AnthropicStreamEncoder} (exact event sequence,
 * byte-correct payload JSON, interleaved text + tool-call fragments, {@code finish}
 * contract).
 */
class AnthropicChunkCodecTest {

    private final AnthropicMessageCodec codec = new AnthropicMessageCodec(JsonSupport.mapper());

    // ------------------------------------------------------------------ decode

    @Test
    void messageStartDecodesToRoleChunk() {
        StreamChunk chunk = codec.decodeChunk(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}");
        assertEquals(
                new StreamChunk(
                        "msg_1",
                        "message",
                        0L,
                        "claude-3",
                        List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                        null,
                        Map.of()),
                chunk);
    }

    @Test
    void textDeltaDecodesToContentChunk() {
        StreamChunk chunk = codec.decodeChunk(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}");
        assertEquals(
                new StreamChunk(
                        null,
                        null,
                        0L,
                        null,
                        List.of(new ChunkChoice(0, new Delta(null, "Hel", null), null)),
                        null,
                        Map.of()),
                chunk);
    }

    @Test
    void inputJsonDeltaDecodesToToolFragmentChunk() {
        StreamChunk chunk = codec.decodeChunk(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":2,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"Par\"}}");
        // verbatim partial JSON fragment keyed by the Anthropic content-block index
        assertEquals(
                new StreamChunk(
                        null,
                        null,
                        0L,
                        null,
                        List.of(new ChunkChoice(
                                0,
                                new Delta(
                                        null,
                                        null,
                                        List.of(new ToolCall(
                                                null, null, new FunctionCall(null, "{\"city\":\"Par"), 2))),
                                null)),
                        null,
                        Map.of()),
                chunk);
    }

    @Test
    void toolUseBlockStartDecodesToFirstFragmentChunk() {
        StreamChunk chunk = codec.decodeChunk(
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":1,"
                        + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{}}}");
        // first fragment carries id + name + empty arguments; role announced (Delta ASSISTANT)
        assertEquals(
                new StreamChunk(
                        null,
                        null,
                        0L,
                        null,
                        List.of(new ChunkChoice(
                                0,
                                new Delta(
                                        ChatRole.ASSISTANT,
                                        null,
                                        List.of(new ToolCall(
                                                "toolu_1", "function", new FunctionCall("get_weather", ""), 1))),
                                null)),
                        null,
                        Map.of()),
                chunk);
    }

    @Test
    void messageDeltaDecodesToFinishAndUsageChunk() {
        StreamChunk chunk = codec.decodeChunk(
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\",\"stop_sequence\":null},"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}");
        assertEquals(
                new StreamChunk(
                        null,
                        null,
                        0L,
                        null,
                        List.of(new ChunkChoice(0, new Delta(null, null, null), "length")),
                        new Usage(10, 5, 15),
                        Map.of()),
                chunk);
    }

    @Test
    void messageDeltaWithoutUsageCarriesFinishReasonOnly() {
        StreamChunk chunk = codec.decodeChunk(
                "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
        assertEquals(
                new StreamChunk(
                        null,
                        null,
                        0L,
                        null,
                        List.of(new ChunkChoice(0, new Delta(null, null, null), "stop")),
                        null,
                        Map.of()),
                chunk);
    }

    @Test
    void messageDeltaStopSequenceIsDropped() {
        // The wire stop_sequence has no canonical home (documented): a user stop sequence
        // terminates the stream but the canonical finish chunk carries only the reason.
        StreamChunk chunk = codec.decodeChunk(
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"stop_sequence\",\"stop_sequence\":\"END\"},"
                        + "\"usage\":{\"output_tokens\":3}}");
        assertEquals(
                new StreamChunk(
                        null,
                        null,
                        0L,
                        null,
                        List.of(new ChunkChoice(0, new Delta(null, null, null), "stop")),
                        new Usage(0, 3, 3),
                        Map.of()),
                chunk);
    }

    @Test
    void messageStartWithMissingMessageStillAnnouncesRole() {
        // A message_start with a missing/null message object (non-conformant upstream)
        // decodes to a role chunk with no identity — tolerated, never an abort.
        StreamChunk chunk = codec.decodeChunk("message_start", "{\"type\":\"message_start\",\"message\":null}");
        assertEquals(
                new StreamChunk(
                        null,
                        "message",
                        0L,
                        null,
                        List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                        null,
                        Map.of()),
                chunk);
    }

    @Test
    void noOpEventsDecodeToNull() {
        assertNull(codec.decodeChunk(
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        assertNull(codec.decodeChunk("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
        assertNull(codec.decodeChunk("message_stop", "{\"type\":\"message_stop\"}"));
        assertNull(codec.decodeChunk("ping", "{\"type\":\"ping\"}"));
        assertNull(codec.decodeChunk("unknown_event", "{\"whatever\":1}"));
        assertNull(codec.decodeChunk(null, "{}"));
    }

    @Test
    void pingRecordHoldsTheWireType() throws Exception {
        // decodeChunk("ping") is a no-op (returns null without reading the body).
        // The record is the sealed-union slot for {"type":"ping"}; construct and
        // serialize so the type is not dead code. Jackson's EXISTING_PROPERTY
        // discriminator consumes `type` on read, so we do not bind through the mapper.
        AnthropicPing ping = new AnthropicPing("ping");
        assertEquals("ping", ping.type());
        String json = JsonSupport.mapper().writeValueAsString(ping);
        assertTrue(json.contains("\"type\":\"ping\""), json);
    }

    // ------------------------------------------------ unknown types

    @Test
    void unknownContentBlockAndDeltaTypesAreTolerated() {
        // thinking_delta / signature_delta (extended thinking), server_tool_use /
        // web_search_tool_result / fallback are real Anthropic block/delta types the codec
        // does not model — they must be dropped, never abort the stream (Anthropic's
        // versioning contract: clients should handle unknown event types gracefully). The
        // pre-fix codec threw InvalidTypeIdException on the first one and killed the stream.
        assertNull(
                codec.decodeChunk(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}"));
        assertNull(
                codec.decodeChunk(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_1\"}}"));
        assertNull(
                codec.decodeChunk(
                        "content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"toolu_1\",\"name\":\"web_search\",\"input\":{\"query\":\"x\"}}}"));
        assertNull(
                codec.decodeChunk(
                        "content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"web_search_tool_result\",\"content\":[{\"type\":\"text\",\"text\":\"hits\"}]}}"));
        // unknown event names remain ignored (pre-existing tolerance)
        assertNull(codec.decodeChunk("some_future_event", "{\"type\":\"some_future_event\"}"));
    }

    @Test
    void extendedThinkingStreamDecodesToTextChunksOnly() {
        // content_block_start(thinking) → thinking_delta → signature_delta →
        // content_block_stop → text delta: the thinking frames are dropped, the text
        // survives, no exception (the pre-fix codec aborted on the first thinking_delta).
        assertNull(
                codec.decodeChunk(
                        "content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}"));
        assertNull(
                codec.decodeChunk(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Let me\"}}"));
        assertNull(
                codec.decodeChunk(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_1\"}}"));
        assertNull(codec.decodeChunk("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
        StreamChunk text = codec.decodeChunk(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Answer\"}}");
        assertEquals("Answer", text.choices().get(0).delta().content());
    }

    // ------------------------------------------------- usage merge

    @Test
    void statelessMessageStartUsageStaysOffTheChunk() {
        // The start's usage carries the real prompt count but is NOT emitted on the
        // message_start chunk — governance settles on the terminal usage-bearing chunk, so
        // emitting it would settle with completion=0 (the stateful decoder merges it onto
        // the message_delta chunk instead; see realShapedStreamAggregatesUsageOnTerminalChunk).
        StreamChunk chunk = codec.decodeChunk(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":25,\"output_tokens\":0}}}");
        assertEquals("msg_1", chunk.id());
        assertNull(chunk.usage(), "message_start chunk must not carry usage");
    }

    @Test
    void realShapedStreamAggregatesUsageOnTerminalChunk() {
        // A real Anthropic stream: message_start carries input_tokens (the prompt count),
        // message_delta carries output_tokens ONLY (never input_tokens). The per-stream
        // decoder merges them so the terminal chunk reports {prompt=25, completion=15,
        // total=40} — the pre-fix stateless decode reported promptTokens=0.
        AnthropicStreamDecoder decoder = codec.newStreamDecoder();
        StreamChunk role = decoder.decodeChunk(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":25,\"output_tokens\":0}}}");
        assertNull(role.usage(), "the opener chunk carries no usage (governance settles terminal)");
        assertNull(decoder.decodeChunk(
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        StreamChunk content = decoder.decodeChunk(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}");
        assertEquals("Hello", content.choices().get(0).delta().content());
        assertNull(decoder.decodeChunk("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
        StreamChunk terminal = decoder.decodeChunk(
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":15}}");
        assertEquals(
                new Usage(25, 15, 40), terminal.usage(), "prompt from message_start + completion from message_delta");
    }

    @Test
    void realShapedStreamMergesCacheFieldsAcrossStartAndDelta() {
        AnthropicStreamDecoder decoder = codec.newStreamDecoder();
        decoder.decodeChunk(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":0,"
                        + "\"cache_creation_input_tokens\":2,\"cache_read_input_tokens\":3}}}");
        StreamChunk terminal = decoder.decodeChunk(
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}");
        assertEquals(
                new Usage(10, 5, 20, 2L, 3L),
                terminal.usage(),
                "cache fields fall back from the start event; total = full input (10 + 2 + 3) + output");
    }

    @Test
    void composedAnthropicToAnthropicPassthroughWithTextToolsAndCache() {
        // Coverage: each direction is pinned, plus the composed aa passthrough
        // of a real-shaped Anthropic stream — text + an interleaved tool call + cache usage —
        // through stateful decoder → encoder. The decoder renumbers tool blocks into the
        // tool-only canonical index and merges usage from message_start + message_delta; the
        // encoder re-assigns Anthropic content-block indices and must re-emit the merged
        // usage on message_delta.
        String[][] frames = {
            {
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":25,\"output_tokens\":0,"
                        + "\"cache_creation_input_tokens\":2,\"cache_read_input_tokens\":3}}}"
            },
            {
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"
            },
            {
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Answer\"}}"
            },
            {"content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"},
            {
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{}}}"
            },
            {
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\","
                        + "\"partial_json\":\"{\\\"city\\\":\\\"Par\"}}"
            },
            {
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\","
                        + "\"partial_json\":\"is\\\"}\"}}"
            },
            {"content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}"},
            {
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":15}}"
            },
        };

        AnthropicStreamDecoder decoder = codec.newStreamDecoder();
        List<StreamChunk> canonical = new ArrayList<>();
        for (String[] frame : frames) {
            StreamChunk chunk = decoder.decodeChunk(frame[0], frame[1]);
            if (chunk != null) {
                canonical.add(chunk);
            }
        }

        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<AnthropicSseEvent> events = new ArrayList<>();
        for (StreamChunk chunk : canonical) {
            events.addAll(encoder.feed(chunk));
        }
        events.addAll(encoder.finish());

        // message_start re-emits the opener's id/model and the prompt-only usage — the
        // decoder's opener chunk is deliberately usage-free (governance settles on the
        // terminal chunk, so the real prompt count is NOT threaded to the opener; the
        // report's LOW #6 documents this as informational, no fix required).
        assertEquals("message_start", events.get(0).event());
        String opener = events.get(0).dataJson();
        assertTrue(opener.contains("\"id\":\"msg_1\""), opener);
        assertTrue(opener.contains("\"input_tokens\":0"), opener);
        // text + tool blocks re-open in order; the tool id/name survive the renumbering
        List<String> starts = events.stream()
                .filter(e -> e.event().equals("content_block_start"))
                .map(AnthropicSseEvent::dataJson)
                .toList();
        assertTrue(starts.get(0).contains("\"type\":\"text\""), starts.toString());
        assertTrue(starts.get(1).contains("\"id\":\"call_1\",\"name\":\"get_weather\""), starts.toString());
        // terminal message_delta carries the merged usage (prompt from message_start +
        // completion from message_delta + cache fields split across both)
        AnthropicSseEvent terminal = events.get(events.size() - 2);
        assertEquals("message_delta", terminal.event());
        assertEquals(
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"input_tokens\":25,\"output_tokens\":15,"
                        + "\"cache_creation_input_tokens\":2,\"cache_read_input_tokens\":3}}",
                terminal.dataJson());
        assertEquals("message_stop", events.get(events.size() - 1).event());
    }

    @Test
    void toolUseStartCarryingInputTreatsItAsFirstFragmentArguments() {
        // Per the fine-grained tool-streaming spec content_block_start.tool_use.input is
        // always {}; a non-conformant upstream that ships the full input in the start
        // block must still surface the arguments (defense-in-depth).
        StreamChunk chunk = codec.decodeChunk(
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":1,"
                        + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"Paris\"}}}");
        assertEquals(
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                ChatRole.ASSISTANT,
                                null,
                                List.of(new ToolCall(
                                        "toolu_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}"),
                                        1))),
                        null)),
                chunk.choices());
    }

    @Test
    void errorEventThrowsApiErrorWithPayloadMessage() {
        AnthropicCodecException e = assertThrows(
                AnthropicCodecException.class,
                () -> codec.decodeChunk(
                        "error",
                        "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));
        assertEquals(AnthropicCodecException.TYPE_API_ERROR, e.type());
        assertEquals("Overloaded", e.getMessage());
    }

    // ------------------------------------------------------------------ encode

    @Test
    void firstFeedOpensMessageAndBlock() {
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<AnthropicSseEvent> events = encoder.feed(new StreamChunk(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                null,
                Map.of()));
        assertEquals(2, events.size());
        assertEquals("message_start", events.get(0).event());
        assertEquals(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}",
                events.get(0).dataJson());
        assertEquals("content_block_start", events.get(1).event());
        assertEquals(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                events.get(1).dataJson());
    }

    @Test
    void openerCarryingUsageEmitsItOnMessageStart() {
        // messageStart must emit the canonical opener chunk's real usage (the
        // prompt side) instead of always re-emitting zeroed 0/0 — an aa passthrough of a
        // canonical stream that carries prompt tokens must preserve them on the outbound
        // wire. The fabricated "msg_start"/"unknown" sentinels are gone (blank id/model is
        // omitted, never leaked).
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<AnthropicSseEvent> events = encoder.feed(new StreamChunk(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                new Usage(14, 0, 14),
                Map.of()));
        assertEquals(2, events.size());
        assertEquals("message_start", events.get(0).event());
        assertEquals(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":14,\"output_tokens\":0}}}",
                events.get(0).dataJson());
        // the terminal message_delta carries the merged usage fed on the last chunk
        List<AnthropicSseEvent> terminal = encoder.finish();
        assertEquals("message_delta", terminal.get(1).event());
        assertEquals(
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"input_tokens\":14,\"output_tokens\":0}}",
                terminal.get(1).dataJson());
    }

    @Test
    void aaPassthroughPreservesMessageStartIdModelAndTerminalUsage() {
        // A fixture-shaped aa round trip: a canonical opener carrying a real id/model and
        // a terminal chunk carrying merged usage re-emit message_start with the same
        // id/model and message_delta with the same input/output tokens.
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<AnthropicSseEvent> opener = encoder.feed(new StreamChunk(
                "msg_01",
                "message",
                0L,
                "claude-3-5-sonnet",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                null,
                Map.of()));
        assertEquals("message_start", opener.get(0).event());
        assertTrue(
                opener.get(0).dataJson().contains("\"id\":\"msg_01\""),
                opener.get(0).dataJson());
        assertTrue(
                opener.get(0).dataJson().contains("\"model\":\"claude-3-5-sonnet\""),
                opener.get(0).dataJson());
        encoder.feed(new StreamChunk(
                null, null, 0L, null, List.of(new ChunkChoice(0, new Delta(null, "Hi", null), null)), null, Map.of()));
        List<AnthropicSseEvent> terminal = encoder.finish();
        assertEquals("message_delta", terminal.get(1).event());
    }

    @Test
    void contentDeltasEmitTextDeltas() {
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        encoder.feed(new StreamChunk(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                null,
                Map.of()));
        List<AnthropicSseEvent> events = encoder.feed(new StreamChunk(
                null, null, 0L, null, List.of(new ChunkChoice(0, new Delta(null, "Hel", null), null)), null, Map.of()));
        assertEquals(1, events.size());
        assertEquals("content_block_delta", events.get(0).event());
        assertEquals(
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}",
                events.get(0).dataJson());
    }

    @Test
    void finishEmitsTerminalSequence() {
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        encoder.feed(new StreamChunk(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                null,
                Map.of()));
        encoder.feed(new StreamChunk(
                null, null, 0L, null, List.of(new ChunkChoice(0, new Delta(null, "Hel", null), null)), null, Map.of()));
        encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(0, new Delta(null, null, null), "stop")),
                new Usage(10, 5, 15),
                Map.of()));
        List<AnthropicSseEvent> events = encoder.finish();
        assertEquals(3, events.size());
        assertEquals("content_block_stop", events.get(0).event());
        assertEquals(
                "{\"type\":\"content_block_stop\",\"index\":0}", events.get(0).dataJson());
        assertEquals("message_delta", events.get(1).event());
        assertEquals(
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}",
                events.get(1).dataJson());
        assertEquals("message_stop", events.get(2).event());
        assertEquals("{\"type\":\"message_stop\"}", events.get(2).dataJson());
    }

    @Test
    void finishWithoutOpenedBlockEmitsNothingWhenNothingToDeliver() {
        // A stream fed only null-delta chunks (no content ever
        // opened a block) must NOT emit message_delta + message_stop without the
        // message_start opener — that is an invalid Anthropic SSE sequence (a strict SDK
        // fails to accumulate). With no usage and no finish reason there is nothing to
        // deliver, so finish emits nothing; a usage-only feed still gets a synthesized
        // opener (see finishAfterUsageOnlyFeedSynthesizesMessageStart).
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        encoder.feed(new StreamChunk(null, null, 0L, null, List.of(new ChunkChoice(0, null, null)), null, Map.of()));
        assertEquals(List.of(), encoder.finish());
        assertEquals(List.of(), encoder.finish()); // stays idempotent
    }

    @Test
    void finishAfterUsageOnlyFeedSynthesizesMessageStart() {
        // A usage-only canonical chunk (choices == null, usage != null) must
        // be captured and delivered; finish synthesizes the missing message_start opener
        // so the terminal sequence is well-formed — never a message_delta first.
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        encoder.feed(new StreamChunk(null, null, 0L, null, null, new Usage(10, 5, 15), Map.of()));
        List<AnthropicSseEvent> events = encoder.finish();
        assertEquals(3, events.size());
        assertEquals("message_start", events.get(0).event());
        assertEquals(
                "{\"type\":\"message_start\",\"message\":{\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}",
                events.get(0).dataJson());
        assertEquals("message_delta", events.get(1).event());
        assertEquals(
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}",
                events.get(1).dataJson());
        assertEquals("message_stop", events.get(2).event());
    }

    @Test
    void nullDeltaTerminalChunkStillCarriesFinishReason() {
        // A terminal chunk with delta == null but finishReason set must
        // still drive the message_delta stop_reason — the null-delta guard used to skip
        // the capture, defaulting to "end_turn" instead of "tool_use".
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        encoder.feed(new StreamChunk(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                null,
                Map.of()));
        encoder.feed(new StreamChunk(
                null, null, 0L, null, List.of(new ChunkChoice(0, null, "tool_calls")), new Usage(10, 5, 15), Map.of()));
        List<AnthropicSseEvent> events = encoder.finish();
        assertEquals(3, events.size());
        assertEquals("content_block_stop", events.get(0).event());
        assertEquals("message_delta", events.get(1).event());
        assertEquals(
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}",
                events.get(1).dataJson());
        assertEquals("message_stop", events.get(2).event());
    }

    @Test
    void finishWithoutAnyFeedSynthesizesMessageStartSequence() {
        // finish on an encoder that never received a
        // feed must still yield a well-formed terminal sequence — the synthesized
        // message_start opener plus message_delta + message_stop — so the Anthropic face
        // never leaves an SSE wire bare with zero frames (a strict SDK fails to
        // accumulate on an empty stream). The opener carries no id/model (none were
        // seen) — omitted by NON_NULL, matching the usage-only synthetic opener.
        // finish stays idempotent.
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<AnthropicSseEvent> events = encoder.finish();
        assertEquals(3, events.size());
        assertEquals("message_start", events.get(0).event());
        assertEquals(
                "{\"type\":\"message_start\",\"message\":{\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[],\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}",
                events.get(0).dataJson());
        assertEquals("message_delta", events.get(1).event());
        assertEquals(
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}",
                events.get(1).dataJson());
        assertEquals("message_stop", events.get(2).event());
        assertEquals(List.of(), encoder.finish()); // stays idempotent
    }

    @Test
    void finishIsIdempotent() {
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        encoder.feed(new StreamChunk(
                "m",
                "message",
                0L,
                "c",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "hi", null), null)),
                null,
                Map.of()));
        assertFalse(encoder.finish().isEmpty());
        assertEquals(List.of(), encoder.finish());
    }

    @Test
    void toolCallFragmentsEmitToolUseBlockSequence() {
        // Canonical tool-call fragments (OpenAI-idiomatic: first fragment carries id+name,
        // continuations carry only partial arguments) map to a lazy tool_use block with
        // input_json_delta per fragment; the text block opened by the role chunk closes first.
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
        // role-only first chunk → message_start + lazily-created text block 0
        assertEquals(2, events.size());
        assertEquals("message_start", events.get(0).event());
        assertEquals("content_block_start", events.get(1).event());
        assertEquals(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                events.get(1).dataJson());

        events.clear();
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null,
                                null,
                                List.of(new ToolCall("call_1", "function", new FunctionCall("get_weather", ""), 0))),
                        null)),
                null,
                Map.of())));
        // new tool fragment → close text block 0, open tool_use block 1, emit the fragment
        assertEquals(3, events.size());
        assertEquals("content_block_stop", events.get(0).event());
        assertEquals(
                "{\"type\":\"content_block_stop\",\"index\":0}", events.get(0).dataJson());
        assertEquals("content_block_start", events.get(1).event());
        assertEquals(
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{}}}",
                events.get(1).dataJson());
        assertEquals("content_block_delta", events.get(2).event());
        assertEquals(
                "{\"type\":\"content_block_delta\",\"index\":1,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\"}}",
                events.get(2).dataJson());

        events.clear();
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null,
                                null,
                                List.of(new ToolCall(null, null, new FunctionCall(null, "{\"city\":\"Par"), 0))),
                        null)),
                null,
                Map.of())));
        // continuation fragment → input_json_delta verbatim, same block index
        assertEquals(1, events.size());
        assertEquals("content_block_delta", events.get(0).event());
        assertEquals(
                "{\"type\":\"content_block_delta\",\"index\":1,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"Par\"}}",
                events.get(0).dataJson());

        events.clear();
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(0, new Delta(null, null, null), "tool_calls")),
                new Usage(10, 5, 15, 2L, 3L),
                Map.of())));
        assertEquals(List.of(), events); // finishReason/usage captured, no events yet

        events.clear();
        events.addAll(encoder.finish());
        // finish → close the open tool block, message_delta(stop_reason "tool_use", usage), message_stop
        assertEquals(3, events.size());
        assertEquals("content_block_stop", events.get(0).event());
        assertEquals(
                "{\"type\":\"content_block_stop\",\"index\":1}", events.get(0).dataJson());
        assertEquals("message_delta", events.get(1).event());
        assertEquals(
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                        + "\"cache_creation_input_tokens\":2,\"cache_read_input_tokens\":3}}",
                events.get(1).dataJson());
        assertEquals("message_stop", events.get(2).event());
    }

    @Test
    void textAndToolBlocksInterleaveWithSequentialIndices() {
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<AnthropicSseEvent> events = new ArrayList<>();
        events.addAll(encoder.feed(new StreamChunk(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "Hel", null), null)),
                null,
                Map.of())));
        assertEquals(3, events.size()); // message_start + content_block_start(0 text) + text_delta

        events.clear();
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null,
                                null,
                                List.of(new ToolCall(
                                        "call_1", "function", new FunctionCall("get_weather", "{\"x\":1}"), 0))),
                        null)),
                null,
                Map.of())));
        // text block 0 closes; tool block opens at index 1
        assertEquals(
                "{\"type\":\"content_block_stop\",\"index\":0}", events.get(0).dataJson());
        assertEquals(
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{}}}",
                events.get(1).dataJson());

        events.clear();
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(0, new Delta(null, "done", null), null)),
                null,
                Map.of())));
        // text after a tool block reopens a text block at index 2
        assertEquals(3, events.size());
        assertEquals("content_block_stop", events.get(0).event());
        assertEquals(
                "{\"type\":\"content_block_stop\",\"index\":1}", events.get(0).dataJson());
        assertEquals("content_block_start", events.get(1).event());
        assertEquals(
                "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                events.get(1).dataJson());
        assertEquals("content_block_delta", events.get(2).event());
        assertEquals(
                "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}",
                events.get(2).dataJson());
    }

    @Test
    void repeatedIdContinuationFragmentDoesNotReopenToolBlock() {
        // Some OpenAI-compatible servers repeat the id on continuation
        // fragments. The pre-fix encoder treated any fragment carrying a non-blank id/name
        // as a brand-new tool, closing the open tool_use block and opening a second one with
        // the same id (duplicate-id tool_use blocks, invalid on the Anthropic wire). A
        // fragment carrying the open block's identity is a continuation: one
        // content_block_start total, one input_json_delta per fragment — never a second
        // content_block_start.
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<AnthropicSseEvent> events = new ArrayList<>();
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null,
                                null,
                                List.of(new ToolCall("call_1", "function", new FunctionCall("get_weather", ""), 0))),
                        null)),
                null,
                Map.of())));
        // first fragment (id + name) → message_start + content_block_start + first delta
        assertEquals(3, events.size());
        assertEquals("message_start", events.get(0).event());
        assertEquals("content_block_start", events.get(1).event());
        assertEquals(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{}}}",
                events.get(1).dataJson());

        events.clear();
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null,
                                null,
                                List.of(new ToolCall(
                                        "call_1", "function", new FunctionCall(null, "{\"city\":\"Par"), 0))),
                        null)),
                null,
                Map.of())));
        // continuation fragment repeating the id → exactly one input_json_delta, no new block
        assertEquals(1, events.size());
        assertEquals("content_block_delta", events.get(0).event());
        assertEquals(
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"Par\"}}",
                events.get(0).dataJson());

        events.clear();
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(
                        0,
                        new Delta(null, null, List.of(new ToolCall(null, null, new FunctionCall(null, "}"), 0))),
                        null)),
                null,
                Map.of())));
        // identity-less continuation → still the same block
        assertEquals(1, events.size());
        assertEquals("content_block_delta", events.get(0).event());
        assertEquals(
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"}\"}}",
                events.get(0).dataJson());

        events.clear();
        events.addAll(encoder.finish());
        // finish closes the single tool block once
        assertEquals(3, events.size());
        assertEquals(
                "{\"type\":\"content_block_stop\",\"index\":0}", events.get(0).dataJson());
        assertEquals("message_delta", events.get(1).event());
        assertEquals("message_stop", events.get(2).event());
    }

    @Test
    void differingIdContinuationFragmentStillOpensANewToolBlock() {
        // Control: the identity check must NOT collapse two genuinely
        // different tools — a fragment with a different id still closes the open block and
        // opens a new tool_use block with the new id.
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null,
                                null,
                                List.of(new ToolCall("call_1", "function", new FunctionCall("get_weather", ""), 0))),
                        null)),
                null,
                Map.of()));
        List<AnthropicSseEvent> events = encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null,
                                null,
                                List.of(new ToolCall("call_2", "function", new FunctionCall("get_stock", ""), 1))),
                        null)),
                null,
                Map.of()));
        assertEquals(3, events.size());
        assertEquals(
                "{\"type\":\"content_block_stop\",\"index\":0}", events.get(0).dataJson());
        assertEquals("content_block_start", events.get(1).event());
        assertEquals(
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"call_2\",\"name\":\"get_stock\",\"input\":{}}}",
                events.get(1).dataJson());
    }

    @Test
    void firstFragmentWithoutIdOrNameThenNamedFragmentReopensWithIdentity() {
        // Degenerate input pinned: a first tool fragment with neither id
        // nor name still opens a tool_use block (omitted id/name — Anthropic rejects such a
        // block, but there is no identity to do better with). A later fragment that names
        // the tool carries an identity the anonymous block never recorded, so it reopens
        // with the real id/name (they only reach the wire at block start).
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<AnthropicSseEvent> events = new ArrayList<>();
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(
                        0,
                        new Delta(null, null, List.of(new ToolCall(null, null, new FunctionCall(null, ""), 0)), null),
                        null)),
                null,
                Map.of())));
        assertEquals(3, events.size());
        assertEquals(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"input\":{}}}",
                events.get(1).dataJson());

        events.clear();
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null,
                                null,
                                List.of(new ToolCall(
                                        "call_1", "function", new FunctionCall("get_weather", "{\"x\":1}"), 0))),
                        null)),
                null,
                Map.of())));
        // the fragment's identity differs from the anonymous open block's (null/null) → the
        // block reopens with the real id/name, which now reach the wire
        assertEquals(3, events.size());
        assertEquals(
                "{\"type\":\"content_block_stop\",\"index\":0}", events.get(0).dataJson());
        assertEquals(
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"call_1\",\"name\":\"get_weather\",\"input\":{}}}",
                events.get(1).dataJson());
    }

    @Test
    void twoToolCallsGetSequentialBlockIndices() {
        // Tool-first stream: no role chunk — the first fragment opens the stream and
        // block 0 is a tool_use block (not the text default).
        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<AnthropicSseEvent> events = new ArrayList<>();
        events.addAll(encoder.feed(new StreamChunk(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null, null, List.of(new ToolCall("call_0", "function", new FunctionCall("f", ""), 0))),
                        null)),
                null,
                Map.of())));
        assertEquals(3, events.size());
        assertEquals("message_start", events.get(0).event());
        assertEquals("content_block_start", events.get(1).event());
        assertEquals(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"call_0\",\"name\":\"f\",\"input\":{}}}",
                events.get(1).dataJson());
        assertEquals(
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\"}}",
                events.get(2).dataJson());

        events.clear();
        events.addAll(encoder.feed(new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null, null, List.of(new ToolCall("call_1", "function", new FunctionCall("g", ""), 1))),
                        null)),
                null,
                Map.of())));
        // second tool: close block 0, open block 1
        assertEquals(3, events.size());
        assertEquals(
                "{\"type\":\"content_block_stop\",\"index\":0}", events.get(0).dataJson());
        assertEquals(
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"call_1\",\"name\":\"g\",\"input\":{}}}",
                events.get(1).dataJson());

        events.clear();
        events.addAll(encoder.finish());
        assertEquals(
                "{\"type\":\"content_block_stop\",\"index\":1}", events.get(0).dataJson());
        assertEquals("message_delta", events.get(1).event());
        assertEquals("message_stop", events.get(2).event());
    }
}
