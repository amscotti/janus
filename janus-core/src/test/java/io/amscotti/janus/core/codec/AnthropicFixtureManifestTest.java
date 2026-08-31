package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * corpus guard for {@code fixtures/anthropic/} — the {@link FixtureManifestTest}
 * analogue: the exact committed file set (a renamed/
 * added/removed fixture fails CI), {@code README.md} present with the "never touches the
 * network" rule and the error-classification table, every {@code.json} parses with the
 * codec's mapper, every {@code chat.stream.sse} frame decodes via
 * {@link AnthropicMessageCodec#decodeChunk(event, data)} (no-op events — {@code
 * content_block_start}/{@code content_block_stop}/{@code message_stop}/{@code ping} —
 * decode to null and are fine), and no fixture contains {@code sk-}-prefixed secret
 * material or an {@code Authorization} header string.
 */
class AnthropicFixtureManifestTest {

    private static final String ROOT = "/fixtures/anthropic";

    private static final List<String> EXPECTED_FILES = List.of(
            "README.md",
            "chat.request.json",
            "chat.request.stream.json",
            "chat.response.json",
            "chat.stream.sse",
            "errors/anthropic.400.json",
            "errors/anthropic.401.json",
            "errors/anthropic.429.json",
            "extended-thinking.stream.sse");

    /** The README-recorded frame count of {@code chat.stream.sse} (11 frames, message_stop terminal). */
    private static final int EXPECTED_FRAMES = 11;

    private final AnthropicMessageCodec codec = new AnthropicMessageCodec(JsonSupport.mapper());

    @Test
    void fixtureCorpusIsExactlyTheExpectedFileSet() throws Exception {
        List<String> actual = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(fixtureRoot())) {
            walk.filter(Files::isRegularFile)
                    .map(fixtureRoot()::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .forEach(actual::add);
        }
        assertEquals(EXPECTED_FILES, actual, "fixture corpus drifted — a file was added, removed or renamed");
    }

    @Test
    void readmeDocumentsTheCapturedOnceCiNeverTouchesNetworkRule() throws Exception {
        String readme = read("README.md");
        assertTrue(readme.contains("never touches the network"), readme);
        assertTrue(readme.contains("deepseek-v4-flash"), readme);
        // The error-classification table (the openai corpus analogue).
        assertTrue(readme.contains("authentication_error"), readme);
        assertTrue(readme.contains("rate_limit_error"), readme);
        // The redaction rule the 401 fixture follows.
        assertTrue(readme.contains("<redacted>"), readme);
    }

    @Test
    void everyJsonParsesWithTheCodecMapper() throws Exception {
        for (String file : EXPECTED_FILES) {
            if (!file.endsWith(".json")) {
                continue;
            }
            String json = read(file);
            assertNotNull(JsonSupport.mapper().readTree(json), file + " must parse with the codec's mapper");
        }
    }

    @Test
    void everyStreamFrameDecodesViaDecodeChunk() throws Exception {
        List<SseTestFrames.SseFrame> frames = SseTestFrames.frames(read("chat.stream.sse"));
        assertEquals(EXPECTED_FRAMES, frames.size(), "frame count must match the README record");
        // message_stop terminal — no [DONE] anywhere on the Anthropic wire.
        assertEquals("message_stop", frames.get(frames.size() - 1).event());
        for (SseTestFrames.SseFrame frame : frames) {
            // No-op events decode to null; content-bearing frames must decode to a chunk
            // (throws on malformed payloads — that is the guard).
            codec.decodeChunk(frame.event(), frame.data());
        }
        assertEquals(
                8,
                frames.stream()
                        .map(frame -> codec.decodeChunk(frame.event(), frame.data()))
                        .filter(java.util.Objects::nonNull)
                        .count(),
                "content-bearing frames (README record)");
    }

    @Test
    void extendedThinkingStreamDecodesWithoutAborting() throws Exception {
        // The extended-thinking fixture exercises real Anthropic block/delta
        // types the codec does not model (thinking_delta, signature_delta, server_tool_use)
        // interleaved with text — the pre-fix codec threw InvalidTypeIdException on the
        // first thinking_delta and killed the whole stream. The fixture must decode through
        // the stateful decoder with the thinking/unknown frames dropped and the text kept.
        List<SseTestFrames.SseFrame> frames = SseTestFrames.frames(read("extended-thinking.stream.sse"));
        assertEquals(12, frames.size(), "extended-thinking frame count (README record)");
        AnthropicStreamDecoder decoder = codec.newStreamDecoder();
        List<StreamChunk> chunks = new ArrayList<>();
        for (SseTestFrames.SseFrame frame : frames) {
            StreamChunk chunk = decoder.decodeChunk(frame.event(), frame.data());
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        // role chunk (id/model from message_start) + the single text delta + the terminal
        // usage chunk — thinking frames and the server_tool_use block are dropped.
        assertEquals(3, chunks.size(), "thinking/unknown frames must be dropped, never abort");
        assertEquals("msg_01", chunks.get(0).id());
        assertEquals("The answer is 42.", chunks.get(1).choices().get(0).delta().content());
        // message_start input 25 + message_delta output 12 → merged terminal usage.
        assertEquals(new Usage(25, 12, 37), chunks.get(2).usage());
    }

    @Test
    void noFixtureContainsSecretMaterial() throws Exception {
        try (Stream<Path> walk = Files.walk(fixtureRoot())) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                // Beyond sk-/Authorization, also reject x-api-key:/api-key:
                // header values, Bearer <token> and AKIA ids (case-insensitively where
                // credentials are presented); a header-name-only mention in an upstream
                // error message ("invalid x-api-key" — the committed 401) is not a
                // violation.
                assertTrue(
                        FixtureSecrets.violations(content).isEmpty(),
                        path.getFileName() + " contains secret material: " + FixtureSecrets.violations(content));
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Path fixtureRoot() throws URISyntaxException, IOException {
        java.net.URL url = AnthropicFixtureManifestTest.class.getResource(ROOT);
        assertNotNull(url, "fixture root " + ROOT + " missing from the test classpath");
        return Path.of(url.toURI());
    }

    private static String read(String relativePath) throws IOException {
        try (InputStream in = AnthropicFixtureManifestTest.class.getResourceAsStream(ROOT + "/" + relativePath)) {
            assertNotNull(in, ROOT + "/" + relativePath + " missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
