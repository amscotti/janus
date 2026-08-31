package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ToolDefinition;
import io.amscotti.janus.core.model.UserMessage;
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
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * One-time, manual, env-gated fixture capture harness : builds canonical
 * requests via the public model records, encodes with {@link OpenAiMessageCodec}, POSTs
 * to the real DeepSeek endpoint with the JDK {@link HttpClient} (janus-core stays
 * dependency-free — no janus-provider import), and writes the committed corpus under
 * {@code src/test/resources/fixtures/openai/}. Also serves as the codec-vs-real-upstream
 * probe (a real chunk decoding cleanly proves the delta shape).
 *
 * <p>Guarded twice so it can never run in CI: {@code @Tag("capture")} + the
 * {@code excludeTags 'capture'} test-task line in {@code janus-core/build.gradle}, and
 * the {@code JANUS_FIXTURE_CAPTURE=1} env gate ({@code @EnabledIfEnvironmentVariable}).
 * {@code DEEPSEEK_API_KEY} must additionally be non-blank (dummy/throwaway key only).
 *
 * <pre>
 * JANUS_FIXTURE_CAPTURE=1 DEEPSEEK_API_KEY=&lt;throwaway-key&gt;./gradlew :janus-core:captureFixtures
 * </pre>
 *
 * <p>The 401 upstream body echoes the presented key; it is redacted to {@code <redacted>}
 * before committing so the {@code FixtureManifestTest} secret guard stays green. After
 * the run, copy the three {@code errors/*.json} files and {@code chat.stream.sse} into
 * {@code janus-gateway/src/test/resources/fixtures/} and update the manifest's frame
 * counts / file set in the three coordinated spots (see the fixture README "Copy rule").
 */
@Tag("capture")
@EnabledIfEnvironmentVariable(named = "JANUS_FIXTURE_CAPTURE", matches = "1")
class FixtureCaptureTest {

    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String REDACTED = "<redacted>";

    private final OpenAiMessageCodec codec = OpenAiMessageCodec.create();

    @Test
    void captureFixtures() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                apiKey != null && !apiKey.isBlank(), "DEEPSEEK_API_KEY required for fixture capture");
        Path target = Path.of(System.getProperty("user.dir"), "src", "test", "resources", "fixtures", "openai");
        Files.createDirectories(target.resolve("errors"));

        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        // Janus-generated request fixtures (no network): the adapter contract sends
        // the non-streaming shape without a `stream` member; the streaming shape carries
        // stream:true + stream_options.include_usage:true.
        Files.writeString(
                target.resolve("chat.request.json"),
                codec.encodeRequest(nonStreamingCanonical()),
                StandardCharsets.UTF_8);
        Files.writeString(
                target.resolve("chat.request.stream.json"),
                codec.encodeRequest(streamingCanonical()),
                StandardCharsets.UTF_8);

        // Non-streaming response body verbatim.
        HttpResponse<String> response = http.send(
                post(apiKey, codec.encodeRequest(nonStreamingCanonical())), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "non-streaming capture failed: " + response.body());
        Files.writeString(target.resolve("chat.response.json"), response.body(), StandardCharsets.UTF_8);

        // Streaming SSE bytes verbatim (raw InputStream read through — framing preserved).
        HttpResponse<InputStream> streamResponse = http.send(
                post(apiKey, codec.encodeRequest(streamingCanonical())), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, streamResponse.statusCode(), "streaming capture failed: HTTP " + streamResponse.statusCode());
        try (InputStream in = streamResponse.body()) {
            Files.write(target.resolve("chat.stream.sse"), in.readAllBytes());
        }

        // Error envelopes: 401 (invalid key), 400 (unknown model), 429 best-effort.
        captureStatus(
                http,
                target,
                codecBodyWithModel("deepseek-v4-flash"),
                "sk-invalid-fixture-capture-key",
                "deepseek.401.json",
                401);
        captureStatus(http, target, codecBodyWithModel("no-such-model-xyz"), apiKey, "deepseek.400.json", 400);
        captureErrorBestEffort(http, target, apiKey, "deepseek.429.json");
    }

    // ---------------------------------------------------------------- HTTP

    private static HttpRequest post(String apiKey, String body) {
        return HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static void captureStatus(
            HttpClient http, Path target, String body, String probeKey, String fileName, int expectedStatus)
            throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(post(probeKey, body), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), "expected " + expectedStatus + ": " + response.body());
        // The upstream 401 echoes the presented key; redact so the manifest secret guard
        // stays green (README documents the redaction).
        Files.writeString(
                target.resolve("errors").resolve(fileName),
                response.body().replace(probeKey, REDACTED),
                StandardCharsets.UTF_8);
    }

    /** DeepSeek 429s are hard to provoke on demand; skip writing unless one arrives. */
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
        OpenAiMessageCodec codec = OpenAiMessageCodec.create();
        return codec.encodeRequest(new ChatRequest(
                model,
                List.of(new UserMessage("What is the weather in Paris?")),
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

    private static ChatRequest nonStreamingCanonical() {
        return new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("What is the weather in Paris?")),
                "You are a helpful assistant.",
                List.of(
                        new ToolDefinition(
                                "function",
                                "get_weather",
                                null,
                                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}")),
                "auto",
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
                null);
    }

    private static ChatRequest streamingCanonical() {
        return new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("What is the weather in Paris?")),
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
                true,
                Map.of("include_usage", true),
                null,
                null,
                null,
                null);
    }
}
