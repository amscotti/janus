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
