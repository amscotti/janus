package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.exception.NotFoundException;
import com.sun.net.httpserver.HttpServer;
import io.amscotti.janus.JanusConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The automated two-node integration test (Docker-gated).
 *
 * <p>Two <b>production-wired</b> Janus application contexts (two {@link EmbeddedServer}s
 * on distinct ports) sharing ONE real Postgres, driven through {@link HttpClient}
 * against both ports with a local golden fake upstream behind them. No
 * {@code TestGovernanceFactory}/{@code TestKeyAuthFactory}/{@code TestMetricsFactory}
 * {@code @Replaces}: the test factories are excluded by name (the two without a
 * {@code janus.test.production-factories} opt-out) / opted out by property, so the
 * production {@code CallStoreFactory} + {@code GovernanceFactory} + {@code MasterKeyProvider}
 * + {@code RouterFactory} + {@code MetricsFactory} run — the production-DI
 * precedent (see {@code ProductionMetricsExpositionTest}) applied to the store
 * seam. The {@code Clock} bean is the production {@code Clock.systemUTC}
 * ({@code RouterFactoryClockTest}'s {@code FixedClockFactory} is gated on its own
 * {@code janus.test.router-clock} property precisely so it cannot leak a frozen
 * clock into this context — see that class). The JDBC URL and master key resolve
 * from the real environment (the env-reference pattern — the
 * {@code janus-gateway} test task pins
 * {@code JANUS_DB_URL}/{@code JANUS_DB_USER}/{@code JANUS_DB_PASS}/{@code JANUS_MASTER_KEY};
 * the shared container binds host port 15432 to match).
 *
 * <p>Asserts (the same contract the gate's {@code drill_multi_node.py} proves over
 * two real processes, automated here):
 * (1) a key created via node A's {@code /key/generate} authenticates on node B
 * (OpenAI + Anthropic faces, streaming + non-streaming; golden content);
 * (2) an {@code rpm: 2} key's requests alternate A/B and the 3rd 429s
 * (shared fixed-window counters — atomic upserts, no overshoot, no dispatch);
 * (3) a {@code budget_usd: 0.01064} key's requests split across A/B settle
 * exactly on the cap cluster-wide; the 3rd 429s before dispatch;
 * (4) spend aggregates: the sum of the two nodes' per-key cost series == the
 * manual 5320-µUSD × N math AND the shared {@code spend} table total == the same;
 * (5) both nodes' {@code CallRecord}s for the key appear in the shared
 * {@code calls} table (cluster-wide {@code recentCalls} view).
 *
 * <p>Docker-gated: the container is started MANUALLY in {@code setUp} (after
 * {@code setPortBindings} — the {@code @Container} extension would start it on a
 * random host port first, before the 15432 binding applies, breaking the static
 * env-pinned URL) behind an {@code assumeTrue(DockerClientFactory…)} assumption, so
 * a Docker-less machine skips (not fails) — the two-process gate drills remain the
 * live proof there. {@code @Testcontainers(disabledWithoutDocker = true)} is kept
 * on the class for the documented intent (the extension is inert without
 * {@code @Container} fields).
 *
 * <p><b>Skip path (wider than "daemon absent").</b> The class runs inside the default
 * {@code test} task, so {@code./gradlew build} must never FAIL on a
 * Docker-present-but-unusable environment. {@code setUp} also aborts (skip, not fail)
 * on: a busy host port 15432 (an unrelated process, or a leaked container — Ryuk is
 * disabled, so a hard-killed test JVM would otherwise poison every later run; the
 * fixed-name {@code docker rm -f} pre-step below clears it), and a missing
 * {@code postgres:16-alpine} image under the default (offline) gate — the default
 * build never pulls images ("CI never touches the network"). The dedicated
 * {@code twoNodeTest} task sets {@code janus.test.offline=false}, so an explicit run
 * may still pull the image when it is not cached.
 */
@Testcontainers(disabledWithoutDocker = true)
class TwoNodeIntegrationTest {

    /** The committed golden usage ( chat.response.json): 14 prompt / 12 completion. */
    private static final String GOLDEN_BODY = """
            {
              "id": "chatcmpl-9d8c7b6a5f4e3d2c1b0a9f8e7d6c5b4a",
              "object": "chat.completion",
              "created": 1785715200,
              "model": "deepseek-v4-flash",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "The weather in Paris is 18 degrees with light rain."
                  },
                  "logprobs": null,
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 14,
                "completion_tokens": 12,
                "total_tokens": 26
              },
              "system_fingerprint": "fp_3a5770e1b4"
            }
            """;

