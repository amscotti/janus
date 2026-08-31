package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.OpenAiMessageCodec;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.provider.ProviderException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * C2 — {@code /v1/chat/completions} integration (plan steps 6-8): non-streaming
 * 200 with codec-byte-equal body, OpenAI error envelopes (400/404/401), and the SSE
 * streaming path read with a raw HTTP client to pin the exact {@code data:...} frame
 * bytes + terminal {@code data: [DONE]} (the #1 byte-shape drift risk).
 */
@MicronautTest
class ChatCompletionsControllerTest {

    private static final OpenAiMessageCodec CODEC = OpenAiMessageCodec.create();

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    EmbeddedServer server;

    // ------------------------------------------------------------ non-streaming

    @Test
    void nonStreamingReturnsCodecByteEqualBody() {
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new io.amscotti.janus.core.model.AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
        TestRouterFactory.BACKEND.completeReturns(response);

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/chat/completions", requestBody(false))
                                .contentType(MediaType.APPLICATION_JSON),
                        String.class);

        assertEquals(HttpStatus.OK, http.getStatus());
        assertEquals(CODEC.encodeResponse(response), http.body());
    }

    @Test
    void malformedRequestIs400OpenAiEnvelope() {
        HttpResponse<String> http = errorResponse(
                HttpRequest.POST("/v1/chat/completions", "{not json").contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
    }

    @Test
    void nullLiteralBodyIs400InvalidRequestError() {
        // A JSON literal `null` body used to bind the DTO to null and NPE
        // inside the codec — a 500 api_error for purely client-malformed input. OpenAI
        // 400s a null body; so must the gateway.
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/chat/completions", "null").contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
    }

    @Test
    void emptyBodyIs400InvalidRequestError() {
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/chat/completions", "").contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
    }

    @Test
    void unknownModelIs404ModelNotFound() {
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/chat/completions", requestBody("no-such-model", false))
                        .contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.NOT_FOUND, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
        assertTrue(http.body().contains("\"code\":\"model_not_found\""), http.body());
    }

    @Test
    void upstreamAuthFailureIs401OpenAiEnvelope() {
        TestRouterFactory.BACKEND.completeFails(new ProviderException(ProviderException.TYPE_AUTH, "invalid api key"));

        HttpResponse<String> http = errorResponse(
                HttpRequest.POST("/v1/chat/completions", requestBody(false)).contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.UNAUTHORIZED, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"authentication_error\""), http.body());
    }

    // --------------------------------------------------------------- streaming

    @Test
    void streamingEmitsByteExactSseFramesAndDone() throws Exception {
        StreamChunk c1 = new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "Hello", null), null)),
                null,
                Map.of());
        StreamChunk c2 = new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(null, " world", null), null)),
                null,
                Map.of());
        TestRouterFactory.BACKEND.streamReturns(Stream.of(c1, c2));

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody(true)))
                .build();
        java.net.http.HttpResponse<InputStream> response =
                http.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
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

        // Byte-exact SSE framing: data: <chunk json> \n\n... data: [DONE] \n\n
        assertEquals(
                List.of("data: " + CODEC.encodeChunk(c1), "", "data: " + CODEC.encodeChunk(c2), "", "data: [DONE]", ""),
                lines,
                "SSE frames must be byte-exact (byte-shape drift guard)");
    }

    // ---------------------------------------------------------------- helpers

    /** {@code exchange()} throws on non-2xx; return the error response body instead. */
    private HttpResponse<String> errorResponse(HttpRequest<?> request) {
        io.micronaut.http.client.exceptions.HttpClientResponseException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        io.micronaut.http.client.exceptions.HttpClientResponseException.class,
                        () -> client.toBlocking().exchange(request, String.class));
        io.micronaut.http.HttpResponse<?> response = exception.getResponse();
        return io.micronaut.http.HttpResponse.status(response.getStatus())
                .body(response.getBody(String.class).orElse(""));
    }

    private static String requestBody(boolean stream) {
        return requestBody("deepseek-v4-flash", stream);
    }

    private static String requestBody(String model, boolean stream) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":" + stream
                + "}";
    }
}
