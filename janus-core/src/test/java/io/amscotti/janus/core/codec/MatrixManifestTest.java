package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
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
import tools.jackson.core.type.TypeReference;

/**
 * corpus guard for {@code fixtures/matrix/} — the golden matrix: exactly
 * {@code oo oa ao aa} × {@code plain tools stream} cells with 4 artifacts each, the
 * {@code canonical/} intermediates, and the {@code README.md} with provenance (the
 * manifest-guard philosophy). Every canonical file parses into the corresponding
 * canonical model type, every cell holds exactly the 4 artifacts (no orphans, none
 * missing), and the README documents provenance + the regeneration command.
 */
class MatrixManifestTest {

    private static final String ROOT = "/fixtures/matrix";

    private static final List<String> DIRS = List.of("oo", "oa", "ao", "aa", "ro", "ra");
    private static final List<String> MODES = List.of("plain", "tools", "stream");

    @Test
    void matrixIsExactlyTheExpectedFileSet() throws Exception {
        List<String> expected = new ArrayList<>();
        expected.add("README.md");
        for (String canonical : List.of(
                "plain.request.json",
                "plain.response.json",
                "tools.request.json",
                "tools.response.json",
                "stream.request.json",
                "stream.chunks.json")) {
            expected.add("canonical/" + canonical);
        }
        for (String dir : DIRS) {
            for (String mode : MODES) {
                expected.add(dir + "/" + mode + "/inbound.request.json");
                expected.add(dir + "/" + mode + "/outbound.request.json");
                expected.add(dir + "/" + mode + "/"
                        + (mode.equals("stream") ? "upstream.stream.sse" : "upstream.response.json"));
                expected.add(dir + "/" + mode + "/"
                        + (mode.equals("stream") ? "outbound.stream.sse" : "outbound.response.json"));
            }
        }
        expected.sort(String::compareTo);

        List<String> actual = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(fixtureRoot())) {
            walk.filter(Files::isRegularFile)
                    .map(fixtureRoot()::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .forEach(actual::add);
        }
        assertEquals(expected, actual, "matrix corpus drifted — a file was added, removed or renamed");
    }

    @Test
    void readmeDocumentsProvenanceAndRegeneration() throws Exception {
        String readme = read("README.md");
        assertTrue(readme.contains("MatrixFixtureGeneratorTest"), readme);
        assertTrue(readme.contains("captureFixtures"), readme);
        assertTrue(readme.contains("never touches the network"), readme);
        // Provenance honesty (the C1 discipline): the upstream legs are hand-authored
        // from the documented wire shapes, not live captures.
        assertTrue(readme.contains("hand-authored"), readme);
    }

    @Test
    void canonicalFilesParseIntoTheirModelTypes() throws Exception {
        var mapper = JsonSupport.mapper();
        for (String mode : MODES) {
            ChatRequest request = mapper.readValue(read("canonical/" + mode + ".request.json"), ChatRequest.class);
            assertNotNull(request.model(), mode + " request must parse");
        }
        for (String mode : List.of("plain", "tools")) {
            ChatResponse response = mapper.readValue(read("canonical/" + mode + ".response.json"), ChatResponse.class);
            assertNotNull(response.model(), mode + " response must parse");
        }
        List<StreamChunk> chunks =
                mapper.readValue(read("canonical/stream.chunks.json"), new TypeReference<List<StreamChunk>>() {});
        assertEquals(8, chunks.size(), "canonical stream chunk count (README record)");
        assertEquals(
                MatrixCanonicals.streamChunks(),
                chunks,
                "the committed canonical chunks must equal the constructed ones");
    }

    // ---------------------------------------------------------------- helpers

    private static Path fixtureRoot() throws URISyntaxException, IOException {
        java.net.URL url = MatrixManifestTest.class.getResource(ROOT);
        assertNotNull(url, "fixture root " + ROOT + " missing from the test classpath");
        return Path.of(url.toURI());
    }

    private static String read(String relativePath) throws IOException {
        try (InputStream in = MatrixManifestTest.class.getResourceAsStream(ROOT + "/" + relativePath)) {
            assertNotNull(in, ROOT + "/" + relativePath + " missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
