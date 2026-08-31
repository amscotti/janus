package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.HostedToolDefinition;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.core.model.UserMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Responses stream encoder's event grammar:
 * created+in_progress prefix; message turns (content_part events); <b>function-call-only
 * turns emit no content_part events</b>; mixed turns; the terminal completed/incomplete
 * event with the full response object and usage; strictly monotonic sequence_number;
 * and the failure snapshot ({@code failed} builds the full object concurrently-safely).
 */
class OpenAiResponsesStreamEncoderTest {

    private final OpenAiResponsesCodec codec = OpenAiResponsesCodec.create();

    private static StreamChunk text(String s) {
        return new StreamChunk(
                "c1",
                "chat.completion.chunk",
                1L,
                "m",
                List.of(new ChunkChoice(0, new Delta(null, s, null), null)),
                null,
                Map.of());
    }

    private static StreamChunk toolFragment(int index, String id, String name, String args) {
        return new StreamChunk(
                "c1",
                "chat.completion.chunk",
                1L,
                "m",
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                null, null, List.of(new ToolCall(id, "function", new FunctionCall(name, args), index))),
                        null)),
                null,
                Map.of());
    }

    private static StreamChunk terminal(String finishReason, Usage usage) {
        return new StreamChunk(
                "c1",
                "chat.completion.chunk",
                1L,
                "m",
                List.of(new ChunkChoice(0, new Delta(null, null, null), finishReason)),
                usage,
                Map.of());
    }

    /** A content chunk carrying the upstream's own (served) model. */
    private static StreamChunk text(String model, String s) {
        return new StreamChunk(
                "c1",
                "chat.completion.chunk",
                1L,
                model,
                List.of(new ChunkChoice(0, new Delta(null, s, null), null)),
                null,
                Map.of());
    }

    private static List<String> types(List<OpenAiResponsesStreamEvent> events) {
        return events.stream().map(OpenAiResponsesStreamEvent::event).toList();
    }

    @Test
    void textTurnEmitsTheFullGrammar() {
        OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder = codec.newStreamEncoder(request());
        assertEquals(List.of("response.created", "response.in_progress"), types(encoder.initialEvents()));

        List<OpenAiResponsesStreamEvent> first = encoder.feed(text("Hel"));
        assertEquals(
                List.of("response.output_item.added", "response.content_part.added", "response.output_text.delta"),
                types(first));
        List<OpenAiResponsesStreamEvent> more = encoder.feed(text("lo"));
        assertEquals(List.of("response.output_text.delta"), types(more));

        List<OpenAiResponsesStreamEvent> done = encoder.finish();
        assertEquals(
                List.of(
                        "response.output_text.done",
                        "response.content_part.done",
                        "response.output_item.done",
                        "response.completed"),
                types(done));
        String completed = done.getLast().dataJson();
        assertTrue(completed.contains("\"status\":\"completed\""), completed);
        assertTrue(completed.contains("\"text\":\"Hello\""), completed);
        assertTrue(
                completed.contains("\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"total_tokens\":0}"), completed);
    }

    @Test
    void toolOnlyTurnEmitsNoContentPartEvents() {
        // The grammar correction from the plan's second review: a function-call-only
        // turn has no message item and therefore no content_part events.
        OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder = codec.newStreamEncoder(request());
        List<OpenAiResponsesStreamEvent> events = encoder.feed(toolFragment(0, "call_1", "get_weather", "{\"ci"));
        events.addAll(encoder.feed(toolFragment(0, null, null, "ty\":\"x\"}")));
        events.addAll(encoder.finish());

        List<String> types = types(events);
        assertFalse(types.contains("response.content_part.added"), String.valueOf(types));
        assertFalse(types.contains("response.output_text.delta"), String.valueOf(types));
        assertTrue(types.contains("response.output_item.added"), String.valueOf(types));
        assertTrue(types.contains("response.function_call_arguments.delta"), String.valueOf(types));
        assertTrue(types.contains("response.function_call_arguments.done"), String.valueOf(types));
        String completed = events.getLast().dataJson();
        assertTrue(completed.contains("\"type\":\"function_call\""), completed);
        assertTrue(completed.contains("\"call_id\":\"call_1\""), completed);
        assertTrue(completed.contains("\"arguments\":\"{\\\"city\\\":\\\"x\\\"}\""), completed);
    }

    @Test
    void usageFromTheTerminalChunkRidesTheCompletedEvent() {
        OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder = codec.newStreamEncoder(request());
        encoder.feed(text("hi"));
        encoder.feed(terminal(ChatResponse.STOP_REASON_STOP, new Usage(10, 5, 15)));
        List<OpenAiResponsesStreamEvent> done = encoder.finish();
        assertTrue(
                done.getLast()
                        .dataJson()
                        .contains("\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}"),
                done.getLast().dataJson());
    }

    @Test
    void lengthFinishMapsToTheIncompleteTerminalEvent() {
        OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder = codec.newStreamEncoder(request());
        encoder.feed(text("hi"));
        encoder.feed(terminal(ChatResponse.STOP_REASON_LENGTH, null));
        List<OpenAiResponsesStreamEvent> done = encoder.finish();
        assertEquals("response.incomplete", done.getLast().event());
        assertTrue(
                done.getLast().dataJson().contains("\"incomplete_details\":{\"reason\":\"max_output_tokens\"}"),
                done.getLast().dataJson());
    }

    @Test
    void hostileToolCallIndexIsTreatedAsSequentialNeverAnAllocationBomb() {
        // The canonical ToolCall.index rides the upstream chunk verbatim; a malformed
        // or hostile OpenAI-compatible upstream sending a huge (or negative) index must
        // not allocate a slot per index value (OOM/CPU spin on a public ingress path).
        // Non-contiguous jumps are treated as the next sequential tool call.
        OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder = codec.newStreamEncoder(request());
        List<OpenAiResponsesStreamEvent> events =
                encoder.feed(toolFragment(Integer.MAX_VALUE, "call_1", "get_weather", "{\"ci"));
        assertEquals(
                List.of("response.output_item.added", "response.function_call_arguments.delta"),
                types(events),
                "one item for the fragment - no per-index slot allocation");
        // a well-formed continuation of tool 0 still merges into slot 0
        assertEquals(
                List.of("response.function_call_arguments.delta"),
                types(encoder.feed(toolFragment(0, null, null, "ty\":\"x\"}"))));
        // a negative jump opens the next sequential tool, never slot -5
        assertEquals(
                List.of("response.output_item.added", "response.function_call_arguments.delta"),
                types(encoder.feed(toolFragment(-5, "call_2", "ping", "{}"))));
        List<OpenAiResponsesStreamEvent> done = encoder.finish();
        String completed = done.getLast().dataJson();
        assertTrue(completed.contains("\"call_id\":\"call_1\""), completed);
        assertTrue(completed.contains("\"arguments\":\"{\\\"city\\\":\\\"x\\\"}\""), completed);
        assertTrue(completed.contains("\"call_id\":\"call_2\""), completed);
    }

    @Test
    void streamedEchoCarriesHostedWebSearchToolAndTheServedModel() {
        // The stream echo must match the non-streaming encodeResponse contract: the
        // tools array includes the hosted web_search entry (the ra leg — the only leg
        // serving hosted search), and the terminal completed object reports the SERVED
        // model captured from the fed chunks (the canonical chunk model) instead of the
        // requested alias. The created/in_progress prefix — emitted before any chunk —
        // echoes the alias (nothing has been served yet).
        OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder = codec.newStreamEncoder(hostedWebSearchRequest());
        String created = encoder.initialEvents().getFirst().dataJson();
        assertTrue(created.contains("\"model\":\"alias\""), created);
        assertTrue(
                created.contains("\"tools\":[{\"type\":\"web_search\",\"search_context_size\":\"medium\""),
                "the created echo carries the hosted entry: " + created);
        encoder.feed(text("served-m", "hi"));
        String completed = encoder.finish().getLast().dataJson();
        assertTrue(completed.contains("\"model\":\"served-m\""), completed);
        assertTrue(
                completed.contains("\"tools\":[{\"type\":\"web_search\",\"search_context_size\":\"medium\""),
                completed);
    }

    @Test
    void toolFragmentsBeforeContentDeltasKeepOutputIndicesMonotonic() {
        // An upstream that streams tool_calls fragments before any content delta must
        // not emit the message item's response.output_item.added with a LOWER
        // output_index after higher ones (strict Responses SDKs reject non-monotonic
        // indices): the message item's output index is allocated sequentially on first
        // open — here fc item 0 opens first, the message item then opens at 1 — and
        // every message-scoped event (content_part, text delta/done, item done) carries
        // that allocated index.
        OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder = codec.newStreamEncoder(request());
        List<OpenAiResponsesStreamEvent> toolEvents = encoder.feed(toolFragment(0, "call_1", "get_weather", "{\"ci"));
        assertEquals("response.output_item.added", toolEvents.get(0).event());
        assertTrue(
                toolEvents.get(0).dataJson().contains("\"output_index\":0"),
                toolEvents.get(0).dataJson());

        List<OpenAiResponsesStreamEvent> textEvents = encoder.feed(text("lo"));
        assertEquals(
                List.of("response.output_item.added", "response.content_part.added", "response.output_text.delta"),
                types(textEvents));
        for (OpenAiResponsesStreamEvent event : textEvents) {
            assertTrue(event.dataJson().contains("\"output_index\":1"), event.dataJson());
        }

        List<OpenAiResponsesStreamEvent> done = encoder.finish();
        // text.done, content_part.done, message item.done (index 1), then the fc
        // item's arguments.done + item.done (index 0 — allocated first, closed last).
        assertEquals("response.output_item.done", done.get(2).event());
        assertTrue(
                done.get(2).dataJson().contains("\"output_index\":1"),
                done.get(2).dataJson());
        assertEquals("response.output_item.done", done.get(4).event());
        assertTrue(
                done.get(4).dataJson().contains("\"output_index\":0"),
                done.get(4).dataJson());
    }

    @Test
    void sequenceNumbersAreStrictlyMonotonicAcrossAllEvents() {
        OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder = codec.newStreamEncoder(request());
        long last = -1;
        for (OpenAiResponsesStreamEvent event : encoder.initialEvents()) {
            last = assertIncreasing(event, last);
        }
        for (OpenAiResponsesStreamEvent event : encoder.feed(text("a"))) {
            last = assertIncreasing(event, last);
        }
        for (OpenAiResponsesStreamEvent event : encoder.feed(toolFragment(0, "c", "f", "x"))) {
            last = assertIncreasing(event, last);
        }
        for (OpenAiResponsesStreamEvent event : encoder.finish()) {
            last = assertIncreasing(event, last);
        }
    }

    @Test
    void failedBuildsAFullResponseObjectFromTheSnapshot() {
        // Thread-safety discipline: failed reads only the immutable snapshot —
        // callable from the watchdog thread while the worker sits mid-feed.
        // Callable before any feed too: the initial snapshot (no items, no usage, no
        // served model) must build the failed object, never trip the snapshot shape.
        OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder = codec.newStreamEncoder(request());
        OpenAiResponsesStreamEvent beforeFeed = encoder.failed(new RuntimeException("died early"));
        assertEquals("response.failed", beforeFeed.event());
        assertTrue(beforeFeed.dataJson().contains("\"model\":\"m\""), beforeFeed.dataJson());
        encoder.feed(text("partial"));
        OpenAiResponsesStreamEvent failed = encoder.failed(new RuntimeException("upstream died"));
        assertEquals("response.failed", failed.event());
        assertTrue(failed.dataJson().contains("\"status\":\"failed\""), failed.dataJson());
        assertTrue(failed.dataJson().contains("\"error\":{\"code\":\"server_error\""), failed.dataJson());
        assertTrue(
                failed.dataJson().contains("\"text\":\"partial\""),
                "snapshot carries the partial item: " + failed.dataJson());
        assertTrue(failed.dataJson().contains("\"sequence_number\""), failed.dataJson());
    }

    private static long assertIncreasing(OpenAiResponsesStreamEvent event, long last) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"sequence_number\\\":(\\d+)")
                .matcher(event.dataJson());
        assertTrue(matcher.find(), event.dataJson());
        long seq = Long.parseLong(matcher.group(1));
        assertTrue(seq > last, "sequence must strictly increase: " + seq + " after " + last);
        return seq;
    }

    private static io.amscotti.janus.core.model.ChatRequest request() {
        return new io.amscotti.janus.core.model.ChatRequest(
                "m",
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
    }

    /**
     * A request that requested alias {@code alias} and carries a hosted web_search
     * definition — the ra-leg shape (the only leg that serves hosted search).
     */
    private static io.amscotti.janus.core.model.ChatRequest hostedWebSearchRequest() {
        return new io.amscotti.janus.core.model.ChatRequest(
                "alias",
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
                List.of(new HostedToolDefinition.WebSearch("medium", null)),
                null,
                null);
    }
}
