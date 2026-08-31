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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * step 3: {@link OpenAiCompatibleAdapter} — the name-parameterized generalization of
 * Generalized from the named {@link DeepSeekAdapter}. Pins the extracted shape: the configured name returned by
 * {@code name}, the ServiceLoader discovery form, base-URL normalization (shared with
 * the DeepSeek subclass), and the loopback fake upstream covering the same contract
 * {@link DeepSeekAdapterTest} pins for the subclass (request-body byte shape,
 * Authorization header, error-status mapping, stream termination, close-releases-the-
 * connection). The unmodified {@link DeepSeekAdapterTest} is the refactor's real gate.
 */
class OpenAiCompatibleAdapterTest {

    private static final OpenAiMessageCodec CODEC = OpenAiMessageCodec.create();

    private static final String RESPONSE_BODY =
            "{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"xai-model\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello from xAI\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";

    private static final String SSE_BODY =
            "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"xai-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"xai-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}\n\n"
                    + "data: [DONE]\n\n";

    private HttpServer server;
    private URI baseUri;
    private OpenAiCompatibleAdapter adapter;
    private boolean contextRegistered;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> capturedAcceptHeader = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        adapter = new OpenAiCompatibleAdapter("xai", baseUri.toString(), "test-key");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void nameReturnsConfiguredName() {
        assertEquals("xai", adapter.name());
        assertEquals("openrouter", new OpenAiCompatibleAdapter("openrouter", baseUri.toString(), "k").name());
    }

    @Test
    void baseEndingInChatCompletionsIsUsedAsTheFullEndpoint() {
        // Versionless OpenAI-compatible upstreams (Perplexity serves
        // https://api.perplexity.ai/chat/completions — no /v1) cannot be expressed
        // by the "base + versioned path" rule alone: an operator who configures the
        // base as the full endpoint path opts out of the appended /v1.
        server.createContext("/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        adapter = new OpenAiCompatibleAdapter("perplexity", baseUri + "/chat/completions", "test-key");

        ChatResponse response = adapter.complete(request(false));

        assertEquals("chatcmpl-123", response.id());
        assertEquals("/chat/completions", capturedPath.get());
    }

    @Test
    void versionedBaseStillAppendsTheVersionedPathOnce() {
        // The opt-out must not weaken the default rule: a plain host base keeps
        // appending exactly one /v1/chat/completions (pinned above; this companion
        // case guards the boundary — a base ending in /chat/completions that ALSO
        // carries /v1 is used verbatim, never doubled).
        server.createContext("/v1/chat/completions", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            byte[] bytes = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        adapter = new OpenAiCompatibleAdapter("pplx-versioned", baseUri + "/v1/chat/completions", "test-key");

        ChatResponse response = adapter.complete(request(false));

        assertEquals("chatcmpl-123", response.id());
        assertEquals("/v1/chat/completions", capturedPath.get());
    }

    @Test
    void discoveryFormHasGenericNameAndInertCredentials() {
        OpenAiCompatibleAdapter discovery = new OpenAiCompatibleAdapter();
        assertEquals(OpenAiCompatibleAdapter.NAME, discovery.name());
        assertEquals("", discovery.baseUrl());
        assertEquals(ProviderAuth.TYPE_BEARER, discovery.auth().type());
        assertEquals("", discovery.auth().secret());
    }

    @Test
    void blankNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OpenAiCompatibleAdapter(" ", baseUri.toString(), "k"));
        assertThrows(IllegalArgumentException.class, () -> new OpenAiCompatibleAdapter(null, baseUri.toString(), "k"));
    }

    @Test
    void blankSecretOmitsAuthorizationHeader() {
        adapter = new OpenAiCompatibleAdapter("xai", baseUri.toString(), "");
        serveResponse(200, "application/json", RESPONSE_BODY);
        adapter.complete(request(false));
        assertNull(capturedAuthHeader.get());
    }

