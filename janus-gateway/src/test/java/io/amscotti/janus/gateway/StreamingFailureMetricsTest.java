package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.provider.ProviderException;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A stream that fails mid-flight (the fake backend dies after the first
 * chunk) must be recorded in {@code janus_requests_total{face="openai",status="5xx"}},
 * never in the 2xx bucket: the publisher emits the SSE error frame to the client
 * <b>and</b> threads the mapped 5xx terminal outcome into the {@code recordRequest}
 * close hook. Without this, a provider dying mid-stream silently inflates the 2xx
 * error-free rate (the {@code ERROR_UPSTREAM} CallRecord and the metrics series
 * would disagree). End-to-end through the real SSE endpoint + shared registry (the
 * {@link TestRouterFactory} fake backend supplies the failing stream).
 */
@MicronautTest
@Property(name = "janus.test.metrics", value = "true")
@Property(name = "janus.test.governance", value = "true")
class StreamingFailureMetricsTest {

    @Inject
    EmbeddedServer server;

    @BeforeEach
    void reset() {
        TestMetricsFactory.REGISTRY.clear();
    }

    @Test
    void midStreamBackendFailureReachesClientAsErrorFrameAndRecords5xx() throws Exception {
        StreamChunk c1 = new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "Hello", null), null)),
                null,
                Map.of());
        // The fake backend yields one chunk, then the upstream dies mid-iteration.
        TestRouterFactory.BACKEND.streamReturns(
                TestStreams.failingAfter(c1, new ProviderException(ProviderException.TYPE_TIMEOUT, "boom")));

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body(true)))
                .build();
        java.net.http.HttpResponse<InputStream> response = java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode(), "a started stream is still HTTP 200 — the failure is a frame");

        String all;
        try (InputStream body = response.body()) {
            StringBuilder sb = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            all = sb.toString();
        }
        assertTrue(all.contains("data: "), "the chunk frame must reach the client:\n" + all);
        assertTrue(all.contains("\"type\":\"api_error\""), "the SSE error frame must reach the client:\n" + all);

        // The request is recorded on stream close (server side) — poll until the
        // series lands, then assert the 5xx bucket got it and no 2xx for this face.
        String scrape = awaitScrapeContaining("janus_requests_total");
        assertTrue(
                scrape.contains("janus_requests_total{face=\"openai\",status=\"5xx\"} 1.0"),
                "a mid-stream failure must land in the 5xx bucket:\n" + scrape);
        assertFalse(
                scrape.contains("janus_requests_total{face=\"openai\",status=\"2xx\"}"),
                "a failed stream must never be recorded as 2xx:\n" + scrape);
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

    private static String body(boolean stream) {
        return "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":"
                + stream + "}";
    }
}