    /** SSE stream mirroring the committed corpus shape (content + usage + [DONE]). */
    private static final String[] STREAM_FRAMES = {
        """
        {"id":"chatcmpl-w42-1","object":"chat.completion.chunk","created":1785715200,"model":"deepseek-v4-flash",\
"choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}""", """
        {"id":"chatcmpl-w42-1","object":"chat.completion.chunk","created":1785715200,"model":"deepseek-v4-flash",\
"choices":[{"index":0,"delta":{"content":"The weather in Paris is 18 degrees with light rain."},"finish_reason":null}]}""", """
        {"id":"chatcmpl-w42-1","object":"chat.completion.chunk","created":1785715200,"model":"deepseek-v4-flash",\
"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""", """
        {"id":"chatcmpl-w42-1","object":"chat.completion.chunk","created":1785715200,"model":"deepseek-v4-flash",\
"choices":[],"usage":{"prompt_tokens":14,"completion_tokens":12,"total_tokens":26}}""", "[DONE]",
    };

    private static final String MODEL = "deepseek-v4-flash";
    private static final String FIXTURE_CONTENT = "The weather in Paris is 18 degrees with light rain.";
    private static final int GOLDEN_MICRO = 5_320; // 14 × 0.14/1000 + 12 × 0.28/1000
    private static final int BUDGET_CAP_MICRO = 10_640; // 0.01064 USD = 2 × 5320
    private static final int FAKE_PORT = 18544; // fixed distinctive port

    /** The shared Postgres image and the fixed-name container (host port 15432 bound to
     * match the test-task env pin; the fixed name lets the pre-step {@code docker rm -f}
     * a container leaked by a hard-killed JVM — Ryuk is disabled). */
    private static final String PG_IMAGE = "postgres:16-alpine";

    private static final String PG_CONTAINER_NAME = "janus-w42-twonode-pg";
    private static final int PG_HOST_PORT = 15432;

    /** The shared Postgres — host port {@value #PG_HOST_PORT} bound to match the test-task env pin. */
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(PG_IMAGE)
            .withDatabaseName("janus")
            .withUsername("janus")
            .withPassword("janus")
            .withCreateContainerCmdModifier(cmd -> cmd.withName(PG_CONTAINER_NAME));

