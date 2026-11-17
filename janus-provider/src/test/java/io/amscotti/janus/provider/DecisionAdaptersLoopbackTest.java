package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.amscotti.janus.core.codec.GeminiDecisionsCodec;
import io.amscotti.janus.core.codec.OpenAiDecisionsCodec;
import io.amscotti.janus.core.codec.SystemOneCodec;
import io.amscotti.janus.core.model.DecisionAnswer;
import io.amscotti.janus.core.model.DecisionQuestion;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Decision adapters against a loopback fake upstream (JDK HttpServer) — the
 * {@code DeepSeekAdapterTest} pattern: request body is the codec encode,
 * endpoint/auth wiring, error-status mapping (incl. Ollama's plain-string
 * error bodies and envelope refinement), and the inert discovery form.
 */
class DecisionAdaptersLoopbackTest {

    private static final String SYSTEMONE_RESPONSE =
            "{\"model\":\"tev1:0.8b\",\"answers\":{\"refund\":{\"type\":\"noul\",\"noul\":0.97}},"
                    + "\"usage\":{\"input_tokens\":42,\"output_tokens\":0},\"prompt_eval_cached_count\":7}";

    private static final String OPENAI_RESPONSE =
            "{\"answers\":[{\"type\":\"predicate\",\"name\":\"refund\",\"probability\":0.91}],"
                    + "\"model\":\"gpt-6-luna\",\"usage\":{\"input_tokens\":30,\"input_tokens_details\":"
                    + "{\"cached_tokens\":0,\"cache_write_tokens\":0},\"output_tokens\":3,\"total_tokens\":33}}";

    private static final String GEMINI_RESPONSE =
            "{\"answers\":{\"refund\":{\"type\":\"noul\",\"noul\":0.93,\"confidence\":0.93}},"
                    + "\"usage\":{\"total_tokens\":210,\"total_input_tokens\":210,\"total_output_tokens\":0,"
                    + "\"input_tokens_by_modality\":[{\"modality\":\"text\",\"tokens\":210}]}}";