    @Test
    void completeDecodesAndSendsPassthroughWireShape() {
        serveResponse(200, "application/json", RESPONSE_BODY);
        ChatRequest request = request(false);

        ChatResponse response = adapter.complete(request);

        assertEquals("chatcmpl-123", response.id());
        assertEquals("xai-model", response.model());
        assertEquals(
                "Hello from xAI", ((AssistantMessage) response.choices().get(0).message()).content());
        assertEquals("stop", response.stopReason());
        // meta passes through untouched (gateway-internal)
        assertEquals("req-1", response.meta().get("request_id"));
        assertEquals(CODEC.encodeRequest(request), capturedBody.get());
        assertFalse(capturedBody.get().contains("\"stream\""));
        assertEquals("/v1/chat/completions", capturedPath.get());
        assertEquals("Bearer test-key", capturedAuthHeader.get());
    }

    @Test
    void completeMapsUpstreamStatuses() {
        assertStatus(401, ProviderException.TYPE_AUTH, false, 401);
        // 403 is a credential/permission rejection on OpenAI-format upstreams
        // (OpenAI/OpenRouter/xAI) — auth, matching the Anthropic adapter (401/403).
        assertStatus(403, ProviderException.TYPE_AUTH, false, 403);
        assertStatus(429, ProviderException.TYPE_RATE_LIMITED, true, 429);
        assertStatus(503, ProviderException.TYPE_UPSTREAM_5XX, true, 503);
        assertStatus(404, ProviderException.TYPE_UPSTREAM_4XX, false, 404);
    }

    // --------------------------------------------- upstream Retry-After capture

    @Test
    void completeCarriesTheUpstreamRetryAfterOn429() {
        // The provider's precise backoff window must survive the
        // passthrough — the adapter captures the upstream Retry-After on a 429 so the
        // gateway can forward it (LiteLLM forwards it; SDKs otherwise fall back to
        // default backoff).
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Retry-After", "37");
            byte[] bytes = "{\"error\":{\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
        assertEquals(Long.valueOf(37), e.retryAfterSeconds());
    }

    @Test
    void streamCarriesTheUpstreamRetryAfterOn429() {
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Retry-After", "12");
            byte[] bytes = "{\"error\":{\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.stream(request(false)));

        assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
        assertEquals(Long.valueOf(12), e.retryAfterSeconds());
    }

    @Test
    void unparseableOrHttpDateRetryAfterIsDroppedNotFatal() {
        // An HTTP-date Retry-After (or garbage) is dropped — null on the
        // exception, never a 500 from the parse.
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Retry-After", "Fri, 31 Dec 1999 23:59:59 GMT");
            byte[] bytes = "{\"error\":{\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
        assertNull(e.retryAfterSeconds(), "an HTTP-date Retry-After is dropped");
    }

    // ------------------------------------------------ timeouts + coverage

    @Test
    void completeSucceedsWhenBodyArrivesAfterRequestTimeout() throws Exception {
        // The request timeout must bound header arrival ONLY on the
        // non-streaming path — a body that stalls past the timeout (a long reasoning-
        // model completion) must not be cut at a wall-clock deadline. The raw upstream
        // flushes its headers immediately, then delays the body (com.sun.net.httpserver
        // buffers headers until the first body write and cannot exercise this).
        try (RawUpstreamServer upstream =
                RawUpstreamServer.headersThenDelayedBody("application/json", RESPONSE_BODY.getBytes(), 500)) {
            OpenAiCompatibleAdapter slowBodyAdapter = new OpenAiCompatibleAdapter(
                    "xai", upstream.baseUrl(), "test-key", Duration.ofMillis(1_000), Duration.ofMillis(150));

            ChatResponse response = slowBodyAdapter.complete(request(false));

            assertEquals(
                    "Hello from xAI",
                    ((AssistantMessage) response.choices().get(0).message()).content());
        }
    }

