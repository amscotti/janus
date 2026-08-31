package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * corpus guard — the resource analogue of the / drift guards: the exact
 * committed file set under {@code fixtures/openai/} (a renamed/added/removed fixture
 * fails CI), {@code README.md} present with the "never touches the network" rule, every
 * {@code.json} parses with the codec's mapper, every {@code chat.stream.sse} frame
 * decodes via {@link OpenAiMessageCodec#decodeChunk} (except the {@code [DONE]}
 * sentinel), and no fixture contains {@code sk-}-prefixed secret material or an
 * {@code Authorization} header string.
 */
class FixtureManifestTest {

    private static final String ROOT = "/fixtures/openai";

    private static final List<String> EXPECTED_FILES = List.of(
            "README.md",
            "chat.request.json",
            "chat.request.stream.json",
            "chat.response.cached.json",
            "chat.response.json",
            "chat.stream.sse",
            "errors/deepseek.400.json",
            "errors/deepseek.401.json",
            "errors/deepseek.429.json");

    private final OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());

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
        // The classification table the gateway ErrorFixtureTest consumes.
        assertTrue(readme.contains("authentication_error"), readme);
        assertTrue(readme.contains("rate_limit_error"), readme);
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
    void everyStreamFrameDecodesViaDecodeChunkExceptDone() throws Exception {
        List<String> payloads = SseTestFrames.dataPayloads(read("chat.stream.sse"));
        assertEquals(9, payloads.size(), "frame count must match the README record (8 chunks + [DONE])");
        assertEquals("[DONE]", payloads.get(payloads.size() - 1));
        for (int i = 0; i < payloads.size() - 1; i++) {
            String payload = payloads.get(i);
            assertNotNull(codec.decodeChunk(payload), "frame " + i + " must decode as an OpenAI chunk");
        }
    }

    @Test
    void noFixtureContainsSecretMaterial() throws Exception {
        try (Stream<Path> walk = Files.walk(fixtureRoot())) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                // Beyond sk-/Authorization, also reject x-api-key:/api-key:
                // header values, Bearer <token> and AKIA ids (case-insensitively where
                // credentials are presented); a header-name-only mention in an upstream
                // error message ("invalid x-api-key") is not a violation.
                assertTrue(
                        FixtureSecrets.violations(content).isEmpty(),
                        path.getFileName() + " contains secret material: " + FixtureSecrets.violations(content));
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Path fixtureRoot() throws URISyntaxException, IOException {
        java.net.URL url = FixtureManifestTest.class.getResource(ROOT);
        assertNotNull(url, "fixture root " + ROOT + " missing from the test classpath");
        return Path.of(url.toURI());
    }

    private static String read(String relativePath) throws IOException {
        try (InputStream in = FixtureManifestTest.class.getResourceAsStream(ROOT + "/" + relativePath)) {
            assertNotNull(in, ROOT + "/" + relativePath + " missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
