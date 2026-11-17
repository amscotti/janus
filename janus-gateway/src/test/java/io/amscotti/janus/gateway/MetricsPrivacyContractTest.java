package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * the Tier-1 privacy contract test (port of the reference implementation
 * {@code test/janus/usage/privacy_contract_test.exs}): a distinctive marker string
 * planted in the prompt <b>and</b> echoed in the fake backend's response must never
 * appear anywhere in the scraped exposition text — not in a series, label, HELP or
 * TYPE line — for both a non-streaming and a streamed request. The marker would leak
 * only if some recorder consumed request/response bodies, which Tier-1 deliberately
 * never does (PRIVACY.md §Tier 1: tokens, latency, metadata, cost —
 * never bodies). No implementation beyond steps 3–6; this test pins the guarantee.
 */
@MicronautTest
@Property(name = "janus.test.governance", value = "true")
@Property(name = "janus.test.metrics", value = "true")
class MetricsPrivacyContractTest {

    private static final String MARKER = "janus-tier1-marker-0f3a7c9e";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    EmbeddedServer server;

    @BeforeEach
    void reset() {
        TestMetricsFactory.REGISTRY.clear();
    }

    @Test
    void nonStreamingPromptAndResponseTextNeverAppearInMetrics() {
        // The fake backend echoes the marker inside the response body.
        TestRouterFactory.BACKEND.completeReturns(chatResponse(MARKER));

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/chat/completions", bodyWithPrompt(MARKER))
                                .contentType(MediaType.APPLICATION_JSON),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertFalse(
                scrape.contains(MARKER),
                "Tier-1 metrics must never carry prompt/response text (privacy contract):\n" + scrape);
    }

