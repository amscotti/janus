package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.amscotti.janus.core.codec.AnthropicMessageCodec;
import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * steps 4-5: {@link AnthropicAdapter} — the native Anthropic Messages upstream —
 * against a loopback fake (JDK {@code com.sun.net.httpserver.HttpServer}), no real
 * network. Pins the wire contract: {@code POST {base}/v1/messages} with {@code x-api-key}
 * + {@code anthropic-version} headers, base-URL {@code /v1} normalization, the forced
 * {@code stream} flag, request-body byte-equality with {@link
 * AnthropicMessageCodec#encodeRequest}, response decode + meta passthrough, the status /
 * error-envelope mapping table (incl. retryable 529), and the streaming sequence
 * (no-op events skipped, {@code message_stop} terminates, no {@code [DONE]}, EOF
 * tolerance, truncation detection, {@code event: error} frames classified, close
 * releases the connection).
 */
class AnthropicAdapterTest {

    private static final AnthropicMessageCodec CODEC = AnthropicMessageCodec.create();

    private static final String RESPONSE_BODY =
            "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3-5-sonnet\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"Hello from Claude\"}],"
                    + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                    + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";

    /** The canonical Anthropic streaming sequence (interleaved ping + EOF-after-message_stop
     * variants built by the tests). Real usage shapes — message_start carries the prompt
     * count (input_tokens), message_delta carries output_tokens ONLY (never input_tokens);
     * the per-stream decoder merges them onto the terminal chunk. */
    private static final String SSE_BODY = "event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
            + "event: content_block_start\n"
            + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
            + "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n"
            + "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}\n\n"
            + "event: content_block_stop\n"
            + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
            + "event: ping\n"
            + "data: {\"type\":\"ping\"}\n\n"
            + "event: message_delta\n"
            + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}\n\n"
            + "event: message_stop\n"
            + "data: {\"type\":\"message_stop\"}\n\n";

    private HttpServer server;
    private URI baseUri;
    private AnthropicAdapter adapter;
    private boolean contextRegistered;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedXApiKey = new AtomicReference<>();
    private final AtomicReference<String> capturedVersion = new AtomicReference<>();
    private final AtomicReference<String> capturedContentType = new AtomicReference<>();
    private final AtomicReference<String> capturedAuthorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        adapter = new AnthropicAdapter(baseUri.toString(), "sk-ant-test");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ------------------------------------------------------------- complete

    @Test
    void completeDecodesResponseAndSendsAnthropicWireShape() {
        serveResponse(200, "application/json", RESPONSE_BODY);
        ChatRequest request = request(false);

        ChatResponse response = adapter.complete(request);

        assertEquals("msg_01", response.id());
        assertEquals("claude-3-5-sonnet", response.model());
        assertEquals(
                "Hello from Claude",
                ((AssistantMessage) response.choices().get(0).message()).content());
        assertEquals("stop", response.stopReason());
        assertEquals(15L, response.usage().totalTokens());
        // meta passes through untouched (gateway-internal)
        assertEquals("req-1", response.meta().get("request_id"));
        // request body is the codec's canonical encode with stream=false (no stream member)
        assertEquals(CODEC.encodeRequest(request), capturedBody.get());
        assertFalse(capturedBody.get().contains("\"stream\""), capturedBody.get());
        assertEquals("/v1/messages", capturedPath.get());
        // Anthropic authenticates with x-api-key, not Authorization: Bearer
        assertEquals("sk-ant-test", capturedXApiKey.get());
        assertEquals(AnthropicAdapter.DEFAULT_ANTHROPIC_VERSION, capturedVersion.get());
        assertEquals("application/json", capturedContentType.get());
        assertNull(capturedAuthorization.get(), "no Authorization header on the Anthropic path");
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
    void blankSecretOmitsXApiKeyHeader() {
        adapter = new AnthropicAdapter(baseUri.toString(), "");
        serveResponse(200, "application/json", RESPONSE_BODY);
        adapter.complete(request(false));
        assertNull(capturedXApiKey.get());
    }

    @Test
    void baseUrlV1SuffixIsStrippedToCanonicalEndpoint() {
        AnthropicAdapter withV1 = new AnthropicAdapter(baseUri + "/v1", "k");
        AnthropicAdapter withV1Slash = new AnthropicAdapter(baseUri + "/v1/", "k");

        assertEquals(adapter.baseUrl(), withV1.baseUrl());
        assertEquals(adapter.baseUrl(), withV1Slash.baseUrl());

        serveResponse(200, "application/json", RESPONSE_BODY);
        withV1.complete(request(false));
        assertEquals("/v1/messages", capturedPath.get());

        serveResponse(200, "application/json", RESPONSE_BODY);
        withV1Slash.complete(request(false));
        assertEquals("/v1/messages", capturedPath.get());
    }

    @Test
    void completeMapsUpstreamStatuses() {
        assertStatus(401, ProviderException.TYPE_AUTH, false, 401);
        assertStatus(403, ProviderException.TYPE_AUTH, false, 403);
        assertStatus(429, ProviderException.TYPE_RATE_LIMITED, true, 429);
        assertStatus(500, ProviderException.TYPE_UPSTREAM_5XX, true, 500);
        assertStatus(529, ProviderException.TYPE_UPSTREAM_5XX, true, 529); // overloaded — retryable
        assertStatus(404, ProviderException.TYPE_UPSTREAM_4XX, false, 404);
    }

    // --------------------------------------------- upstream Retry-After capture

    @Test
    void completeCarriesTheUpstreamRetryAfterOn429() {
        // Anthropic's tiered 429s carry a Retry-After window that must
        // survive the passthrough — the adapter captures it so the gateway can forward it.
        server.createContext("/v1/messages", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Retry-After", "42");
            byte[] bytes =
                    "{\"type\":\"error\",\"error\":{\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
        assertEquals(Long.valueOf(42), e.retryAfterSeconds());
    }

    // ------------------------------------------------ timeouts + coverage

    @Test
    void completeSucceedsWhenBodyArrivesAfterRequestTimeout() throws Exception {
        // The request timeout must bound header arrival ONLY on the
        // non-streaming path — a body that stalls past the timeout (a long Claude
        // completion with extended thinking) must not be cut at a wall-clock deadline.
        // The raw upstream flushes its headers immediately, then delays the body
        // (com.sun.net.httpserver buffers headers until the first body write).
        try (RawUpstreamServer upstream =
                RawUpstreamServer.headersThenDelayedBody("application/json", RESPONSE_BODY.getBytes(), 500)) {
            AnthropicAdapter slowBodyAdapter = new AnthropicAdapter(
                    upstream.baseUrl(),
                    "sk-ant-test",
                    AnthropicAdapter.DEFAULT_ANTHROPIC_VERSION,
                    Duration.ofMillis(1_000),
                    Duration.ofMillis(150));

            ChatResponse response = slowBodyAdapter.complete(request(false));

            assertEquals(
                    "Hello from Claude",
                    ((AssistantMessage) response.choices().get(0).message()).content());
        }
    }

    @Test
    void discoveryInstanceCallFailsFastWithTypedError() {
        // A blank-base discovery instance must fail fast with a clear typed error,
        // not a raw IllegalArgumentException from a relative URI.
        AnthropicAdapter discovery = new AnthropicAdapter();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> discovery.complete(request(false)));

        assertTrue(e.getMessage().contains("no base URL"), e.getMessage());
    }

    @Test
    void completeErrorBodyNeverLeaksSecretIntoException() {
        // The error body is probed for its envelope type only; the body text (which
        // can echo the presented key) must never reach an exception message.
        serveResponse(
                401,
                "application/json",
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"Incorrect API key provided: sk-ant-real-key-123456789012345678\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_AUTH, e.type());
        assertFalse(e.getMessage().contains("sk-ant-real-key"), e.getMessage());
    }

    @Test
    void completeRefinesStatusViaErrorEnvelope() {
        // envelope error.type refines the status-based default when present
        serveResponse(
                500,
                "application/json",
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));
        assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());

        serveResponse(
                401,
                "application/json",
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}");
        e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));
        assertEquals(ProviderException.TYPE_AUTH, e.type());

        serveResponse(
                529,
                "application/json",
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}");
        e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));
        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
        assertTrue(e.retryable());
    }

    @Test
    void completeFallsBackToStatusMappingForNonJsonErrorBody() {
        serveResponse(500, "text/html", "<html>proxy error</html>");
        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));
        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
        assertEquals(Integer.valueOf(500), e.statusCode());

        serveResponse(404, "text/plain", "not found");
        e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));
        assertEquals(ProviderException.TYPE_UPSTREAM_4XX, e.type());
    }

    @Test
    void completeErrorBodyWithLoneTrailingBackslashFallsBackToStatusMapping() {
        // A truncated error body whose envelope-type string ends in a lone
        // backslash must not crash the probe — it falls back to the status mapping
        // (upstream_5xx for a 500) instead of surfacing as a raw runtime exception.
        serveResponse(500, "application/json", "{\"type\":\"error\",\"error\":{\"type\":\"api_error\\");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
        assertEquals(Integer.valueOf(500), e.statusCode());
    }

    @Test
    void errorBodyStallOnCompleteIsBoundedAndPreservesStatus() throws Exception {
        // The Anthropic error body was read unboundedly (a stalled
        // error body pinned a worker thread forever). It is now read under the bounded
        // body-read deadline like the OpenAI-compatible adapter — a 500 head flushed
        // immediately with the body stalled past bodyReadTimeout returns promptly
        // (no hang), and the fix keeps the head-derived status: a 500 stays
        // upstream_5xx, not a lossy timeout.
        byte[] error = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\"}}".getBytes(StandardCharsets.UTF_8);
        try (RawUpstreamServer upstream = RawUpstreamServer.start(500, "application/json", error, 2_000)) {
            AnthropicAdapter stallAdapter = new AnthropicAdapter(
                    upstream.baseUrl(),
                    "sk-ant-test",
                    AnthropicAdapter.DEFAULT_ANTHROPIC_VERSION,
                    Duration.ofMillis(500),
                    Duration.ofMillis(1_000),
                    Duration.ofMillis(150));

            ProviderException e = assertThrows(ProviderException.class, () -> stallAdapter.complete(request(false)));

            assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
            assertTrue(e.retryable());
            assertEquals(Integer.valueOf(500), e.statusCode());
        }
    }

    @Test
    void errorBodyStallOnStreamIsBoundedAndPreservesStatus() throws Exception {
        // Streaming branch: the same bounded read applies to the
        // streaming non-2xx error body — it returns promptly instead of hanging, and the
        // head-derived status survives the stalled probe (500 → upstream_5xx).
        byte[] error =
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}".getBytes(StandardCharsets.UTF_8);
        try (RawUpstreamServer upstream = RawUpstreamServer.start(500, "application/json", error, 2_000)) {
            AnthropicAdapter stallAdapter = new AnthropicAdapter(
                    upstream.baseUrl(),
                    "sk-ant-test",
                    AnthropicAdapter.DEFAULT_ANTHROPIC_VERSION,
                    Duration.ofMillis(500),
                    Duration.ofMillis(1_000),
                    Duration.ofMillis(150));

            ProviderException e = assertThrows(ProviderException.class, () -> stallAdapter.stream(request(false)));

            assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
            assertTrue(e.retryable());
            assertEquals(Integer.valueOf(500), e.statusCode());
        }
    }

    // ------------------------------------------------ error-body read hygiene

    @Test
    void errorBodyStallPreservesStatusAndRetryAfterOnComplete() throws Exception {
        // A stalled non-2xx body must not discard the already-received head —
        // a 429 with a stalled body stays rate_limited and carries the provider's
        // Retry-After backoff window (previously the head-derived status was dropped and
        // the 429 surfaced as a retryable timeout with no backoff, burning a router retry
        // slot and losing Anthropic's precise tiered over-limit window).
        byte[] error = "{\"type\":\"error\",\"error\":{\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
        try (RawUpstreamServer upstream = RawUpstreamServer.start(out -> {
            out.write(("HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\n"
                            + "Retry-After: 42\r\nContent-Length: " + error.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(2_000); // the error body never arrives within bodyReadTimeout
        })) {
            AnthropicAdapter stallAdapter = new AnthropicAdapter(
                    upstream.baseUrl(),
                    "sk-ant-test",
                    AnthropicAdapter.DEFAULT_ANTHROPIC_VERSION,
                    Duration.ofMillis(500),
                    Duration.ofMillis(1_000),
                    Duration.ofMillis(150));

            ProviderException e = assertThrows(ProviderException.class, () -> stallAdapter.complete(request(false)));

            assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
            assertTrue(e.retryable());
            assertEquals(Integer.valueOf(429), e.statusCode());
            assertEquals(Long.valueOf(42), e.retryAfterSeconds());
        }
    }

    @Test
    void errorBodyStallPreservesStatusAndRetryAfterOnStream() throws Exception {
        // Streaming branch: the same head-derived preservation — a streaming
        // 429 with a stalled body stays rate_limited with the Retry-After window.
        byte[] error = "{\"type\":\"error\",\"error\":{\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
        try (RawUpstreamServer upstream = RawUpstreamServer.start(out -> {
            out.write(("HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\n"
                            + "Retry-After: 12\r\nContent-Length: " + error.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(2_000);
        })) {
            AnthropicAdapter stallAdapter = new AnthropicAdapter(
                    upstream.baseUrl(),
                    "sk-ant-test",
                    AnthropicAdapter.DEFAULT_ANTHROPIC_VERSION,
                    Duration.ofMillis(500),
                    Duration.ofMillis(1_000),
                    Duration.ofMillis(150));

            ProviderException e = assertThrows(ProviderException.class, () -> stallAdapter.stream(request(false)));

            assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
            assertEquals(Integer.valueOf(429), e.statusCode());
            assertEquals(Long.valueOf(12), e.retryAfterSeconds());
        }
    }

    @Test
    void errorBodyStallKeeps401AsAuthNotRetryable() throws Exception {
        // A 401 whose error body stalls must stay auth / non-retryable — a
        // retryable timeout would make the router burn a retry slot on an auth failure.
        byte[] error = "{\"type\":\"error\",\"error\":{\"message\":\"bad key\"}}".getBytes(StandardCharsets.UTF_8);
        try (RawUpstreamServer upstream = RawUpstreamServer.start(out -> {
            out.write(("HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\n" + "Content-Length: "
                            + error.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(2_000);
        })) {
            AnthropicAdapter stallAdapter = new AnthropicAdapter(
                    upstream.baseUrl(),
                    "sk-ant-test",
                    AnthropicAdapter.DEFAULT_ANTHROPIC_VERSION,
                    Duration.ofMillis(500),
                    Duration.ofMillis(1_000),
                    Duration.ofMillis(150));

            ProviderException e = assertThrows(ProviderException.class, () -> stallAdapter.complete(request(false)));

            assertEquals(ProviderException.TYPE_AUTH, e.type());
            assertFalse(e.retryable(), "a 401 with a stalled body must stay non-retryable");
            assertEquals(Integer.valueOf(401), e.statusCode());
        }
    }

    @Test
    void successBodyStallTimesOutOnComplete() throws Exception {
        // Coverage: the 200-success branch of the bounded body read —
        // a 200 head flushed immediately with the body stalled past bodyReadTimeout must
        // surface as TYPE_TIMEOUT (not a hang), and is not cut by the request timeout.
        try (RawUpstreamServer upstream =
                RawUpstreamServer.start(200, "application/json", RESPONSE_BODY.getBytes(), 2_000)) {
            AnthropicAdapter stallAdapter = new AnthropicAdapter(
                    upstream.baseUrl(),
                    "sk-ant-test",
                    AnthropicAdapter.DEFAULT_ANTHROPIC_VERSION,
                    Duration.ofMillis(1_000),
                    Duration.ofMillis(500),
                    Duration.ofMillis(150));

            ProviderException e = assertThrows(ProviderException.class, () -> stallAdapter.complete(request(false)));

            assertEquals(ProviderException.TYPE_TIMEOUT, e.type());
            assertTrue(e.retryable());
        }
    }

    @Test
    void completeRefinesHugeErrorBodyWhenEnvelopeIsNearTheStart() {
        // The error-body read is capped (readErrorBody) — a multi-MB non-2xx
        // body still classifies correctly when the envelope's error.type sits near the
        // start (within the cap).
        serveResponse(
                500,
                "application/json",
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"" + "x".repeat(1_000_000)
                        + "\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
        assertEquals(Integer.valueOf(500), e.statusCode());
    }

    @Test
    void completeFallsBackToStatusWhenErrorEnvelopeIsPastTheCap() {
        // An envelope whose error.type sits past the read cap cannot be
        // probed — the truncated prefix yields no type, so the status mapping applies
        // (a 404 with a rate-limit type buried after a huge message stays upstream_4xx,
        // not rate_limited).
        serveResponse(
                404,
                "application/json",
                "{\"type\":\"error\",\"error\":{\"message\":\"" + "x".repeat(1_000_000)
                        + "\",\"type\":\"rate_limit_error\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_UPSTREAM_4XX, e.type());
        assertEquals(Integer.valueOf(404), e.statusCode());
    }

    @Test
    void completeReturnsConnectionToThePool() throws Exception {
        // On success the adapter returns the connection to the keep-alive pool
        // — two consecutive complete calls against a server that holds the connection
        // open reuse one server-side connection.
        AtomicInteger connections = new AtomicInteger();
        byte[] body = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
        String responseHead =
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
        try (RawUpstreamServer upstream = RawUpstreamServer.startOnSocket(socket -> {
            socket.setSoTimeout(3_000);
            connections.incrementAndGet();
            for (int i = 0; i < 2; i++) {
                RawUpstreamServer.readRequestHead(socket);
                socket.getOutputStream().write(responseHead.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
            }
        })) {
            AnthropicAdapter poolAdapter = new AnthropicAdapter(upstream.baseUrl(), "sk-ant-test");

            poolAdapter.complete(request(false));
            poolAdapter.complete(request(false));

            assertEquals(1, connections.get(), "both complete() calls must reuse one pooled connection");
        }
    }

    @Test
    void interruptedWhileWaitingForUpstreamIsNotRetryable() {
        // A locally interrupted wait is not an upstream fault — non-retryable
        // (the router must not burn a retry slot on a sticky interrupt), and the
        // interrupt is restored for the caller.
        Thread.currentThread().interrupt();
        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));
        assertFalse(e.retryable());
        assertEquals(ProviderException.TYPE_UPSTREAM_4XX, e.type());
        assertTrue(Thread.interrupted(), "the interrupt must be restored for the caller");
    }

    @Test
    void discoveryInstanceStreamFailsFastWithTypedError() {
        // coverage: the discovery instance must fail fast on the streaming path too
        // (previously only complete was pinned).
        AnthropicAdapter discovery = new AnthropicAdapter();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> discovery.stream(request(false)));

        assertTrue(e.getMessage().contains("no base URL"), e.getMessage());
    }

    @Test
    void completeThrowsNetworkWhenConnectionRefused() throws Exception {
        AnthropicAdapter deadAdapter = new AnthropicAdapter("http://127.0.0.1:" + freePort(), "sk-ant-test");

        ProviderException e = assertThrows(ProviderException.class, () -> deadAdapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_NETWORK, e.type());
        assertTrue(e.retryable());
    }

    @Test
    void completeThrowsTimeoutWhenUpstreamIsSlow() {
        // The header-arrival (request) timeout path. The connect-timeout path is
        // covered-by-construction: it is the same send → HttpTimeoutException →
        // TYPE_TIMEOUT branch (a blackhole-IP connect test is slow/unreliable on CI).
        server.createContext("/v1/messages", exchange -> {
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
        AnthropicAdapter slowAdapter = new AnthropicAdapter(
                baseUri.toString(),
                "k",
                AnthropicAdapter.DEFAULT_ANTHROPIC_VERSION,
                Duration.ofMillis(500),
                Duration.ofMillis(150));

        ProviderException e = assertThrows(ProviderException.class, () -> slowAdapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_TIMEOUT, e.type());
        assertTrue(e.retryable());
    }

    @Test
    void customAnthropicVersionHeaderSent() {
        adapter = new AnthropicAdapter(
                baseUri.toString(), "k", "2024-01-01", Duration.ofSeconds(10), Duration.ofSeconds(60));
        serveResponse(200, "application/json", RESPONSE_BODY);
        adapter.complete(request(false));
        assertEquals("2024-01-01", capturedVersion.get());
    }

    @Test
    void completeWrapsGarbageUpstreamBodyAsBadUpstreamPayload() {
        serveResponse(200, "application/json", "this is not json");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
        assertFalse(e.retryable());
    }

    // -------------------------------------------------------------- stream

    @Test
    void streamEmitsChunksSkipsNoOpsAndTerminatesOnMessageStop() {
        serveResponse(200, "text/event-stream", SSE_BODY);
        ChatRequest request = request(false);

        List<StreamChunk> chunks;
        try (Stream<StreamChunk> stream = adapter.stream(request)) {
            chunks = stream.toList();
        }

        assertEquals(4, chunks.size(), "no-op events (text block start, block stop, ping) must be skipped");
        // message_start → role-announced chunk
        assertEquals("msg_1", chunks.get(0).id());
        assertEquals(ChatRole.ASSISTANT, chunks.get(0).choices().get(0).delta().role());
        // content_block_delta events → content chunks
        assertEquals("Hello", chunks.get(1).choices().get(0).delta().content());
        assertEquals(" world", chunks.get(2).choices().get(0).delta().content());
        // message_delta → terminal chunk with finishReason + usage
        assertEquals("stop", chunks.get(3).choices().get(0).finishReason());
        // The per-stream decoder merged the prompt side (message_start input 10) with
        // the completion side (message_delta output 5) — prompt tokens are no longer dropped.
        assertEquals(10L, chunks.get(3).usage().promptTokens(), "prompt tokens must survive the per-stream merge");
        assertEquals(5L, chunks.get(3).usage().completionTokens());
        assertEquals(15L, chunks.get(3).usage().totalTokens());
        // no [DONE] sentinel anywhere on the Anthropic wire
        assertFalse(SSE_BODY.contains("[DONE]"));
        // wire body forces stream=true regardless of the canonical flag
        assertTrue(capturedBody.get().contains("\"stream\":true"), capturedBody.get());
        assertEquals(CODEC.encodeRequest(copyWithStream(request, true)), capturedBody.get());
    }

    @Test
    void streamToleratesEofAfterMessageStopWithoutTrailingBlankLine() {
        // real upstreams close the connection right after message_stop — no blank line
        String body = SSE_BODY.substring(0, SSE_BODY.length() - 2); // drop the final "\n\n"
        serveResponse(200, "text/event-stream", body);

        List<StreamChunk> chunks;
        try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
            chunks = stream.toList();
        }

        assertEquals(4, chunks.size());
    }

    @Test
    void streamWithoutMessageStopIsTruncation() {
        // a clean EOF without the message_stop terminal event is a truncated stream
        serveResponse(
                200,
                "text/event-stream",
                "event: message_start\n"
                        + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\n");

        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.toList();
            }
        });

        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
    }

    @Test
    void streamOfEmptySseIsTruncation() {
        serveResponse(200, "text/event-stream", "");

        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.toList();
            }
        });

        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
    }

    @Test
    void streamErrorFrameClassifiedByEnvelopeType() {
        assertStreamErrorFrame(
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}",
                ProviderException.TYPE_AUTH,
                false);
        assertStreamErrorFrame(
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}",
                ProviderException.TYPE_RATE_LIMITED,
                true);
        assertStreamErrorFrame(
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}",
                ProviderException.TYPE_UPSTREAM_5XX,
                true);
        // unknown envelope type → upstream_4xx default
        assertStreamErrorFrame(
                "{\"type\":\"error\",\"error\":{\"type\":\"weird_error\",\"message\":\"??\"}}",
                ProviderException.TYPE_UPSTREAM_4XX,
                false);
    }

    @Test
    void streamErrorFrameWithEmptyDataStillClassified() {
        // An error frame with an empty payload must not be silently skipped —
        // classified with the upstream_4xx default instead of surfacing as a truncation.
        assertStreamErrorFrame("", ProviderException.TYPE_UPSTREAM_4XX, false);
    }

    @Test
    void streamErrorFrameWithNoDataLineStillClassified() {
        // `event: error\n\n` — no data: line at all — must be dispatched as an error
        // frame (the parser dispatches data-less non-default event frames at the
        // blank-line terminator), not swallowed to surface as a truncation.
        serveResponse(200, "text/event-stream", "event: error\n\n");
        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.toList();
            }
        });
        assertEquals(ProviderException.TYPE_UPSTREAM_4XX, e.type());
        assertFalse(e.retryable());
    }

    @Test
    void streamErrorFrameWithLoneTrailingBackslashDoesNotCrash() {
        // Streaming path: an error frame whose envelope-type string ends in a
        // lone backslash must not escape as a raw runtime exception — the probe yields no
        // envelope type, so the frame classifies with the upstream_4xx default.
        serveResponse(
                200,
                "text/event-stream",
                "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"api_error\\\n\n");

        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.toList();
            }
        });

        assertEquals(ProviderException.TYPE_UPSTREAM_4XX, e.type());
    }

    @Test
    void streamToleratesTruncatedDataUnderMessageStop() {
        // The message_stop tolerance is content-blind — the terminal event's data
        // payload is irrelevant to termination, so a truncated payload under it is
        // deliberately swallowed (a clean termination, never a truncation error).
        serveResponse(
                200,
                "text/event-stream",
                "event: message_start\n"
                        + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\n"
                        + "event: message_stop\ndata: {\"partial\"");

        try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
            assertEquals(1, stream.toList().size());
        }
    }

    @Test
    void streamThrowsOnNon200BeforeAnyChunk() {
        serveResponse(
                503,
                "application/json",
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.stream(request(false)));

        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
        assertTrue(e.retryable());
        assertEquals(Integer.valueOf(503), e.statusCode());
    }

    @Test
    void streamWrapsInvalidEventPayloadAsBadUpstreamPayload() {
        serveResponse(200, "text/event-stream", "event: message_start\ndata: this is not json\n\n");

        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.toList();
            }
        });

        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
        assertFalse(e.retryable());
    }

    @Test
    void streamThrowsNetworkWhenConnectionRefused() throws Exception {
        AnthropicAdapter deadAdapter = new AnthropicAdapter("http://127.0.0.1:" + freePort(), "sk-ant-test");

        ProviderException e = assertThrows(ProviderException.class, () -> deadAdapter.stream(request(false)));

        assertEquals(ProviderException.TYPE_NETWORK, e.type());
    }

    @Test
    void closingStreamReleasesHttpConnection() throws Exception {
        AtomicBoolean disconnectObserved = new AtomicBoolean();
        server.createContext("/v1/messages", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0); // chunked — connection stays open
                OutputStream out = exchange.getResponseBody();
                out.write(("event: message_start\n"
                                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3\",\"content\":[],\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\n"
                                + "event: content_block_delta\n"
                                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"a\"}}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(300); // the client closes the stream during this window
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
            // first chunk is the message_start role announcement; second carries the delta
            List<StreamChunk> firstTwo = stream.limit(2).toList();
            assertEquals("a", firstTwo.get(1).choices().get(0).delta().content());
            // close runs onClose → response body closed → connection released
        }

        awaitTrue(disconnectObserved, 2_000);
        assertTrue(disconnectObserved.get(), "server must observe the client disconnect after the stream is closed");
    }

    // -------------------------------------------------------------- helpers

    private void serveResponse(int status, String contentType, String body) {
        if (contextRegistered) {
            server.removeContext("/v1/messages");
        }
        server.createContext("/v1/messages", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedXApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            capturedVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
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
        serveResponse(status, "application/json", "{\"type\":\"error\",\"error\":{\"message\":\"nope\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(type, e.type());
        assertEquals(retryable, e.retryable());
        assertEquals(Integer.valueOf(statusCode), e.statusCode());
    }

    private void assertStreamErrorFrame(String frameData, String expectedType, boolean retryable) {
        serveResponse(200, "text/event-stream", "event: error\ndata: " + frameData + "\n\n");

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
                "claude-3-5-sonnet",
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

    /** Mirrors the adapter's wire decision: stream copy (no stream_options on Anthropic). */
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
                null,
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
