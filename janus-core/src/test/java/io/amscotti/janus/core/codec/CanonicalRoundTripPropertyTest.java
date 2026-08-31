package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property/round-trip idempotence over a bounded combinatorial generator
 * of canonical shapes (JUnit 5 {@code @ParameterizedTest} only; janus-core stays
 * dependency-free — no property library): every shape must round-trip
 * {@code canonical → wire → canonical} with <b>record equality</b> through both codecs,
 * and the format-neutral shapes additionally survive the cross-format composition
 * {@code canonical → wireA → canonical → wireB → canonical}.
 *
 * <p><b>Documented non-idempotences excluded (the codec javadocs are the source of
 * truth):</b> multi-choice responses (Anthropic collapses into one joined choice — the
 * generator emits single-choice shapes), {@code system} + {@code SystemMessage}
 * duplication (the canonical home is the {@code system} field — no {@code SystemMessage}
 * in {@code messages}), OpenAI-only request fields ({@code seed}, {@code n},
 * {@code frequencyPenalty}, {@code presencePenalty}, {@code logitBias},
 * {@code responseFormat} — dropped on the Anthropic wire, so the cross-format list
 * excludes them; the single-codec round-trips keep them). Anthropic-sourced usage
 * (regular prompt + additive cache total) restores the full input count on the OpenAI
 * wire — the usage record round-trips with equality (asserted in {@link
 * #anthropicSourcedCacheUsageRestoresFullInputOnTheOpenAiWire}). Response {@code object}/
 * {@code created} are format-mutating by design (Anthropic wire {@code type} →
 * canonical {@code object}, {@code created} → 0): the round-trips use the per-format
 * canonical shapes, and the golden matrix documents the cross-format behavior.
 */
class CanonicalRoundTripPropertyTest {

    private final OpenAiMessageCodec openAi = new OpenAiMessageCodec(JsonSupport.mapper());
    private final AnthropicMessageCodec anthropic = new AnthropicMessageCodec(JsonSupport.mapper());

    // ----------------------------------------------------------------- requests

    static Stream<ChatRequest> requests() {
        Map<String, Object> functionChoice = new HashMap<>();
        functionChoice.put("type", "function");
        Map<String, Object> function = new HashMap<>();
        function.put("name", "get_weather");
        functionChoice.put("function", function);

        Map<String, Object> extras = new HashMap<>();
        extras.put("custom", "pass-through");
        extras.put("null_field", null);

        return Stream.of(
                // plain
                new ChatRequest(
                        "model-1",
                        List.of(new UserMessage("what is the weather in Paris?")),
                        "be brief",
                        null,
                        null,
                        0.7,
                        0.9,
                        null, // topK — dropped on the OpenAI egress (strict OpenAI 400s top_k)
                        512,
                        List.of("END"),
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
                        null),
                // streaming + stream_options
                new ChatRequest(
                        "model-1",
                        List.of(new UserMessage("what is the weather in Paris?")),
                        "be brief",
                        null,
                        null,
                        null,
                        null,
                        null,
                        512,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        Map.of("include_usage", true),
                        null,
                        null,
                        null,
                        null),
                // tools + tool_choice "none"
                new ChatRequest(
                        "model-1",
                        List.of(new UserMessage("what is the weather in Paris?")),
                        "be brief",
                        List.of(new ToolDefinition(
                                "function",
                                "get_weather",
                                "current weather in a city",
                                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")),
                        "none",
                        null,
                        null,
                        null,
                        512,
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
                        null),
                // multi-turn tools + tool_choice function object + extras
                new ChatRequest(
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
                        "be brief",
                        List.of(new ToolDefinition(
                                "function",
                                "get_weather",
                                null,
                                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")),
                        functionChoice,
                        null,
                        null,
                        null,
                        512,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        Map.of("include_usage", true),
                        null,
                        null,
                        extras,
                        null),
                // OpenAI-only fields (single-codec round-trips only — Anthropic drops them)
                new ChatRequest(
                        "model-1",
                        List.of(new UserMessage("what is the weather in Paris?")),
                        "be brief",
                        null,
                        "auto",
                        0.3,
                        0.8,
                        null, // topK — dropped on the OpenAI egress (strict OpenAI 400s top_k)
                        256,
                        List.of("STOP"),
                        42L,
                        2,
                        0.5,
                        0.2,
                        Map.of("token", 1.0),
                        Map.of("type", "text"),
                        false,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    @ParameterizedTest
    @MethodSource("requests")
    void requestRoundTripsThroughTheOpenAiWire(ChatRequest request) {
        assertEquals(request, openAi.decodeRequest(openAi.encodeRequest(request)));
    }

    /**
     * Anthropic round-trip: format-neutral shapes only — the OpenAI-only fields
     * ({@code seed}/{@code n}/{@code frequencyPenalty}/{@code presencePenalty}/
     * {@code logitBias}/{@code responseFormat}) have no Anthropic wire home and are
     * dropped on encode (documented non-idempotence #5 — the codec javadoc).
     */
    @ParameterizedTest
    @MethodSource("anthropicRoundTrippableRequests")
    void requestRoundTripsThroughTheAnthropicWire(ChatRequest request) {
        assertEquals(request, anthropic.decodeRequest(anthropic.encodeRequest(request)));
    }

    /** Format-neutral shapes (no OpenAI-only fields) — full cross-format composition. */
    static Stream<ChatRequest> formatNeutralRequests() {
        return requests()
                .filter(request -> request.seed() == null
                        && request.n() == null
                        && request.frequencyPenalty() == null
                        && request.presencePenalty() == null
                        && request.logitBias() == null
                        && request.responseFormat() == null);
    }

    /**
     * Shapes that round-trip through the Anthropic wire: format-neutral AND without
     * stream_options — D1 made streamOptions an Anthropic-wire non-idempotence
     * (Anthropic has no stream_options; the codec drops the canonical's flag on
     * Anthropic encode, pinned by {@link #streamOptionsAreDroppedOnTheAnthropicWireAsDocumented}).
     */
    static Stream<ChatRequest> anthropicRoundTrippableRequests() {
        return formatNeutralRequests().filter(request -> request.streamOptions() == null);
    }

    @ParameterizedTest
    @MethodSource("anthropicRoundTrippableRequests")
    void crossFormatCompositionIsIdempotentForRequests(ChatRequest request) {
        // canonical → OpenAI wire → canonical → Anthropic wire → canonical (record equality
        // at every hop — the inline matrix made this exhaustive for tools).
        ChatRequest viaOpenAi = openAi.decodeRequest(openAi.encodeRequest(request));
        assertEquals(request, viaOpenAi);
        assertEquals(request, anthropic.decodeRequest(anthropic.encodeRequest(viaOpenAi)));
        // and the reverse order
        ChatRequest viaAnthropic = anthropic.decodeRequest(anthropic.encodeRequest(request));
        assertEquals(request, viaAnthropic);
        assertEquals(request, openAi.decodeRequest(openAi.encodeRequest(viaAnthropic)));
    }

    // ---------------------------------------------------------------- responses

    /** OpenAI-shaped canonical responses (object "chat.completion", real created). */
    static Stream<ChatResponse> openAiResponses() {
        return Stream.of(
                new ChatResponse(
                        "chatcmpl-1",
                        "chat.completion",
                        1700000000L,
                        "model-1",
                        List.of(new ChatChoice(0, new AssistantMessage("It is 18C.", null), "stop")),
                        new Usage(10, 5, 15),
                        ChatResponse.STOP_REASON_STOP,
                        null,
                        null),
                new ChatResponse(
                        "chatcmpl-2",
                        "chat.completion",
                        1700000001L,
                        "model-1",
                        List.of(new ChatChoice(
                                0,
                                new AssistantMessage(
                                        "checking",
                                        List.of(new ToolCall(
                                                "call_1",
                                                "function",
                                                new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                                "tool_calls")),
                        new Usage(10, 5, 15),
                        ChatResponse.STOP_REASON_TOOL_CALLS,
                        extrasWithNull("system_fingerprint", "fp_x", "logprobs", null),
                        null));
    }

    /** Anthropic-shaped canonical responses (object "message", created 0). */
    static Stream<ChatResponse> anthropicResponses() {
        return openAiResponses()
                .map(response -> new ChatResponse(
                        response.id(),
                        "message",
                        0L,
                        response.model(),
                        response.choices(),
                        response.usage(),
                        response.stopReason(),
                        response.extras(),
                        response.meta()));
    }

    @ParameterizedTest
    @MethodSource("openAiResponses")
    void responseRoundTripsThroughTheOpenAiWire(ChatResponse response) {
        assertEquals(response, openAi.decodeResponse(openAi.encodeResponse(response)));
    }

    @ParameterizedTest
    @MethodSource("anthropicResponses")
    void responseRoundTripsThroughTheAnthropicWire(ChatResponse response) {
        assertEquals(response, anthropic.decodeResponse(anthropic.encodeResponse(response)));
    }

    @org.junit.jupiter.api.Test
    void anthropicSourcedCacheUsageRestoresFullInputOnTheOpenAiWire() {
        // Anthropic decode: promptTokens is REGULAR input (input_tokens excludes the
        // additive cache counts) and the total is derived as full input + output, so
        // the canonical satisfies prompt + completion + cache == total — the restore
        // invariant. The OpenAI encode therefore counts the FULL input inside
        // prompt_tokens (cached subsets of it in the details split), and the OpenAI
        // decode splits it back: the Anthropic-produced usage round-trips the
        // OpenAI wire with record equality (15 input tokens fully accounted).
        ChatResponse anthropicShaped = anthropic.decodeResponse(
                "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"Hello!\"}],\"stop_reason\":\"end_turn\","
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
                        + "\"cache_creation_input_tokens\":2,\"cache_read_input_tokens\":3}}");
        assertEquals(new Usage(10, 5, 20, 2L, 3L), anthropicShaped.usage());
        String viaOpenAiWire = openAi.encodeResponse(anthropicShaped);
        assertTrue(viaOpenAiWire.contains("\"prompt_tokens\":15"), viaOpenAiWire);
        assertTrue(viaOpenAiWire.contains("\"total_tokens\":20"), viaOpenAiWire);
        assertTrue(viaOpenAiWire.contains("\"cached_tokens\":3"), viaOpenAiWire);
        // `object` is format-mutating by design (Anthropic "message" vs OpenAI
        // "chat.completion" — see the class javadoc), so the cross-format comparison
        // pins the USAGE record: the Anthropic-produced canonical survives the OpenAI
        // wire with all six usage fields intact (15 input tokens fully accounted).
        assertEquals(
                anthropicShaped.usage(),
                openAi.decodeResponse(viaOpenAiWire).usage(),
                "the Anthropic-produced usage survives the OpenAI wire with record equality");
        assertEquals(anthropicShaped, anthropic.decodeResponse(anthropic.encodeResponse(anthropicShaped)));
    }

    @org.junit.jupiter.api.Test
    void streamOptionsAreDroppedOnTheAnthropicWireAsDocumented() {
        // D1 (blessed fix): Anthropic has no stream_options — the canonical's
        // streamOptions (OpenAI-idiomatic) must not reach the Anthropic wire. This is
        // now a documented Anthropic-wire non-idempotence (like the OpenAI-only fields):
        // the OpenAI leg keeps the flag, the Anthropic leg drops it.
        ChatRequest withStreamOptions = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                512,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                Map.of("include_usage", true),
                null,
                null,
                Map.of(),
                null);
        ChatRequest viaAnthropic = anthropic.decodeRequest(anthropic.encodeRequest(withStreamOptions));
        assertNull(viaAnthropic.streamOptions(), "stream_options must be dropped on the Anthropic wire");
        assertEquals(true, viaAnthropic.stream(), "the stream flag itself still round-trips");
        // The OpenAI wire keeps them — the canonical round-trips there.
        assertEquals(
                withStreamOptions,
                openAi.decodeRequest(openAi.encodeRequest(withStreamOptions)),
                "OpenAI wire keeps stream_options (the canonical home)");
    }

    // ------------------------------------------------------------------- chunks

    /** OpenAI-shaped canonical chunks (id/object/created/model present). */
    static Stream<StreamChunk> openAiChunks() {
        return Stream.of(
                new StreamChunk(
                        "chatcmpl-1",
                        "chat.completion.chunk",
                        1700000000L,
                        "model-1",
                        List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "", null), null)),
                        null,
                        Map.of()),
                new StreamChunk(
                        "chatcmpl-1",
                        "chat.completion.chunk",
                        1700000000L,
                        "model-1",
                        List.of(new ChunkChoice(0, new Delta(null, "It is", null), null)),
                        null,
                        Map.of()),
                new StreamChunk(
                        "chatcmpl-1",
                        "chat.completion.chunk",
                        1700000000L,
                        "model-1",
                        List.of(new ChunkChoice(0, new Delta(null, null, null), "stop")),
                        null,
                        Map.of()),
                new StreamChunk(
                        "chatcmpl-1",
                        "chat.completion.chunk",
                        1700000000L,
                        "model-1",
                        List.of(),
                        new Usage(10, 5, 15),
                        Map.of()));
    }

    @ParameterizedTest
    @MethodSource("openAiChunks")
    void chunkRoundTripsThroughTheOpenAiWire(StreamChunk chunk) {
        assertEquals(chunk, openAi.decodeChunk(openAi.encodeChunk(chunk)));
    }

    /** Anthropic-shaped canonical chunk sequence (the -pinned encoder round-trip). */
    static Stream<List<StreamChunk>> anthropicChunkSequences() {
        // role + text delta + merged terminal — the encoder passes content chunks through
        // in order and re-emits the merged terminal from message_delta. A sequence must
        // start with a role chunk (message_start supplies the stream identity); the
        // standalone-terminal case opens the stream with the encoder's default identity
        // and is documented, not asserted here (the golden matrix pins the full stream).
        return Stream.of(List.of(
                new StreamChunk(
                        "msg_1",
                        "message",
                        0L,
                        "claude-3",
                        List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                        null,
                        Map.of()),
                new StreamChunk(
                        null,
                        null,
                        0L,
                        null,
                        List.of(new ChunkChoice(0, new Delta(null, "It is", null), null)),
                        null,
                        Map.of()),
                new StreamChunk(
                        null,
                        null,
                        0L,
                        null,
                        List.of(new ChunkChoice(0, new Delta(null, null, null), "stop")),
                        new Usage(10, 5, 15),
                        Map.of())));
    }

    @ParameterizedTest
    @MethodSource("anthropicChunkSequences")
    void chunkSequenceRoundTripsThroughTheAnthropicEncoder(List<StreamChunk> sequence) throws Exception {
        AnthropicStreamEncoder encoder = anthropic.newStreamEncoder();
        List<StreamChunk> decoded = new java.util.ArrayList<>();
        for (StreamChunk chunk : sequence) {
            encoder.feed(chunk).forEach(event -> {
                StreamChunk d = anthropic.decodeChunk(event.event(), event.dataJson());
                if (d != null) {
                    decoded.add(d);
                }
            });
        }
        encoder.finish().forEach(event -> {
            StreamChunk d = anthropic.decodeChunk(event.event(), event.dataJson());
            if (d != null) {
                decoded.add(d);
            }
        });
        // The encoder is terminal-adding : content chunks pass through in
        // order and the merged terminal chunk re-emerges from message_delta — but only
        // when the sequence actually fed content (a zero-feed stream synthesizes the
        // opener→delta→stop sequence with no content chunks, so the round-trip is still
        // the empty sequence).
        assertEquals(sequence, decoded);
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> extrasWithNull(String key, String value, String nullKey, Object nullValue) {
        Map<String, Object> extras = new HashMap<>();
        extras.put(key, value);
        extras.put(nullKey, nullValue);
        return extras;
    }
}
