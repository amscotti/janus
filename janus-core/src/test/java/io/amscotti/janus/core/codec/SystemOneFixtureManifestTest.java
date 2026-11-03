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
 * Corpus guard for {@code fixtures/systemone/} — the {@link FixtureManifestTest}
 * analogue for decision-model fixtures: exact file set, every JSON parses,
 * no secret material.
 */
class SystemOneFixtureManifestTest {

    private static final String ROOT = "/fixtures/systemone";

    private static final List<String> EXPECTED_FILES = List.of(
            "README.md",
            "decisions.request.json",
            "decisions.response.ollama-nimble.json",
            "decisions.response.ollama-tev1.json",
            "openai.decisions.request.json",
            "openai.decisions.response.json");

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
    void everyJsonParsesAndContainsNoSecretMaterial() throws Exception {
        for (String file : EXPECTED_FILES) {
            String content = read(file);
            assertTrue(
                    FixtureSecrets.violations(content).isEmpty(),
                    file + " contains secret material: " + FixtureSecrets.violations(content));
            if (file.endsWith(".json")) {
                assertNotNull(JsonSupport.mapper().readTree(content), file + " must parse with the codec's mapper");
            }
        }
    }

    @Test
    void everyFixtureDecodesThroughItsCodec() throws Exception {
        // A structurally-valid but codec-undecodable fixture would otherwise pass
        // the parse-only guard above (the FixtureManifestTest analogue decodes
        // every SSE fixture through its codec — same bar here).
        SystemOneCodec systemOne = new SystemOneCodec(JsonSupport.mapper());
        OpenAiDecisionsCodec openAi = new OpenAiDecisionsCodec(JsonSupport.mapper());
        assertNotNull(systemOne.decodeRequest(read("decisions.request.json")));
        assertNotNull(systemOne.decodeResponse(read("decisions.response.ollama-tev1.json")));
        assertNotNull(systemOne.decodeResponse(read("decisions.response.ollama-nimble.json")));
        assertNotNull(openAi.decodeRequest(read("openai.decisions.request.json")));
        assertNotNull(openAi.decodeResponse(read("openai.decisions.response.json")));
    }

    private static Path fixtureRoot() throws URISyntaxException, IOException {
        java.net.URL url = SystemOneFixtureManifestTest.class.getResource(ROOT);
        assertNotNull(url, "fixture root " + ROOT + " missing from the test classpath");
        return Path.of(url.toURI());
    }

    private static String read(String relativePath) throws IOException {
        try (InputStream in = SystemOneFixtureManifestTest.class.getResourceAsStream(ROOT + "/" + relativePath)) {
            assertNotNull(in, ROOT + "/" + relativePath + " missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
