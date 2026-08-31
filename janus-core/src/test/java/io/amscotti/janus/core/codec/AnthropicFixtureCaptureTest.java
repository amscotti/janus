package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * one-time, manual, env-gated fixture capture harness (Track A) — the {@link
 * FixtureCaptureTest} analogue for the {@code fixtures/anthropic/} corpus: builds the
 * canonical requests via {@link MatrixCanonicals}, encodes with
 * {@link AnthropicMessageCodec}, POSTs to the real Anthropic Messages endpoint with the
 * JDK {@link HttpClient} (janus-core stays dependency-free), and writes the committed
 * corpus in place. {@code anthropic/chat.request.json}/{@code chat.request.stream.json}
 * are codec-deterministic (also regenerated offline by {@link MatrixFixtureGeneratorTest});
 * the response/stream/error files are replaced with real upstream bytes when a key is
 * available (the C1 precedent: provenance in the README updated accordingly).
 *
 * <p>Guarded twice so it can never run in CI: {@code @Tag("capture")} + the
 * {@code excludeTags 'capture'} test-task line in {@code janus-core/build.gradle}, and
 * the {@code JANUS_FIXTURE_CAPTURE=1} env gate. {@code ANTHROPIC_API_KEY} must
 * additionally be non-blank (dummy/throwaway key only).
 *
 * <pre>
 * JANUS_FIXTURE_CAPTURE=1 ANTHROPIC_API_KEY=&lt;throwaway-key&gt;./gradlew :janus-core:captureFixtures
 * </pre>
 *
 * <p>The 401 upstream body echoes the presented key; it is redacted to {@code <redacted>}
 * before committing so the {@link AnthropicFixtureManifestTest} secret guard stays green.
 * After the run, update the README provenance + frame counts and re-run the manifest /
 * golden / idempotence suites (the coordinated edit spots the README names).
 */
@Tag("capture")
@EnabledIfEnvironmentVariable(named = "JANUS_FIXTURE_CAPTURE", matches = "1")
class AnthropicFixtureCaptureTest {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String REDACTED = "<redacted>";

    private final AnthropicMessageCodec codec = new AnthropicMessageCodec(JsonSupport.mapper());

    @Test
    void captureAnthropicFixtures() throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY required for fixture capture");
        Path target = Path.of(System.getProperty("user.dir"), "src", "test", "resources", "fixtures", "anthropic");
        Files.createDirectories(target.resolve("errors"));

        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        // Janus-generated request fixtures (no network): the canonical shapes via the
        // Anthropic codec (max_tokens defaulted to 4096; stream_options dropped on the
        // Anthropic wire — D1).
        Files.writeString(
                target.resolve("chat.request.json"),
                codec.encodeRequest(MatrixCanonicals.toolsRequest()),
                StandardCharsets.UTF_8);
        Files.writeString(
                target.resolve("chat.request.stream.json"),
                codec.encodeRequest(MatrixCanonicals.streamRequest()),
                StandardCharsets.UTF_8);

        // Non-streaming response body verbatim.
        HttpResponse<String> response = http.send(
                post(apiKey, codec.encodeRequest(MatrixCanonicals.plainRequest())),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "non-streaming capture failed: " + response.body());
        Files.writeString(target.resolve("chat.response.json"), response.body(), StandardCharsets.UTF_8);

        // Streaming SSE bytes verbatim (raw InputStream read through — framing preserved).
        HttpResponse<InputStream> streamResponse = http.send(
                post(apiKey, codec.encodeRequest(MatrixCanonicals.streamRequest())),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, streamResponse.statusCode(), "streaming capture failed: HTTP " + streamResponse.statusCode());
        try (InputStream in = streamResponse.body()) {
            Files.write(target.resolve("chat.stream.sse"), in.readAllBytes());
        }

        // Error envelopes: 401 (invalid key), 400 (unknown model — the OpenAI harness
        // precedent), 429 best-effort.
        captureStatus(http, target, "sk-invalid-fixture-capture-key", "deepseek-v4-flash", "anthropic.401.json", 401);
        captureStatus(http, target, apiKey, "no-such-model-xyz", "anthropic.400.json", 400);
        captureErrorBestEffort(http, target, apiKey, "anthropic.429.json");
    }

    // ---------------------------------------------------------------- HTTP

    private static HttpRequest post(String apiKey, String body) {
        return HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static void captureStatus(
            HttpClient http, Path target, String probeKey, String model, String fileName, int expectedStatus)
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                http.send(post(probeKey, codecBodyWithModel(model)), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), "expected " + expectedStatus + ": " + response.body());
        // The upstream 401 echoes the presented key; redact so the manifest secret guard
        // stays green (README documents the redaction). The real key is also redacted on
        // the 400 path so a key echo there can never be committed.
        Files.writeString(
                target.resolve("errors").resolve(fileName),
                response.body().replace(probeKey, REDACTED),
                StandardCharsets.UTF_8);
    }

    /** Anthropic 429s are hard to provoke on demand; skip writing unless one arrives. */
    private static void captureErrorBestEffort(HttpClient http, Path target, String apiKey, String fileName)
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                http.send(post(apiKey, codecBodyWithModel("deepseek-v4-flash")), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            Files.writeString(target.resolve("errors").resolve(fileName), response.body(), StandardCharsets.UTF_8);
        }
        assertTrue(
                response.statusCode() == 200 || response.statusCode() == 429,
                "unexpected status " + response.statusCode());
    }

    // ------------------------------------------------------------- fixtures

    private static String codecBodyWithModel(String model) {
        AnthropicMessageCodec codec = AnthropicMessageCodec.create();
        return codec.encodeRequest(new ChatRequest(
                model,
                List.of(new io.amscotti.janus.core.model.UserMessage("What is the weather in Paris?")),
                "You are a helpful assistant.",
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
                null));
    }
}
