package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.amscotti.janus.core.codec.DecisionCodecException;
import io.amscotti.janus.core.model.DecisionQuestion;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.provider.GeminiDecisionsAdapter;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end production-DI gateway tests for {@code POST /v1/systemone} routing to
 * {@code gemini-decisions} (real codecs, {@link DecisionRouterFactory},
 * {@link DecisionModelListFactory}, {@link KeyAuthFilter}, {@link Governance}, and Prometheus
 * {@code /metrics} against a port-0 loopback stub).
 */
class GeminiDecisionsControllerTest {

    private static final String TEST_MASTER_KEY = "w42-twonode-test-master-key";
    private static final String VALID_PNG_B64 = Base64.getEncoder()
            .encodeToString(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00});
    private static final String VALID_PDF_B64 =
            Base64.getEncoder().encodeToString("%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));
    private static final String VALID_WAV_B64 =
            Base64.getEncoder().encodeToString(new byte[] {'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V', 'E'});
    private static final String SECRET_CANARY = "UPSTREAM_INTERNAL_SECRET_CANARY";

    private HttpServer geminiServer;
    private HttpServer openAiServer;
    private EmbeddedServer embeddedServer;
    private HttpClient httpClient;

    private static final tools.jackson.databind.ObjectMapper JSON = GatewayJson.mapper();
    private final List<String> geminiBodies = new ArrayList<>();
    private final List<String> geminiGoogKeys = new ArrayList<>();
    private final List<String> geminiAuthHeaders = new ArrayList<>();
    private final AtomicInteger geminiCalls = new AtomicInteger();
    private final AtomicInteger openAiCalls = new AtomicInteger();
    private final AtomicReference<Integer> nextStatus = new AtomicReference<>(200);
    private final AtomicReference<String> nextBody = new AtomicReference<>("{}");
    private final AtomicReference<String> nextRetryAfter = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (httpClient != null) {
            httpClient.close();
        }
        if (embeddedServer != null) {
            embeddedServer.close();
        }
        if (geminiServer != null) {
            geminiServer.stop(0);
        }
        if (openAiServer != null) {
            openAiServer.stop(0);
        }
    }

    private void startGateway() throws IOException {
        geminiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        geminiServer.createContext("/", exchange -> {
            geminiCalls.incrementAndGet();
            geminiGoogKeys.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            geminiAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            geminiBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (nextRetryAfter.get() != null) {
                exchange.getResponseHeaders().set("Retry-After", nextRetryAfter.get());
            }
            byte[] bytes = nextBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(nextStatus.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        geminiServer.start();

        openAiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        openAiServer.createContext("/", exchange -> {
            openAiCalls.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = ("{\"error\":{\"message\":\"unexpected openai call\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        openAiServer.start();

        String geminiBase = "http://127.0.0.1:" + geminiServer.getAddress().getPort();
        String openAiBase = "http://127.0.0.1:" + openAiServer.getAddress().getPort();

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("micronaut.server.port", 0);
        props.put("janus.test.production-factories", "true");
        props.put("janus.auth.master-key-env", "JANUS_MASTER_KEY");
        props.put("janus.store.type", "memory");
        props.put("janus.router.max-retries", 1);
        props.put("janus.router.allowed-fails", 3);

        props.put("janus.decision-providers.google-decisions.wire-format", "gemini-decisions");
        props.put("janus.decision-providers.google-decisions.base-url", geminiBase);
        props.put("janus.decision-providers.google-decisions.api-key-env", "JANUS_MASTER_KEY");

        props.put("janus.decision-providers.openai-decisions.wire-format", "openai-decisions");
        props.put("janus.decision-providers.openai-decisions.base-url", openAiBase);
        props.put("janus.decision-providers.openai-decisions.api-key-env", "JANUS_MASTER_KEY");

        // Primary vision-enabled gemini alias
        props.put("janus.decision-model-list[0].name", "gemini-decision-1");
        props.put("janus.decision-model-list[0].provider", "google-decisions");
        props.put("janus.decision-model-list[0].vision", true);

        // Text-only gemini alias (vision = false)
        props.put("janus.decision-model-list[1].name", "gemini-text-only");
        props.put("janus.decision-model-list[1].provider", "google-decisions");
        props.put("janus.decision-model-list[1].upstream-model", "gemini-decision-1");
        props.put("janus.decision-model-list[1].vision", false);

        // Mixed pool: OpenAI Decisions first, Gemini Decisions second (no OpenAI HTTP call on PDF!)
        props.put("janus.decision-model-list[2].name", "gemini-pooled");
        props.put("janus.decision-model-list[2].provider", "openai-decisions");
        props.put("janus.decision-model-list[2].upstream-model", "gpt-6-luna");
        props.put("janus.decision-model-list[2].vision", true);
        props.put("janus.decision-model-list[3].name", "gemini-pooled");
        props.put("janus.decision-model-list[3].provider", "google-decisions");
        props.put("janus.decision-model-list[3].upstream-model", "gemini-decision-1");
        props.put("janus.decision-model-list[3].vision", true);

        // Exact published AI Studio pricing: $0.10 / 1M input = 0.0001 / 1K, output = 0.0
        props.put("janus.pricing.models[0].name", "gemini-decision-1");
        props.put("janus.pricing.models[0].input-per-1k", "0.0001");
        props.put("janus.pricing.models[0].output-per-1k", "0.0");

        props.put("janus.pricing.models[1].name", "gemini-text-only");
        props.put("janus.pricing.models[1].input-per-1k", "0.0001");
        props.put("janus.pricing.models[1].output-per-1k", "0.0");

        props.put("janus.pricing.models[2].name", "gemini-pooled");
        props.put("janus.pricing.models[2].input-per-1k", "0.0001");
        props.put("janus.pricing.models[2].output-per-1k", "0.0");

        embeddedServer = ApplicationContext.run(EmbeddedServer.class, props, Environment.TEST);
        httpClient = HttpClient.create(embeddedServer.getURL());
    }

    private String mintKey(List<String> models, String budgetUsd) {
        StringBuilder body = new StringBuilder("{\"models\":[");
        for (int i = 0; i < models.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append('"').append(models.get(i)).append('"');
        }
        body.append(']');
        if (budgetUsd != null) {
            body.append(",\"budget_usd\":").append(budgetUsd);
        }
        body.append('}');
        var req = io.micronaut.http.HttpRequest.POST("/key/generate", body.toString())
                .header("Authorization", "Bearer " + TEST_MASTER_KEY)
                .contentType(MediaType.APPLICATION_JSON_TYPE);
        String raw = httpClient.toBlocking().retrieve(req);
        return JSON.readTree(raw).get("key").asString();
    }

    private io.micronaut.http.HttpResponse<String> postSystemOneExchange(String key, String jsonBody) {
        var req = io.micronaut.http.HttpRequest.POST("/v1/systemone", jsonBody)
                .header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON_TYPE);
        return httpClient.toBlocking().exchange(req, String.class);
    }

    private JsonNode postSystemOne(String key, String jsonBody) {
        String raw = postSystemOneExchange(key, jsonBody).getBody(String.class).orElseThrow();
        return JSON.readTree(raw);
    }

    private HttpClientResponseException postSystemOneError(String key, String jsonBody) {
        var req = io.micronaut.http.HttpRequest.POST("/v1/systemone", jsonBody)
                .contentType(MediaType.APPLICATION_JSON_TYPE);
        if (key != null) {
            req = req.header("Authorization", "Bearer " + key);
        }
        var request = req;
        return assertThrows(
                HttpClientResponseException.class, () -> httpClient.toBlocking().exchange(request, String.class));
    }

    @Test
    void textDomainJsonAndMultiFormatRequestsTranslateToGeminiDecisionsAndEchoAlias() throws Exception {
        startGateway();
        // 0.12 USD = 120,000 micro-USD -> 80% soft cap (96,000) fires on Call 1 (100,000 micro-USD)
        String key = mintKey(List.of("gemini-decision-1", "gemini-text-only"), "0.12");

        // 1. Plain string state + noul/choice/score + 1,000,000 input tokens (= 100,000 micro-USD)
        nextStatus.set(200);
        nextBody.set("""
                {
                  "answers": {
                    "q_noul": {"type": "noul", "noul": 0.96, "confidence": 0.96},
                    "q_choice": {"type": "choice", "choice": "billing", "probabilities": {"billing": 0.9, "other": 0.1}, "confidence": 0.9},
                    "q_score": {"type": "score", "score": 1.5, "legend": {"0": "Low", "1": "Mid", "2": "High"}, "probabilities": {"0": 0.1, "1": 0.3, "2": 0.6}, "confidence": 0.6}
                  },
                  "usage": {
                    "total_tokens": 1000000,
                    "total_input_tokens": 1000000,
                    "total_output_tokens": 0,
                    "input_tokens_by_modality": [{"modality": "text", "tokens": 1000000}]
                  }
                }
                """);

        var resHttp = postSystemOneExchange(key, """
                {
                  "model": "gemini-decision-1",
                  "state": "User reports duplicate invoice #12.",
                  "questions": {
                    "q_noul": {"type": "noul", "instructions": "Duplicate charge?", "criteria": {"true": "Yes", "false": "No"}},
                    "q_choice": {"type": "choice", "instructions": "Category?", "criteria": {"billing": "Billing", "other": "Other"}},
                    "q_score": {"type": "score", "instructions": "Severity?", "criteria": ["Low", "Mid", "High"]}
                  }
                }
                """);
        assertNull(resHttp.getHeaders().get("X-Janus-Budget-Used-Micro-Usd"));
        JsonNode res = JSON.readTree(resHttp.getBody(String.class).orElseThrow());

        assertEquals("gemini-decision-1", res.get("model").asString());
        assertEquals(0.96, res.get("answers").get("q_noul").get("noul").asDouble(), 1e-6);
        assertEquals("billing", res.get("answers").get("q_choice").get("choice").asString());
        assertEquals(1.5, res.get("answers").get("q_score").get("score").asDouble(), 1e-6);
        assertEquals(1_000_000L, res.get("usage").get("input_tokens").asLong());
        assertEquals(0L, res.get("usage").get("output_tokens").asLong());

        assertEquals(TEST_MASTER_KEY, geminiGoogKeys.get(0));
        assertNull(geminiAuthHeaders.get(0));
        JsonNode sent = JSON.readTree(geminiBodies.get(0));
        assertEquals("User reports duplicate invoice #12.", sent.get("input").asString());
        assertEquals(
                "Yes",
                sent.get("questions")
                        .get("q_noul")
                        .get("criteria")
                        .get("true_description")
                        .asString());

        // 2. Domain JSON Map state folds to compact text block
        nextBody.set("""
                {
                  "answers": {"q": {"type": "noul", "noul": 0.8}},
                  "usage": {"total_tokens": 1000, "total_input_tokens": 1000, "total_output_tokens": 0}
                }
                """);
        var domainHttp = postSystemOneExchange(key, """
                {
                  "model": "gemini-text-only",
                  "state": {"amount": 42, "currency": "USD"},
                  "questions": {"q": {"type": "noul", "instructions": "Valid?"}}
                }
                """);
        assertEquals("soft", domainHttp.getHeaders().get("X-Janus-Budget-Warning"));
        assertEquals("100100", domainHttp.getHeaders().get("X-Janus-Budget-Used-Micro-Usd"));
        JsonNode domainSent = JSON.readTree(geminiBodies.get(1));
        assertEquals("text", domainSent.get("input").get(0).get("type").asString());
        assertEquals(
                "{\"amount\":42,\"currency\":\"USD\"}",
                domainSent.get("input").get(0).get("text").asString());

        // 3. Cross-dialect & native multi-format (text + PNG + PDF + WAV + MP4 + shared images[])
        String multiBody = """
                {
                  "model": "gemini-decision-1",
                  "state": [
                    {"type": "input_text", "text": "Review attachments:"},
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64,%s", "detail": "high"}},
                    {"type": "file", "file": {"file_data": "data:application/pdf;base64,%s"}},
                    {"type": "input_audio", "input_audio": {"data": "%s", "format": "wav"}},
                    {"type": "video_url", "video_url": {"url": "https://example.com/clip.mp4"}}
                  ],
                  "images": ["%s"],
                  "questions": {"q": {"type": "noul", "instructions": "All clear?"}}
                }
                """.formatted(VALID_PNG_B64, VALID_PDF_B64, VALID_WAV_B64, VALID_PNG_B64);
        var multiHttp = postSystemOneExchange(key, multiBody);
        assertEquals("100200", multiHttp.getHeaders().get("X-Janus-Budget-Used-Micro-Usd"));

        JsonNode multiSent = JSON.readTree(geminiBodies.get(2)).get("input");
        assertEquals(6, multiSent.size());
        assertEquals("text", multiSent.get(0).get("type").asString());
        assertEquals("image", multiSent.get(1).get("type").asString());
        assertEquals("high", multiSent.get(1).get("resolution").asString());
        assertEquals("document", multiSent.get(2).get("type").asString());
        assertEquals("application/pdf", multiSent.get(2).get("mime_type").asString());
        assertEquals("audio", multiSent.get(3).get("type").asString());
        assertEquals("audio/wav", multiSent.get(3).get("mime_type").asString());
        assertEquals("video", multiSent.get(4).get("type").asString());
        assertEquals("https://example.com/clip.mp4", multiSent.get(4).get("uri").asString());
        assertEquals("image", multiSent.get(5).get("type").asString());
        assertEquals("image/png", multiSent.get(5).get("mime_type").asString());

        // 4. Verify $0.10/Mtok accounting + Prometheus registry privacy labels (direct scrape avoids
        // Micronaut PrometheusEndpoint PipedInputStream race under parallel test load)
        String metrics = embeddedServer
                .getApplicationContext()
                .getBean(PrometheusMeterRegistry.class)
                .scrape();
        assertTrue(metrics.contains("face=\"systemone\""), metrics);
        assertTrue(metrics.contains("janus_cost_micro_usd_total 100200.0"), metrics);
        assertTrue(metrics.contains("janus_key_cost_micro_usd_total"), metrics);
        assertFalse(metrics.contains("duplicate invoice"), "metrics must never leak prompt text");
    }

    @Test
    void visionGateUnsupportedRequestsAndGoogleErrorsDoNotLeakAndFailoverWorks() throws Exception {
        startGateway();
        String key = mintKey(List.of("gemini-decision-1", "gemini-text-only", "gemini-pooled"), null);

        // 1. Vision gate rejects image evidence on gemini-text-only (0 upstream calls)
        HttpClientResponseException visionBlocked = postSystemOneError(key, """
                {
                  "model": "gemini-text-only",
                  "state": "Look",
                  "images": ["%s"],
                  "questions": {"q": {"type": "noul", "instructions": "Clear?"}}
                }
                """.formatted(VALID_PNG_B64));
        assertEquals(HttpStatus.BAD_REQUEST, visionBlocked.getStatus());
        assertEquals(0, geminiCalls.get());

        // 2. BoxQuestion / non-empty assets / missing instructions rejected with 400 (0 upstream calls)
        HttpClientResponseException boxErr = postSystemOneError(key, """
                {
                  "model": "gemini-decision-1",
                  "state": "Look",
                  "images": ["%s"],
                  "questions": {"q": {"type": "box", "instructions": "Find"}}
                }
                """.formatted(VALID_PNG_B64));
        assertEquals(HttpStatus.BAD_REQUEST, boxErr.getStatus());

        HttpClientResponseException assetsErr = postSystemOneError(key, """
                {
                  "model": "gemini-decision-1",
                  "state": "Look",
                  "assets": {"a": {"type": "image", "image_url": "https://example.com/a.png"}},
                  "questions": {"q": {"type": "noul", "instructions": "?"}}
                }
                """);
        assertEquals(HttpStatus.BAD_REQUEST, assetsErr.getStatus());

        HttpClientResponseException nullInstrErr = postSystemOneError(key, """
                {
                  "model": "gemini-decision-1",
                  "state": "Look",
                  "questions": {"q": {"type": "noul", "instructions": null}}
                }
                """);
        assertEquals(HttpStatus.BAD_REQUEST, nullInstrErr.getStatus());
        assertEquals(0, geminiCalls.get());

        // 3. Google upstream error envelopes map cleanly without leaking body text
        String simpleReq = """
                {
                  "model": "gemini-decision-1",
                  "state": "hi",
                  "questions": {"q": {"type": "noul", "instructions": "?"}}
                }
                """;
        nextStatus.set(400);
        nextBody.set("{\"error\":{\"message\":\"" + SECRET_CANARY + "\",\"code\":\"invalid_request\"}}");
        HttpClientResponseException bad400 = postSystemOneError(key, simpleReq);
        assertEquals(HttpStatus.BAD_REQUEST, bad400.getStatus());
        assertFalse(bad400.getResponse().getBody(String.class).orElse("").contains(SECRET_CANARY));

        nextStatus.set(401);
        nextBody.set("{\"error\":{\"code\":401,\"message\":\"" + SECRET_CANARY + "\",\"status\":\"UNAUTHENTICATED\"}}");
        assertEquals(HttpStatus.UNAUTHORIZED, postSystemOneError(key, simpleReq).getStatus());

        nextStatus.set(403);
        nextBody.set(
                "{\"error\":{\"code\":403,\"message\":\"" + SECRET_CANARY + "\",\"status\":\"PERMISSION_DENIED\"}}");
        assertEquals(HttpStatus.UNAUTHORIZED, postSystemOneError(key, simpleReq).getStatus());

        nextStatus.set(404);
        nextBody.set("{\"error\":{\"message\":\"" + SECRET_CANARY + "\",\"code\":\"not_found\"}}");
        assertEquals(HttpStatus.NOT_FOUND, postSystemOneError(key, simpleReq).getStatus());

        nextStatus.set(429);
        nextRetryAfter.set("9");
        nextBody.set(
                "{\"error\":{\"code\":429,\"message\":\"" + SECRET_CANARY + "\",\"status\":\"RESOURCE_EXHAUSTED\"}}");
        HttpClientResponseException rate429 = postSystemOneError(key, simpleReq);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rate429.getStatus());
        assertEquals("9", rate429.getResponse().getHeaders().get("Retry-After"));
        nextRetryAfter.set(null);

        nextStatus.set(200);
        nextBody.set("{\"answers\":{},\"usage\":{\"total_tokens\":1,\"total_input_tokens\":1}}");
        assertEquals(HttpStatus.BAD_GATEWAY, postSystemOneError(key, simpleReq).getStatus());

        // 4. Mixed pool failover: openai-decisions (priority 1) declines PDF locally, fails over to gemini-decisions
        // (priority 2)
        nextStatus.set(200);
        nextBody.set("""
                {
                  "answers": {"q": {"type": "noul", "noul": 0.91}},
                  "usage": {"total_tokens": 500, "total_input_tokens": 500, "total_output_tokens": 0}
                }
                """);
        JsonNode pooledRes = postSystemOne(key, """
                {
                  "model": "gemini-pooled",
                  "state": [{"type": "document", "data": "%s", "mime_type": "application/pdf"}],
                  "questions": {"q": {"type": "noul", "instructions": "Valid?"}}
                }
                """.formatted(VALID_PDF_B64));
        assertEquals("gemini-pooled", pooledRes.get("model").asString());
        assertEquals(0.91, pooledRes.get("answers").get("q").get("noul").asDouble(), 1e-6);
        assertEquals(0, openAiCalls.get(), "openai-decisions must fail capability check locally before HTTP");
    }

    @Test
    void authScopeBudgetAndBackendLogSafeSanitizationHold() throws Exception {
        startGateway();

        // Missing auth -> 401
        String simpleReq = """
                {
                  "model": "gemini-decision-1",
                  "state": "hi",
                  "questions": {"q": {"type": "noul", "instructions": "?"}}
                }
                """;
        assertEquals(
                HttpStatus.UNAUTHORIZED, postSystemOneError(null, simpleReq).getStatus());

        // Scope denied -> 403
        String scopedKey = mintKey(List.of("gemini-text-only"), "0.0001");
        assertEquals(
                HttpStatus.FORBIDDEN, postSystemOneError(scopedKey, simpleReq).getStatus());

        // Budget exhausted after 1st $0.10-rate request ($0.0001 budget allows 1000 tokens, next request exceeds)
        nextStatus.set(200);
        nextBody.set("""
                {
                  "answers": {"q": {"type": "noul", "noul": 0.75}},
                  "usage": {"total_tokens": 2000, "total_input_tokens": 2000, "total_output_tokens": 0}
                }
                """);
        postSystemOne(scopedKey, """
                {
                  "model": "gemini-text-only",
                  "state": "hi",
                  "questions": {"q": {"type": "noul", "instructions": "?"}}
                }
                """);
        HttpClientResponseException overBudget = postSystemOneError(scopedKey, """
                {
                  "model": "gemini-text-only",
                  "state": "hi",
                  "questions": {"q": {"type": "noul", "instructions": "?"}}
                }
                """);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, overBudget.getStatus());

        // DecisionAdapterBackend vision gate sanitizes malicious model strings against log/CRLF injection
        DecisionAdapterBackend textOnlyBackend = new DecisionAdapterBackend(
                new GeminiDecisionsAdapter("http://127.0.0.1:1", ""), "gemini-text-only", "gemini-decision-1", false);
        DecisionRequest dirtyModelReq = new DecisionRequest(
                "bad\nmodel\r\tname" + "x".repeat(200),
                "Look",
                List.of(VALID_PNG_B64),
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of());
        DecisionCodecException sanitizedErr =
                assertThrows(DecisionCodecException.class, () -> textOnlyBackend.decide(dirtyModelReq));
        assertFalse(sanitizedErr.getMessage().contains("\n"));
        assertFalse(sanitizedErr.getMessage().contains("\r"));
        assertTrue(sanitizedErr.getMessage().contains("bad model  name"), sanitizedErr.getMessage());
        assertTrue(sanitizedErr.getMessage().contains("..."), sanitizedErr.getMessage());
        assertTrue(sanitizedErr.getMessage().length() <= 280, sanitizedErr.getMessage());
    }
}