    @Test
    void streamIsNotCutByRequestTimeoutWhenBodyStallsAfterHeaders() throws Exception {
        // Inverse: the streaming path is genuinely header-arrival-only — a
        // body stall after headers (longer than the request timeout) must not cut the
        // stream (guards the correct streaming behavior).
        byte[] chunk1 = ("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"a\"},\"finish_reason\":null}]}\n\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = ("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"b\"},\"finish_reason\":null}]}\n\n"
                        + "data: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
        try (RawUpstreamServer upstream = RawUpstreamServer.start(out -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: "
                            + (chunk1.length + chunk2.length) + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.write(chunk1);
            out.flush();
            Thread.sleep(500); // stall past the request timeout
            out.write(chunk2);
            out.flush();
        })) {
            OpenAiCompatibleAdapter slowBodyAdapter = new OpenAiCompatibleAdapter(
                    "xai", upstream.baseUrl(), "test-key", Duration.ofMillis(1_000), Duration.ofMillis(150));

            List<StreamChunk> chunks;
            try (Stream<StreamChunk> stream = slowBodyAdapter.stream(request(false))) {
                chunks = stream.toList();
            }

            assertEquals(2, chunks.size());
            assertEquals("a", chunks.get(0).choices().get(0).delta().content());
            assertEquals("b", chunks.get(1).choices().get(0).delta().content());
        }
    }

    @Test
    void completeThrowsTimeoutWhenUpstreamIsSlow() {
        // The header-arrival (request) timeout path. The connect-timeout path is
        // covered-by-construction: it is the same send → HttpTimeoutException →
        // TYPE_TIMEOUT branch (a blackhole-IP connect test is slow/unreliable on CI).
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        OpenAiCompatibleAdapter slowAdapter = new OpenAiCompatibleAdapter(
                "xai", baseUri.toString(), "test-key", Duration.ofMillis(500), Duration.ofMillis(150));

        ProviderException e = assertThrows(ProviderException.class, () -> slowAdapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_TIMEOUT, e.type());
        assertTrue(e.retryable());
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
        OpenAiCompatibleAdapter slowAdapter = new OpenAiCompatibleAdapter(
                "xai", baseUri.toString(), "test-key", Duration.ofMillis(500), Duration.ofMillis(150));

        ProviderException e = assertThrows(ProviderException.class, () -> slowAdapter.stream(request(false)));

        assertEquals(ProviderException.TYPE_TIMEOUT, e.type());
    }

    @Test
    void discoveryInstanceCallFailsFastWithTypedError() {
        // A blank-base call must surface as a clear typed error, not a raw
        // IllegalArgumentException from a relative URI (SPI contract).
        OpenAiCompatibleAdapter discovery = new OpenAiCompatibleAdapter();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> discovery.complete(request(false)));