    @Test
    void decisionFaceNeverLeaksStateOrAnswerTextAndGetsItsOwnSeries() {
        // The privacy pin for the fifth face, in JUnit (not only the opt-in smoke
        // drill): a marker planted in the decision state AND echoed by the fake
        // backend's answer must never appear in the exposition, while the
        // face="systemone" series proves the face records at all.
        TestRouterFactory.DECISION_BACKEND.decideReturns(new io.amscotti.janus.core.model.DecisionResponse(
                "jev-latest",
                Map.of("q", new io.amscotti.janus.core.model.DecisionAnswer.NoulAnswer(0.9)),
                new io.amscotti.janus.core.model.Usage(10, 0, 10),
                Map.of(),
                Map.of()));

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/v1/systemone",
                                        "{\"model\":\"jev-latest\",\"state\":\"decide about " + MARKER
                                                + "\",\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}")
                                .contentType(MediaType.APPLICATION_JSON),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertFalse(scrape.contains(MARKER), "decision state/answers are user content:\n" + scrape);
        assertTrue(
                scrape.contains("janus_requests_total{face=\"systemone\",status=\"2xx\"}"),
                "the decision face records its own series:\n" + scrape);
    }

    @Test
    void streamedRequestLeaksNothing() throws Exception {
        TestRouterFactory.BACKEND.streamReturns(Stream.of(chunk(MARKER)));

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(streamingBodyWithPrompt(MARKER)))
                .build();
        java.net.http.HttpResponse<InputStream> response = java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        try (InputStream body = response.body()) {
            StringBuilder all = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    all.append(line).append('\n');
                }
            }
            assertTrue(all.toString().contains("data: [DONE]"), "the stream must run to completion");
        }

        // The request is recorded on stream close (server side) — poll until
        // the series lands before scraping (the sibling leg is synchronous; this one
        // has a real close-hook race window).
        String scrape = awaitScrapeContaining("janus_requests_total");
        assertFalse(
                scrape.contains(MARKER),
                "Tier-1 metrics must never carry streamed prompt/response text (privacy contract):\n" + scrape);
        assertAllRegisteredMeterTagKeysInAllowlist();
    }

    @Test
    void allFiveFacesAndErrorPathsNeverLeakContentAndKeepAllowlistedTagKeysOnly() throws Exception {
        java.util.Set<String> allowedTags = java.util.Set.of("face", "status", "key_id", "provider", "base_url", "le");

        // 1. Anthropic /v1/messages (200 JSON)
        TestRouterFactory.BACKEND.completeReturns(chatResponse(MARKER));
        String anthropicJson = "{\"model\":\"deepseek-v4-flash\",\"max_tokens\":16,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + MARKER + "\"}]}";
        assertEquals(
                HttpStatus.OK,
                client.toBlocking()
                        .exchange(
                                HttpRequest.POST("/v1/messages", anthropicJson)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("anthropic-version", "2023-06-01"),
                                String.class)
                        .getStatus());

        // 2. Anthropic /v1/messages/count_tokens (200 + 400 error path)
        TestRouterFactory.BACKEND.countTokensReturns(123);
        assertEquals(
                HttpStatus.OK,
                client.toBlocking()
                        .exchange(
                                HttpRequest.POST("/v1/messages/count_tokens", anthropicJson)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("anthropic-version", "2023-06-01"),
                                String.class)
                        .getStatus());
        TestRouterFactory.BACKEND.countTokensUnsupported();
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeError(HttpRequest.POST("/v1/messages/count_tokens", anthropicJson)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("anthropic-version", "2023-06-01"))
                        .getStatus());

        // 3. OpenAI /v1/responses (200 JSON + 200 SSE + 502 error path)
        TestRouterFactory.BACKEND.completeReturns(chatResponse(MARKER));
        String responsesJson =
                "{\"model\":\"deepseek-v4-flash\",\"input\":\"" + MARKER + "\",\"instructions\":\"" + MARKER + "\"}";
        assertEquals(
                HttpStatus.OK,
                client.toBlocking()
                        .exchange(
                                HttpRequest.POST("/v1/responses", responsesJson)
                                        .contentType(MediaType.APPLICATION_JSON),
                                String.class)
                        .getStatus());

        TestRouterFactory.BACKEND.streamReturns(Stream.of(chunk(MARKER)));
        java.net.http.HttpRequest sseReq = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/v1/responses"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"deepseek-v4-flash\",\"input\":\"" + MARKER + "\",\"stream\":true}"))
                .build();
        java.net.http.HttpResponse<InputStream> sseResp = java.net.http.HttpClient.newHttpClient()
                .send(sseReq, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, sseResp.statusCode());
        try (InputStream body = sseResp.body()) {
            body.readAllBytes();
        }

        // 4. Multipart /v1/systemone (200 + 400 + 415)
        TestRouterFactory.DECISION_VISION_BACKEND.decideReturns(new io.amscotti.janus.core.model.DecisionResponse(
                "vision-model",
                Map.of("q", new io.amscotti.janus.core.model.DecisionAnswer.NoulAnswer(0.9)),
                new io.amscotti.janus.core.model.Usage(10, 0, 10),
                Map.of(),
                Map.of()));
        String boundary = "priv-boundary";
        String mpReqJson = "{\"model\":\"vision-model\",\"state\":{\"p\":\"p1\",\"note\":\"" + MARKER + "\"},"
                + "\"assets\":{\"p1\":{\"type\":\"image\",\"part\":\"asset-0\"}},"
                + "\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"" + MARKER + "\"}}}";
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"request\"\r\n"
                        + "Content-Type: application/json\r\n\r\n" + mpReqJson + "\r\n"
                        + "--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"asset-0\"; filename=\"a.png\"\r\n"
                        + "Content-Type: image/png\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] mpBody = new byte[head.length + png.length + tail.length];
        System.arraycopy(head, 0, mpBody, 0, head.length);
        System.arraycopy(png, 0, mpBody, head.length, png.length);
        System.arraycopy(tail, 0, mpBody, head.length + png.length, tail.length);
        java.net.http.HttpRequest mpHttp = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/v1/systemone"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(mpBody))
                .build();
        assertEquals(
                200,
                java.net.http.HttpClient.newHttpClient()
                        .send(mpHttp, java.net.http.HttpResponse.BodyHandlers.ofString())
                        .statusCode());

        // 5. Error paths across faces (400 malformed, 415 bad Content-Type, 502 upstream)
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeError(HttpRequest.POST("/v1/chat/completions", "{\"bad\":" + MARKER)
                                .contentType(MediaType.APPLICATION_JSON))
                        .getStatus());
        assertEquals(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                exchangeError(HttpRequest.POST("/v1/systemone", MARKER).contentType(MediaType.TEXT_PLAIN_TYPE))
                        .getStatus());
        TestRouterFactory.BACKEND.completeFails(new io.amscotti.janus.provider.ProviderException(
                io.amscotti.janus.provider.ProviderException.TYPE_UPSTREAM_5XX, "boom " + MARKER, 503, null));
        assertEquals(
                HttpStatus.BAD_GATEWAY,
                exchangeError(HttpRequest.POST("/v1/chat/completions", bodyWithPrompt(MARKER))
                                .contentType(MediaType.APPLICATION_JSON))
                        .getStatus());

        String scrape = awaitScrapeContaining("face=\"responses\"");
        assertFalse(scrape.contains(MARKER), "marker leaked across 5-face privacy check:\n" + scrape);
        for (String expectedFace : List.of("openai", "anthropic", "responses", "systemone")) {
            assertTrue(
                    scrape.contains("face=\"" + expectedFace + "\""),
                    "expected metrics for face=" + expectedFace + " in:\n" + scrape);
        }
        assertAllRegisteredMeterTagKeysInAllowlist(allowedTags);
    }

    private static void assertAllRegisteredMeterTagKeysInAllowlist() {
        assertAllRegisteredMeterTagKeysInAllowlist(
                java.util.Set.of("face", "status", "key_id", "provider", "base_url", "le"));
    }

    private static void assertAllRegisteredMeterTagKeysInAllowlist(java.util.Set<String> allowedTags) {
        assertFalse(TestMetricsFactory.REGISTRY.getMeters().isEmpty(), "expected registered meters");
        for (io.micrometer.core.instrument.Meter meter : TestMetricsFactory.REGISTRY.getMeters()) {
            for (io.micrometer.core.instrument.Tag tag : meter.getId().getTags()) {
                assertTrue(
                        allowedTags.contains(tag.getKey()),
                        "unallowlisted tag key '" + tag.getKey() + "' on meter " + meter.getId());
            }
        }
    }

    private HttpResponse<String> exchangeError(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = (HttpResponse<String>) e.getResponse();
            return response;
        }
    }

    /** Poll the shared registry until {@code series} appears (5 s bound), then scrape. */
    private static String awaitScrapeContaining(String series) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String scrape = TestMetricsFactory.REGISTRY.scrape();
            if (scrape.contains(series)) {
                return scrape;
            }
            Thread.sleep(10);
        }
        return TestMetricsFactory.REGISTRY.scrape();
    }

    private static String bodyWithPrompt(String content) {
        return "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"" + content
                + "\"}],\"stream\":false,\"max_tokens\":16}";
    }

    private static String streamingBodyWithPrompt(String content) {
        return "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"" + content
                + "\"}],\"stream\":true,\"max_tokens\":16}";
    }

    private static ChatResponse chatResponse(String echoed) {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage(echoed, null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static StreamChunk chunk(String text) {
        return new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, text, null), null)),
                null,
                Map.of());
    }
}
