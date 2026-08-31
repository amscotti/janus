package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * golden stream fixture: split the captured {@code chat.stream.sse} with {@link
 * SseTestFrames}, assert the canonical delta shape per frame (role on the first chunk,
 * content deltas, {@code index} per choice, nullable {@code finish_reason}, the
 * {@code stream_options} usage chunk), and assert {@code encodeChunk} is semantically
 * deep-equal (order-insensitive tree compare) to the captured payload — the captured
 * chunks are constructed without unknown members, so decode → re-encode is value-
 * preserving and in fact byte-identical. Also pins the terminal {@code [DONE]} sentinel
 * and the frame count against the README record (9 frames: 8 chunks + {@code [DONE]}).
 */
class GoldenStreamFixtureTest {

    private static final String FIXTURE = "/fixtures/openai/chat.stream.sse";

    private static final String CHUNK_ID = "chatcmpl-2f4e1c1b9c8a4f2f9c1b2d3e4f5a6b7c";

    /** Content deltas in wire order (frames 1–5 of the capture). */
    private static final List<String> CONTENT_DELTAS =
            List.of("The", " weather", " in Paris", " is 18", " degrees with light rain.");

    private final OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());

    @Test
    void everyFrameDecodesToTheCanonicalDeltaShapeAndReencodesSemanticallyEqual() throws Exception {
        List<String> payloads = SseTestFrames.dataPayloads(read());
        // README record: 9 data frames — 8 chunk frames + terminal [DONE].
        assertEquals(9, payloads.size(), "frame count must match the README record");
        assertEquals("[DONE]", payloads.get(payloads.size() - 1), "the stream must terminate with [DONE]");

        for (int i = 0; i < payloads.size() - 1; i++) {
            String payload = payloads.get(i);
            StreamChunk chunk = codec.decodeChunk(payload);
            assertChunkShape(i, chunk);
            assertTrue(
                    JsonSupport.treeEquals(
                            JsonSupport.mapper().readTree(codec.encodeChunk(chunk)),
                            JsonSupport.mapper().readTree(payload)),
                    "frame " + i + " must re-encode semantically equal to the captured payload");
        }
    }

    /** Per-frame canonical expectations (index 0 = role chunk; 1–5 = content; 6 =
     * finish; 7 = usage). */
    private static void assertChunkShape(int index, StreamChunk chunk) {
        assertEquals(CHUNK_ID, chunk.id(), "frame " + index);
        assertEquals("chat.completion.chunk", chunk.object(), "frame " + index);
        assertEquals(1785715200L, chunk.created(), "frame " + index);
        assertEquals("deepseek-v4-flash", chunk.model(), "frame " + index);
        assertEquals(Map.of(), chunk.extras(), "frame " + index);

        if (index == 0) {
            // First chunk carries the role; DeepSeek sends an empty content alongside.
            assertEquals(
                    List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "", null), null)),
                    chunk.choices(),
                    "frame " + index);
            assertNull(chunk.usage(), "frame " + index);
            return;
        }
        if (index <= CONTENT_DELTAS.size()) {
            assertEquals(
                    List.of(new ChunkChoice(0, new Delta(null, CONTENT_DELTAS.get(index - 1), null), null)),
                    chunk.choices(),
                    "frame " + index);
            assertNull(chunk.usage(), "frame " + index);
            return;
        }
        if (index == CONTENT_DELTAS.size() + 1) {
            // Terminal choice chunk: empty delta + finish_reason.
            assertEquals(
                    List.of(new ChunkChoice(0, new Delta(null, null, null), "stop")),
                    chunk.choices(),
                    "frame " + index);
            assertNull(chunk.usage(), "frame " + index);
            return;
        }
        if (index == CONTENT_DELTAS.size() + 2) {
            // stream_options.include_usage terminal chunk: no choices, usage only.
            assertEquals(List.of(), chunk.choices(), "frame " + index);
            assertEquals(new Usage(14, 12, 26), chunk.usage(), "frame " + index);
            return;
        }
        throw new AssertionError("unexpected frame index " + index);
    }

    private static String read() throws IOException {
        try (InputStream in = GoldenStreamFixtureTest.class.getResourceAsStream(FIXTURE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
