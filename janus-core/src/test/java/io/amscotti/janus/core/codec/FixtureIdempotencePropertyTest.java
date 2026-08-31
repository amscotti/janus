package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fixture-driven idempotence: for <b>every</b> committed wire artifact in
 * {@code fixtures/matrix/} and {@code fixtures/anthropic/}, {@code decode → encode →
 * decode} yields the same canonical on the second pass (idempotence), and the
 * Janus-generated legs ({@code inbound.request.json}, {@code outbound.request.json},
 * {@code outbound.response.json}, {@code outbound.stream.sse}) additionally round-trip
 * <b>byte-identically</b> (their committed bytes are exactly {@code encode(decode(·))}).
 * The upstream-shaped legs are asserted at the semantic level (values survive; the
 * re-encoded wire is not asserted byte-equal to the upstream's bytes — equality
 * rules).
 */
class FixtureIdempotencePropertyTest {

    private static final String MATRIX = "/fixtures/matrix";
    private static final String ANTHROPIC = "/fixtures/anthropic";

    private static final List<String> DIRS = List.of("oo", "oa", "ao", "aa");
    private static final List<String> MODES = List.of("plain", "tools", "stream");

    private final OpenAiMessageCodec openAi = new OpenAiMessageCodec(JsonSupport.mapper());
    private final AnthropicMessageCodec anthropic = new AnthropicMessageCodec(JsonSupport.mapper());

    @Test
    void everyMatrixArtifactIsIdempotent() throws Exception {
        for (String dir : DIRS) {
            boolean openAiFace = dir.charAt(0) == 'o';
            boolean openAiUpstream = dir.charAt(1) == 'o';
            for (String mode : MODES) {
                String prefix = MATRIX + "/" + dir + "/" + mode + "/";
                assertRequestByteGolden(prefix + "inbound.request.json", openAiFace, dir + "/" + mode + " inbound");
                assertRequestByteGolden(
                        prefix + "outbound.request.json", openAiUpstream, dir + "/" + mode + " outbound");
                if (mode.equals("stream")) {
                    assertUpstreamSseIdempotent(prefix + "upstream.stream.sse", openAiUpstream, dir + "/" + mode);
                    assertOutboundSseByteGolden(prefix + "outbound.stream.sse", openAiFace, dir + "/" + mode);
                } else {
                    assertUpstreamResponseIdempotent(
                            prefix + "upstream.response.json", openAiUpstream, dir + "/" + mode);
                    assertOutboundResponseByteGolden(prefix + "outbound.response.json", openAiFace, dir + "/" + mode);
                }
            }
        }
    }

    @Test
    void everyAnthropicCorpusArtifactIsIdempotent() throws Exception {
        assertRequestByteGolden(ANTHROPIC + "/chat.request.json", false, "anthropic request");
        assertRequestByteGolden(ANTHROPIC + "/chat.request.stream.json", false, "anthropic stream request");
        assertUpstreamResponseIdempotent(ANTHROPIC + "/chat.response.json", false, "anthropic response");
        assertUpstreamSseIdempotent(ANTHROPIC + "/chat.stream.sse", false, "anthropic stream");
        // The stateful decoder merges message_start's prompt side with
        // message_delta's completion side into the terminal chunk usage — pin the
        // README-recorded merged value so the merge cannot silently regress in the
        // corpus-level property test (previously the stateless decoder round-tripped a
        // lossy prompt=0 usage).
        List<StreamChunk> terminal = decodeAnthropicFrames(read(ANTHROPIC + "/chat.stream.sse"));
        assertEquals(new Usage(14, 12, 26), terminal.get(terminal.size() - 1).usage(), "merged terminal usage");
    }

    // ------------------------------------------------------------- assertions

    private void assertRequestByteGolden(String resource, boolean openAi, String label) throws Exception {
        String bytes = read(resource);
        WireCodec codec = codec(openAi);
        ChatRequest first = codec.decodeRequest(bytes);
        ChatRequest second = codec.decodeRequest(codec.encodeRequest(first));
        assertEquals(first, second, label + " decode → encode → decode must be stable");
        assertEquals(bytes, codec.encodeRequest(first), label + " must re-encode byte-identically");
    }

    private void assertOutboundResponseByteGolden(String resource, boolean openAi, String label) throws Exception {
        String bytes = read(resource);
        WireCodec codec = codec(openAi);
        ChatResponse first = codec.decodeResponse(bytes);
        ChatResponse second = codec.decodeResponse(codec.encodeResponse(first));
        assertEquals(first, second, label + " decode → encode → decode must be stable");
        assertEquals(bytes, codec.encodeResponse(first), label + " must re-encode byte-identically");
    }

    private void assertUpstreamResponseIdempotent(String resource, boolean openAi, String label) throws Exception {
        WireCodec codec = codec(openAi);
        ChatResponse first = codec.decodeResponse(read(resource));
        ChatResponse second = codec.decodeResponse(codec.encodeResponse(first));
        assertEquals(first, second, label + " upstream response must be decode-idempotent");
    }

    private void assertUpstreamSseIdempotent(String resource, boolean openAi, String label) throws Exception {
        List<StreamChunk> first = openAi ? decodeOpenAiFrames(read(resource)) : decodeAnthropicFrames(read(resource));
        List<StreamChunk> second =
                openAi ? decodeOpenAiFrames(renderOpenAi(first)) : decodeAnthropicFrames(renderAnthropic(first));
        assertEquals(first, second, label + " upstream stream must be decode-idempotent");
    }

    private void assertOutboundSseByteGolden(String resource, boolean openAi, String label) throws Exception {
        String bytes = read(resource);
        List<StreamChunk> chunks = openAi ? decodeOpenAiFrames(bytes) : decodeAnthropicFrames(bytes);
        String reencoded = openAi ? renderOpenAi(chunks) : renderAnthropic(chunks);
        assertEquals(bytes, reencoded, label + " outbound stream must re-encode byte-identically");
    }

    // -------------------------------------------------------------- helpers

    private WireCodec codec(boolean openAiWire) {
        return WireCodec.of(openAiWire, openAi, anthropic);
    }

    private List<StreamChunk> decodeOpenAiFrames(String sse) {
        List<String> payloads = SseTestFrames.dataPayloads(sse);
        List<StreamChunk> chunks = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            String payload = payloads.get(i);
            if (!"[DONE]".equals(payload)) {
                chunks.add(openAi.decodeChunk(payload));
            }
        }
        return chunks;
    }

    private List<StreamChunk> decodeAnthropicFrames(String sse) {
        // Use the stateful per-stream decoder, not the stateless
        // decodeChunk — the corpus-level property test must exercise the merged-usage
        // path (message_start prompt side + message_delta completion side → terminal
        // chunk usage), not silently pin the stateless decoder's lossy prompt=0 shape.
        AnthropicStreamDecoder decoder = anthropic.newStreamDecoder();
        List<StreamChunk> chunks = new ArrayList<>();
        for (SseTestFrames.SseFrame frame : SseTestFrames.frames(sse)) {
            StreamChunk chunk = decoder.decodeChunk(frame.event(), frame.data());
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    private static String renderOpenAi(List<StreamChunk> chunks) throws Exception {
        OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());
        StringBuilder out = new StringBuilder();
        for (StreamChunk chunk : chunks) {
            out.append("data: ").append(codec.encodeChunk(chunk)).append("\n\n");
        }
        out.append("data: [DONE]");
        return out.toString();
    }

    private static String renderAnthropic(List<StreamChunk> chunks) throws Exception {
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

    private static String read(String resource) throws IOException {
        try (InputStream in = FixtureIdempotencePropertyTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing fixture resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
