package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.amscotti.janus.core.codec.OpenAiMessageCodec;
import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.UserMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * steps 3-4: {@link DeepSeekAdapter} complete/stream against a loopback fake upstream
 * (JDK {@code com.sun.net.httpserver.HttpServer}) — no real network. Covers the
 * passthrough wire-shape guard (request body byte-equals the codec encode), error-status
 * mapping, transport failures, base-URL normalization, streaming chunk sequence, upstream
 * error frames, and the stream-close-releases-the-connection contract.
 */
class DeepSeekAdapterTest {

    private static final OpenAiMessageCodec CODEC = OpenAiMessageCodec.create();

    private static final String RESPONSE_BODY =
            "{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"deepseek-v4-flash\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello from DeepSeek\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";

    private static final String SSE_BODY =
            "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}\n\n"
                    + "data: [DONE]\n\n";

    private HttpServer server;
    private URI baseUri;
    private DeepSeekAdapter adapter;
    private boolean contextRegistered;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedAuthHeader = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        adapter = new DeepSeekAdapter(baseUri.toString(), "test-key");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ------------------------------------------------------------- complete

    @Test
    void completeDecodesResponseAndSendsPassthroughWireShape() {
        serveResponse(200, "application/json", RESPONSE_BODY);
        ChatRequest request = request(false);

        ChatResponse response = adapter.complete(request);

        assertEquals("chatcmpl-123", response.id());
        assertEquals("deepseek-v4-flash", response.model());
        assertEquals(1, response.choices().size());
        assertEquals(
                "Hello from DeepSeek",
                ((AssistantMessage) response.choices().get(0).message()).content());
        assertEquals("stop", response.stopReason());
        assertEquals(15L, response.usage().totalTokens());
        // meta passes through untouched (gateway-internal)
        assertEquals("req-1", response.meta().get("request_id"));
        // request body is the codec's canonical encode with stream=false (no stream member)
        assertEquals(CODEC.encodeRequest(request), capturedBody.get());
        assertFalse(capturedBody.get().contains("\"stream\""));
        assertEquals("/v1/chat/completions", capturedPath.get());
    }

    @Test
    void completeForcesStreamFalseRegardlessOfCanonicalFlag() {
        serveResponse(200, "application/json", RESPONSE_BODY);
        ChatRequest streamingCanonical = request(true);

        adapter.complete(streamingCanonical);

        assertEquals(CODEC.encodeRequest(copyWithStream(streamingCanonical, false)), capturedBody.get());
        assertFalse(capturedBody.get().contains("\"stream\""));
    }

    @Test
    void authorizationHeaderCarriesBearerSecretOnBothPaths() {
        serveResponse(200, "application/json", RESPONSE_BODY);
        adapter.complete(request(false));
        assertEquals("Bearer test-key", capturedAuthHeader.get());

        serveResponse(200, "text/event-stream", "data: [DONE]\n\n");
        try (Stream<StreamChunk> stream = adapter.stream(request(true))) {
            stream.toList();
        }
        assertEquals("Bearer test-key", capturedAuthHeader.get());
    }

    @Test
    void blankSecretOmitsAuthorizationHeader() {
        adapter = new DeepSeekAdapter(baseUri.toString(), "");
        serveResponse(200, "application/json", RESPONSE_BODY);
        adapter.complete(request(false));
        assertNull(capturedAuthHeader.get());
    }

    @Test
    void discoveryFormIsInert() {
        // The ServiceLoader discovery form is blank-base inert (was armed with the
        // real default base URL + blank secret — a mis-wired discovery instance would
        // have issued real upstream requests without auth).
        DeepSeekAdapter discovery = new DeepSeekAdapter();
        assertEquals(DeepSeekAdapter.NAME, discovery.name());
        assertEquals("", discovery.baseUrl());
        assertEquals("", discovery.auth().secret());
    }

    @Test
    void discoveryInstanceCallFailsFastWithTypedError() {
        DeepSeekAdapter discovery = new DeepSeekAdapter();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> discovery.complete(request(false)));

