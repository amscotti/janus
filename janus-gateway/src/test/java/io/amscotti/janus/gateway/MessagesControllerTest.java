package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.AnthropicMessageCodec;
import io.amscotti.janus.core.codec.AnthropicSseEvent;
import io.amscotti.janus.core.codec.AnthropicStreamEncoder;
import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.ToolCall;
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
 * C2 — {@code /v1/messages} integration (plan steps 7-8): non-streaming 200 with
 * codec-byte-equal body and {@code application/json} (request is always
 * {@code application/json}; the {@code anthropic-version}/{@code anthropic-beta}
 * headers are accepted without validation — {@code anthropic-beta} is folded into
 * meta and forwarded upstream), Anthropic error envelopes (400/404/401/429), and the
 * SSE streaming path
 * read with a raw HTTP client to pin the byte-exact {@code event: X\ndata: Y\n\n}
 * named frames + the terminal {@code message_stop} (no {@code [DONE]}) — the plan's #1
 * byte-shape drift risk, probed in Step 1. Tool paths render through the face (
 * behavior rendered, not re-implemented): {@code tool_use}/{@code stop_reason:"tool_use"}
 * non-streaming bodies and {@code input_json_delta} streaming frames.
 */
@MicronautTest
class MessagesControllerTest {

    private static final AnthropicMessageCodec CODEC = AnthropicMessageCodec.create();

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    EmbeddedServer server;

    // ------------------------------------------------------------ non-streaming

