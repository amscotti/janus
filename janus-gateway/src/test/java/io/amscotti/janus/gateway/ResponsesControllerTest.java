package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.Usage;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /v1/responses} integration against the
 * canned {@link TestRouterFactory#BACKEND} upstream: the full decode → canonical →
 * fake-upstream → encode round trip, the face's envelope on the 400 rows, the
 * stream rejection, the stub retrieval routes, and Tier-1 metering on the
 * {@code responses} face label (decision A).
 */
@MicronautTest
@Property(name = "janus.test.metrics", value = "true")
class ResponsesControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    io.micronaut.runtime.server.EmbeddedServer server;

    @BeforeEach
    void reset() {
        TestMetricsFactory.REGISTRY.clear();
        TestRouterFactory.BACKEND.completeReturns(new ChatResponse(
                "chatcmpl-test",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                null));
    }

    @Test
    void nonStreamingRoundTripEncodesTheResponsesShape() {
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/v1/responses",
                                        "{\"model\":\"deepseek-v4-flash\",\"input\":\"hello\",\"instructions\":\"be brief\"}")
                                .contentType(MediaType.APPLICATION_JSON),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        String body = http.body();
        assertTrue(body.contains("\"object\":\"response\""), body);
        assertTrue(body.contains("\"id\":\"resp_chatcmpl-test\""), body);
        assertTrue(body.contains("\"instructions\":\"be brief\""), body);
        assertTrue(body.contains("\"text\":\"Hello!\""), body);
        assertTrue(body.contains("\"store\":false"), body);
        assertTrue(body.contains("\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}"), body);
    }

    @Test
    void stateless400RowsRenderTheOpenAiEnvelope() {
        for (String body : new String[] {
            "{\"model\":\"deepseek-v4-flash\",\"input\":\"hi\",\"store\":true}",
            "{\"model\":\"deepseek-v4-flash\",\"input\":\"hi\",\"previous_response_id\":\"resp_1\"}",
            "{\"model\":\"deepseek-v4-flash\",\"input\":\"hi\",\"background\":true}"
        }) {
            HttpResponse<String> http =
                    errorResponse(HttpRequest.POST("/v1/responses", body).contentType(MediaType.APPLICATION_JSON));
            assertEquals(HttpStatus.BAD_REQUEST, http.getStatus(), body);
            assertTrue(http.body().contains("\"invalid_request_error\""), http.body());
        }
    }

    @Test
    void unknownModelIs404ModelNotFound() {
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/responses", "{\"model\":\"no-such-model\",\"input\":\"hi\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.NOT_FOUND, http.getStatus());
        assertTrue(http.body().contains("model_not_found"), http.body());
    }

    @Test
    void streamingDeliversTheResponsesEventGrammar() throws Exception {
        // stream:true streams — created.. completed over text/event-stream,
        // with response.completed carrying usage (decision B: the ingress decode
        // forces include_usage upstream, so this face always settles usage).
        TestRouterFactory.BACKEND.streamReturns(java.util.stream.Stream.of(
                new io.amscotti.janus.core.model.StreamChunk(
                        "c1",
                        "chat.completion.chunk",
                        1L,
                        "deepseek-v4-flash",
                        List.of(new io.amscotti.janus.core.model.ChunkChoice(
                                0, new io.amscotti.janus.core.model.Delta(null, "Hi", null), null)),
                        null,
                        Map.of()),
                new io.amscotti.janus.core.model.StreamChunk(
                        "c1",
                        "chat.completion.chunk",
                        1L,
                        "deepseek-v4-flash",
                        List.of(new io.amscotti.janus.core.model.ChunkChoice(
                                0, new io.amscotti.janus.core.model.Delta(null, null, null), "stop")),
                        new Usage(10, 5, 15),
                        Map.of())));

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpResponse<java.io.InputStream> response = http.send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("http://localhost:" + server.getPort() + "/v1/responses"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"deepseek-v4-flash\",\"input\":\"hi\",\"stream\":true}"))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertEquals(
                "text/event-stream",
                response.headers().firstValue("Content-Type").orElse(""));

        StringBuilder sse = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sse.append(line).append('\n');
            }
        }
        String body = sse.toString();
        assertTrue(body.contains("event: response.created"), body);
        assertTrue(body.contains("event: response.output_text.delta"), body);
        assertTrue(body.contains("event: response.completed"), body);
        assertTrue(body.contains("\"usage\":{\"input_tokens\":10"), body);
    }

    @Test
    void stubRetrievalRoutesRenderTheEnvelope404() {
        for (io.micronaut.http.HttpMethod method :
                List.of(io.micronaut.http.HttpMethod.GET, io.micronaut.http.HttpMethod.DELETE)) {
            HttpResponse<String> http = errorResponse(
                    HttpRequest.create(method, "/v1/responses/resp_123").contentType(MediaType.APPLICATION_JSON));
            assertEquals(HttpStatus.NOT_FOUND, http.getStatus());
            assertTrue(http.body().contains("response_not_found"), http.body());
            assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
        }
    }

    @Test
    void responsesFaceIsTier1MeteredOnItsOwnLabel() {
        // Decision A: face="responses" — distinct SLO series, never folded into openai.
        client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/responses", "{\"model\":\"deepseek-v4-flash\",\"input\":\"hi\"}")
                                .contentType(MediaType.APPLICATION_JSON),
                        String.class);
        errorResponse(
                HttpRequest.POST("/v1/responses", "{\"model\":\"deepseek-v4-flash\",\"input\":\"hi\",\"store\":true}")
                        .contentType(MediaType.APPLICATION_JSON));

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"responses\",status=\"2xx\"} 1.0"),
                "the responses face must be metered on its own label:\n" + scrape);
        assertTrue(
                scrape.contains("janus_requests_total{face=\"responses\",status=\"4xx\"} 1.0"),
                "400s meter 4xx on the responses face:\n" + scrape);
    }

    /** 4xx responses surface as {@code HttpClientResponseException} — read the body out. */
    private HttpResponse<String> errorResponse(HttpRequest<?> request) {
        HttpClientResponseException exception = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class, () -> client.toBlocking().exchange(request, String.class));
        HttpResponse<?> response = exception.getResponse();
        return HttpResponse.status(response.getStatus())
                .body(response.getBody(String.class).orElse(""));
    }
}
