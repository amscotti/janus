package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The golden matrix replay: direction pairs (face wire × upstream wire:
 * {@code oo} {@code oa} {@code ao} {@code aa}) × 3 modes (plain, tools, streaming),
 * asserting <b>byte-level wire correctness</b> of every translation leg against the
 * committed {@code fixtures/matrix/} corpus.
 *
 * <p>Per cell: (1) {@code faceCodec.decodeRequest(inbound.request.json)} equals the
 * canonical request (record equality — the canonical is read from
 * {@code canonical/*.request.json} with {@link JsonSupport#mapper}); (2)
 * {@code upstreamCodec.encodeRequest(canonical)} is byte-identical to
 * {@code outbound.request.json} (stream mode: {@code stream:true} present,
 * {@code stream_options.include_usage} on OpenAI-outbound streams, {@code max_tokens}
 * 4096 on Anthropic-outbound — the codec default the canonical carries explicitly —
 * and <b>no</b> {@code stream_options} on Anthropic-outbound streams ( D1: real
 * Anthropic rejects the field; the codec drops the canonical's streamOptions on
 * Anthropic encode), see (5)); (3)
 * the upstream response decodes to the canonical response (OpenAI upstreams: literal
 * record equality; Anthropic upstreams: the documented format fields
 * {@code object="message"}, {@code created=0}, upstream id, no extras — the rest
 * record-equal) and the face outbound response is byte-identical to
 * {@code outbound.response.json}; (4) stream cells split the upstream SSE into
 * {@code (event, data)} frames and assert the decoded chunks (OpenAI: exactly the
 * canonical 8; Anthropic: the documented 7-frame merge of the canonical finish+usage
 * chunks), then emit the face-wire stream and assert {@code outbound.stream.sse}
 * frame-by-frame byte-exact — Anthropic face streams end {@code message_stop} with
 * <b>no {@code [DONE]}</b>, OpenAI face streams end {@code data: [DONE]}; (5) the
 * passthrough cells ({@code oo}, {@code aa}) pin precisely what Janus changes: on the
 * committed shapes the outbound request is byte-identical to the inbound request, and
 * the {@code aa} stream outbound is byte-identical to its upstream <em>except the two
 * usage frames</em> (the passthrough: a real-shaped upstream
 * carries the prompt count on {@code message_start} and {@code output_tokens} only on
 * {@code message_delta}, while the encoder re-emits zeroed 0/0 message_start usage and
 * the merged prompt-preserving message_delta usage — asserted frame-by-frame); the
 * {@code oo}
 * stream outbound differs from the upstream corpus only by the documented explicit-null
 * stripping ({@code finish_reason: null} is omitted on re-encode —
 * {@code @JsonInclude(NON_NULL)}), asserted semantically. The stream-request legs
 * pin the D1 fix: the canonical carries {@code stream_options.include_usage}
 * (OpenAI-idiomatic — the convention) but the codec drops it on Anthropic-outbound
 * encode, so {@code oa}/{@code aa} stream requests carry <b>no</b>
 * {@code stream_options} (real Anthropic rejects unknown request fields; the strict
 * gate fake pins the same schema validation).
 */
class GoldenMatrixTest {

    private static final String ROOT = "/fixtures/matrix";

    /** Direction pairs (face × upstream). */
    private static final List<String> DIRS = List.of("oo", "oa", "ao", "aa");

    private static final List<String> MODES = List.of("plain", "tools", "stream");

    /** The anthropic upstream response id (the corpus's synthetic identifier). */
    private static final String ANTHROPIC_RESPONSE_ID = "msg_9d8c7b6a5f4e3d2c1b0a9f8e7d6c5b4a";

    private final OpenAiMessageCodec openAi = new OpenAiMessageCodec(JsonSupport.mapper());
    private final AnthropicMessageCodec anthropic = new AnthropicMessageCodec(JsonSupport.mapper());

    @Test
    void everyCellReplaysByteForByte() throws Exception {
        for (String dir : DIRS) {
            for (String mode : MODES) {
                assertCell(dir, mode);
            }
        }
    }

    /**
     * The {@code ro}/{@code ra} legs — the Responses face's
     * golden replay against the SAME canonical artifacts and the SAME upstream legs as
     * the chat faces (the cross-face proof: one canonical, three wires). Inbound is
     * hand-authored corpus (the face is decode-only for requests); the outbound request
     * + upstream legs are byte-copies of the {@code oo}/{@code oa} cells; the outbound
     * response/stream are the Responses encode, pinned byte-exact (stream via the
     * deterministic-clock encoder — {@code created_at} is the matrix clock).
     */
    @Test
    void responsesLegsReplayByteForByte() throws Exception {
        OpenAiResponsesCodec responses = OpenAiResponsesCodec.create();
        java.util.function.LongSupplier matrixClock = () -> MatrixCanonicals.CREATED * 1000L;
        for (String dir : java.util.List.of("ro", "ra")) {
            String sibling = dir.equals("ro") ? "oo" : "oa";
            for (String mode : MODES) {
                // (1) the inbound decodes to the canonical (record equality).
                String canonicalJson = read("/fixtures/matrix/canonical/" + mode + ".request.json");
                ChatRequest canonical = JsonSupport.mapper().readValue(canonicalJson, ChatRequest.class);
                assertEquals(
                        canonical,
                        responses.decodeRequest(read(cell(dir, mode, "inbound.request.json"))),
                        dir + "/" + mode + " inbound decode");

                // (2) the outbound request is byte-identical to the sibling face's
                // (same canonical → same upstream encode — the matrix's core claim).
                assertEquals(
                        read(cell(sibling, mode, "outbound.request.json")),
                        read(cell(dir, mode, "outbound.request.json")),
                        dir + "/" + mode + " outbound request matches the sibling face");

                // (3) the upstream legs are byte-copies of the sibling's.
                String upstreamFile = mode.equals("stream") ? "upstream.stream.sse" : "upstream.response.json";
                assertEquals(
                        read(cell(sibling, mode, upstreamFile)), read(cell(dir, mode, upstreamFile)), dir + "/" + mode);

                if (mode.equals("stream")) {
                    // (4) the encoder reproduces the committed event stream frame-exact.
                    List<SseTestFrames.SseFrame> expected =
                            SseTestFrames.frames(read(cell(dir, mode, "outbound.stream.sse")));
                    OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder =
                            responses.newStreamEncoder(canonical, matrixClock);
                    List<SseTestFrames.SseFrame> actual = new ArrayList<>();
                    for (var event : encoder.initialEvents()) {
                        actual.add(new SseTestFrames.SseFrame(event.event(), event.dataJson()));
                    }
                    for (StreamChunk chunk : MatrixCanonicals.streamChunks()) {
                        for (var event : encoder.feed(chunk)) {
                            actual.add(new SseTestFrames.SseFrame(event.event(), event.dataJson()));
                        }
                    }
                    for (var event : encoder.finish()) {
                        actual.add(new SseTestFrames.SseFrame(event.event(), event.dataJson()));
                    }
                    assertEquals(expected, actual, dir + "/stream event stream");
                    assertEquals(
                            "response.completed",
                            expected.getLast().event(),
                            "the terminal event is response.completed");
                } else {
                    // (4) the response encode is byte-exact (request-aware echo).
                    String canonicalResponseJson = read("/fixtures/matrix/canonical/" + mode + ".response.json");
                    ChatResponse response = JsonSupport.mapper().readValue(canonicalResponseJson, ChatResponse.class);
                    assertEquals(
                            read(cell(dir, mode, "outbound.response.json")),
                            responses.encodeResponse(canonical, response),
                            dir + "/" + mode + " outbound response");
                }
            }
        }
    }

    @Test
    void passthroughCellsPinExactlyWhatJanusChanges() throws Exception {
        // oo + aa: the outbound request is byte-identical to the inbound request for all
        // three modes — Janus canonicalizes and re-emits; on the committed shapes the
        // canonicalized request bytes are the wire bytes (the matrix README documents the
        // canonicalized shape, incl. stream_options: the / adapter only preserves
        // request.streamOptions on the streaming path — it never injects include_usage —
        // so the oo/aa stream cells document a client that already requested it, and the
        // codec passes the canonical flag through to the wire).
        for (String mode : MODES) {
            assertEquals(
                    read(cell("oo", mode, "inbound.request.json")),
                    read(cell("oo", mode, "outbound.request.json")),
                    "oo/" + mode + " request passthrough");
            assertEquals(
                    read(cell("aa", mode, "inbound.request.json")),
                    read(cell("aa", mode, "outbound.request.json")),
                    "aa/" + mode + " request passthrough");
        }
        // aa stream: the outbound is byte-identical to its upstream EXCEPT the two usage
        // frames (documented in the matrix README): message_start — the upstream
        // (a real-shaped corpus stream) carries the prompt count
        // {"input_tokens":14,"output_tokens":0} while the encoder re-emits zeroed 0/0 (the
        // canonical opener chunk carries no usage — the merge delivers the prompt side
        // on the terminal chunk); message_delta — the upstream carries output_tokens only
        // (a real Anthropic terminal event) while the encoder emits the merged
        // {"input_tokens":14,"output_tokens":12} (prompt preserved). Everything else is
        // frame-by-frame byte-identical.
        List<SseTestFrames.SseFrame> aaUpstream =
                SseTestFrames.frames(read(cell("aa", "stream", "upstream.stream.sse")));
        List<SseTestFrames.SseFrame> aaOutbound =
                SseTestFrames.frames(read(cell("aa", "stream", "outbound.stream.sse")));
        assertEquals(aaUpstream.size(), aaOutbound.size(), "aa stream frame counts must match");
        for (int i = 0; i < aaUpstream.size(); i++) {
            if (i == 0) {
                // message_start: identical modulo the nested usage frame.
                assertEquals("message_start", aaOutbound.get(i).event(), "aa frame 0 event");
                assertTrue(
                        treeEqualsIgnoringUsage(
                                aaUpstream.get(i).data(), aaOutbound.get(i).data()),
                        "aa message_start must be identical modulo the usage frame");
                assertTrue(
                        aaOutbound.get(i).data().contains("\"input_tokens\":0"),
                        "aa outbound message_start carries zeroed 0/0 usage: "
                                + aaOutbound.get(i).data());
            } else if (i == 9) {
                // message_delta: identical modulo the usage frame — the outbound carries the
                // merged prompt-preserving usage.
                assertEquals("message_delta", aaOutbound.get(i).event(), "aa frame 9 event");
                assertTrue(
                        treeEqualsIgnoringUsage(
                                aaUpstream.get(i).data(), aaOutbound.get(i).data()),
                        "aa message_delta must be identical modulo the usage frame");
                assertTrue(
                        aaOutbound.get(i).data().contains("\"input_tokens\":14"),
                        aaOutbound.get(i).data());
                assertTrue(
                        aaOutbound.get(i).data().contains("\"output_tokens\":12"),
                        aaOutbound.get(i).data());
            } else {
                assertEquals(aaUpstream.get(i), aaOutbound.get(i), "aa frame " + i);
            }
        }
        // oo stream: outbound re-encode strips the upstream corpus's explicit
        // "finish_reason":null members (NON_NULL) — value-preserving, asserted
        // semantically; the byte-golden outbound identity is asserted in everyCellReplays.
        List<String> upstreamPayloads = SseTestFrames.dataPayloads(read(cell("oo", "stream", "upstream.stream.sse")));
        List<String> outboundPayloads = SseTestFrames.dataPayloads(read(cell("oo", "stream", "outbound.stream.sse")));
        assertEquals(upstreamPayloads.size(), outboundPayloads.size(), "oo stream frame counts must match");
        for (int i = 0; i < upstreamPayloads.size() - 1; i++) { // skip the [DONE] sentinel
            assertTrue(
                    JsonSupport.treeEquals(
                            JsonSupport.mapper().readTree(outboundPayloads.get(i)),
                            JsonSupport.mapper().readTree(upstreamPayloads.get(i))),
                    "oo stream frame " + i + " must be value-preserving vs the upstream corpus");
        }
    }

    // ------------------------------------------------------------------ cells

    private void assertCell(String dir, String mode) throws Exception {
        boolean openAiFace = dir.charAt(0) == 'o';
        boolean openAiUpstream = dir.charAt(1) == 'o';
        ChatRequest canonical = canonicalRequest(mode);

        // (1) inbound decode → canonical (record equality). Anthropic-face stream cells
        // tolerate the D1 documented non-idempotence: the Anthropic wire has no
        // stream_options, so the face inbound cannot carry the canonical's flag — the
        // decode equals the canonical with streamOptions nulled (the OpenAI-face cells
        // round-trip it exactly).
        String inbound = read(cell(dir, mode, "inbound.request.json"));
        ChatRequest expectedCanonical =
                !openAiFace && mode.equals("stream") ? withoutStreamOptions(canonical) : canonical;
        assertEquals(
                expectedCanonical,
                WireCodec.of(openAiFace, openAi, anthropic).decodeRequest(inbound),
                dir + "/" + mode + " inbound must decode to the canonical request");

        // (2) outbound request byte-golden.
        String outboundRequest = read(cell(dir, mode, "outbound.request.json"));
        assertEquals(
                outboundRequest,
                WireCodec.of(openAiUpstream, openAi, anthropic).encodeRequest(canonical),
                dir + "/" + mode + " outbound request must be byte-identical to encode(canonical)");
        if (mode.equals("stream")) {
            assertTrue(outboundRequest.contains("\"stream\":true"), dir + "/" + mode + " stream flag");
            if (openAiUpstream) {
                assertTrue(
                        outboundRequest.contains("\"stream_options\":{\"include_usage\":true}"),
                        dir + " OpenAI-outbound stream request must carry stream_options.include_usage");
            } else {
                assertTrue(
                        outboundRequest.contains("\"max_tokens\":4096"),
                        dir + " Anthropic-outbound request must carry max_tokens (codec default)");
                // D1 (blessed fix): Anthropic has no stream_options — the codec must
                // NOT emit the canonical's streamOptions on the Anthropic wire (real
                // Anthropic rejects unknown request fields, and the strict fake pins the
                // same schema validation deterministically). The corpus regenerates
                // without the field; this absence pin is the red test the D1 fix turns
                // green (the corpus pinned the passthrough as the hand-off).
                assertFalse(
                        outboundRequest.contains("stream_options"),
                        dir + " Anthropic-outbound stream request must NOT carry stream_options");
            }
        } else {
            // Non-streaming outbound requests carry no stream member ( adapter contract).
            assertFalse(outboundRequest.contains("\"stream\":"), dir + "/" + mode + " must not carry stream");
        }

        if (mode.equals("stream")) {
            assertStreamCell(dir, openAiFace, openAiUpstream);
        } else {
            assertResponseCell(dir, mode, openAiFace, openAiUpstream);
        }
    }

    private void assertResponseCell(String dir, String mode, boolean openAiFace, boolean openAiUpstream)
            throws Exception {
        String upstream = read(cell(dir, mode, "upstream.response.json"));
        String outbound = read(cell(dir, mode, "outbound.response.json"));
        ChatResponse canonical = canonicalResponse(mode);

        ChatResponse decoded;
        if (openAiUpstream) {
            // OpenAI-shaped upstream: literal record equality with the canonical response.
            decoded = openAi.decodeResponse(upstream);
            assertEquals(canonical, decoded, dir + "/" + mode + " upstream must decode to the canonical response");
        } else {
            // Anthropic-shaped upstream: the documented format fields differ
            // (object "message" from the wire type, created 0 — Anthropic has no
            // timestamp, upstream id, no extras); everything else is record-equal.
            decoded = anthropic.decodeResponse(upstream);
            assertEquals(canonical.choices(), decoded.choices(), dir + "/" + mode + " choices");
            assertEquals(canonical.model(), decoded.model(), dir + "/" + mode + " model");
            assertEquals(canonical.usage(), decoded.usage(), dir + "/" + mode + " usage");
            assertEquals(canonical.stopReason(), decoded.stopReason(), dir + "/" + mode + " stop reason");
            assertEquals("message", decoded.object(), dir + "/" + mode + " object (Anthropic wire type)");
            assertEquals(0L, decoded.created(), dir + "/" + mode + " created (Anthropic has none)");
            assertEquals(ANTHROPIC_RESPONSE_ID, decoded.id(), dir + "/" + mode + " upstream id");
            assertEquals(Map.of(), decoded.extras(), dir + "/" + mode + " extras");
        }
        // Face outbound response: byte-golden from the decoded canonical (the chain is
        // upstream wire → canonical → face wire; the outbound documents exactly that).
        assertEquals(
                outbound,
                WireCodec.of(openAiFace, openAi, anthropic).encodeResponse(decoded),
                dir + "/" + mode + " outbound response must be byte-identical to encode(decode(upstream))");
    }

    private void assertStreamCell(String dir, boolean openAiFace, boolean openAiUpstream) throws Exception {
        String upstream = read(cell(dir, "stream", "upstream.stream.sse"));
        String outbound = read(cell(dir, "stream", "outbound.stream.sse"));
        List<StreamChunk> canonicalChunks = canonicalChunks();

        if (openAiUpstream) {
            // OpenAI-shaped upstream: the corpus decodes to exactly the canonical chunks.
            List<String> payloads = SseTestFrames.dataPayloads(upstream);
            assertEquals("[DONE]", payloads.get(payloads.size() - 1), dir + " upstream must end [DONE]");
            List<StreamChunk> decoded = payloads.subList(0, payloads.size() - 1).stream()
                    .map(openAi::decodeChunk)
                    .toList();
            assertEquals(canonicalChunks, decoded, dir + " upstream chunks must equal the canonical chunks");
        } else {
            // Anthropic-shaped upstream: 11 frames; the 8 content-bearing chunks merge the
            // canonical finish + usage chunks into one message_delta chunk (/
            // documented shape — Anthropic carries stop_reason + usage in a single
            // terminal event) and re-emit the OpenAI role chunk's empty content as an
            // empty text_delta (the encoder's canonicalized shape, documented). The frames
            // decode through the per-stream stateful decoder — the real-shaped upstream
            // splits usage across message_start (input_tokens) and message_delta
            // (output_tokens only), which the decoder merges onto the terminal chunk.
            List<SseTestFrames.SseFrame> frames = SseTestFrames.frames(upstream);
            assertEquals(11, frames.size(), dir + " frame count (README record)");
            AnthropicStreamDecoder decoder = anthropic.newStreamDecoder();
            List<StreamChunk> decoded = new ArrayList<>();
            for (SseTestFrames.SseFrame frame : frames) {
                StreamChunk chunk = decoder.decodeChunk(frame.event(), frame.data());
                if (chunk != null) {
                    decoded.add(chunk);
                }
            }
            assertEquals(8, decoded.size(), dir + " content-bearing chunks");
            // role chunk: id/model from message_start, object "message", created 0,
            // assistant role announced with null content (the OpenAI role chunk carries "").
            assertEquals(MatrixCanonicals.CHUNK_ID, decoded.get(0).id());
            assertEquals("message", decoded.get(0).object());
            assertEquals(0L, decoded.get(0).created());
            assertEquals(MatrixCanonicals.MODEL, decoded.get(0).model());
            assertEquals(
                    List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                    decoded.get(0).choices());
            assertNull(decoded.get(0).usage());
            // empty text_delta: the OpenAI role chunk's "" content re-emitted verbatim.
            assertEquals(
                    List.of(new ChunkChoice(0, new Delta(null, "", null), null)),
                    decoded.get(1).choices(),
                    dir + " empty text delta");
            // text deltas 2-6: choices equal the canonical content chunks 1-5.
            for (int i = 1; i <= 5; i++) {
                assertEquals(
                        canonicalChunks.get(i).choices(), decoded.get(i + 1).choices(), dir + " delta " + i);
            }
            // terminal: finish reason + usage merged into one chunk.
            assertEquals(canonicalChunks.get(6).choices(), decoded.get(7).choices(), dir + " terminal choices");
            assertEquals(canonicalChunks.get(7).usage(), decoded.get(7).usage(), dir + " terminal usage");
        }

        // Face outbound stream: byte-exact against the committed file.
        assertEquals(
                outbound,
                openAiFace ? emitOpenAiSse(canonicalChunks) : emitAnthropicSse(canonicalChunks),
                dir + " outbound stream must be byte-identical to the face-codec emission");
        if (openAiFace) {
            assertTrue(outbound.endsWith("data: [DONE]"), dir + " OpenAI face must end [DONE]");
            assertFalse(outbound.contains("event:"), dir + " OpenAI face must not carry event: lines");
        } else {
            assertTrue(outbound.contains("event: message_stop"), dir + " Anthropic face must end message_stop");
            assertFalse(outbound.contains("[DONE]"), dir + " Anthropic face must not carry [DONE]");
        }
    }

    // ----------------------------------------------------------------- emission

    private static String emitOpenAiSse(List<StreamChunk> chunks) throws Exception {
        OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());
        StringBuilder out = new StringBuilder();
        for (StreamChunk chunk : chunks) {
            out.append("data: ").append(codec.encodeChunk(chunk)).append("\n\n");
        }
        out.append("data: [DONE]");
        return out.toString();
    }

    private static String emitAnthropicSse(List<StreamChunk> chunks) throws Exception {
        AnthropicStreamEncoder encoder = new AnthropicMessageCodec(JsonSupport.mapper()).newStreamEncoder();
        StringBuilder out = new StringBuilder();
        for (StreamChunk chunk : chunks) {
            for (AnthropicSseEvent event : encoder.feed(chunk)) {
                out.append("event: ").append(event.event()).append('\n');
                out.append("data: ").append(event.dataJson()).append("\n\n");
            }
        }
        for (AnthropicSseEvent event : encoder.finish()) {
            out.append("event: ").append(event.event()).append('\n');
            out.append("data: ").append(event.dataJson()).append("\n\n");
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- fixtures

    private static ChatRequest canonicalRequest(String mode) throws Exception {
        return JsonSupport.mapper().readValue(read(ROOT + "/canonical/" + mode + ".request.json"), ChatRequest.class);
    }

    private static ChatResponse canonicalResponse(String mode) throws Exception {
        return JsonSupport.mapper().readValue(read(ROOT + "/canonical/" + mode + ".response.json"), ChatResponse.class);
    }

    @SuppressWarnings("unchecked")
    private static List<StreamChunk> canonicalChunks() throws Exception {
        return JsonSupport.mapper()
                .readValue(
                        read(ROOT + "/canonical/stream.chunks.json"),
                        new tools.jackson.core.type.TypeReference<List<StreamChunk>>() {});
    }

    private static String cell(String dir, String mode, String artifact) {
        return ROOT + "/" + dir + "/" + mode + "/" + artifact;
    }

    /** Tree-equality with the {@code usage} member dropped (top-level and the nested
     * {@code message} object) — the aa passthrough re-documentation compares the
     * non-usage frames exactly and the usage frames modulo usage (see
     * {@link #passthroughCellsPinExactlyWhatJanusChanges}). */
    private static boolean treeEqualsIgnoringUsage(String leftJson, String rightJson) throws Exception {
        var mapper = JsonSupport.mapper();
        JsonNode left = mapper.readTree(leftJson);
        JsonNode right = mapper.readTree(rightJson);
        stripUsage(left);
        stripUsage(right);
        return JsonSupport.treeEquals(left, right);
    }

    private static void stripUsage(JsonNode node) {
        if (node instanceof tools.jackson.databind.node.ObjectNode object) {
            object.remove("usage");
            JsonNode message = object.get("message");
            if (message instanceof tools.jackson.databind.node.ObjectNode messageObject) {
                messageObject.remove("usage");
            }
        }
    }

    /**
     * Copy with {@code streamOptions} nulled — the D1 documented Anthropic-wire
     * non-idempotence (the canonical keeps the OpenAI-idiomatic flag; the Anthropic
     * wire cannot carry it, so an Anthropic-face decode sees {@code null}).
     */
    private static ChatRequest withoutStreamOptions(ChatRequest request) {
        return new ChatRequest(
                request.model(),
                request.messages(),
                request.system(),
                request.tools(),
                request.toolChoice(),
                request.temperature(),
                request.topP(),
                request.topK(),
                request.maxTokens(),
                request.stop(),
                request.seed(),
                request.n(),
                request.frequencyPenalty(),
                request.presencePenalty(),
                request.logitBias(),
                request.responseFormat(),
                request.stream(),
                null,
                request.reasoning(),
                request.cacheControl(),
                request.extras(),
                request.meta());
    }

    private static String read(String resourcePath) throws IOException {
        try (InputStream in = GoldenMatrixTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("missing fixture resource " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