    @Test
    void nonStreamingReturnsCodecByteEqualBodyWithJsonContentType() {
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "end_turn")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
        TestRouterFactory.BACKEND.completeReturns(response);

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/messages", anthropicBody(false))
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("anthropic-version", "2023-06-01")
                                .header("beta", "tools-2024-04-04"),
                        String.class);

        assertEquals(HttpStatus.OK, http.getStatus());
        assertEquals(
                MediaType.APPLICATION_JSON,
                http.getContentType().map(MediaType::toString).orElse(null));
        assertEquals(CODEC.encodeResponse(response), http.body());
    }

    @Test
    void malformedRequestIs400InvalidRequestErrorEnvelope() {
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/messages", "{not json").contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"error\""), http.body());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
        // The codec owns the message text (/ scope); the face only guarantees the
        // envelope shape + a recognisable client-error hint.
        assertTrue(http.body().contains("invalid"), http.body());
    }

    @Test
    void nullLiteralBodyIs400InvalidRequestErrorEnvelope() {
        // A JSON literal `null` body used to bind the DTO to null and NPE
        // inside the codec — a 500 api_error for purely client-malformed input. The
        // real Anthropic API 400s a null body; so must the gateway.
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/messages", "null").contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"error\""), http.body());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
    }

    @Test
    void emptyBodyIs400InvalidRequestErrorEnvelope() {
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/messages", "").contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"error\""), http.body());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
    }

    @Test
    void unknownModelIs404NotFoundError() {
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/messages", anthropicBody("no-such-model", false))
                        .contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.NOT_FOUND, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"error\""), http.body());
        assertTrue(http.body().contains("\"type\":\"not_found_error\""), http.body());
    }

    @Test
    void upstreamAuthFailureIs401AuthenticationError() {
        TestRouterFactory.BACKEND.completeFails(new ProviderException(ProviderException.TYPE_AUTH, "invalid api key"));

        HttpResponse<String> http = errorResponse(
                HttpRequest.POST("/v1/messages", anthropicBody(false)).contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.UNAUTHORIZED, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"error\""), http.body());
        assertTrue(http.body().contains("\"type\":\"authentication_error\""), http.body());
        assertTrue(http.body().contains("invalid api key"), http.body());
    }

    @Test
    void upstreamRateLimitIs429RateLimitError() {
        TestRouterFactory.BACKEND.completeFails(
                new ProviderException(ProviderException.TYPE_RATE_LIMITED, "slow down"));

        HttpResponse<String> http = errorResponse(
                HttpRequest.POST("/v1/messages", anthropicBody(false)).contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"error\""), http.body());
        assertTrue(http.body().contains("\"type\":\"rate_limit_error\""), http.body());
    }

    @Test
    void nonStreamingToolResponseRendersToolUseBlocksAndStopReason() {
        // behavior rendered: canonical tool_calls → Anthropic tool_use content
        // blocks + stop_reason "tool_use" (byte-asserted via the codec itself).
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(
                        0,
                        new AssistantMessage(
                                "I'll check.",
                                List.of(new ToolCall(
                                        "toolu_1", "function", new FunctionCall("get_weather", "{\"city\":\"SF\"}")))),
                        "tool_calls")),
                null,
                ChatResponse.STOP_REASON_TOOL_CALLS,
                Map.of(),
                Map.of());
        TestRouterFactory.BACKEND.completeReturns(response);

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/messages", anthropicBodyWithTools(false))
                                .contentType(MediaType.APPLICATION_JSON),
                        String.class);

        assertEquals(HttpStatus.OK, http.getStatus());
        assertEquals(CODEC.encodeResponse(response), http.body());
        assertTrue(http.body().contains("\"type\":\"tool_use\""), http.body());
        assertTrue(http.body().contains("\"stop_reason\":\"tool_use\""), http.body());
    }

    // --------------------------------------------------------------- streaming

    @Test
    void streamingEmitsByteExactNamedFramesTerminatedByMessageStop() throws Exception {
        List<StreamChunk> chunks = List.of(
                chunk("Hello"),
                new StreamChunk(
                        "msg_1",
                        "message",
                        1_700_000_000L,
                        "deepseek-v4-flash",
                        List.of(new ChunkChoice(0, new Delta(null, " world", null), "end_turn")),
                        null,
                        Map.of()));
        TestRouterFactory.BACKEND.streamReturns(Stream.of(chunks.toArray(StreamChunk[]::new)));

        List<String> lines = rawStreamLines(anthropicBody(true), Map.of());
        assertEquals(expectedFrameLines(chunks), lines, "SSE frames must be byte-exact (named event/data pairs)");
        assertFalse(lines.contains("data: [DONE]"), "Anthropic streams end with message_stop, never [DONE]");
        assertTrue(lines.contains("event: message_stop"), "terminal event must be present");
        assertTrue(lines.contains("event: message_start"), "opener event must be present");
    }

    @Test
    void streamingContentTypeIsTextEventStream() throws Exception {
        TestRouterFactory.BACKEND.streamReturns(Stream.of(chunk("Hello")));

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(anthropicBody(true)))
                .build();
        java.net.http.HttpResponse<InputStream> response =
                http.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertEquals(
                "text/event-stream",
                response.headers().firstValue("Content-Type").orElse(""));
        // drain to release the connection
        try (InputStream body = response.body()) {
            body.readAllBytes();
        }
    }

    @Test
    void streamingMidStreamFailureEmitsErrorFrameThenCleanEnd() throws Exception {
        StreamChunk c1 = chunk("Hello");
        Stream<StreamChunk> failing =
                TestStreams.failingAfter(c1, new ProviderException(ProviderException.TYPE_NETWORK, "connection reset"));
        TestRouterFactory.BACKEND.streamReturns(failing);

        List<String> lines = rawStreamLines(anthropicBody(true), Map.of());
        assertTrue(lines.contains("event: error"), "mid-stream failure must emit a named error event: " + lines);
        assertTrue(
                lines.contains(
                        "data: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"connection reset\"}}"),
                lines.toString());
        // The error frame is terminal: finish is skipped on the failure path (the
        // upstream is dead, encoder state discarded) — no message_stop after error.
        assertEquals("event: error", lines.get(lines.size() - 3), "error frame must be the last frame: " + lines);
        assertFalse(lines.contains("event: message_stop"), "no terminal sequence after an error frame: " + lines);
    }

    @Test
    void streamingToolRequestEmitsInputJsonDeltaFrames() throws Exception {
        // Anthropic request with tools → canonical tools → fake stream with
        // tool-call fragments → Anthropic input_json_delta frames, byte-asserted.
        List<StreamChunk> chunks = List.of(
                toolChunk("call_1", "get_weather", "{\"city\":\"S"),
                toolChunk(null, null, "an"),
                new StreamChunk(
                        "msg_1",
                        "message",
                        1_700_000_000L,
                        "deepseek-v4-flash",
                        List.of(new ChunkChoice(
                                0,
                                new Delta(
                                        null,
                                        null,
                                        List.of(new ToolCall(null, "function", new FunctionCall(null, "}")))),
                                "tool_calls")),
                        null,
                        Map.of()));
        TestRouterFactory.BACKEND.streamReturns(Stream.of(chunks.toArray(StreamChunk[]::new)));

        List<String> lines = rawStreamLines(anthropicBodyWithTools(true), Map.of());
        assertEquals(expectedFrameLines(chunks), lines, "tool frames must be byte-exact");
        assertTrue(lines.contains("event: content_block_start"), lines.toString());
        assertTrue(
                lines.stream().anyMatch(l -> l.startsWith("data: ") && l.contains("\"input_json_delta\"")),
                lines.toString());
        assertTrue(lines.contains("event: message_stop"), lines.toString());
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

    /**
     * POST {@code /v1/messages} over a raw JDK HTTP client and read every line of the
     * SSE body — byte-exact {@code event: X\ndata: Y\n\n} frames (the named-frame
     * drift risk probed in Step 1).
     */
    private List<String> rawStreamLines(String body, Map<String, String> headers) throws Exception {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/v1/messages"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        java.net.http.HttpResponse<InputStream> response =
                http.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
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
        return lines;
    }

    /** The byte-exact SSE lines one stateful encoder produces for the whole stream. */
    private static List<String> expectedFrameLines(List<StreamChunk> chunks) throws Exception {
        AnthropicStreamEncoder encoder = CODEC.newStreamEncoder();
        List<String> lines = new ArrayList<>();
        for (StreamChunk chunk : chunks) {
            for (AnthropicSseEvent frame : encoder.feed(chunk)) {
                lines.add("event: " + frame.event());
                lines.add("data: " + frame.dataJson());
                lines.add("");
            }
        }
        for (AnthropicSseEvent frame : encoder.finish()) {
            lines.add("event: " + frame.event());
            lines.add("data: " + frame.dataJson());
            lines.add("");
        }
        return lines;
    }

    private static StreamChunk chunk(String content) {
        return new StreamChunk(
                "msg_1",
                "message",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, content, null), null)),
                null,
                Map.of());
    }

    private static StreamChunk toolChunk(String id, String name, String arguments) {
        return new StreamChunk(
                "msg_1",
                "message",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(
                        0,
                        new Delta(
                                ChatRole.ASSISTANT,
                                null,
                                List.of(new ToolCall(id, "function", new FunctionCall(name, arguments)))),
                        null)),
                null,
                Map.of());
    }

    private static String anthropicBody(boolean stream) {
        return anthropicBody("deepseek-v4-flash", stream);
    }

    private static String anthropicBody(String model, boolean stream) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":16,"
                + "\"stream\":" + stream + "}";
    }

    private static String anthropicBodyWithTools(boolean stream) {
        return "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"what's the weather?\"}],"
                + "\"max_tokens\":64,\"stream\":" + stream
                + ",\"tools\":[{\"name\":\"get_weather\",\"description\":\"Get the weather for a city\","
                + "\"input_schema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}]}";
    }
}