    private static HttpServer fake;
    private static final AtomicInteger FAKE_HITS = new AtomicInteger();
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static ApplicationContext contextA;
    private static ApplicationContext contextB;
    private static EmbeddedServer serverA;
    private static EmbeddedServer serverB;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker unavailable — skipping the two-node integration test" + " ");
        // Skip (never fail) on every Docker-present-but-unusable shape: a busy host port
        // 15432, a stale leaked container (cleared by the fixed-name rm -f below), or a
        // missing postgres:16-alpine image under the default (offline) gate.
        abortIfHostPortBusy();
        try {
            removeStaleSharedPostgres();
        } catch (RuntimeException e) {
            abort("cannot reach the Docker daemon to reset the shared container — skipping the"
                    + " two-node integration test (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        if (isOffline() && !hasLocalImage(PG_IMAGE)) {
            abort(PG_IMAGE + " is not cached locally — skipping the two-node integration test under the"
                    + " default gate (offline; run ./gradlew :janus-gateway:twoNodeTest to pull)");
        }
        try {
            PG.setPortBindings(List.of(PG_HOST_PORT + ":5432"));
            PG.start();
        } catch (RuntimeException e) {
            abort("shared Postgres could not start — skipping the two-node integration test ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        fake = bindFakeServer();

        // Free ephemeral ports — fixed 18080/18081 collide with local tools (e.g. a
        // static-file server on 18081 returns Go's "404 page not found" and looks like
        // a Janus routing failure).
        int portA = freePort();
        int portB = freePort();
        contextA = buildContext(portA, "node A");
        contextB = buildContext(portB, "node B");
        serverA = contextA.getBean(EmbeddedServer.class);
        serverB = contextB.getBean(EmbeddedServer.class);
        serverA.start();
        serverB.start();
    }

    /** Bind an ephemeral free port on loopback and return its number. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    @AfterAll
    static void tearDown() {
        if (serverA != null) {
            serverA.stop();
        }
        if (serverB != null) {
            serverB.stop();
        }
        if (contextA != null) {
            contextA.close();
        }
        if (contextB != null) {
            contextB.close();
        }
        if (fake != null) {
            fake.stop(0);
        }
        if (PG != null && PG.isRunning()) {
            PG.stop();
        }
    }

    /** Whether the default (offline) gate rule applies: the default {@code test} task
     * sets {@code janus.test.offline=true}; the dedicated {@code twoNodeTest} task sets
     * it false so an explicit run may pull a missing image. */
    private static boolean isOffline() {
        return Boolean.parseBoolean(System.getProperty("janus.test.offline", "true"));
    }

    /** True iff {@code port} is currently free on the loopback interface. */
    private static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void abortIfHostPortBusy() {
        if (!isPortFree(PG_HOST_PORT)) {
            abort("host port " + PG_HOST_PORT + " is already in use — skipping the two-node integration test"
                    + " (the shared container must bind it to match the test-task JANUS_DB_URL pin)");
        }
    }

    /**
     * {@code docker rm -f} the fixed-name shared container. Ryuk is disabled (the lima
     * Docker VM — see build.gradle), so container reaping rests on {@code @AfterAll}
     * {@code PG.stop}; a hard-killed test JVM would otherwise leak the container on
     * {@value #PG_HOST_PORT} and poison every later run on the host. Mirrors the store smoke
     * runner's {@code docker rm -f "$PG_CONTAINER"} pre-step (run.sh).
     */
    private static void removeStaleSharedPostgres() {
        try {
            DockerClientFactory.instance()
                    .client()
                    .removeContainerCmd(PG_CONTAINER_NAME)
                    .withForce(true)
                    .exec();
        } catch (NotFoundException ignored) {
            // no stale container — the happy path
        }
    }

    /** True iff {@code image} is already present in the local Docker cache (never pulls). */
    private static boolean hasLocalImage(String image) {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Binds the in-suite fake upstream on {@link #FAKE_PORT}. Copy of the
     * {@code ProductionMetricsExpositionTest} fail-loudly retry: a stray collision on the
     * distinctive port must stay VISIBLE (not flaky), so the bind is retried and then
     * fails with a message naming the port.
     */
    private static HttpServer bindFakeServer() throws IOException {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", FAKE_PORT), 0);
                // Adapter posts {base}/v1/chat/completions (OpenAI-compatible path).
                server.createContext("/v1/chat/completions", exchange -> {
                    FAKE_HITS.incrementAndGet();
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    String text = new String(body, StandardCharsets.UTF_8);
                    boolean wantsStream = text.contains("\"stream\":true") || text.contains("\"stream\": true");
                    boolean wantsUsage =
                            text.contains("\"include_usage\":true") || text.contains("\"include_usage\": true");
                    if (!wantsStream) {
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, GOLDEN_BODY.getBytes(StandardCharsets.UTF_8).length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(GOLDEN_BODY.getBytes(StandardCharsets.UTF_8));
                        }
                        return;
                    }
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        for (int i = 0; i < STREAM_FRAMES.length; i++) {
                            if (i == 3 && !wantsUsage) {
                                continue; // real-OpenAI gating: no include_usage ⇒ no terminal usage frame
                            }
                            os.write(("data: " + STREAM_FRAMES[i] + "\n\n").getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        }
                    }
                });
                server.start();
                return server;
            } catch (IOException e) {
                if (attempt == 4) {
                    throw new IOException(
                            "fake upstream port " + FAKE_PORT + " is busy after 5 attempts — "
                                    + "another process is squatting the test port (see "
                                    + "TwoNodeIntegrationTest.FAKE_PORT)",
                            e);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while retrying the fake port bind", ie);
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** One production-wired context (the production-DI pattern, no test factories). */
    private static ApplicationContext buildContext(int port, String label) {
        Map<String, Object> props = new HashMap<>();
        props.put("micronaut.server.port", port);
        props.put("endpoints.all.enabled", true);
        props.put("endpoints.all.sensitive", false);
        props.put("janus.model-list[0].name", MODEL);
        props.put("janus.model-list[0].provider", "openai-compatible");
        props.put("janus.model-list[0].base-url", "http://127.0.0.1:" + FAKE_PORT);
        props.put("janus.keys.master-key-env", "JANUS_MASTER_KEY");
        props.put("janus.pricing.models[0].name", MODEL);
        props.put("janus.pricing.models[0].input-per-1k", 0.14);
        props.put("janus.pricing.models[0].output-per-1k", 0.28);
        props.put("janus.pricing.models[0].default-max-tokens", 4096);
        props.put("janus.limits.window", "fixed");
        props.put("janus.store.type", "postgres");
        props.put("janus.store.jdbc-url-env", "JANUS_DB_URL");
        props.put("janus.store.user-env", "JANUS_DB_USER");
        props.put("janus.store.password-env", "JANUS_DB_PASS");
        props.put("janus.store.max-pool-size", 5);
        props.put("janus.store.retention", 1000);
        props.put("janus.test.production-factories", "true"); // opts TestMetricsFactory/TestRouterFactory out
        ApplicationContext context = ApplicationContext.builder()
                .environments(Environment.TEST)
                // The two test factories WITHOUT the production-factories opt-out would
                // @Replaces the production KeyStore/Clock/MasterKeyProvider/Governance —
                // exclude them so the production composition runs over the shared Postgres.
                .exclude(TestKeyAuthFactory.class.getName(), TestGovernanceFactory.class.getName())
                .properties(props)
                .start();
        context.getBean(JanusConfig.class); // force the config binding (fail fast at boot, like the CLI)
        return context;
    }

    // ------------------------------------------------------------------ HTTP

    private static String adminBase(int port) {
        return "http://127.0.0.1:" + port;
    }

    private static String v1Base(int port) {
        return "http://127.0.0.1:" + port + "/v1";
    }

    private record Response(int status, Map<String, List<String>> headers, String body) {}

    private static Response send(String method, String url, String jsonBody, String apiKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (jsonBody != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            builder.header("Content-Type", "application/json");
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (apiKey != null) {
            builder.header("x-api-key", apiKey);
        }
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers().map(), response.body());
    }

    private record GeneratedKey(String keyId, String key) {}

    private static GeneratedKey generateKey(int port, String master, String name, Map<String, Object> caps)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("models", List.of(MODEL));
        body.putAll(caps);
        Response response = send("POST", adminBase(port) + "/key/generate", json(body), master);
        assertEquals(200, response.status(), "POST /key/generate on node A: " + response.body());
        Matcher keyPattern =
                Pattern.compile("\"key\"\\s*:\\s*\"(sk-janus-[^\"]+)\"").matcher(response.body());
        assertTrue(keyPattern.find(), "generated key missing from: " + response.body());
        Matcher idMatcher = Pattern.compile("\"key_id\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
        assertTrue(idMatcher.find(), "key_id missing from: " + response.body());
        return new GeneratedKey(idMatcher.group(1), keyPattern.group(1));
    }

    private static String json(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":");
            sb.append(jsonValue(entry.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String jsonValue(Object value) {
        if (value instanceof String string) {
            return '"' + string + '"';
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(jsonValue(list.get(i)));
            }
            return sb.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append('"').append(entry.getKey()).append("\":").append(jsonValue(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        return String.valueOf(value); // numbers / booleans
    }

    private static Response chat(int port, String key, String content, boolean stream, boolean includeUsage)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", 16); // small reserve: the golden body's actual usage is 14/12 tokens
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        if (stream) {
            body.put("stream", true);
            if (includeUsage) {
                body.put("stream_options", Map.of("include_usage", true));
            }
        }
        return send("POST", v1Base(port) + "/chat/completions", json(body), key);
    }

    private static Response messages(int port, String key, String content) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", 1024);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        return send("POST", v1Base(port) + "/messages", json(body), key);
    }

    // ------------------------------------------------------------------ DB + metrics

    private static long dbLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for: " + sql);
            return rs.getLong(1);
        }
    }

    private static final Pattern KEY_ID_SHAPE = Pattern.compile("[0-9a-f]{32}");

    /**
     * Validates a server-issued key id before it is interpolated into SQL text.
     * Hygiene: {@link #generateKey} captures the id with {@code [^"]+}, so a future
     * server or fixture returning a non-hex id must never flow unescaped into a query.
     */
    private static void requireHexKeyId(String keyId) {
        if (!KEY_ID_SHAPE.matcher(keyId).matches()) {
            throw new IllegalArgumentException("key_id must be exactly 32 lowercase hex chars, got: " + keyId);
        }
    }

    /** The epoch-aligned fixed-window start (seconds) for the local clock — mirrors
     * {@code PgRateLimiter.windowStart}: {@code floorDiv(now, 60) * 60}. */
    private static long currentAlignedWindow() {
        long nowSeconds = System.currentTimeMillis() / 1000;
        return Math.floorDiv(nowSeconds, 60) * 60;
    }

    /**
     * Asserts the shared fixed-window RPM counter stayed EXACT after a 3-request leg
     * against an {@code rpm:2} key: {@code totalConsumed} requests must be spread over
     * the key's {@code requests} counter rows with no single window ever exceeding the
     * rpm cap. Window-agnostic on purpose — the rows carry the app's {@code window_start}
     * (whatever clock produced them), so the assertion never guesses a window. In the
     * 429 path this is the "denied never consumes" invariant (totalConsumed == 2); in a
     * rollover it proves the reset was exact (all three consumed, still capped per window).
     */
    private static void assertCounterExact(String keyId, long totalConsumed, String label) throws Exception {
        long sum = 0;
        long max = 0;
        try (Connection connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT count FROM rate_limits WHERE key_id = ? AND dimension = ?")) {
            ps.setString(1, keyId);
            ps.setString(2, "requests");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long count = rs.getLong(1);
                    sum += count;
                    max = Math.max(max, count);
                }
            }
        }
        assertEquals(totalConsumed, sum, label + " (total consumed across the touched windows)");
        assertTrue(max <= 2, label + " — no single window may exceed the rpm:2 cap (max row count " + max + ")");
    }

    /**
     * One RPM leg against a fresh {@code rpm:2} key: requests 1 and 2 on alternating
     * nodes, then the 3rd on the first node. The 3rd must 429 with the OpenAI-face
     * {@code rate_limit_error} envelope + {@code Retry-After ∈ [1, 60]} and never
     * dispatch — OR the fixed window rolled between the requests (the documented
     * counter reset), in which case the DB proves the reset was exact.
     */
    private static void assertSharedRpmLeg(int firstPort, int secondPort, GeneratedKey generated, String prefix)
            throws Exception {
        String key = generated.key();
        String keyId = generated.keyId();
        assertEquals(
                200, chat(firstPort, key, prefix + "-1", false, false).status(), prefix + " request 1 (first node)");
        assertEquals(
                200, chat(secondPort, key, prefix + "-2", false, false).status(), prefix + " request 2 (second node)");

        int hitsBefore = FAKE_HITS.get();
        Response third = chat(firstPort, key, prefix + "-3", false, false); // the 3rd, served by the first node
        if (third.status() == 429) {
            assertTrue(third.body().contains("rate_limit_error"), third.body());
            List<String> retryAfter = third.headers().getOrDefault("Retry-After", List.of());
            assertTrue(
                    retryAfter.stream().anyMatch(v -> {
                        try {
                            int seconds = Integer.parseInt(v);
                            return seconds >= 1 && seconds <= 60;
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }),
                    "Retry-After missing/out of [1, 60]: " + third.headers());
            assertEquals(hitsBefore, FAKE_HITS.get(), "the throttled request must never dispatch");
            assertCounterExact(keyId, 2, "the denied 3rd request never consumed");
        } else {
            // A fixed-window rollover reset the shared counter between the requests — the
            // documented semantic. Prove it was EXACT: all three consumed, no window over cap.
            assertEquals(
                    200,
                    third.status(),
                    prefix + " 3rd: expected 429 or an exact window rollover, got " + third.body());
            assertCounterExact(keyId, 3, "a rollover let the 3rd consume against a fresh window");
        }
    }

    private static double keyedCost(int port, String keyId) throws Exception {
        Response response = send("GET", adminBase(port) + "/metrics", null, null);
        assertEquals(200, response.status(), "GET /metrics on node " + port);
        double total = 0.0;
        for (String line : response.body().split("\n")) {
            // Anchored to the series name + '{' so a sibling series sharing the name as a
            // prefix (e.g. a Micrometer `_created` timestamp sample) can never be absorbed.
            if (!isSeriesLine(line, "janus_key_cost_micro_usd_total")) {
                continue;
            }
            if (!line.contains("key_id=\"" + keyId + "\"")) {
                continue;
            }
            Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)$").matcher(line);
            if (matcher.find()) {
                total += Double.parseDouble(matcher.group(1));
            }
        }
        return total;
    }

    /** True iff {@code line} is a Prometheus exposition sample for {@code name}: the
     * name immediately followed by '{' (labeled), whitespace (unlabeled), or end-of-line —
     * never a longer name that merely starts with it. */
    private static boolean isSeriesLine(String line, String name) {
        if (!line.startsWith(name) || line.length() == name.length()) {
            return false;
        }
        char next = line.charAt(name.length());
        return next == '{' || next == ' ' || next == '\t';
    }

    private static void awaitTrue(CheckedBoolean condition, long timeoutMillis, String label) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.get()) {
                    return;
                }
            } catch (Throwable t) {
                last = t;
            }
            Thread.sleep(200);
        }
        fail("timeout waiting for " + label + (last == null ? "" : ": " + last));
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }

    // ================================================================== tests

    @Test
    void keyCreatedOnNodeAAuthenticatesOnNodeB() throws Exception {
        String master = System.getenv("JANUS_MASTER_KEY");
        assertNotNull(master, "JANUS_MASTER_KEY must be set by the test task");
        GeneratedKey generated = generateKey(serverA.getPort(), master, "w42-it-key", Map.of());
        String key = generated.key();

        // non-streaming, both faces, on node B
        Response openai = chat(serverB.getPort(), key, "w42-it-b-o", false, false);
        assertEquals(200, openai.status(), "OpenAI face on B: " + openai.body());
        assertTrue(openai.body().contains(FIXTURE_CONTENT), "golden content on B: " + openai.body());
        assertTrue(openai.body().contains("\"prompt_tokens\":14"), openai.body());
        Response anthropic = messages(serverB.getPort(), key, "w42-it-b-a");
        assertEquals(200, anthropic.status(), "Anthropic face on B: " + anthropic.body());
        assertTrue(anthropic.body().contains(FIXTURE_CONTENT), anthropic.body());

        // streaming on node B (include_usage → terminal-chunk settle)
        Response stream = chat(serverB.getPort(), key, "w42-it-b-stream", true, true);
        assertEquals(200, stream.status(), "streaming on B: " + stream.body());
        assertTrue(stream.body().contains(FIXTURE_CONTENT), "streamed golden content on B: " + stream.body());
        assertTrue(stream.body().contains("[DONE]"), "stream terminated cleanly on B");

        // a wrong key still 401s on B (auth is enforcing through the shared store)
        Response bad = chat(serverB.getPort(), "sk-janus-bogus-000", "w42-it-bad", false, false);
        assertEquals(401, bad.status(), "unknown key on B: " + bad.body());
    }

    @Test
    void sharedRpmIsExactAcrossNodes() throws Exception {
        String master = System.getenv("JANUS_MASTER_KEY");
        int portA = serverA.getPort();
        int portB = serverB.getPort();

        // 3rd served by A: requests alternate A → B → A.
        GeneratedKey key1 = generateKey(portA, master, "w42-it-rpm", Map.of("rpm", 2));
        requireHexKeyId(key1.keyId());
        assertSharedRpmLeg(portA, portB, key1, "w42-rpm");

        // mirrored alternation — 3rd served by B: B → A → B.
        GeneratedKey key2 = generateKey(portA, master, "w42-it-rpm2", Map.of("rpm", 2));
        requireHexKeyId(key2.keyId());
        assertSharedRpmLeg(portB, portA, key2, "w42-rpm2");
    }

    /**
     * Boundary-pinned RPM case: only runs when the local clock is naturally in the tail
     * of a fixed window, so the three localhost round-trips all land in the SAME window
     * with a wide margin and the "3rd must 429" assertion is exercised against the shared
     * counter at the window's tail. Skips elsewhere — {@link #sharedRpmIsExactAcrossNodes}
     * is the always-on proof; this one pins the tail. The rollover-tolerant leg still
     * holds if the margin ever proves too small on a loaded box.
     */
    @Test
    void rpmThirdRequestInSameTailWindowStill429s() throws Exception {
        String master = System.getenv("JANUS_MASTER_KEY");
        long remaining = currentAlignedWindow() * 1000 + 60_000 - System.currentTimeMillis();
        assumeTrue(
                remaining >= 5_000 && remaining <= 15_000,
                "not within the tail of a fixed window (remaining=" + remaining + "ms) —"
                        + " skipping the boundary-pinned RPM case");

        int portA = serverA.getPort();
        int portB = serverB.getPort();
        GeneratedKey generated = generateKey(portA, master, "w42-it-rpm-tail", Map.of("rpm", 2));
        requireHexKeyId(generated.keyId());
        assertSharedRpmLeg(portA, portB, generated, "w42-rpm-tail");
    }

    @Test
    void sharedBudgetSettlesExactlyOnCapClusterWide() throws Exception {
        String master = System.getenv("JANUS_MASTER_KEY");
        GeneratedKey generated = generateKey(serverA.getPort(), master, "w42-it-budget", Map.of("budget_usd", 0.01064));
        String key = generated.key();
        String keyId = generated.keyId();
        requireHexKeyId(keyId);
        int portA = serverA.getPort();
        int portB = serverB.getPort();

        Response r1 = chat(portA, key, "w42-budget-1", false, false);
        assertEquals(200, r1.status(), "budget request 1 on A: " + r1.body());
        Response r2 = chat(portB, key, "w42-budget-2", false, false);
        assertEquals(200, r2.status(), "budget request 2 on B: " + r2.body());

        awaitTrue(
                () -> dbLong("SELECT settled FROM spend WHERE key_id = '" + keyId + "'") == BUDGET_CAP_MICRO,
                10_000,
                "DB spend settled == cap (10640) after the two 200s");

        int hitsBefore = FAKE_HITS.get();
        Response third = chat(portA, key, "w42-budget-3", false, false);
        assertEquals(429, third.status(), "budget 3rd must 429 BEFORE dispatch: " + third.body());
        assertTrue(third.body().contains("rate_limit_error"), third.body());
        assertTrue(third.headers().get("Retry-After") == null, "no Retry-After on a budget denial (no timer refill)");
        assertEquals(hitsBefore, FAKE_HITS.get(), "budget 429 must not dispatch");
        assertEquals(
                BUDGET_CAP_MICRO,
                dbLong("SELECT settled FROM spend WHERE key_id = '" + keyId + "'"),
                "settled == cap, no overspend");
        assertEquals(
                0,
                dbLong("SELECT pending FROM spend WHERE key_id = '" + keyId + "'"),
                "pending == 0 after the denials");
    }

    @Test
    void sharedWindowedBudgetIsExactAcrossNodes() throws Exception {
        // The multi-node pin: a key with budget_usd AND budget_duration enforces
        // the SAME window row on both nodes (the composite (key_id, window_start) PK),
        // the windowed denial carries Retry-After (≤ the tiny duration), and a mid-test
        // rollover flips BOTH nodes via the shared DB row. Production runs
        // Clock.systemUTC, so the key uses a tiny duration (2s) instead of a long
        // sleep; because a 2s window can roll between any two requests, the cap leg is
        // rollover-tolerant (the assertSharedRpmLeg pattern): requests alternate until
        // two land in one window, and the next one's 429 is observed.
        String master = System.getenv("JANUS_MASTER_KEY");
        assertNotNull(master, "JANUS_MASTER_KEY must be set by the test task");
        GeneratedKey generated = generateKey(
                serverA.getPort(), master, "w42-it-windowed", Map.of("budget_usd", 0.01064, "budget_duration", 2));
        String key = generated.key();
        String keyId = generated.keyId();
        requireHexKeyId(keyId);
        int portA = serverA.getPort();
        int portB = serverB.getPort();

        // Alternate A/B until the shared window cap trips. Each allowed request settles
        // exactly GOLDEN_MICRO; two in one window reach the cap and the NEXT request —
        // whichever node serves it — 429s. Requests already settled by earlier loop
        // iterations can also trip the cap (a rollover-tolerant alternation), so any
        // 429 in the sequence is the observation; only the dispatch count is asserted
        // (one upstream hit per 200, none for the 429).
        Response denial = null;
        long hitsAtDenialStart = -1;
        int dispatchedAtDenial = -1;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (denial == null && System.nanoTime() < deadline) {
            long hitsBefore = FAKE_HITS.get();
            int dispatched = 0;
            for (int i = 0; i < 3 && denial == null; i++) {
                int port = i % 2 == 0 ? portA : portB;
                Response response = chat(port, key, "w42-win-" + i, false, false);
                if (response.status() == 200) {
                    dispatched++;
                } else {
                    denial = response;
                    hitsAtDenialStart = hitsBefore;
                    dispatchedAtDenial = dispatched;
                }
            }
            if (denial == null) {
                assertEquals(hitsBefore + 3, FAKE_HITS.get(), "each allowed windowed request dispatched once");
            }
        }
        assertNotNull(denial, "a same-window cap denial must occur within the bounded alternation");
        assertTrue(denial.body().contains("rate_limit_error"), denial.body());
        assertTrue(denial.body().contains("budget"), "the budget denial names the budget: " + denial.body());
        List<String> retryAfter = denial.headers().getOrDefault("Retry-After", List.of());
        assertTrue(
                retryAfter.stream().anyMatch(v -> {
                    try {
                        int seconds = Integer.parseInt(v);
                        return seconds >= 1 && seconds <= 2;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }),
                "a windowed budget denial carries Retry-After ∈ [1, 2] (the tiny 2s window): " + denial.headers());
        assertEquals(
                hitsAtDenialStart + dispatchedAtDenial,
                FAKE_HITS.get(),
                "the 429 never dispatched (one hit per 200, none for the denial)");
        long cappedWindow = newestWindowStart(keyId);
        assertEquals(BUDGET_CAP_MICRO, newestWindowSettled(keyId), "the capped window row holds exactly the cap");
        assertEquals(
                0, windowPending(keyId, cappedWindow), "pending == 0 in the capped window (denials never consume)");

        // The mid-test rollover flips both nodes via the shared row: wait out the 2s
        // window, then node B admits again into a FRESH window row while the capped
        // row survives untouched.
        long totalBefore = totalSettled(keyId);
        awaitTrue(() -> System.currentTimeMillis() / 1000 >= cappedWindow + 2, 5_000, "the tiny window to roll over");
        Response fresh = chat(portB, key, "w42-win-4", false, false);
        assertEquals(200, fresh.status(), "the rollover reset the shared window for node B: " + fresh.body());
        long freshWindow = newestWindowStart(keyId);
        assertTrue(freshWindow > cappedWindow, "the fresh request landed in a newer window row");
        assertEquals(GOLDEN_MICRO, newestWindowSettled(keyId), "the fresh window row holds one golden settle");
        assertEquals(
                BUDGET_CAP_MICRO,
                windowSettled(keyId, cappedWindow),
                "the capped row is untouched by the rollover (per-window isolation)");
        assertEquals(totalBefore + GOLDEN_MICRO, totalSettled(keyId), "the all-time sum grows by exactly one settle");
    }

    /** The key's newest spend row window_start (the current reset window). */
    private static long newestWindowStart(String keyId) throws Exception {
        return dbLong("SELECT window_start FROM spend WHERE key_id = '" + keyId + "'"
                + " ORDER BY window_start DESC LIMIT 1");
    }

    /** The key's newest spend row settled total (the windowed budget view). */
    private static long newestWindowSettled(String keyId) throws Exception {
        return dbLong(
                "SELECT settled FROM spend WHERE key_id = '" + keyId + "'" + " ORDER BY window_start DESC LIMIT 1");
    }

    /** The key's all-time settled sum across window rows. */
    private static long totalSettled(String keyId) throws Exception {
        return dbLong("SELECT COALESCE(sum(settled), 0) FROM spend WHERE key_id = '" + keyId + "'");
    }

    /** One specific window row's settled total. */
    private static long windowSettled(String keyId, long windowStart) throws Exception {
        return dbLong("SELECT settled FROM spend WHERE key_id = '" + keyId + "' AND window_start = " + windowStart);
    }

    /** One specific window row's pending total. */
    private static long windowPending(String keyId, long windowStart) throws Exception {
        return dbLong("SELECT pending FROM spend WHERE key_id = '" + keyId + "' AND window_start = " + windowStart);
    }

    @Test
    void spendAggregatesAcrossNodesAndMetrics() throws Exception {
        String master = System.getenv("JANUS_MASTER_KEY");
        // A budgeted key: the shared `spend` table row is created by the reserve/settle
        // flow (unbudgeted keys skip it) — a large budget so the per-request reserve
        // estimates never bind; the settled column still lands exactly at N × 5320.
        GeneratedKey generated = generateKey(serverA.getPort(), master, "w42-it-spend", Map.of("budget_usd", 1.0));
        String key = generated.key();
        String keyId = generated.keyId();
        requireHexKeyId(keyId);
        int portA = serverA.getPort();
        int portB = serverB.getPort();

        assertEquals(200, chat(portA, key, "w42-spend-1", false, false).status(), "spend req 1 on A");
        assertEquals(200, messages(portB, key, "w42-spend-2").status(), "spend req 2 (anthropic) on B");
        assertEquals(200, chat(portB, key, "w42-spend-3", true, true).status(), "spend req 3 (stream) on B");

        int expected = 3 * GOLDEN_MICRO; // manual math: N × 5320 micro-USD
        awaitTrue(
                () -> dbLong("SELECT settled FROM spend WHERE key_id = '" + keyId + "'") == expected,
                10_000,
                "DB spend == " + expected);
        assertEquals(
                expected,
                dbLong("SELECT settled FROM spend WHERE key_id = '" + keyId + "'"),
                "DB spend total == manual math");

        // the sum of the two nodes' per-key cost series == the same manual math
        awaitTrue(() -> keyedCost(portA, keyId) + keyedCost(portB, keyId) == expected, 10_000, "metrics sum");
        double metricsSum = keyedCost(portA, keyId) + keyedCost(portB, keyId);
        assertEquals(expected, metricsSum, 0.001, "per-node metrics sum == DB total == manual math");
        assertNotEquals(0.0, keyedCost(portA, keyId), "node A served at least one keyed request");
        assertNotEquals(0.0, keyedCost(portB, keyId), "node B served at least one keyed request");

        // the shared calls table holds both nodes' CallRecords (cluster-wide recentCalls view)
        assertEquals(
                3,
                dbLong("SELECT count(*) FROM calls WHERE key_id = '" + keyId + "'"),
                "one CallRecord per settled request, from both nodes");
        assertEquals(
                3,
                dbLong("SELECT count(DISTINCT request_id) FROM calls WHERE key_id = '" + keyId + "'"),
                "distinct request ids in the shared calls table");
    }
}
