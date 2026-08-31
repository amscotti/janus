package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * end-to-end recording through <b>both controllers</b> (real
 * {@link Governance} + the shared real {@link PrometheusMeterRegistry} via
 * {@link TestMetricsFactory}; auth on so the per-key series are exercised):
 *
 * <ul>
 * <li>a successful keyed request on each face lands in the 2xx bucket and the
 * latency histogram, records the unlabeled + per-key token/cost series with the
 * exact micro-USD cost (10×0.14 + 5×0.28 = 2 800, the DeepSeek fixtures);
 * <li>a governance-denied request (rpm exceeded) lands in the 4xx bucket on both
 * faces (never dispatched upstream);
 * <li>a {@code ProviderException} (fake upstream 5xx) lands in the 5xx bucket;
 * <li>a streamed request records its 2xx + duration on stream close and its usage
 * from the terminal usage chunk.
 * </ul>
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
@Property(name = "janus.test.governance", value = "true")
@Property(name = "janus.test.metrics", value = "true")
class MetricsControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    EmbeddedServer server;

    @Inject
    KeyStore keyStore;

    @BeforeEach
    void reset() {
        TestMetricsFactory.REGISTRY.clear();
    }

    // ------------------------------------------------- non-streaming, both faces

    @Test
    void openAiSuccessRecordsAllTier1Series() {
        KeyStore.CreatedKey created = createKey();
        TestRouterFactory.BACKEND.completeReturns(chatResponse());

        assertEquals(HttpStatus.OK, postOpenAi(created.fullKey()).getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertLine(scrape, "janus_requests_total{face=\"openai\",status=\"2xx\"} 1.0");
        assertLine(scrape, "janus_request_duration_seconds_count{face=\"openai\"} 1");
        assertLine(scrape, "janus_tokens_in_total 10.0");
        assertLine(scrape, "janus_tokens_out_total 5.0");
        assertLine(scrape, "janus_cost_micro_usd_total 2800.0");
        assertLine(
                scrape, "janus_key_requests_total{key_id=\"" + created.record().id() + "\"} 1.0");
        assertLine(
                scrape, "janus_key_tokens_in_total{key_id=\"" + created.record().id() + "\"} 10.0");
        assertLine(
                scrape,
                "janus_key_tokens_out_total{key_id=\"" + created.record().id() + "\"} 5.0");
        assertLine(
                scrape,
                "janus_key_cost_micro_usd_total{key_id=\"" + created.record().id() + "\"} 2800.0");
    }

    @Test
    void anthropicSuccessRecordsAllTier1Series() {
        KeyStore.CreatedKey created = createKey();
        TestRouterFactory.BACKEND.completeReturns(chatResponse());

        assertEquals(HttpStatus.OK, postAnthropic(created.fullKey()).getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertLine(scrape, "janus_requests_total{face=\"anthropic\",status=\"2xx\"} 1.0");
        assertLine(scrape, "janus_request_duration_seconds_count{face=\"anthropic\"} 1");
        assertLine(scrape, "janus_tokens_in_total 10.0");
        assertLine(scrape, "janus_tokens_out_total 5.0");
        assertLine(scrape, "janus_cost_micro_usd_total 2800.0");
        assertLine(
                scrape, "janus_key_requests_total{key_id=\"" + created.record().id() + "\"} 1.0");
        assertLine(
                scrape,
                "janus_key_cost_micro_usd_total{key_id=\"" + created.record().id() + "\"} 2800.0");
    }

    // -------------------------------------------------------- status buckets

    @Test
    void governanceDeniedRequestLandsIn4xxBucketOnOpenAiFace() {
        KeyStore.CreatedKey created = createKey(null, 1, null); // rpm = 1
        TestRouterFactory.BACKEND.completeReturns(chatResponse());

        assertEquals(HttpStatus.OK, postOpenAi(created.fullKey()).getStatus());
        TestRouterFactory.BACKEND.completeCalls.clear();
        HttpResponse<?> denied = errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertEquals(0, TestRouterFactory.BACKEND.completeCalls.size(), "the denied request must not dispatch");

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertLine(scrape, "janus_requests_total{face=\"openai\",status=\"2xx\"} 1.0");
        assertLine(scrape, "janus_requests_total{face=\"openai\",status=\"4xx\"} 1.0");
        assertTrue(!scrape.contains("status=\"5xx\""), scrape);
    }

    @Test
    void governanceDeniedRequestLandsIn4xxBucketOnAnthropicFace() {
        KeyStore.CreatedKey created = createKey(null, 1, null);
        TestRouterFactory.BACKEND.completeReturns(chatResponse());

        assertEquals(HttpStatus.OK, postAnthropic(created.fullKey()).getStatus());
        HttpResponse<?> denied = errorResponse(postRequest("/v1/messages", created.fullKey(), anthropicBody()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertLine(scrape, "janus_requests_total{face=\"anthropic\",status=\"2xx\"} 1.0");
        assertLine(scrape, "janus_requests_total{face=\"anthropic\",status=\"4xx\"} 1.0");
    }

    @Test
    void providerFailureLandsIn5xxBucket() {
        KeyStore.CreatedKey created = createKey();
        TestRouterFactory.BACKEND.completeFails(new ProviderException(ProviderException.TYPE_UPSTREAM_5XX, "boom"));

        HttpResponse<?> failed = errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody()));
        assertEquals(HttpStatus.BAD_GATEWAY, failed.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertLine(scrape, "janus_requests_total{face=\"openai\",status=\"5xx\"} 1.0");
        assertTrue(!scrape.contains("status=\"2xx\""), scrape);
    }

    // ---------------------------------------------------------------- streaming

    @Test
    void streamingRecordsOnCloseFromTerminalUsageChunk() throws Exception {
        KeyStore.CreatedKey created = createKey();
        TestRouterFactory.BACKEND.streamReturns(Stream.of(contentChunk("Hello"), usageChunk(new Usage(10, 5, 15))));

        java.net.http.HttpResponse<InputStream> response = streamResponse(openAiBody(true), created.fullKey());
        assertEquals(
                "text/event-stream",
                response.headers().firstValue("Content-Type").orElse(""));
        try (InputStream body = response.body()) {
            readAll(body);
        }
        // The publisher closes the upstream stream at exhaustion — the onClose hook
        // records the request and the wrap settles usage from the terminal chunk.
        awaitCondition(() -> TestMetricsFactory.REGISTRY.scrape().contains("janus_request_duration_seconds_count"));

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertLine(scrape, "janus_requests_total{face=\"openai\",status=\"2xx\"} 1.0");
        assertLine(scrape, "janus_tokens_in_total 10.0");
        assertLine(
                scrape, "janus_key_tokens_in_total{key_id=\"" + created.record().id() + "\"} 10.0");
        assertLine(
                scrape,
                "janus_key_cost_micro_usd_total{key_id=\"" + created.record().id() + "\"} 2800.0");
    }

    // ------------------------------------------------------------------ helpers

    private KeyStore.CreatedKey createKey() {
        return createKey(1.0, null, null);
    }

    private KeyStore.CreatedKey createKey(Double budgetUsd, Integer rpm, Integer tpm) {
        return keyStore.create(new KeyStore.KeyCreateRequest(
                "metrics-test", List.of("deepseek-v4-flash"), null, budgetUsd, null, rpm, tpm));
    }

    private HttpResponse<String> postOpenAi(String fullKey) {
        return client.toBlocking().exchange(postRequest("/v1/chat/completions", fullKey, openAiBody()), String.class);
    }

    private HttpResponse<String> postAnthropic(String fullKey) {
        return client.toBlocking().exchange(postRequest("/v1/messages", fullKey, anthropicBody()), String.class);
    }

    private HttpRequest<String> postRequest(String path, String fullKey, String body) {
        return HttpRequest.POST(path, body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", fullKey);
    }

    /** {@code exchange()} throws on non-2xx; return the exception's response (headers kept). */
    private HttpResponse<?> errorResponse(HttpRequest<?> request) {
        HttpClientResponseException exception = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class, () -> client.toBlocking().exchange(request, String.class));
        return exception.getResponse();
    }

    private java.net.http.HttpResponse<InputStream> streamResponse(String body, String fullKey) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("x-api-key", fullKey)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        return java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
    }

    private static String openAiBody() {
        return openAiBody(false);
    }

    private static String openAiBody(boolean stream) {
        return "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":"
                + stream + ",\"max_tokens\":16}";
    }

    private static String anthropicBody() {
        return "{\"model\":\"deepseek-v4-flash\",\"max_tokens\":16,\"stream\":false,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private static ChatResponse chatResponse() {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static StreamChunk contentChunk(String text) {
        return new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, text, null), null)),
                null,
                Map.of());
    }

    private static StreamChunk usageChunk(Usage usage) {
        return new StreamChunk(
                "chatcmpl-1", "chat.completion.chunk", 1_700_000_000L, "deepseek-v4-flash", List.of(), usage, Map.of());
    }

    private static String readAll(InputStream body) throws Exception {
        StringBuilder all = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
            }
        }
        return all.toString();
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                org.junit.jupiter.api.Assertions.fail("condition not met within 5s");
            }
            Thread.sleep(10);
        }
    }

    /** Line-exact scrape assertion (the step-4 plan's "scrape text exact" bar). */
    private static void assertLine(String scrape, String expected) {
        assertTrue(
                scrape.lines().anyMatch(line -> line.equals(expected)),
                "expected line: " + expected + "\nscrape:\n" + scrape);
    }
}