        assertTrue(e.getMessage().contains("no base URL"), e.getMessage());
    }

    @Test
    void completeRefinesStatusViaErrorEnvelope() {
        // Parity with the Anthropic adapter: the OpenAI error envelope's error.type
        // refines the status-based default on the non-streaming path.
        serveResponse(500, "application/json", "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));
        assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
        assertTrue(e.retryable());
        assertEquals(Integer.valueOf(500), e.statusCode());

        serveResponse(503, "application/json", "{\"error\":{\"type\":\"server_error\",\"message\":\"boom\"}}");
        e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));
        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
    }

    @Test
    void completeErrorBodyNeverLeaksSecretIntoException() {
        // The non-2xx body is read only to probe the envelope type; the body text
        // (which can echo the presented key) must never reach an exception message.
        serveResponse(
                500,
                "application/json",
                "{\"error\":{\"type\":\"api_error\",\"message\":\"Incorrect API key provided: sk-real-key-123456789012345678\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
        assertFalse(e.getMessage().contains("sk-real-key"), e.getMessage());
    }

    @Test
    void upstreamBodyTextNeverReachesTheExceptionMessage() {
        // The adapter's message contract is a generic, upstream-body-free text
        // (the ErrorFixtureTest premise note) — an envelope that echoes the presented
        // key inside the message must still surface as the generic form, never the
        // body text (which the redaction choke point cannot rely on if it never arrives).
        serveResponse(
                401,
                "application/json",
                "{\"error\":{\"message\":\"Incorrect API key provided: sk-real-key-123456789012345678\","
                        + "\"type\":\"invalid_request_error\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_AUTH, e.type());
        assertEquals("upstream returned HTTP 401 for chat completion", e.getMessage(), e.getMessage());
    }

    @Test
    void completeErrorBodyWithLoneTrailingBackslashFallsBackToStatusMapping() {
        // A truncated error body whose top-level string value ends in a lone
        // backslash must not crash the probe — it falls back to the status mapping
        // (upstream_5xx for a 500) instead of surfacing as a raw runtime exception.
        serveResponse(500, "application/json", "{\"error\":\"abc\\");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
        assertEquals(Integer.valueOf(500), e.statusCode());
    }

    @Test
    void streamNon2xxRefinesStatusViaErrorEnvelope() {
        // The streaming non-2xx path must probe the OpenAI error envelope's
        // error.type like complete does (parity + LiteLLM's type-first mapping) — a
        // streaming 403 carrying authentication_error surfaces as auth, a streaming 500
        // carrying rate_limit_error as rate_limited.
        serveResponse(
                403, "application/json", "{\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}");
        ProviderException e = assertThrows(ProviderException.class, () -> adapter.stream(request(false)));
        assertEquals(ProviderException.TYPE_AUTH, e.type());
        assertEquals(Integer.valueOf(403), e.statusCode());

        serveResponse(500, "application/json", "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
        e = assertThrows(ProviderException.class, () -> adapter.stream(request(false)));
        assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
        assertTrue(e.retryable());
        assertEquals(Integer.valueOf(500), e.statusCode());
    }

    @Test
    void streamNon2xxBodyStallIsBoundedAndPreservesStatus() throws Exception {
        // The streaming non-2xx error body is read under the bounded
        // body-read deadline (like complete) — a stalled error body must not pin the
        // worker thread on the raw read. The read surfaces promptly, and the fix
        // keeps the head-derived status (a 500 stays upstream_5xx, not a lossy timeout).
        byte[] error = "{\"error\":{\"type\":\"server_error\"}}".getBytes(StandardCharsets.UTF_8);
        try (RawUpstreamServer upstream = RawUpstreamServer.start(500, "application/json", error, 2_000)) {
            OpenAiCompatibleAdapter stallAdapter = new OpenAiCompatibleAdapter(
                    "xai",
                    upstream.baseUrl(),
                    "test-key",
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
        // slot and losing the precise window).
        byte[] error = "{\"error\":{\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
        try (RawUpstreamServer upstream = RawUpstreamServer.start(out -> {
            out.write(("HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\n"
                            + "Retry-After: 37\r\nContent-Length: " + error.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(2_000); // the error body never arrives within bodyReadTimeout
        })) {
            OpenAiCompatibleAdapter stallAdapter = new OpenAiCompatibleAdapter(
                    "xai",
                    upstream.baseUrl(),
                    "test-key",
                    Duration.ofMillis(500),
                    Duration.ofMillis(1_000),
                    Duration.ofMillis(150));

            ProviderException e = assertThrows(ProviderException.class, () -> stallAdapter.complete(request(false)));

            assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
            assertTrue(e.retryable());
            assertEquals(Integer.valueOf(429), e.statusCode());
            assertEquals(Long.valueOf(37), e.retryAfterSeconds());
        }
    }

    @Test
    void errorBodyStallPreservesStatusAndRetryAfterOnStream() throws Exception {
        // Streaming branch: the same head-derived preservation — a streaming
        // 429 with a stalled body stays rate_limited with the Retry-After window.
        byte[] error = "{\"error\":{\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
        try (RawUpstreamServer upstream = RawUpstreamServer.start(out -> {
            out.write(("HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\n"
                            + "Retry-After: 12\r\nContent-Length: " + error.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(2_000);
        })) {
            OpenAiCompatibleAdapter stallAdapter = new OpenAiCompatibleAdapter(
                    "xai",
                    upstream.baseUrl(),
                    "test-key",
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
        byte[] error = "{\"error\":{\"message\":\"bad key\"}}".getBytes(StandardCharsets.UTF_8);
        try (RawUpstreamServer upstream = RawUpstreamServer.start(out -> {
            out.write(("HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\n" + "Content-Length: "
                            + error.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(2_000);
        })) {
            OpenAiCompatibleAdapter stallAdapter = new OpenAiCompatibleAdapter(
                    "xai",
                    upstream.baseUrl(),
                    "test-key",
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
            OpenAiCompatibleAdapter stallAdapter = new OpenAiCompatibleAdapter(
                    "xai",
                    upstream.baseUrl(),
                    "test-key",
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
                "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"" + "x".repeat(1_000_000) + "\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
        assertEquals(Integer.valueOf(500), e.statusCode());
    }

    @Test
    void completeFallsBackToStatusWhenErrorEnvelopeIsPastTheCap() {
        // An envelope whose error.type sits past the read cap cannot be
        // probed — the truncated prefix yields no type, so the status mapping applies
        // (a 404 with the type buried after a huge message stays upstream_4xx, not auth).
        serveResponse(
                404,
                "application/json",
                "{\"error\":{\"message\":\"" + "x".repeat(1_000_000) + "\",\"type\":\"authentication_error\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_UPSTREAM_4XX, e.type());
        assertEquals(Integer.valueOf(404), e.statusCode());
    }

    @Test
    void completeMapsPermissionErrorEnvelopeToAuth() {
        // OpenAI's error.type "permission_error" is a credential/permission failure —
        // auth (parity with the 403 status mapping), not the generic upstream_4xx.
        serveResponse(403, "application/json", "{\"error\":{\"type\":\"permission_error\",\"message\":\"forbidden\"}}");
        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));
        assertEquals(ProviderException.TYPE_AUTH, e.type());
        assertFalse(e.retryable());
        assertEquals(Integer.valueOf(403), e.statusCode());
    }

    @Test
    void completeReturnsConnectionToThePool() throws Exception {
        // On success the adapter returns the connection to the keep-alive pool
        // — two consecutive complete calls against a server that holds the connection
        // open reuse one server-side connection (previously only the streaming close
        // contract was pinned). Reading to EOF releases the connection in practice; the
        // explicit close makes the contract robust to a future short-circuiting decode.
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
            OpenAiCompatibleAdapter poolAdapter = new OpenAiCompatibleAdapter("xai", upstream.baseUrl(), "test-key");

            poolAdapter.complete(request(false));
            poolAdapter.complete(request(false));

            assertEquals(1, connections.get(), "both complete() calls must reuse one pooled connection");
        }
    }

    @Test
    void streamSendsEventStreamAcceptAndCompleteSendsJson() {
        // Informational: the streaming path advertises SSE (DeepSeek's documented
        // curl shape) so strict OpenAI-compatible upstreams treat it as a stream; the
        // non-streaming path keeps application/json.
        serveResponse(200, "text/event-stream", SSE_BODY);
        try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
            stream.toList();
        }
        assertEquals("text/event-stream", capturedAcceptHeader.get());

        serveResponse(200, "application/json", RESPONSE_BODY);
        adapter.complete(request(false));
        assertEquals("application/json", capturedAcceptHeader.get());
    }

    @Test
    void interruptedWhileWaitingForUpstreamIsNotRetryable() {
        // A locally interrupted wait is not an upstream fault — it must not be
        // classified as a retryable network failure (the router would otherwise burn a
        // retry slot, since the interrupt flag is sticky and the immediate retry
        // re-throws). The interrupt must still be restored for the caller.
        Thread.currentThread().interrupt();
        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));
        assertFalse(e.retryable());
        assertEquals(ProviderException.TYPE_UPSTREAM_4XX, e.type());
        assertTrue(Thread.interrupted(), "the interrupt must be restored for the caller");
    }

    @Test
    void bodyReadTimeoutDoesNotLeakPlatformThreads() throws Exception {
        // The bounded body read runs on a fresh virtual thread per call — a
        // burst of stalled reads must leave no lingering platform threads behind
        // (virtual threads are not enumerated by getAllStackTraces; the previous
        // per-call single-thread executor would leave "janus-body-read" platform
        // threads around).
        byte[] error = "{\"error\":{\"type\":\"server_error\"}}".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            try (RawUpstreamServer upstream = RawUpstreamServer.start(500, "application/json", error, 2_000)) {
                OpenAiCompatibleAdapter stallAdapter = new OpenAiCompatibleAdapter(
                        "xai",
                        upstream.baseUrl(),
                        "test-key",
                        Duration.ofMillis(500),
                        Duration.ofMillis(1_000),
                        Duration.ofMillis(100));
                assertThrows(ProviderException.class, () -> stallAdapter.complete(request(false)));
            }
        }
        long lingering = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("janus-body-read"))
                .count();
        assertEquals(0, lingering, "no platform thread may linger from a timed-out body read");
    }

    @Test
    void negativeRetryAfterIsDropped() {
        // coverage: retryAfterSeconds keeps only delta-seconds >= 0 — a negative value
        // is dropped (null), never a 500.
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Retry-After", "-5");
            byte[] bytes = "{\"error\":{\"message\":\"slow down\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.complete(request(false)));

        assertEquals(ProviderException.TYPE_RATE_LIMITED, e.type());
        assertNull(e.retryAfterSeconds(), "a negative Retry-After is dropped");
    }

    @Test
    void discoveryInstanceStreamFailsFastWithTypedError() {
        // coverage: the discovery instance must fail fast on the streaming path too
        // (previously only complete was pinned).
        OpenAiCompatibleAdapter discovery = new OpenAiCompatibleAdapter();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> discovery.stream(request(false)));

        assertTrue(e.getMessage().contains("no base URL"), e.getMessage());
    }

    @Test
    void streamErrorFrameWithQuotedOrFloatStatusClassifiedByStatus() {
        // Status must parse when quoted or floating-point — a nonstandard type
        // with a quoted/float 429 still maps to rate_limited (status wins), not
        // upstream_4xx (the type-based fallback for an unmapped type).
        assertStreamErrorFrame(
                "{\"error\":{\"type\":\"weird_error\",\"status\":\"429\"}}", ProviderException.TYPE_RATE_LIMITED, true);
        assertStreamErrorFrame(
                "{\"error\":{\"type\":\"weird_error\",\"status\":429.0}}", ProviderException.TYPE_RATE_LIMITED, true);
    }

    @Test
    void streamErrorFrameWithLoneTrailingBackslashDoesNotCrash() {
        // Streaming path: a malformed error frame whose top-level string value
        // ends in a lone backslash must not escape as a raw runtime exception — it surfaces
        // as a typed bad-upstream-payload failure (the frame is neither a parseable error
        // envelope nor a valid chunk).
        serveResponse(200, "text/event-stream", "data: {\"error\":\"abc\\\n\n");

        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.toList();
            }
        });

        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
    }

    @Test
    void streamErrorFrameWith2xxStatusClassifiedByType() {
        // Status wins only for a real non-2xx status — a 200-status rate-limit
        // frame is classified by type → rate_limited (retryable), not upstream_4xx.
        assertStreamErrorFrame(
                "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_error\",\"status\":200}}",
                ProviderException.TYPE_RATE_LIMITED,
                true);
    }

    @Test
    void chunkWithChoicesAndErrorMemberDecodesAsChunk() {
        // A chunk that adds a benign top-level "error" member still decodes as a
        // chunk — a real chunk always carries choices, a real error frame never does.
        serveResponse(
                200,
                "text/event-stream",
                "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},\"finish_reason\":null}],"
                        + "\"error\":{\"hint\":1}}\n\n"
                        + "data: [DONE]\n\n");

        try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
            List<StreamChunk> chunks = stream.toList();
            assertEquals(1, chunks.size());
            assertEquals("a", chunks.get(0).choices().get(0).delta().content());
        }
    }

    @Test
    void chunkWithErrorNullDecodesAsChunk() {
        // Hardening: "error": null is a regular chunk, not an error frame.
        serveResponse(
                200,
                "text/event-stream",
                "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},\"finish_reason\":null}],"
                        + "\"error\":null}\n\n"
                        + "data: [DONE]\n\n");

        try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
            assertEquals(1L, stream.count());
        }
    }

    @Test
    void streamErrorFrameAfterValidChunksPropagates() {
        // A mid-stream error frame arriving after valid chunks must propagate (and the
        // stream's onClose releases the connection).
        serveResponse(
                200,
                "text/event-stream",
                "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"error\":{\"message\":\"boom\",\"type\":\"server_error\"}}\n\n");

        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.toList();
            }
        });

        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
    }

    @Test
    void baseUrlV1SuffixIsStrippedToCanonicalEndpoint() {
        OpenAiCompatibleAdapter withV1 = new OpenAiCompatibleAdapter("xai", baseUri + "/v1", "k");
        assertEquals(adapter.baseUrl(), withV1.baseUrl());

        serveResponse(200, "application/json", RESPONSE_BODY);
        withV1.complete(request(false));
        assertEquals("/v1/chat/completions", capturedPath.get());
    }

    @Test
    void streamEmitsChunksAndTerminatesOnDone() {
        serveResponse(200, "text/event-stream", SSE_BODY);

        List<StreamChunk> chunks;
        try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
            chunks = stream.toList();
        }

        assertEquals(2, chunks.size());
        assertEquals("assistant", chunks.get(0).choices().get(0).delta().role().wire());
        assertEquals("Hello", chunks.get(0).choices().get(0).delta().content());
        assertEquals(" world", chunks.get(1).choices().get(0).delta().content());
        assertTrue(capturedBody.get().contains("\"stream\":true"));
    }

    @Test
    void streamToleratesEofAfterDoneWithoutTrailingBlankLine() {
        // real upstreams close the connection right after the sentinel — no blank line
        String body = SSE_BODY.substring(0, SSE_BODY.length() - 2); // drop the final "\n\n"
        serveResponse(200, "text/event-stream", body);

        List<StreamChunk> chunks;
        try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
            chunks = stream.toList();
        }

        assertEquals(2, chunks.size());
    }

    @Test
    void streamWithoutDoneSentinelIsTruncation() {
        // a clean EOF without the [DONE] sentinel is a truncated stream — never a
        // complete response (parity with the Anthropic message_stop rule and the
        // docs/adding-a-provider.md streaming contract)
        serveResponse(
                200,
                "text/event-stream",
                "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Half an answer\"},\"finish_reason\":null}]}\n\n");

        ProviderException e = assertThrows(ProviderException.class, () -> {
            try (Stream<StreamChunk> stream = adapter.stream(request(false))) {
                stream.toList();
            }
        });

        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
        assertFalse(e.retryable());
    }

    @Test
    void streamThrowsOnNon200BeforeAnyChunk() {
        serveResponse(503, "application/json", "{\"error\":{\"message\":\"unavailable\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> adapter.stream(request(false)));

        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, e.type());
        assertEquals(Integer.valueOf(503), e.statusCode());
    }

    @Test
    void closingStreamReleasesHttpConnection() throws Exception {
        AtomicBoolean disconnectObserved = new AtomicBoolean();
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                out.write(
                        "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},\"finish_reason\":null}]}\n\n"
                                .getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(300);
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
            capturedAcceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));
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
                "xai-model",
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