        assertTrue(e.getMessage().contains("no base URL"), e.getMessage());
    }

    @Test
    void streamOptionsPreservedOnStreamAndDroppedOnComplete() {
        ChatRequest withOptions = new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("hi")),
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
                null,
                false,
                Map.of("include_usage", true),
                null,
                null,
                Map.of(),
                Map.of());

        // complete path drops the meaningless stream_options (adapter wire decision)
        serveResponse(200, "application/json", RESPONSE_BODY);
        adapter.complete(withOptions);
        assertFalse(capturedBody.get().contains("stream_options"));

        // stream path keeps them
        serveResponse(200, "text/event-stream", "data: [DONE]\n\n");
        try (Stream<StreamChunk> stream = adapter.stream(withOptions)) {
            stream.toList();
        }
        assertTrue(capturedBody.get().contains("\"stream_options\":{\"include_usage\":true}"), capturedBody.get());
        assertTrue(capturedBody.get().contains("\"stream\":true"), capturedBody.get());
    }

    @Test
    void completeMapsUpstreamStatuses() {
        assertStatus(401, ProviderException.TYPE_AUTH, false, 401);
        assertStatus(429, ProviderException.TYPE_RATE_LIMITED, true, 429);
        assertStatus(500, ProviderException.TYPE_UPSTREAM_5XX, true, 500);
        assertStatus(404, ProviderException.TYPE_UPSTREAM_4XX, false, 404);
    }

    @Test
    void completeThrowsNetworkWhenConnectionRefused() throws Exception {
        DeepSeekAdapter deadAdapter = new DeepSeekAdapter("http://127.0.0.1:" + freePort(), "test-key");

        ProviderException e = assertThrows(ProviderException.class, () -> deadAdapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_NETWORK, e.type());
        assertTrue(e.retryable());
    }

    @Test
    void completeThrowsTimeoutWhenUpstreamIsSlow() {
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(2_000);
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // client timed out and disconnected — expected
            }
        });
        DeepSeekAdapter slowAdapter =
                new DeepSeekAdapter(baseUri.toString(), "test-key", Duration.ofMillis(500), Duration.ofMillis(150));

        ProviderException e = assertThrows(ProviderException.class, () -> slowAdapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_TIMEOUT, e.type());
        assertTrue(e.retryable());
    }

    @Test
    void completeWrapsGarbageUpstreamBodyAsBadUpstreamPayload() {
        serveResponse(200, "application/json", "this is not json");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
        assertFalse(e.retryable());
    }

    @Test
    void baseUrlV1SuffixIsStrippedToCanonicalEndpoint() {
        DeepSeekAdapter withV1 = new DeepSeekAdapter(baseUri + "/v1", "test-key");
        DeepSeekAdapter withV1Slash = new DeepSeekAdapter(baseUri + "/v1/", "test-key");

        assertEquals(adapter.baseUrl(), withV1.baseUrl());
        assertEquals(adapter.baseUrl(), withV1Slash.baseUrl());

        serveResponse(200, "application/json", RESPONSE_BODY);
        withV1.complete(request(false));
        assertEquals("/v1/chat/completions", capturedPath.get());

        serveResponse(200, "application/json", RESPONSE_BODY);
        withV1Slash.complete(request(false));
        assertEquals("/v1/chat/completions", capturedPath.get());
    }

    // -------------------------------------------------------------- stream

    @Test
    void streamEmitsChunksAndTerminatesOnDone() {
        serveResponse(200, "text/event-stream", SSE_BODY);
        ChatRequest request = request(false);

        List<StreamChunk> chunks;
        try (Stream<StreamChunk> stream = adapter.stream(request)) {
            chunks = stream.toList();
        }

        assertEquals(3, chunks.size());
        assertEquals("assistant", chunks.get(0).choices().get(0).delta().role().wire());
        assertEquals("Hello", chunks.get(0).choices().get(0).delta().content());
        assertEquals(" world", chunks.get(1).choices().get(0).delta().content());
        assertEquals("stop", chunks.get(2).choices().get(0).finishReason());
        assertEquals(6L, chunks.get(2).usage().totalTokens());
        // wire body forces stream=true regardless of the canonical flag
        assertEquals(CODEC.encodeRequest(copyWithStream(request, true)), capturedBody.get());
        assertTrue(capturedBody.get().contains("\"stream\":true"));
    }

    @Test
    void streamErrorFrameTypes() {
        assertStreamErrorFrame(
                "{\"error\":{\"message\":\"bad key\",\"type\":\"authentication_error\"}}",
                ProviderException.TYPE_AUTH,
                false);
        assertStreamErrorFrame(
                "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_error\"}}",
                ProviderException.TYPE_RATE_LIMITED,
                true);
        assertStreamErrorFrame(
                "{\"error\":{\"message\":\"boom\",\"type\":\"server_error\"}}",
                ProviderException.TYPE_UPSTREAM_5XX,
                true);
        assertStreamErrorFrame("{\"error\":{\"message\":\"no type\"}}", ProviderException.TYPE_UPSTREAM_4XX, false);
        assertStreamErrorFrame(
                "{\"error\":{\"message\":\"quota\",\"status\":429}}", ProviderException.TYPE_RATE_LIMITED, true);
    }

    @Test
    void streamThrowsOnNon200BeforeAnyChunk() {
        serveResponse(503, "application/json", "{\"error\":{\"message\":\"unavailable\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.stream(request(false)));

        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
        assertTrue(e.retryable());
        assertEquals(Integer.valueOf(503), e.statusCode());
    }

    @Test
    void streamWrapsInvalidChunkAsBadUpstreamPayload() {
        serveResponse(200, "text/event-stream", "data: this is not json\n\n");

        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.toList();
            }
        });

        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
        assertFalse(e.retryable());
    }

    @Test
    void streamWrapsTruncatedFrameAsBadUpstreamPayload() {
        serveResponse(200, "text/event-stream", "data: {\"partial");

        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.toList();
            }
        });

        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
    }

    @Test
    void streamOfEmptySseIsTruncation() {
        // A 200 with an empty SSE body never carried the [DONE] sentinel — a truncated
        // stream (bad_upstream_payload), not a complete zero-chunk response (parity with
        // the Anthropic adapter: EOF without the terminal marker is always a truncation).
        serveResponse(200, "text/event-stream", "");

        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.count();
            }
        });

        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
    }

    @Test
    void streamThrowsNetworkWhenConnectionRefused() throws Exception {
        DeepSeekAdapter deadAdapter = new DeepSeekAdapter("http://127.0.0.1:" + freePort(), "test-key");

        ProviderException e = assertThrows(ProviderException.class, () -> deadAdapter.stream(request(false)));

        assertEquals(ProviderException.TYPE_NETWORK, e.type());
    }

    @Test
    void streamThrowsTimeoutWhenUpstreamIsSlow() {
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        DeepSeekAdapter slowAdapter =
                new DeepSeekAdapter(baseUri.toString(), "test-key", Duration.ofMillis(500), Duration.ofMillis(150));

        ProviderException e = assertThrows(ProviderException.class, () -> slowAdapter.stream(request(false)));

        assertEquals(ProviderException.TYPE_TIMEOUT, e.type());
    }

    @Test
    void closingStreamReleasesHttpConnection() throws Exception {
        AtomicBoolean disconnectObserved = new AtomicBoolean();
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0); // chunked — connection stays open
                OutputStream out = exchange.getResponseBody();
                out.write(
                        "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},\"finish_reason\":null}]}\n\n"
                                .getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(300); // the client closes the stream during this window
                // Keep writing until the client disconnect surfaces (broken pipe/reset):
                // the first post-close write can succeed into OS buffers, a later one
                // fails once the client's closed socket answers with RST.
                byte[] filler = new byte[16 * 1024];
                for (int i = 0; i < 100 && !disconnectObserved.get(); i++) {
                    out.write(filler);
                    out.flush();
                    Thread.sleep(10);
                }
            } catch (IOException e) {
                disconnectObserved.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
            StreamChunk first = stream.findFirst().orElseThrow();
            assertEquals("a", first.choices().get(0).delta().content());
            // close runs onClose → response body closed → connection released
        }

        awaitTrue(disconnectObserved, 2_000);
        assertTrue(disconnectObserved.get(), "server must observe the client disconnect after the stream is closed");
    }

    // -------------------------------------------------------------- helpers

    private void serveResponse(int status, String contentType, String body) {
        if (contextRegistered) {
            server.removeContext("/v1/chat/completions");
        }
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        contextRegistered = true;
    }

    private void assertStatus(int status, String type, boolean retryable, int statusCode) {
        serveResponse(status, "application/json", "{\"error\":{\"message\":\"nope\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(type, e.type());
        assertEquals(retryable, e.retryable());
        assertEquals(Integer.valueOf(statusCode), e.statusCode());
    }

    private void assertStreamErrorFrame(String frameData, String expectedType, boolean retryable) {
        serveResponse(200, "text/event-stream", "data: " + frameData + "\n\n");

        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.toList();
            }
        });

        assertEquals(expectedType, e.type());
        assertEquals(retryable, e.retryable());
    }

    private static ChatRequest request(boolean stream) {
        return new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("hello")),
                "be concise",
                null,
                null,
                0.7,
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
                stream,
                null,
                null,
                null,
                Map.of(),
                Map.of("request_id", "req-1"));
    }

    /** Mirrors the adapter's wire decision: stream copy with stream_options dropped when false. */
    private static ChatRequest copyWithStream(ChatRequest request, boolean stream) {
        return new ChatRequest(
                request.model(),
                request.messages(),
                request.system(),
                request.tools(),
                request.toolChoice(),
                request.temperature(),
                request.topP(),
                request.topK(),
                request.maxTokens(),
                request.stop(),
                request.seed(),
                request.n(),
                request.frequencyPenalty(),
                request.presencePenalty(),
                request.logitBias(),
                request.responseFormat(),
                stream,
                stream ? request.streamOptions() : null,
                request.reasoning(),
                request.cacheControl(),
                request.extras(),
                request.meta());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitTrue(AtomicBoolean flag, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!flag.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
