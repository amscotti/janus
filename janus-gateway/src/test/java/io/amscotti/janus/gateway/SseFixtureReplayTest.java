package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.amscotti.janus.core.codec.OpenAiMessageCodec;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.core.util.SseFrameSplitter;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * captured stream replay through the real SSE endpoint: decode the committed
 * {@code chat.stream.sse} (verbatim copy in this module's test resources — the gateway
 * classpath cannot see core *test* resources) to canonical {@link StreamChunk}s, feed
 * them to the shared {@link FakeBackend} via {@code streamReturns(...)}, hit the real
 * {@code /v1/chat/completions} endpoint with a raw {@code java.net.http} client (same
 * pattern), and assert each emitted {@code data:} payload is byte-exact
 * against the codec's re-encode of the captured chunk (the codec normalizes explicit
 * nulls such as {@code "finish_reason":null} on non-terminal chunks — value preservation
 * is asserted semantically below) with byte-exact framing ({@code data: } prefix +
 * blank line) and the terminal {@code data: [DONE]} — the byte-exact framing test
 * extended from canned to captured bytes.
 *
 * <p>The frame splitter is the shared main-source {@link SseFrameSplitter}
 * (no private re-implementation here — the gateway classpath can see core main sources),
 * and the copy is pinned like the core {@code GoldenStreamFixtureTest}: 9 frames
 * (8 chunks + {@code [DONE]}) and the terminal chunk's merged usage {@code Usage(14,12,26)}
 * (README record), so a stale/truncated copy cannot pass with the same last frame.
 */
@MicronautTest
class SseFixtureReplayTest {

    private static final OpenAiMessageCodec CODEC = OpenAiMessageCodec.create();
    private static final String STREAM_FIXTURE = "/fixtures/stream/chat.stream.sse";

    @Inject
    EmbeddedServer server;

    @Test
    void capturedChunksReplayThroughTheSseEndpointWithByteExactFraming() throws Exception {
        List<String> captured = SseFrameSplitter.dataPayloads(readFixture());
        assertEquals(9, captured.size(), "gateway stream copy must have 9 frames (8 chunks + [DONE])");
        assertEquals("[DONE]", captured.get(captured.size() - 1), "capture must end with [DONE]");
        List<StreamChunk> chunks = captured.stream()
                .filter(payload -> !"[DONE]".equals(payload))
                .map(CODEC::decodeChunk)
                .toList();
        assertEquals(
                new Usage(14, 12, 26), chunks.get(chunks.size() - 1).usage(), "terminal usage chunk (README record)");
        TestRouterFactory.BACKEND.streamReturns(Stream.of(chunks.toArray(StreamChunk[]::new)));

        java.net.http.HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody()))
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertEquals(
                "text/event-stream",
                response.headers().firstValue("Content-Type").orElse(""));

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }

        // Byte-exact SSE framing over the codec's re-encode of each captured chunk
        // (the codec normalizes explicit nulls like "finish_reason":null on non-terminal
        // chunks — value preservation is asserted semantically below and in
        // GoldenStreamFixtureTest) — the byte-shape drift guard extended to captured
        // bytes: data: <re-encoded payload> \n\n... data: [DONE] \n\n.
        List<String> expectedLines = new ArrayList<>();
        for (int i = 0; i < captured.size(); i++) {
            String payload = captured.get(i);
            if ("[DONE]".equals(payload)) {
                expectedLines.add("data: [DONE]");
            } else {
                expectedLines.add("data: " + CODEC.encodeChunk(chunks.get(i)));
            }
            expectedLines.add("");
        }
        assertEquals(expectedLines, lines, "SSE frames must be byte-exact (byte-shape drift guard)");

        // Semantic deep-equality: each emitted payload decodes to the same canonical
        // chunk that was fed to the backend (order-insensitive — the captured payloads
        // also round-trip byte-identically, asserted above).
        for (int i = 0; i < chunks.size(); i++) {
            String emitted = lines.get(i * 2).substring("data: ".length());
            assertEquals(chunks.get(i), CODEC.decodeChunk(emitted), "emitted payload " + i);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String requestBody() {
        return "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";
    }

    private static String readFixture() throws IOException {
        try (InputStream in = SseFixtureReplayTest.class.getResourceAsStream(STREAM_FIXTURE)) {
            assertNotNull(in, STREAM_FIXTURE + " missing from the gateway test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