    private HttpServer server;
    private URI baseUri;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private final AtomicReference<String> capturedGoogKey = new AtomicReference<>();
    private final AtomicReference<String> capturedDangerImages = new AtomicReference<>();
    private final AtomicReference<String> capturedDangerLongContext = new AtomicReference<>();
    private final AtomicReference<Integer> nextStatus = new AtomicReference<>(200);
    private final AtomicReference<String> nextBody = new AtomicReference<>("{}");
    private final AtomicReference<Long> nextHeaderDelayMillis = new AtomicReference<>(0L);
    private final AtomicReference<Long> nextBodyDelayMillis = new AtomicReference<>(0L);
    private final AtomicReference<String> nextRetryAfter = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> stallEntered = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> stallRelease = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedGoogKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            capturedDangerImages.set(exchange.getRequestHeaders().getFirst("TypeSafe-Danger-Zone-Images"));
            capturedDangerLongContext.set(exchange.getRequestHeaders().getFirst("TypeSafe-Danger-Zone-Long-Context"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sleep(nextHeaderDelayMillis.getAndSet(0L));
            CountDownLatch entered = stallEntered.getAndSet(null);
            CountDownLatch release = stallRelease.getAndSet(null);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (nextRetryAfter.get() != null) {
                exchange.getResponseHeaders().set("Retry-After", nextRetryAfter.get());
            }
            if (entered != null && release != null) {
                exchange.sendResponseHeaders(nextStatus.get(), 1024);
                exchange.getResponseBody().flush();
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            byte[] bytes = nextBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(nextStatus.get(), bytes.length);
            sleep(nextBodyDelayMillis.getAndSet(0L));
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void systemOnePostsCodecBodyToVersionedPathAndDecodes() {
        serve(200, SYSTEMONE_RESPONSE);
        SystemOneAdapter adapter = new SystemOneAdapter("ollama-local", baseUri.toString(), "");
        DecisionRequest request = request("d3");

        DecisionResponse response = adapter.decide(request);

        assertEquals("/v1/systemone", capturedPath.get());
        assertNull(capturedAuth.get(), "blank secret (local upstream) omits Authorization");
        assertEquals(SystemOneCodec.create().encodeRequest(request), capturedBody.get());
        DecisionAnswer.NoulAnswer answer = assertInstanceOf(
                DecisionAnswer.NoulAnswer.class, response.answers().get("refund"));
        assertEquals(0.97, answer.noul());
        assertEquals(42, response.usage().promptTokens());
        // meta passes through untouched (gateway-internal)
        assertEquals("req-1", response.meta().get("request_id"));
    }

    @Test
    void systemOneFullEndpointAndCloudflareRunPathsAreVerbatim() {
        serve(200, SYSTEMONE_RESPONSE);
        SystemOneAdapter verbatim = new SystemOneAdapter("ollama", baseUri + "/v1/systemone", "k");
        verbatim.decide(request("d3"));
        assertEquals("/v1/systemone", capturedPath.get());
        assertEquals("Bearer k", capturedAuth.get());

        SystemOneAdapter cloudflare = new SystemOneAdapter(
                "cloudflare", baseUri + "/client/v4/accounts/acct/ai/run/@cf/cloudflare/clef", "k");
        cloudflare.decide(request("clef"));
        assertEquals("/client/v4/accounts/acct/ai/run/@cf/cloudflare/clef", capturedPath.get());
    }

    @Test
    void openAiDecisionsPostsTranslatedBodyToDecisionsPath() {
        serve(200, OPENAI_RESPONSE);
        OpenAiDecisionsAdapter adapter = new OpenAiDecisionsAdapter(baseUri.toString(), "test-key");
        DecisionRequest request = request("gpt-6-luna");

        DecisionResponse response = adapter.decide(request);

        assertEquals("/v1/decisions", capturedPath.get());
        assertEquals("Bearer test-key", capturedAuth.get());
        assertEquals(OpenAiDecisionsCodec.create().encodeRequest(request), capturedBody.get());
        // A bare string state travels as OpenAI's string input, questions as a named array.
        assertTrue(capturedBody.get().contains("\"input\":\"I was charged twice.\""), capturedBody.get());
        assertTrue(capturedBody.get().contains("\"type\":\"predicate\""), capturedBody.get());
        DecisionAnswer.NoulAnswer answer = assertInstanceOf(
                DecisionAnswer.NoulAnswer.class, response.answers().get("refund"));
        assertEquals(0.91, answer.noul());
    }

    @Test
    void errorStatusesMapWithEnvelopeAndPlainStringBodies() {
        SystemOneAdapter adapter = new SystemOneAdapter("ollama-local", baseUri.toString(), "");

        serve(400, "{\"error\":\"model \\\"qwen\\\" is not a decision model\"}");
        ProviderException badModel = assertThrows(ProviderException.class, () -> adapter.decide(request("qwen")));
        assertEquals(ProviderException.TYPE_UPSTREAM_4XX, badModel.type());
        assertEquals(400, badModel.statusCode());

        serve(429, "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
        nextRetryAfter.set("17");
        ProviderException limited = assertThrows(ProviderException.class, () -> adapter.decide(request("d3")));
        assertEquals(ProviderException.TYPE_RATE_LIMITED, limited.type());
        assertEquals(17L, limited.retryAfterSeconds(), "upstream Retry-After threads through to the 429 envelope");
        assertTrue(limited.retryable());
        nextRetryAfter.set(null);

        serve(401, "{\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}");
        ProviderException auth = assertThrows(ProviderException.class, () -> adapter.decide(request("d3")));
        assertEquals(ProviderException.TYPE_AUTH, auth.type());

        serve(503, "upstream exploded");
        ProviderException five = assertThrows(ProviderException.class, () -> adapter.decide(request("d3")));
        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, five.type());
    }

    @Test
    void headerAndBodyStallsMapToTimeout() {
        SystemOneAdapter adapter = new SystemOneAdapter(
                "slow", baseUri.toString(), "", Duration.ofMillis(100), Duration.ofMillis(150), Duration.ofMillis(150));

        serve(200, SYSTEMONE_RESPONSE);
        nextHeaderDelayMillis.set(1_000L);
        ProviderException header = assertThrows(ProviderException.class, () -> adapter.decide(request("d3")));
        assertEquals(ProviderException.TYPE_TIMEOUT, header.type());

        serve(200, SYSTEMONE_RESPONSE);
        nextBodyDelayMillis.set(1_000L);
        ProviderException body = assertThrows(ProviderException.class, () -> adapter.decide(request("d3")));
        assertEquals(ProviderException.TYPE_TIMEOUT, body.type());
    }

    @Test
    void incompleteOrExtraAnswerSetsAreBadUpstreamPayload() {
        // The request asks for `refund`; an upstream that drops it (or invents a
        // key) must be a typed 502, never a 200 with a missing decision.
        serve(200, "{\"model\":\"tev1:0.8b\",\"answers\":{},\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}");
        SystemOneAdapter adapter = new SystemOneAdapter("ollama-local", baseUri.toString(), "");
        ProviderException missing = assertThrows(ProviderException.class, () -> adapter.decide(request("d3")));
        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, missing.type());

        serve(
                200,
                "{\"model\":\"tev1:0.8b\",\"answers\":{\"refund\":{\"type\":\"noul\",\"noul\":0.9},"
                        + "\"invented\":{\"type\":\"noul\",\"noul\":0.1}},\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}");
        ProviderException extra = assertThrows(ProviderException.class, () -> adapter.decide(request("d3")));
        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, extra.type());
    }

    @Test
    void typeSafeDangerZoneImagesHeaderIsDerivedFromAssetsAndBoxQuestions() {
        serve(
                200,
                "{\"model\":\"jev-latest\",\"answers\":{\"locate\":{\"type\":\"box\","
                        + "\"box\":{\"top\":0.1,\"bottom\":0.2,\"left\":0.3,\"right\":0.4},\"confidence\":0.7}},"
                        + "\"usage\":{\"input_tokens\":42,\"output_tokens\":0}}");
        SystemOneAdapter adapter = new SystemOneAdapter("typesafe", baseUri.toString(), "k");
        Map<String, Object> assets =
                Map.of("p1", Map.of("type", "image", "format", "png", "data_base64", "iVBORw0KGgo="));
        DecisionRequest request = new DecisionRequest(
                "jev-latest",
                Map.of("photo", "p1"),
                null,
                Map.of("locate", new DecisionQuestion.BoxQuestion("Find the mug in `photo`.")),
                Map.of("assets", assets),
                Map.of());

        adapter.decide(request);

        assertEquals("true", capturedDangerImages.get(), "assets/Box must turn on TypeSafe's danger-zone gate");
        assertNull(capturedDangerLongContext.get());
        assertTrue(capturedBody.get().contains("\"assets\":{\"p1\""), capturedBody.get());
        assertTrue(capturedBody.get().contains("\"type\":\"box\""), capturedBody.get());
    }

    @Test
    void typeSafeLongContextHeaderIsForwardedFromTheClientMeta() {
        serve(
                200,
                "{\"model\":\"jev-latest\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.9}},"
                        + "\"usage\":{\"input_tokens\":42,\"output_tokens\":0}}");
        SystemOneAdapter adapter = new SystemOneAdapter("typesafe", baseUri.toString(), "k");
        DecisionRequest request = new DecisionRequest(
                "jev-latest",
                "s",
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of(DecisionRequest.META_DANGER_ZONE_LONG_CONTEXT, true));

        adapter.decide(request);

        assertEquals("true", capturedDangerLongContext.get());
        assertNull(capturedDangerImages.get(), "no image evidence ⇒ images gate stays off");
    }

    @Test
    void localVisionImagesRideTheSystemOneBody() {
        serve(
                200,
                "{\"model\":\"tev1:0.8b\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.9}},"
                        + "\"usage\":{\"input_tokens\":42,\"output_tokens\":0}}");
        SystemOneAdapter adapter = new SystemOneAdapter("ollama-local", baseUri.toString(), "");
        DecisionRequest request = new DecisionRequest(
                "d3",
                "Inspect this photo.",
                java.util.List.of("iVBORw0KGgo="),
                Map.of("q", new DecisionQuestion.NoulQuestion("Is it damaged?", null)),
                Map.of(),
                Map.of());

        adapter.decide(request);

        assertTrue(capturedBody.get().contains("\"images\":[\"iVBORw0KGgo=\"]"), capturedBody.get());
        assertNull(capturedDangerImages.get(), "Ollama images[] is not the TypeSafe assets gate");
    }

    @Test
    void cloudflareEnvelopeIsUnwrappedIntoTheModelResponse() {
        serve(200, "{\"result\":" + SYSTEMONE_RESPONSE + ",\"success\":true,\"errors\":[],\"messages\":[]}");
        SystemOneAdapter cloudflare = new SystemOneAdapter(
                "cloudflare", baseUri + "/client/v4/accounts/acct/ai/run/@cf/cloudflare/clef", "k");
        DecisionResponse response = cloudflare.decide(request("clef"));
        DecisionAnswer.NoulAnswer answer = assertInstanceOf(
                DecisionAnswer.NoulAnswer.class, response.answers().get("refund"));
        assertEquals(0.97, answer.noul());
        assertEquals(42, response.usage().promptTokens());
    }

    @Test
    void cloudflareSuccessFalseIsABadUpstreamPayload() {
        serve(200, "{\"result\":null,\"success\":false,\"errors\":[{\"message\":\"nope\"}],\"messages\":[]}");
        SystemOneAdapter cloudflare = new SystemOneAdapter(
                "cloudflare", baseUri + "/client/v4/accounts/acct/ai/run/@cf/cloudflare/clef", "k");
        ProviderException e = assertThrows(ProviderException.class, () -> cloudflare.decide(request("clef")));
        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
    }

    @Test
    void malformedSuccessBodyIsBadUpstreamPayload() {
        serve(200, "{\"answers\":42}");
        SystemOneAdapter adapter = new SystemOneAdapter("ollama-local", baseUri.toString(), "");
        ProviderException e = assertThrows(ProviderException.class, () -> adapter.decide(request("d3")));
        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
    }

    @Test
    void discoveryInstancesFailFastOnCall() {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> new SystemOneAdapter().decide(request("d3")));
        assertTrue(e.getMessage().contains("no base URL"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> new OpenAiDecisionsAdapter().decide(request("gpt-6-luna")));
        assertThrows(
                IllegalStateException.class, () -> new GeminiDecisionsAdapter().decide(request("gemini-decision-1")));
    }

    @Test
    void emptyAndNullAssetsDoNotTriggerDangerZoneImagesHeader() {
        serve(200, SYSTEMONE_RESPONSE);
        SystemOneAdapter adapter = new SystemOneAdapter("typesafe", baseUri.toString(), "");

        DecisionRequest emptyAssets = new DecisionRequest(
                "d3",
                "Check status.",
                null,
                Map.of("refund", new DecisionQuestion.NoulQuestion("Refund?", null)),
                Map.of("assets", Map.of()),
                Map.of());
        adapter.decide(emptyAssets);
        assertNull(capturedDangerImages.get(), "empty assets {} must not enable danger-zone-images");

        Map<String, Object> nullAssetsExtras = new LinkedHashMap<>();
        nullAssetsExtras.put("assets", null);
        DecisionRequest nullAssets = new DecisionRequest(
                "d3",
                "Check status.",
                null,
                Map.of("refund", new DecisionQuestion.NoulQuestion("Refund?", null)),
                nullAssetsExtras,
                Map.of());
        adapter.decide(nullAssets);
        assertNull(capturedDangerImages.get(), "null assets must not enable danger-zone-images");
    }

    @Test
    void geminiDecisionsPostsTranslatedBodyWithXGoogApiKeyAndOmitsAuthorization() {
        serve(200, GEMINI_RESPONSE);
        GeminiDecisionsAdapter adapter = new GeminiDecisionsAdapter(baseUri.toString(), "goog-secret");
        DecisionRequest request = request("gemini-decision-1");

        DecisionResponse response = adapter.decide(request);

        assertEquals("/v1beta/decisions", capturedPath.get());
        assertEquals("goog-secret", capturedGoogKey.get());
        assertNull(capturedAuth.get(), "gemini-decisions must not send Authorization: Bearer");
        assertEquals(GeminiDecisionsCodec.create().encodeRequest(request), capturedBody.get());
        assertNull(response.model(), "Gemini omits model so backend echoes requested alias");
        DecisionAnswer.NoulAnswer answer = assertInstanceOf(
                DecisionAnswer.NoulAnswer.class, response.answers().get("refund"));
        assertEquals(0.93, answer.noul());
        assertEquals(210, response.usage().promptTokens());
        assertEquals(0, response.usage().completionTokens());
        assertEquals("req-1", response.meta().get("request_id"));

        // Verbatim /decisions path suffix + blank secret omits auth headers
        GeminiDecisionsAdapter verbatim = new GeminiDecisionsAdapter(baseUri + "/v1beta/decisions", "");
        verbatim.decide(request("gemini-decision-1"));
        assertEquals("/v1beta/decisions", capturedPath.get());
        assertNull(capturedGoogKey.get());
        assertNull(capturedAuth.get());
    }

    @Test
    void geminiDecisionsUnsupportedBoxFailsFastAsUnsupportedRequestWithoutHttpCall() {
        GeminiDecisionsAdapter adapter = new GeminiDecisionsAdapter(baseUri.toString(), "goog-secret");
        capturedPath.set(null);
        DecisionRequest boxRequest = new DecisionRequest(
                "gemini-decision-1",
                "Find the widget.",
                java.util.List.of("iVBORw0KGgo="),
                Map.of("bbox", new DecisionQuestion.BoxQuestion("Find it")),
                Map.of(),
                Map.of());
        ProviderException e = assertThrows(ProviderException.class, () -> adapter.decide(boxRequest));
        assertEquals(ProviderException.TYPE_UNSUPPORTED_REQUEST, e.type());
        assertTrue(e.retryable(), "unsupported_request fails over to remaining pool candidates");
        assertNull(capturedPath.get(), "capability rejection must happen locally before issuing HTTP call");
    }

    @Test
    void geminiDecisionsClassifiesGoogleEnvelopesWithoutLeakingErrorBodyText() {
        GeminiDecisionsAdapter adapter = new GeminiDecisionsAdapter(baseUri.toString(), "goog-secret");
        String secretCanary = "SENSITIVE_UPSTREAM_PROMPT_CANARY";

        // 400 Interactions invalid_request & safety
        for (String code : new String[] {"invalid_request", "safety", "content_blocked"}) {
            serve(400, "{\"error\":{\"message\":\"" + secretCanary + "\",\"code\":\"" + code + "\"}}");
            ProviderException err =
                    assertThrows(ProviderException.class, () -> adapter.decide(request("gemini-decision-1")), code);
            assertEquals(ProviderException.TYPE_UPSTREAM_4XX, err.type());
            assertEquals(400, err.statusCode());
            assertFalse(err.retryable());
            assertFalse(err.getMessage().contains(secretCanary), "must never leak upstream body text");
        }

        // 401 RPC UNAUTHENTICATED
        serve(401, "{\"error\":{\"code\":401,\"message\":\"" + secretCanary + "\",\"status\":\"UNAUTHENTICATED\"}}");
        ProviderException unauth =
                assertThrows(ProviderException.class, () -> adapter.decide(request("gemini-decision-1")));
        assertEquals(ProviderException.TYPE_AUTH, unauth.type());
        assertEquals(401, unauth.statusCode());
        assertFalse(unauth.retryable());
        assertFalse(unauth.getMessage().contains(secretCanary));

        // 403 RPC PERMISSION_DENIED
        serve(403, "{\"error\":{\"code\":403,\"message\":\"" + secretCanary + "\",\"status\":\"PERMISSION_DENIED\"}}");
        ProviderException forbidden =
                assertThrows(ProviderException.class, () -> adapter.decide(request("gemini-decision-1")));
        assertEquals(ProviderException.TYPE_AUTH, forbidden.type());
        assertEquals(403, forbidden.statusCode());
        assertFalse(forbidden.retryable());
        assertFalse(forbidden.getMessage().contains(secretCanary));

        // 404 Interactions not_found
        serve(404, "{\"error\":{\"message\":\"" + secretCanary + "\",\"code\":\"not_found\"}}");
        ProviderException notFound =
                assertThrows(ProviderException.class, () -> adapter.decide(request("gemini-decision-1")));
        assertEquals(ProviderException.TYPE_UPSTREAM_4XX, notFound.type());
        assertEquals(404, notFound.statusCode());
        assertFalse(notFound.retryable());
        assertFalse(notFound.getMessage().contains(secretCanary));

        // 429 RPC RESOURCE_EXHAUSTED with Retry-After
        nextRetryAfter.set("11");
        serve(429, "{\"error\":{\"code\":429,\"message\":\"" + secretCanary + "\",\"status\":\"RESOURCE_EXHAUSTED\"}}");
        ProviderException rateLimited =
                assertThrows(ProviderException.class, () -> adapter.decide(request("gemini-decision-1")));
        assertEquals(ProviderException.TYPE_RATE_LIMITED, rateLimited.type());
        assertEquals(11L, rateLimited.retryAfterSeconds());
        assertTrue(rateLimited.retryable());
        assertFalse(rateLimited.getMessage().contains(secretCanary));
        nextRetryAfter.set(null);

        // 503 RPC UNAVAILABLE (retryable)
        serve(503, "{\"error\":{\"code\":503,\"message\":\"" + secretCanary + "\",\"status\":\"UNAVAILABLE\"}}");
        ProviderException unavailable =
                assertThrows(ProviderException.class, () -> adapter.decide(request("gemini-decision-1")));
        assertEquals(ProviderException.TYPE_UPSTREAM_5XX, unavailable.type());
        assertTrue(unavailable.retryable());
        assertFalse(unavailable.getMessage().contains(secretCanary));
    }

    @Test
    void interruptedReadErrorBodyOnHttp503ReturnsNonRetryableAndPreservesInterruptFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stallEntered.set(entered);
        stallRelease.set(release);
        serve(503, "");

        GeminiDecisionsAdapter adapter = new GeminiDecisionsAdapter(
                baseUri.toString(), "goog-secret", Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(5));
        Thread caller = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                if (entered.await(2, TimeUnit.SECONDS)) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (System.nanoTime() < deadline) {
                        for (StackTraceElement frame : caller.getStackTrace()) {
                            if ("readErrorBody".equals(frame.getMethodName())) {
                                caller.interrupt();
                                return;
                            }
                        }
                        Thread.onSpinWait();
                    }
                    caller.interrupt();
                }
            } catch (InterruptedException ignored) {
                // test cleanup
            }
        });
        interrupter.start();
        try {
            ProviderException thrown =
                    assertThrows(ProviderException.class, () -> adapter.decide(request("gemini-decision-1")));
            assertFalse(thrown.retryable(), "interrupted HTTP 503 error-body read must not return retryable=true");
            assertEquals(ProviderException.TYPE_UPSTREAM_4XX, thrown.type());
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must remain set");
        } finally {
            Thread.interrupted();
            release.countDown();
            interrupter.join(2000);
        }
    }

    private void serve(int status, String body) {
        nextStatus.set(status);
        nextBody.set(body);
        nextHeaderDelayMillis.set(0L);
        nextBodyDelayMillis.set(0L);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static DecisionRequest request(String model) {
        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
        questions.put("refund", new DecisionQuestion.NoulQuestion("Is the customer asking for money back?", null));
        return new DecisionRequest(
                model, "I was charged twice.", null, questions, Map.of(), Map.of("request_id", "req-1"));
    }

    @Test
    void adapterDeadlinesMatchTheDocumentedTimeoutsTableAndThreadThroughTheConstructor() {
        // The decision-plane half of the timeouts table in docs/architecture.md —
        // the gateway-side TimeoutContractTest cannot see these (package-private
        // constants, by module design), so the drift guard lives here: connect 10 s,
        // header-arrival 60 s, body-read 300 s.
        assertEquals(Duration.ofSeconds(10), AbstractDecisionAdapter.CONNECT_TIMEOUT);
        assertEquals(Duration.ofSeconds(60), AbstractDecisionAdapter.HEADER_ARRIVAL_TIMEOUT);
        assertEquals(Duration.ofSeconds(300), AbstractDecisionAdapter.DEFAULT_BODY_READ_TIMEOUT);

        SystemOneAdapter threaded = new SystemOneAdapter(
                "n", "http://127.0.0.1:1", "k", Duration.ofSeconds(3), Duration.ofSeconds(7), Duration.ofSeconds(9));
        assertEquals(Duration.ofSeconds(3), threaded.connectTimeout());
        assertEquals(Duration.ofSeconds(7), threaded.headerTimeout());
        assertEquals(Duration.ofSeconds(9), threaded.bodyReadTimeout());

        OpenAiDecisionsAdapter openAi = new OpenAiDecisionsAdapter(
                "http://127.0.0.1:1", "k", Duration.ofSeconds(3), Duration.ofSeconds(7), Duration.ofSeconds(9));
        assertEquals(Duration.ofSeconds(3), openAi.connectTimeout());
        assertEquals(Duration.ofSeconds(7), openAi.headerTimeout());
        assertEquals(Duration.ofSeconds(9), openAi.bodyReadTimeout());

        GeminiDecisionsAdapter gemini = new GeminiDecisionsAdapter(
                "http://127.0.0.1:1", "k", Duration.ofSeconds(3), Duration.ofSeconds(7), Duration.ofSeconds(9));
        assertEquals(Duration.ofSeconds(3), gemini.connectTimeout());
        assertEquals(Duration.ofSeconds(7), gemini.headerTimeout());
        assertEquals(Duration.ofSeconds(9), gemini.bodyReadTimeout());

        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemOneAdapter(
                        "n", "http://127.0.0.1:1", "k", Duration.ZERO, Duration.ofSeconds(7), Duration.ofSeconds(9)));
    }
}
