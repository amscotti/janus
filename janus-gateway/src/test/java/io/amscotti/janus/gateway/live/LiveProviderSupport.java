package io.amscotti.janus.gateway.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared helpers for env-gated live provider ITs. Talks to the {@link EmbeddedServer}
 * Janus context (production factories). Live tests never run under the default
 * {@code test}/{@code build} task ({@code @Tag("live")} is excluded).
 */
final class LiveProviderSupport {

    static final String LIVE_OPT_IN = "JANUS_LIVE";
    static final String MASTER_ENV = "JANUS_MASTER_KEY";

    static final String WEATHER_TOOLS_JSON = """
            [{
              "type": "function",
              "function": {
                "name": "get_weather",
                "description": "Get the current weather for a city. Always use this for weather questions.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "city": { "type": "string", "description": "City name" }
                  },
                  "required": ["city"]
                }
              }
            }]
            """;

    /** Two tools for parallel multi-tool live pins. */
    static final String MULTI_TOOLS_JSON = """
            [
              {
                "type": "function",
                "function": {
                  "name": "get_weather",
                  "description": "Get the current weather for a city.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "city": { "type": "string", "description": "City name" }
                    },
                    "required": ["city"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "get_time",
                  "description": "Get the current local time for a city.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "city": { "type": "string", "description": "City name" }
                    },
                    "required": ["city"]
                  }
                }
              }
            ]
            """;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);
    /**
     * Minted virtual keys, keyed by {@link #virtualKeyCacheKey}. Static so one
     * {@code @MicronautTest} can reuse a key across its methods; the client identity
     * in the key keeps a second live IT class from presenting a secret its own
     * in-memory store has never seen.
     */
    private static final Map<String, String> KEY_CACHE = new ConcurrentHashMap<>();

    private static final ObjectMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private LiveProviderSupport() {}

    static boolean envSet(String name) {
        String v = System.getenv(name);
        return v != null && !v.isBlank();
    }

    static void assumeProviderKey(String envName) {
        assumeTrue(envSet(envName), () -> "SKIP: " + envName + " not set — live provider test not run");
    }

    static String requireMasterKey() {
        String key = System.getenv(MASTER_ENV);
        assumeTrue(
                key != null && !key.isBlank(),
                "SKIP: JANUS_MASTER_KEY not set (liveTest task should supply a default)");
        return key;
    }

    /**
     * Cache identity for {@link #virtualKey}: the model list scoped to this HTTP
     * client (each {@code @MicronautTest} injects its own client against its own
     * EmbeddedServer).
     */
    static String virtualKeyCacheKey(Object client, List<String> models) {
        return System.identityHashCode(client) + ":" + String.join(",", models);
    }

    static String virtualKey(HttpClient client, List<String> models) {
        return KEY_CACHE.computeIfAbsent(
                virtualKeyCacheKey(client, models),
                k -> mintKeyFull(client, models, Map.of()).key());
    }

    static String virtualKeyWithCaps(HttpClient client, List<String> models, Map<String, Object> caps) {
        return mintKeyFull(client, models, caps == null ? Map.of() : caps).key();
    }

    /** Mint a virtual key and return both the secret and the stable {@code key_id} (for metrics). */
    static MintedKey mintKeyFull(HttpClient client, List<String> models, Map<String, Object> caps) {
        String master = requireMasterKey();
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("name", "live-" + System.nanoTime());
        body.put("models", models);
        body.putAll(caps == null ? Map.of() : caps);
        String json = JSON.writeValueAsString(body);
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/generate", json)
                                .contentType(MediaType.APPLICATION_JSON_TYPE)
                                .header("x-api-key", master),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus(), "key/generate");
        JsonNode node = readTree(http.body());
        String key = node.get("key").stringValue();
        String keyId = node.get("key_id").stringValue();
        assertNotNull(key);
        assertNotNull(keyId);
        assertTrue(key.startsWith("sk-janus-"), key);
        assertTrue(!keyId.isBlank(), "key_id blank");
        return new MintedKey(key, keyId);
    }

    private static String mintKey(HttpClient client, List<String> models, Map<String, Object> caps) {
        return mintKeyFull(client, models, caps).key();
    }

    record MintedKey(String key, String keyId) {}

    /**
     * {@code POST /v1/responses} against the live gateway — returns the raw status
     * + body so failure-mode cases (store: true → 400) can assert without the 2xx
     * precondition. Bearer auth like every live helper.
     */
    static StatusBody openaiResponsesRaw(HttpClient client, String virtualKey, String jsonBody) {
        try {
            HttpResponse<String> http = client.toBlocking()
                    .exchange(
                            HttpRequest.POST("/v1/responses", jsonBody)
                                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                                    .bearerAuth(virtualKey),
                            String.class);
            return new StatusBody(http.getStatus(), http.body(), readTree(http.body()), http.getHeaders());
        } catch (HttpClientResponseException e) {
            String raw = e.getResponse().getBody(String.class).orElse("");
            JsonNode json;
            try {
                json = readTree(raw);
            } catch (RuntimeException ignored) {
                json = JSON.nullNode();
            }
            return new StatusBody(e.getStatus(), raw, json, e.getResponse().getHeaders());
        }
    }

    /**
     * {@code POST /v1/responses} with {@code stream:true} — the raw
     * SSE body (200-enforced like every stream helper; a non-2xx is a JSON envelope).
     */
    static String responsesStream(EmbeddedServer server, String virtualKey, String jsonBody)
            throws IOException, InterruptedException {
        URI uri = URI.create(server.getURL().toString().replaceAll("/$", "") + "/v1/responses");
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-api-key", virtualKey)
                .POST(BodyPublishers.ofString(jsonBody))
                .build();
        java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .version(Version.HTTP_1_1)
                .build();
        return requireStreamOk("POST " + uri, jdk.send(req, BodyHandlers.ofString()));
    }

    static JsonNode openaiChat(HttpClient client, String virtualKey, String jsonBody) {
        StatusBody res = openaiChatRaw(client, virtualKey, jsonBody);
        assertEquals(HttpStatus.OK, res.status(), () -> "chat/completions: " + res.raw());
        return res.json();
    }

    static StatusBody openaiChatRaw(HttpClient client, String virtualKey, String jsonBody) {
        try {
            HttpResponse<String> http = client.toBlocking()
                    .exchange(
                            HttpRequest.POST("/v1/chat/completions", jsonBody)
                                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                                    .bearerAuth(virtualKey),
                            String.class);
            return new StatusBody(http.getStatus(), http.body(), readTree(http.body()), http.getHeaders());
        } catch (HttpClientResponseException e) {
            String raw = e.getResponse().getBody(String.class).orElse("");
            JsonNode json;
            try {
                json = readTree(raw);
            } catch (RuntimeException ignored) {
                json = JSON.nullNode();
            }
            return new StatusBody(e.getStatus(), raw, json, e.getResponse().getHeaders());
        } catch (HttpClientException e) {
            // No HTTP response (e.g. ReadTimeoutException on a slow reasoning upstream):
            // surface a clean, diagnosable failure instead of an uncaught client
            // exception escaping the helper.
            throw new AssertionError("no HTTP response from gateway: " + e.getMessage(), e);
        }
    }

    static JsonNode anthropicMessages(HttpClient client, String virtualKey, String jsonBody) {
        StatusBody res = anthropicMessagesRaw(client, virtualKey, jsonBody);
        assertEquals(HttpStatus.OK, res.status(), () -> "/v1/messages: " + res.raw());
        return res.json();
    }

    static StatusBody anthropicMessagesRaw(HttpClient client, String virtualKey, String jsonBody) {
        try {
            HttpResponse<String> http = client.toBlocking()
                    .exchange(
                            HttpRequest.POST("/v1/messages", jsonBody)
                                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                                    .header("x-api-key", virtualKey)
                                    .header("anthropic-version", "2023-06-01"),
                            String.class);
            return new StatusBody(http.getStatus(), http.body(), readTree(http.body()), http.getHeaders());
        } catch (HttpClientResponseException e) {
            String raw = e.getResponse().getBody(String.class).orElse("");
            JsonNode json;
            try {
                json = readTree(raw);
            } catch (RuntimeException ignored) {
                json = JSON.nullNode();
            }
            return new StatusBody(e.getStatus(), raw, json, e.getResponse().getHeaders());
        } catch (HttpClientException e) {
            throw new AssertionError("no HTTP response from gateway: " + e.getMessage(), e);
        }
    }

    record StatusBody(HttpStatus status, String raw, JsonNode json, io.micronaut.http.HttpHeaders headers) {}

    static String openaiStream(EmbeddedServer server, String virtualKey, String jsonBody)
            throws IOException, InterruptedException {
        URI uri = URI.create(server.getURL().toString().replaceAll("/$", "") + "/v1/chat/completions");
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + virtualKey)
                .POST(BodyPublishers.ofString(jsonBody))
                .build();
        java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .version(Version.HTTP_1_1)
                .build();
        return requireStreamOk("POST " + uri, jdk.send(req, BodyHandlers.ofString()));
    }

    /**
     * Open a streaming chat, read until at least one SSE {@code data:} frame arrives, then
     * close the body (client abort). Returns the partial payload read before abort.
     * Pins that Janus emits SSE before the client hangs up — and that abort does not
     * cascade as an uncaught gateway fault (connection is released).
     */
    static String openaiStreamAbortAfterFirstData(EmbeddedServer server, String virtualKey, String jsonBody)
            throws IOException, InterruptedException {
        URI uri = URI.create(server.getURL().toString().replaceAll("/$", "") + "/v1/chat/completions");
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + virtualKey)
                .POST(BodyPublishers.ofString(jsonBody))
                .build();
        java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .version(Version.HTTP_1_1)
                .build();
        java.net.http.HttpResponse<java.io.InputStream> res =
                jdk.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        int code = res.statusCode();
        if (code < 200 || code >= 300) {
            String err = new String(res.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            throw new AssertionError("stream abort probe → HTTP " + code + ": " + err);
        }
        StringBuilder acc = new StringBuilder();
        byte[] buf = new byte[4096];
        try (java.io.InputStream in = res.body()) {
            while (acc.indexOf("data:") < 0) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                acc.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                if (acc.length() > 256_000) {
                    break; // safety
                }
            }
            // Client abort: closing the body tears down the connection mid-stream.
        }
        return acc.toString();
    }

    /** GET /metrics text (auth-exempt ops path). */
    static String scrapeMetrics(HttpClient client) {
        HttpResponse<String> http = client.toBlocking()
                .exchange(HttpRequest.GET("/metrics").accept(MediaType.TEXT_PLAIN_TYPE), String.class);
        assertEquals(HttpStatus.OK, http.getStatus(), () -> "metrics: " + http.body());
        return http.body() == null ? "" : http.body();
    }

    /**
     * Parse a Prometheus counter value for {@code series} with optional label
     * {@code key_id="…"}. Returns empty if the series line is absent.
     */
    static java.util.OptionalDouble metricCounter(String metricsText, String series, String keyIdOrNull) {
        String needle = keyIdOrNull == null ? series + " " : series + "{key_id=\"" + keyIdOrNull + "\"}";
        for (String line : metricsText.split("\n")) {
            if (line.startsWith("#")) {
                continue;
            }
            if (keyIdOrNull == null) {
                if (line.startsWith(series + " ") || line.startsWith(series + "{")) {
                    // unlabeled or multi-label — only accept exact unlabeled form for totals
                    if (line.startsWith(series + " ")) {
                        String[] parts = line.trim().split("\\s+");
                        return java.util.OptionalDouble.of(Double.parseDouble(parts[parts.length - 1]));
                    }
                }
            } else if (line.contains(needle)
                    || (line.startsWith(series + "{") && line.contains("key_id=\"" + keyIdOrNull + "\""))) {
                String[] parts = line.trim().split("\\s+");
                return java.util.OptionalDouble.of(Double.parseDouble(parts[parts.length - 1]));
            }
        }
        return java.util.OptionalDouble.empty();
    }

    /**
     * Expected micro-USD for the live IT pricing rows on {@code deepseek-v4-flash}
     * (peak Aug 2026: {@code $0.44 / $1.32 per 1M} → {@code 0.00044 / 0.00132}
     * per 1K) — matches {@link io.amscotti.janus.store.CostCalculator#costMicroUsd}.
     */
    static long expectedMicroUsdDeepseekFlash(long promptTokens, long completionTokens) {
        double micro =
                promptTokens * 0.00044 * 1_000_000.0 / 1_000.0 + completionTokens * 0.00132 * 1_000_000.0 / 1_000.0;
        return (long) Math.floor(micro + 0.5);
    }

    /**
     * Expected micro-USD for the live IT pricing rows on {@code grok-4.6}
     * (official xAI Aug 2026: {@code $2.00 / $6.00 per 1M} →
     * {@code input-per-1k=0.002}, {@code output-per-1k=0.006}) — matches
     * {@link io.amscotti.janus.store.CostCalculator#costMicroUsd} for the regular
     * input/output terms. Cache-read ({@code $0.50} per 1M) is billed separately
     * and added from the cost header in the spend-settle pin.
     */
    static long expectedMicroUsdGrok46(long promptTokens, long completionTokens) {
        double micro = promptTokens * 0.002 * 1_000_000.0 / 1_000.0 + completionTokens * 0.006 * 1_000_000.0 / 1_000.0;
        return (long) Math.floor(micro + 0.5);
    }

    static String anthropicStream(EmbeddedServer server, String virtualKey, String jsonBody)
            throws IOException, InterruptedException {
        URI uri = URI.create(server.getURL().toString().replaceAll("/$", "") + "/v1/messages");
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-api-key", virtualKey)
                .header("anthropic-version", "2023-06-01")
                .POST(BodyPublishers.ofString(jsonBody))
                .build();
        java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .version(Version.HTTP_1_1)
                .build();
        return requireStreamOk("POST " + uri, jdk.send(req, BodyHandlers.ofString()));
    }

    /**
     * A stream must be 2xx — SSE frames are only produced on success; a non-2xx is a
     * JSON error envelope (401/429/502…). Fail loudly with the status + body so a
     * mid-stream failure is diagnosable instead of a confusing "no data: frames"
     * content assertion.
     */
    private static String requireStreamOk(String label, java.net.http.HttpResponse<String> res) {
        int code = res.statusCode();
        if (code < 200 || code >= 300) {
            String body = res.body();
            throw new AssertionError(
                    label + " → HTTP " + code + ": " + (body.length() <= 500 ? body : body.substring(0, 500) + "..."));
        }
        return res.body();
    }

    static String plainChatBody(String model) {
        return plainChatBody(model, 32, null);
    }

    /**
     * @param maxTokens completion budget (reasoning models like Muse Spark need ≥256–1024
     *     or they finish with empty content and {@code finish_reason=length})
     * @param extraFields optional raw JSON object fields after {@code max_tokens}
     *     (e.g. {@code "reasoning": {"effort": "none"}}) — no leading comma
     */
    static String plainChatBody(String model, int maxTokens, String extraFields) {
        String extras = extraFields == null || extraFields.isBlank() ? "" : ",\n  " + extraFields;
        return """
                {
                  "model": "%s",
                  "messages": [{"role":"user","content":"Reply with exactly the word pong and nothing else."}],
                  "max_tokens": %d%s
                }
                """.formatted(model, maxTokens, extras);
    }

    static String streamChatBody(String model) {
        return streamChatBody(model, 32, null);
    }

    static String streamChatBody(String model, int maxTokens, String extraFields) {
        String extras = extraFields == null || extraFields.isBlank() ? "" : ",\n  " + extraFields;
        return """
                {
                  "model": "%s",
                  "stream": true,
                  "messages": [{"role":"user","content":"Reply with exactly the word pong and nothing else."}],
                  "max_tokens": %d%s
                }
                """.formatted(model, maxTokens, extras);
    }

    static String toolCallBody(String model, String city) {
        return toolCallBody(model, city, "required", 256, "\"thinking\": {\"type\": \"disabled\"}");
    }

    /**
     * Tool-call request with configurable {@code tool_choice} and extras (OpenRouter/Meta
     * models differ: Qwen rejects {@code required} in thinking mode; Meta only allows
     * {@code auto}).
     */
    static String toolCallBody(String model, String city, String toolChoice, int maxTokens, String extraFields) {
        String extras = extraFields == null || extraFields.isBlank() ? "" : ",\n  " + extraFields;
        // tool_choice may be a bare string (auto/required/none) — emit as JSON string.
        return """
                {
                  "model": "%s",
                  "messages": [{"role":"user","content":"What is the weather in %s? You must use the get_weather tool."}],
                  "tools": %s,
                  "tool_choice": "%s",
                  "max_tokens": %d%s
                }
                """.formatted(model, city, WEATHER_TOOLS_JSON, toolChoice, maxTokens, extras);
    }

    static String openAiToolFollowUpBody(
            String model, JsonNode turn1, String toolCallId, String city, String extraFields) {
        return openAiToolFollowUpBody(model, turn1, toolCallId, city, 256, extraFields);
    }

    static String openAiToolFollowUpBody(
            String model, JsonNode turn1, String toolCallId, String city, int maxTokens, String extraFields) {
        JsonNode msg = turn1.path("choices").path(0).path("message");
        String assistantJson = msg.toString();
        String extras = extraFields == null || extraFields.isBlank() ? "" : ",\n  " + extraFields;
        return """
                {
                  "model": "%s",
                  "messages": [
                    {"role":"user","content":"What is the weather?"},
                    %s,
                    {"role":"tool","tool_call_id":"%s","content":"{\\"temp\\":18,\\"city\\":\\"%s\\"}"}
                  ],
                  "tools": %s,
                  "max_tokens": %d%s
                }
                """.formatted(model, assistantJson, toolCallId, city, WEATHER_TOOLS_JSON, maxTokens, extras);
    }

    static void assertNonEmptyAssistantContent(JsonNode res) {
        JsonNode content = res.path("choices").path(0).path("message").path("content");
        assertTrue(content.isString() && !content.stringValue().isBlank(), res::toString);
    }

    /** Non-throwing probe of {@link #assertNonEmptyAssistantContent} (provider-variance retries). */
    static boolean hasNonEmptyAssistantContent(JsonNode res) {
        JsonNode content = res.path("choices").path(0).path("message").path("content");
        return content.isString() && !content.stringValue().isBlank();
    }

    /** Non-throwing probe of {@link #assertOpenAiToolCall} (provider-variance retries). */
    static boolean hasOpenAiToolCall(JsonNode res) {
        JsonNode tools = res.path("choices").path(0).path("message").path("tool_calls");
        return tools.isArray()
                && !tools.isEmpty()
                && tools.path(0).path("function").path("name").isString();
    }

    /** Parse assistant chat content as a JSON object, stripping an optional markdown fence. */
    static JsonNode parseJsonObjectFromAssistant(JsonNode chatResponse) {
        JsonNode content = chatResponse.path("choices").path(0).path("message").path("content");
        assertTrue(content.isString() && !content.stringValue().isBlank(), chatResponse::toString);
        String trimmed = content.stringValue().trim();
        if (trimmed.startsWith("```")) {
            int newline = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (newline > 0 && end > newline) {
                trimmed = trimmed.substring(newline + 1, end).trim();
            }
        }
        final String jsonText = trimmed;
        JsonNode parsed = JSON.readTree(jsonText);
        assertTrue(parsed.isObject(), () -> "assistant content is not a JSON object: " + jsonText);
        return parsed;
    }

    static String responsesOutputText(JsonNode response) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : response.path("output")) {
            if (!"message".equals(item.path("type").stringValue())) {
                continue;
            }
            for (JsonNode part : item.path("content")) {
                if ("output_text".equals(part.path("type").stringValue())
                        && part.path("text").isString()) {
                    text.append(part.path("text").stringValue());
                }
            }
        }
        return text.toString();
    }

    static void assertPromptCacheRead(StatusBody hit) {
        long body = cacheReadTokens(hit.json().path("usage"));
        long head = headerLong(hit, "X-Janus-Cost-Cache-Read-Micro-Usd");
        assertTrue(
                body >= 1024 || head > 0,
                () -> "expected a cache read of the 1024-token prefix. bodyRead=" + body + " headerRead=" + head
                        + " usage=" + hit.json().path("usage"));
    }

    static void assertOpenAiToolCall(JsonNode res) {
        JsonNode tools = res.path("choices").path(0).path("message").path("tool_calls");
        assertTrue(tools.isArray() && !tools.isEmpty(), res::toString);
        assertTrue(tools.path(0).path("function").path("name").isString(), res::toString);
    }

    /** Assert the assistant message carries at least {@code min} tool_calls with function names. */
    static void assertOpenAiToolCallsAtLeast(JsonNode res, int min) {
        JsonNode tools = res.path("choices").path(0).path("message").path("tool_calls");
        assertTrue(tools.isArray() && tools.size() >= min, () -> "expected ≥" + min + " tool_calls: " + res);
        for (JsonNode call : tools) {
            assertTrue(call.path("function").path("name").isString(), res::toString);
            assertTrue(
                    call.path("id").isString() && !call.path("id").stringValue().isBlank(), res::toString);
        }
    }

    static String multiToolCallBody(String model, String city) {
        return """
                {
                  "model": "%s",
                  "messages": [{
                    "role": "user",
                    "content": "For %s: call get_weather AND get_time. You must use both tools in this turn (parallel tool calls)."
                  }],
                  "tools": %s,
                  "tool_choice": "required",
                  "parallel_tool_calls": true,
                  "max_tokens": 512,
                  "thinking": {"type": "disabled"}
                }
                """.formatted(model, city, MULTI_TOOLS_JSON);
    }

    /**
     * Multi-round plain chat: seed a name, then ask for it back. The first-turn
     * assistant message is embedded as returned by the wire so multi-turn history
     * round-trips through the codec.
     */
    static String multiRoundFollowUpBody(String model, JsonNode turn1, String ask) {
        return multiRoundFollowUpBody(model, turn1, ask, 128, null);
    }

    static String multiRoundFollowUpBody(String model, JsonNode turn1, String ask, int maxTokens, String extraFields) {
        JsonNode msg = turn1.path("choices").path(0).path("message");
        String assistantJson = msg.toString();
        String extras = extraFields == null || extraFields.isBlank() ? "" : ",\n  " + extraFields;
        return """
                {
                  "model": "%s",
                  "messages": [
                    {"role":"user","content":"My secret codeword is zebra42. Acknowledge in one short sentence."},
                    %s,
                    {"role":"user","content":"%s"}
                  ],
                  "max_tokens": %d%s
                }
                """.formatted(model, assistantJson, ask.replace("\"", "\\\""), maxTokens, extras);
    }

    static String multiRoundSeedBody(String model, int maxTokens, String extraFields) {
        String extras = extraFields == null || extraFields.isBlank() ? "" : ",\n  " + extraFields;
        return """
                {
                  "model": "%s",
                  "messages": [{"role":"user","content":"My secret codeword is zebra42. Acknowledge in one short sentence."}],
                  "max_tokens": %d%s
                }
                """.formatted(model, maxTokens, extras);
    }

    static String systemPromptChatBody(String model, String system, String user) {
        return systemPromptChatBody(model, system, user, 64, null);
    }

    static String systemPromptChatBody(String model, String system, String user, int maxTokens, String extraFields) {
        String extras = extraFields == null || extraFields.isBlank() ? "" : ",\n  " + extraFields;
        return """
                {
                  "model": "%s",
                  "messages": [
                    {"role":"system","content":"%s"},
                    {"role":"user","content":"%s"}
                  ],
                  "max_tokens": %d%s
                }
                """.formatted(model, system.replace("\"", "\\\""), user.replace("\"", "\\\""), maxTokens, extras);
    }

    /**
     * OpenAI-face multi-tool follow-up: inject one tool message per tool_call from turn1.
     * {@code resultsByName} maps function name → tool result JSON string.
     */
    static String openAiMultiToolFollowUpBody(
            String model, JsonNode turn1, Map<String, String> resultsByName, String extraFields) {
        JsonNode msg = turn1.path("choices").path(0).path("message");
        String assistantJson = msg.toString();
        StringBuilder toolMsgs = new StringBuilder();
        for (JsonNode call : msg.path("tool_calls")) {
            String id = call.path("id").stringValue();
            String name = call.path("function").path("name").stringValue();
            String result = resultsByName.getOrDefault(name, "{\"ok\":true}");
            // Escape for embedding inside a JSON string value that we then paste as raw JSON
            // objects — result is raw JSON, not a quoted string, so re-serialize carefully.
            String escaped = result.replace("\\", "\\\\").replace("\"", "\\\"");
            if (!toolMsgs.isEmpty()) {
                toolMsgs.append(",\n                    ");
            }
            toolMsgs.append("{\"role\":\"tool\",\"tool_call_id\":\"")
                    .append(id)
                    .append("\",\"content\":\"")
                    .append(escaped)
                    .append("\"}");
        }
        String extras = extraFields == null || extraFields.isBlank() ? "" : ",\n  " + extraFields;
        return """
                {
                  "model": "%s",
                  "messages": [
                    {"role":"user","content":"Use the tools."},
                    %s,
                    %s
                  ],
                  "tools": %s,
                  "max_tokens": 256%s
                }
                """.formatted(model, assistantJson, toolMsgs, MULTI_TOOLS_JSON, extras);
    }

    static JsonNode openaiGet(HttpClient client, String path, String virtualKeyOrNull) {
        try {
            var req = HttpRequest.GET(path).accept(MediaType.APPLICATION_JSON_TYPE);
            if (virtualKeyOrNull != null && !virtualKeyOrNull.isBlank()) {
                req.bearerAuth(virtualKeyOrNull);
            }
            HttpResponse<String> http = client.toBlocking().exchange(req, String.class);
            assertEquals(HttpStatus.OK, http.getStatus(), () -> path + ": " + http.body());
            return readTree(http.body());
        } catch (HttpClientResponseException e) {
            String raw = e.getResponse().getBody(String.class).orElse("");
            throw new AssertionError(path + " → " + e.getStatus() + ": " + raw, e);
        }
    }

    static JsonNode readTree(String raw) {
        return JSON.readTree(raw);
    }

    /**
     * ≥1024-token stable prefix for live prompt-cache write+hit (GPT-5.6 / Sonnet 5
     * minimum). {@code salt} must stay identical across the write and hit requests.
     */
    static String cacheablePrefix(String salt) {
        return ("Stable Janus prompt-cache prefix. Policy v1. Salt=" + salt + ". ").repeat(90);
    }

    static long cacheReadTokens(JsonNode usage) {
        long chat = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        if (chat > 0) {
            return chat;
        }
        long responses =
                usage.path("input_tokens_details").path("cached_tokens").asLong(0);
        if (responses > 0) {
            return responses;
        }
        return usage.path("cache_read_input_tokens").asLong(0);
    }

    static long cacheWriteTokens(JsonNode usage) {
        long chat =
                usage.path("prompt_tokens_details").path("cache_write_tokens").asLong(0);
        if (chat > 0) {
            return chat;
        }
        return usage.path("cache_creation_input_tokens").asLong(0);
    }

    static long headerLong(StatusBody res, String name) {
        if (res.headers() == null) {
            return 0L;
        }
        String value = res.headers().get(name);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Cache hits are visible as Anthropic {@code cache_read_input_tokens}, OpenAI
     * Chat Completions {@code prompt_tokens_details.cached_tokens} /
     * {@code prompt_cache_hit_tokens}, Responses {@code input_tokens_details.cached_tokens},
     * or Janus cost headers.
     */
    static void assertPromptCacheHit(StatusBody first, StatusBody second) {
        JsonNode firstUsage = first.json().path("usage");
        JsonNode secondUsage = second.json().path("usage");
        long bodyWrite1 = cacheWriteTokens(firstUsage);
        long bodyRead1 = cacheReadTokens(firstUsage);
        long bodyRead2 = cacheReadTokens(secondUsage);
        long headWrite1 = headerLong(first, "X-Janus-Cost-Cache-Creation-Micro-Usd");
        long headRead1 = headerLong(first, "X-Janus-Cost-Cache-Read-Micro-Usd");
        long headRead2 = headerLong(second, "X-Janus-Cost-Cache-Read-Micro-Usd");
        assertTrue(
                bodyRead2 >= 1024 || headRead2 > 0 || bodyRead1 >= 1024 || headRead1 > 0,
                () -> "expected a cache hit of the 1024-token prefix."
                        + " bodyWrite1=" + bodyWrite1 + " bodyRead1=" + bodyRead1 + " bodyRead2=" + bodyRead2
                        + " headWrite1=" + headWrite1 + " headRead1=" + headRead1 + " headRead2=" + headRead2
                        + " firstUsage=" + firstUsage + " secondUsage=" + secondUsage);
    }
}
