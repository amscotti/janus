package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.store.KeyStore;
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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link KeyAuthFilter}'s <b>virtual-key leg</b> must run its blocking store work off
 * the Netty event loop (the same posture {@link AdminExecuteOnTest} pins for the admin
 * controllers): Micronaut invokes {@code doFilter} on the IO thread, and with
 * {@code [janus.store] type = "postgres"} every {@code KeyStore.authenticate} is a
 * blocking JDBC round-trip (pool checkout + SELECT + UPDATE). The filter defers the
 * decision to {@code TaskExecutors.BLOCKING} (the {@code DeferredAuthPublisher}), so a
 * recording {@link KeyStore} observes {@code authenticate} on a blocking-executor
 * thread — never {@code *-nioEventLoopGroup-*} — and both a plain and a streaming
 * request complete normally through the deferred chain (the bridge must be invisible
 * on the wire).
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
@Property(name = "janus.test.record-threads", value = "true")
class KeyAuthExecuteOnTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    EmbeddedServer server;

    @Inject
    KeyStore keyStore;

    @BeforeEach
    void clear() {
        RecordingKeyStoreFactory.RecordingKeyStore.clear();
    }

    @Test
    void virtualKeyAuthenticateRunsOffTheNettyEventLoop() {
        String key = createKey();
        TestRouterFactory.BACKEND.completeReturns(chatResponse());

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/chat/completions", chatRequestBody(false))
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-api-key", key),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus(), "the deferred auth must complete and the chain proceed");

        assertFalse(
                RecordingKeyStoreFactory.RecordingKeyStore.AUTH_EXECUTED_ON.isEmpty(),
                "the recording store must observe the filter's authenticate");
        for (String thread : RecordingKeyStoreFactory.RecordingKeyStore.AUTH_EXECUTED_ON) {
            assertFalse(
                    thread.contains("nioEventLoop"),
                    "virtual-key auth must never run on the Netty event loop (ran on: " + thread + ")");
            assertTrue(
                    thread.contains("executor"),
                    "virtual-key auth should run on a blocking executor thread: " + thread);
        }
    }

    @Test
    void streamingThroughTheDeferredAuthBridgeIsByteExactSse() throws Exception {
        // The deferred bridge must be wire-invisible for SSE too: after the offloaded
        // decision passes, the stream's frames (and the terminal [DONE]) flow exactly
        // as they did when the chain proceeded synchronously.
        String key = createKey();
        StreamChunk c1 = new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new io.amscotti.janus.core.model.ChunkChoice(
                        0,
                        new io.amscotti.janus.core.model.Delta(
                                io.amscotti.janus.core.model.ChatRole.ASSISTANT, "Hello", null),
                        null)),
                null,
                Map.of());
        StreamChunk c2 = new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new io.amscotti.janus.core.model.ChunkChoice(
                        0, new io.amscotti.janus.core.model.Delta(null, " world", null), null)),
                null,
                Map.of());
        TestRouterFactory.BACKEND.streamReturns(Stream.of(c1, c2));

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("x-api-key", key)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(chatRequestBody(true)))
                .build();
        java.net.http.HttpResponse<java.io.InputStream> response =
                http.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertEquals(
                "text/event-stream",
                response.headers().firstValue("Content-Type").orElse(""),
                "the SSE content type must survive the deferred chain");

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        assertEquals(
                List.of(
                        "data: "
                                + io.amscotti.janus.core.codec.OpenAiMessageCodec.create()
                                        .encodeChunk(c1),
                        "",
                        "data: "
                                + io.amscotti.janus.core.codec.OpenAiMessageCodec.create()
                                        .encodeChunk(c2),
                        "",
                        "data: [DONE]",
                        ""),
                lines,
                "SSE frames through the deferred auth bridge must be byte-exact");
    }

    // ---------------------------------------------------------------- helpers

    private String createKey() {
        return keyStore.create(new KeyStore.KeyCreateRequest(
                        "offloop", List.of("deepseek-v4-flash"), null, null, null, null, null))
                .fullKey();
    }

    private static ChatResponse chatResponse() {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new io.amscotti.janus.core.model.AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static String chatRequestBody(boolean stream) {
        return "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":"
                + stream + "}";
    }
}
