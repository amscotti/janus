package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.airlift.compress.zstd.ZstdOutputStream;
import io.amscotti.janus.core.codec.DecisionCodecException;
import io.amscotti.janus.core.codec.SystemOneCodec;
import io.amscotti.janus.core.model.DecisionAnswer;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.router.DecisionBackend;
import io.amscotti.janus.router.DecisionRouter;
import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.CallStatus;
import io.amscotti.janus.store.FixedWindowRateLimiter;
import io.amscotti.janus.store.InMemoryCallStore;
import io.amscotti.janus.store.InMemorySpendLedger;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.multipart.MultipartBody;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /v1/systemone} face integration: a Jev-shaped request returns
 * the codec-byte-equal Jev-shaped response by model id, with OpenAI-envelope
 * errors for malformed requests (400), unknown decision models (404
 * {@code model_not_found}), and upstream failures (502). The fake decision
 * backends are wired by {@link TestRouterFactory}.
 */
@MicronautTest
class SystemOneControllerTest {

    private static final SystemOneCodec CODEC = SystemOneCodec.create();

    private static final String REQUEST = """
            {"model":"jev-latest","state":"I was charged twice.","questions":{
              "refund":{"type":"noul","instructions":"Is the customer asking for money back?"},
              "team":{"type":"choice","instructions":"Which team?","criteria":{"billing":"Charges","technical":"Bugs"}}}}
            """;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    EmbeddedServer server;

    @Inject
    SystemOneController injectedController;

    @BeforeEach
    void resetSharedBackend() {
        TestRouterFactory.DECISION_BACKEND.reset();
        TestRouterFactory.DECISION_VISION_BACKEND.reset();
    }

    @Test
    void jevShapedRequestReturnsCodecByteEqualResponse() {
        DecisionResponse response = new DecisionResponse(
                "jev-latest",
                Map.of(
                        "refund", new DecisionAnswer.NoulAnswer(0.97),
                        "team",
                                new DecisionAnswer.ChoiceAnswer(
                                        "billing", Map.of("billing", 0.9, "technical", 0.1), 0.8)),
                new Usage(42, 0, 42),
                Map.of(),
                Map.of());
        TestRouterFactory.DECISION_BACKEND.decideReturns(response);

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", REQUEST).contentType(MediaType.APPLICATION_JSON),
                        String.class);

        assertEquals(HttpStatus.OK, http.getStatus());
        assertEquals(CODEC.encodeResponse(response), http.body());
        assertEquals(
                "jev-latest", TestRouterFactory.DECISION_BACKEND.calls.get(0).model());
    }

    @Test
    void modelIdSelectsTheBackend() {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "d3", Map.of("refund", new DecisionAnswer.NoulAnswer(0.5)), new Usage(10, 0, 10), Map.of(), Map.of()));
        String body = REQUEST.replace("jev-latest", "d3");

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", body).contentType(MediaType.APPLICATION_JSON), String.class);

        assertEquals(HttpStatus.OK, http.getStatus());
        // Route selection is proven by the dispatched request's model, not the
        // canned response echo (which would pass even if the wrong alias served).
        assertEquals("d3", TestRouterFactory.DECISION_BACKEND.calls.get(0).model());
        assertEquals(1, TestRouterFactory.DECISION_BACKEND.calls.size());
        assertTrue(http.body().contains("\"model\":\"d3\""), http.body());
    }

    @Test
    void dangerZoneHeadersRideIntoTheCanonicalRequestMeta() {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.5)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("TypeSafe-Danger-Zone-Images", "true")
                                .header("TypeSafe-Danger-Zone-Long-Context", "true"),
                        String.class);

        assertEquals(HttpStatus.OK, http.getStatus());
        DecisionRequest seen = TestRouterFactory.DECISION_BACKEND.calls.get(0);
        assertEquals(Boolean.TRUE, seen.meta().get(DecisionRequest.META_DANGER_ZONE_IMAGES));
        assertEquals(Boolean.TRUE, seen.meta().get(DecisionRequest.META_DANGER_ZONE_LONG_CONTEXT));
    }

    @Test
    void visionAliasForAssetsOnlyRequestIsSelectedByImageEvidence() {
        TestRouterFactory.DECISION_VISION_BACKEND.decideReturns(new DecisionResponse(
                "mixed-vision",
                Map.of("locate", new DecisionAnswer.BoxAnswer(0.1, 0.9, 0.2, 0.8, 0.7)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));
        String body = "{\"model\":\"mixed-vision\",\"state\":{\"photo\":\"p1\"},"
                + "\"assets\":{\"p1\":{\"type\":\"image\",\"format\":\"png\",\"data_base64\":\"iVBORw0KGgo=\"}},"
                + "\"questions\":{\"locate\":{\"type\":\"box\",\"instructions\":\"Find the mug.\"}}}";

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", body).contentType(MediaType.APPLICATION_JSON), String.class);

        assertEquals(HttpStatus.OK, http.getStatus());
        assertEquals(1, TestRouterFactory.DECISION_VISION_BACKEND.calls.size());
        assertEquals(0, TestRouterFactory.DECISION_BACKEND.calls.size(), "text-only backend must not receive assets");
    }

    @Test
    void gzipCompressedRequestBodiesAreAccepted() throws Exception {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.5)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", gzip(REQUEST))
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Content-Encoding", "gzip"),
                        String.class);

        assertEquals(HttpStatus.OK, http.getStatus(), http.body());
        assertEquals(1, TestRouterFactory.DECISION_BACKEND.calls.size());
    }

    @Test
    void invalidUtf8BodyIs400NotAReplacementCharacter() {
        HttpResponse<?> bad = errorResponse(HttpRequest.POST("/v1/systemone", new byte[] {(byte) 0xC3, 0x28})
                .contentType(MediaType.APPLICATION_JSON));

        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatus());
        assertTrue(bad.getBody(String.class).orElse("").contains("not valid UTF-8"));
        assertEquals(0, TestRouterFactory.DECISION_BACKEND.calls.size());
    }

    @Test
    void decompressedBodyOver32MiBIs413() throws Exception {
        byte[] huge = new byte[32 * 1024 * 1024 + 1];
        Arrays.fill(huge, (byte) 'a');

        HttpResponse<?> tooBig = errorResponse(HttpRequest.POST("/v1/systemone", zstd(huge))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Encoding", "zstd"));

        assertEquals(
                HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                tooBig.getStatus(),
                tooBig.getBody(String.class).orElse(""));
        assertTrue(tooBig.getBody(String.class).orElse("").contains("exceeds 32 MiB"));
        assertEquals(0, TestRouterFactory.DECISION_BACKEND.calls.size());
    }

    @Test
    void zstdCompressedRequestBodiesAreDecompressedBeforeDecode() throws Exception {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.5)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", zstd(REQUEST))
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Content-Encoding", "zstd"),
                        String.class);

        assertEquals(HttpStatus.OK, http.getStatus());
        assertEquals(1, TestRouterFactory.DECISION_BACKEND.calls.size());
        assertEquals(
                "jev-latest", TestRouterFactory.DECISION_BACKEND.calls.get(0).model());
    }

    @Test
    void corruptZstdBodyIs400NotAFrameworkError() {
        HttpResponse<?> bad = errorResponse(HttpRequest.POST("/v1/systemone", new byte[] {1, 2, 3})
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Encoding", "zstd"));

        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatus());
        assertTrue(bad.getBody(String.class).orElse("").contains("invalid zstd request body"));
    }

    @Test
    void unsupportedContentTypeIs415WithAnActionableEnvelope() {
        HttpResponse<?> rejected =
                errorResponse(HttpRequest.POST("/v1/systemone", "{}").contentType(MediaType.TEXT_PLAIN));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, rejected.getStatus());
        String body = rejected.getBody(String.class).orElse("");
        assertTrue(body.contains("\"type\":\"invalid_request_error\""), body);
        assertTrue(body.contains("Content-Type"), body);
    }

    @Test
    void unsupportedRequestEncodingIs415WithAnActionableEnvelope() {
        HttpResponse<?> rejected = errorResponse(HttpRequest.POST("/v1/systemone", REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Encoding", "br"));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, rejected.getStatus());
        String body = rejected.getBody(String.class).orElse("");
        assertTrue(body.contains("\"type\":\"invalid_request_error\""), body);
        assertTrue(body.contains("Content-Encoding"), body);
    }

    @Test
    void multipartTypeSafeImagePartIsInlinedIntoTheAsset() throws Exception {
        TestRouterFactory.DECISION_VISION_BACKEND.decideReturns(new DecisionResponse(
                "mixed-vision",
                Map.of("locate", new DecisionAnswer.BoxAnswer(0.1, 0.2, 0.3, 0.4, 0.7)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));
        String requestJson = "{\"model\":\"mixed-vision\",\"state\":{\"photo\":\"p1\"},"
                + "\"assets\":{\"p1\":{\"type\":\"image\",\"part\":\"photo\"}},"
                + "\"questions\":{\"locate\":{\"type\":\"box\",\"instructions\":\"Find the mug.\"}}}";
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        MultipartBody multipart = MultipartBody.builder()
                .addPart("request", requestJson)
                .addPart("photo", "photo.png", MediaType.IMAGE_PNG_TYPE, png)
                .build();

        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/systemone", multipart).contentType(MediaType.MULTIPART_FORM_DATA));

        assertEquals(HttpStatus.OK, http.getStatus(), http.body());
        DecisionRequest seen = TestRouterFactory.DECISION_VISION_BACKEND.calls.get(0);
        Map<?, ?> assets = (Map<?, ?>) seen.extras().get("assets");
        Map<?, ?> asset = (Map<?, ?>) assets.get("p1");
        assertEquals(Base64.getEncoder().encodeToString(png), asset.get("data_base64"));
        assertFalse(asset.containsKey("part"), "the file part is inlined, never forwarded");
        assertEquals("png", asset.get("format"));
        assertEquals(0, TestRouterFactory.DECISION_BACKEND.calls.size(), "text-only backend must not serve images");
    }

    @Test
    void zstdCompressedMultipartBodyIsDecompressedAndParsed() throws Exception {
        TestRouterFactory.DECISION_VISION_BACKEND.decideReturns(new DecisionResponse(
                "mixed-vision",
                Map.of("locate", new DecisionAnswer.BoxAnswer(0.1, 0.2, 0.3, 0.4, 0.7)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));
        String requestJson = "{\"model\":\"mixed-vision\",\"state\":{\"photo\":\"p1\"},"
                + "\"assets\":{\"p1\":{\"type\":\"image\",\"part\":\"asset-0\"}},"
                + "\"questions\":{\"locate\":{\"type\":\"box\",\"instructions\":\"Find the mug.\"}}}";
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        String boundary = "janus-zstd-multipart";
        byte[] compressed = zstd(multipartBody(boundary, requestJson, png));

        java.net.http.HttpClient raw = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpResponse<String> http = raw.send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("http://127.0.0.1:" + server.getPort() + "/v1/systemone"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("Content-Encoding", "zstd")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(compressed))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertEquals(200, http.statusCode(), http.body());
        DecisionRequest seen = TestRouterFactory.DECISION_VISION_BACKEND.calls.get(0);
        Map<?, ?> asset = (Map<?, ?>) ((Map<?, ?>) seen.extras().get("assets")).get("p1");
        assertEquals(Base64.getEncoder().encodeToString(png), asset.get("data_base64"));
        assertEquals("png", asset.get("format"));
    }

    @Test
    void multipartMissingRequestOrNonFilePartOrDuplicateRequestIs400() {
        String boundary = "janus-multipart-negative";
        String model = "\"model\":\"mixed-vision\",\"state\":\"x\",\"questions\":{\"q\":{\"type\":\"noul\"}}";
        String requestPart =
                "Content-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n{" + model
                        + "}";
        String filePart = "Content-Disposition: form-data; name=\"asset-0\"; filename=\"asset-0\"\r\n"
                + "Content-Type: image/png\r\n\r\nnot-an-image";
        String textPart = "Content-Disposition: form-data; name=\"note\"\r\n\r\nhello";

        java.net.http.HttpResponse<String> missingRequest = multipartPost(boundary, filePart);
        assertEquals(400, missingRequest.statusCode(), missingRequest.body());
        assertTrue(missingRequest.body().contains("must carry the JSON `request` part"));

        java.net.http.HttpResponse<String> nonFile = multipartPost(boundary, requestPart, textPart);
        assertEquals(400, nonFile.statusCode(), nonFile.body());
        assertTrue(nonFile.body().contains("must be a file part"));

        java.net.http.HttpResponse<String> duplicateRequest = multipartPost(boundary, requestPart, requestPart);
        assertEquals(400, duplicateRequest.statusCode(), duplicateRequest.body());
        assertTrue(duplicateRequest.body().contains("exactly one `request` part"));
        assertEquals(0, TestRouterFactory.DECISION_VISION_BACKEND.calls.size(), "all rejected before dispatch");
    }

    @Test
    void multipartMissingFilePartIs400BeforeDispatch() throws Exception {
        String requestJson = "{\"model\":\"mixed-vision\",\"state\":\"x\","
                + "\"assets\":{\"p1\":{\"type\":\"image\",\"part\":\"photo\"}},"
                + "\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}";
        MultipartBody multipart =
                MultipartBody.builder().addPart("request", requestJson).build();

        HttpResponse<?> bad =
                errorResponse(HttpRequest.POST("/v1/systemone", multipart).contentType(MediaType.MULTIPART_FORM_DATA));

        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatus());
        assertTrue(bad.getBody(String.class).orElse("").contains("missing multipart part"));
        assertEquals(0, TestRouterFactory.DECISION_VISION_BACKEND.calls.size());
    }

    private static byte[] zstd(String value) throws IOException {
        return zstd(value.getBytes(StandardCharsets.UTF_8));
    }

    private java.net.http.HttpResponse<String> multipartPost(String boundary, String... parts) {
        StringBuilder body = new StringBuilder();
        for (String part : parts) {
            body.append("--").append(boundary).append("\r\n").append(part).append("\r\n");
        }
        body.append("--").append(boundary).append("--\r\n");
        try {
            return java.net.http.HttpClient.newHttpClient()
                    .send(
                            java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                            "http://127.0.0.1:" + server.getPort() + "/v1/systemone"))
                                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(
                                            body.toString().getBytes(StandardCharsets.ISO_8859_1)))
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] gzip(String value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static byte[] zstd(byte[] value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(out)) {
            zstd.write(value);
        }
        return out.toByteArray();
    }

    private static byte[] multipartBody(String boundary, String requestJson, byte[] png) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"request\"\r\n"
                        + "Content-Type: application/json\r\n\r\n"
                        + requestJson + "\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"asset-0\"; filename=\"asset-0\"\r\n"
                        + "Content-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(png);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    @Test
    void malformedRequestIs400OpenAiEnvelope() {
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/systemone", "{\"model\":\"d3\",\"questions\":{}}")
                        .contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
    }

    @Test
    void unknownDecisionModelIs404ModelNotFound() {
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/systemone", REQUEST.replace("jev-latest", "no-such-model"))
                        .contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.NOT_FOUND, http.getStatus());
        assertTrue(http.body().contains("\"code\":\"model_not_found\""), http.body());
    }

    @Test
    void upstreamFailureIs502() {
        TestRouterFactory.DECISION_BACKEND.decideFails(
                new ProviderException(ProviderException.TYPE_UPSTREAM_5XX, "boom", 503, null));
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/systemone", REQUEST).contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.BAD_GATEWAY, http.getStatus());
    }

    @Test
    void jsonNullBodyIs400NeverA500() {
        // The chat faces pin this (MalformedBodyAccountingTest): a JSON-literal null
        // or empty body must be a typed 400, never an NPE-driven 5xx bucket.
        for (String bad : new String[] {"null", ""}) {
            HttpResponse<String> http =
                    errorResponse(HttpRequest.POST("/v1/systemone", bad).contentType(MediaType.APPLICATION_JSON));
            assertEquals(HttpStatus.BAD_REQUEST, http.getStatus(), bad);
            assertTrue(http.body().contains("invalid_request_error"), http.body());
        }
    }

    @Test
    void imageRequestToATextOnlyModelIs400BeforeDispatch() {
        // The router-level capability filter fails closed and deterministically —
        // no dispatch, and no backend identity in the client-facing message.
        String body = REQUEST.replace(
                "\"state\":\"I was charged twice.\"", "\"state\":\"I was charged twice.\",\"images\":[\"aGVsbG8=\"]");
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/systemone", body).contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("vision = true"), http.body());
        assertFalse(
                http.body().contains("fake-decision"), "no operator backend names in client errors: " + http.body());
        assertTrue(TestRouterFactory.DECISION_BACKEND.calls.isEmpty(), "fail-closed before any dispatch");
    }

    @Test
    void imageRequestToAVisionModelRoutesAndSucceeds() {
        TestRouterFactory.DECISION_VISION_BACKEND.decideReturns(new DecisionResponse(
                "vision-model",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.9)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));
        String body = REQUEST.replace("jev-latest", "vision-model")
                .replace(
                        "\"state\":\"I was charged twice.\"",
                        "\"state\":\"I was charged twice.\",\"images\":[\"aGVsbG8=\"]");

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", body).contentType(MediaType.APPLICATION_JSON), String.class);

        assertEquals(HttpStatus.OK, http.getStatus());
        assertEquals(1, TestRouterFactory.DECISION_VISION_BACKEND.calls.size());
        assertEquals(
                List.of("aGVsbG8="),
                TestRouterFactory.DECISION_VISION_BACKEND.calls.get(0).images());
    }

    private HttpResponse<String> errorResponse(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = (HttpResponse<String>) e.getResponse();
            return response;
        }
    }

    @Test
    void gzipCompressedMultipartAndExplicitIdentityEncodingWorkOverHttp() throws Exception {
        TestRouterFactory.DECISION_VISION_BACKEND.decideReturns(new DecisionResponse(
                "mixed-vision",
                Map.of("locate", new DecisionAnswer.BoxAnswer(0.1, 0.2, 0.3, 0.4, 0.7)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));
        String requestJson = "{\"model\":\"mixed-vision\",\"state\":{\"photo\":\"p1\"},"
                + "\"assets\":{\"p1\":{\"type\":\"image\",\"part\":\"asset-0\"}},"
                + "\"questions\":{\"locate\":{\"type\":\"box\",\"instructions\":\"Find the mug.\"}}}";
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        String boundary = "janus-gzip-multipart";
        byte[] compressed = gzipBytes(multipartBody(boundary, requestJson, png));

        java.net.http.HttpClient raw = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpResponse<String> mpResp = raw.send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("http://127.0.0.1:" + server.getPort() + "/v1/systemone"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("Content-Encoding", "gzip")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(compressed))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertEquals(200, mpResp.statusCode(), mpResp.body());
        DecisionRequest seenMp = TestRouterFactory.DECISION_VISION_BACKEND.calls.get(0);
        Map<?, ?> asset = (Map<?, ?>) ((Map<?, ?>) seenMp.extras().get("assets")).get("p1");
        assertEquals(Base64.getEncoder().encodeToString(png), asset.get("data_base64"));

        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.8)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));
        HttpResponse<String> identityResp = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Content-Encoding", "identity"),
                        String.class);
        assertEquals(HttpStatus.OK, identityResp.getStatus());
        assertEquals(1, TestRouterFactory.DECISION_BACKEND.calls.size());
    }

    @Test
    void commaSeparatedAndStackedContentEncodingsAre415OverHttp() {
        for (String encoding : List.of("identity, zstd", "zstd, gzip")) {
            HttpResponse<String> rejected = errorResponse(HttpRequest.POST("/v1/systemone", REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Content-Encoding", encoding));
            assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, rejected.getStatus(), encoding + ": " + rejected.body());
            assertTrue(rejected.body().contains("\"type\":\"invalid_request_error\""), rejected.body());
        }
    }

    @Test
    void dangerZoneFalseAndSingleFlagCombinations() {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.5)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));

        // Both explicitly false -> no danger-zone meta keys added
        client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("TypeSafe-Danger-Zone-Images", "false")
                                .header("TypeSafe-Danger-Zone-Long-Context", "false"),
                        String.class);
        assertNull(TestRouterFactory.DECISION_BACKEND.calls.get(0).meta().get(DecisionRequest.META_DANGER_ZONE_IMAGES));
        assertNull(TestRouterFactory.DECISION_BACKEND
                .calls
                .get(0)
                .meta()
                .get(DecisionRequest.META_DANGER_ZONE_LONG_CONTEXT));

        // Images true only
        client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("TypeSafe-Danger-Zone-Images", "true"),
                        String.class);
        assertEquals(
                Boolean.TRUE,
                TestRouterFactory.DECISION_BACKEND.calls.get(1).meta().get(DecisionRequest.META_DANGER_ZONE_IMAGES));
        assertNull(TestRouterFactory.DECISION_BACKEND
                .calls
                .get(1)
                .meta()
                .get(DecisionRequest.META_DANGER_ZONE_LONG_CONTEXT));

        // Images false + Long-Context true
        client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("TypeSafe-Danger-Zone-Images", "false")
                                .header("TypeSafe-Danger-Zone-Long-Context", "true"),
                        String.class);
        assertNull(TestRouterFactory.DECISION_BACKEND.calls.get(2).meta().get(DecisionRequest.META_DANGER_ZONE_IMAGES));
        assertEquals(
                Boolean.TRUE,
                TestRouterFactory.DECISION_BACKEND
                        .calls
                        .get(2)
                        .meta()
                        .get(DecisionRequest.META_DANGER_ZONE_LONG_CONTEXT));
    }

    @Test
    void directControllerGzipJsonMultipartBombCorruptAndNonMagicFallback() throws Exception {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.6)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));
        TestRouterFactory.DECISION_VISION_BACKEND.decideReturns(new DecisionResponse(
                "mixed-vision",
                Map.of("locate", new DecisionAnswer.BoxAnswer(0.1, 0.2, 0.3, 0.4, 0.7)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));

        // 1. Direct gzip JSON
        HttpResponse<String> gzipJson = injectedController.systemOne(
                new ByteArrayInputStream(gzip(REQUEST)),
                HttpRequest.POST("/v1/systemone", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Encoding", "gzip"));
        assertEquals(HttpStatus.OK, gzipJson.getStatus());

        // 2. Direct gzip multipart
        String requestJson = "{\"model\":\"mixed-vision\",\"state\":{\"photo\":\"p1\"},"
                + "\"assets\":{\"p1\":{\"type\":\"image\",\"part\":\"asset-0\"}},"
                + "\"questions\":{\"locate\":{\"type\":\"box\",\"instructions\":\"Find the mug.\"}}}";
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        String boundary = "direct-gzip-mp";
        HttpResponse<String> gzipMp = injectedController.systemOne(
                new ByteArrayInputStream(gzipBytes(multipartBody(boundary, requestJson, png))),
                HttpRequest.POST("/v1/systemone", "")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("Content-Encoding", "gzip"));
        assertEquals(HttpStatus.OK, gzipMp.getStatus());

        // 3. Content-Encoding: gzip when Netty already decompressed (plain JSON bytes, non-gzip magic)
        HttpResponse<String> preDecompressed = injectedController.systemOne(
                new ByteArrayInputStream(REQUEST.getBytes(StandardCharsets.UTF_8)),
                HttpRequest.POST("/v1/systemone", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Encoding", "gzip"));
        assertEquals(HttpStatus.OK, preDecompressed.getStatus());

        // 4. Content-Encoding: gzip with 0x1F + non-0x8B second byte & 1-byte stream
        assertThrows(
                DecisionCodecException.class,
                () -> injectedController.systemOne(
                        new ByteArrayInputStream(new byte[] {0x1F, 'a'}),
                        HttpRequest.POST("/v1/systemone", "")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Content-Encoding", "gzip")));
        assertThrows(
                DecisionCodecException.class,
                () -> injectedController.systemOne(
                        new ByteArrayInputStream(new byte[] {'{'}),
                        HttpRequest.POST("/v1/systemone", "")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Content-Encoding", "gzip")));

        // 5. Corrupt gzip starting with gzip magic -> 400
        DecisionCodecException corrupt = assertThrows(
                DecisionCodecException.class,
                () -> injectedController.systemOne(
                        new ByteArrayInputStream(new byte[] {0x1F, (byte) 0x8B, 1, 2, 3}),
                        HttpRequest.POST("/v1/systemone", "")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Content-Encoding", "gzip")));
        assertTrue(corrupt.getMessage().contains("invalid gzip request body"), corrupt.getMessage());

        // 6. Oversized (>32 MiB decompressed) gzip bomb -> 413
        byte[] huge = new byte[SystemOneController.MAX_DECOMPRESSED_BYTES + 1];
        Arrays.fill(huge, (byte) 'a');
        ContentLengthExceededException bomb = assertThrows(
                ContentLengthExceededException.class,
                () -> injectedController.systemOne(
                        new ByteArrayInputStream(gzipBytes(huge)),
                        HttpRequest.POST("/v1/systemone", "")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Content-Encoding", "gzip")));
        assertTrue(bomb.getMessage().contains("exceeds 32 MiB"), bomb.getMessage());

        // 7. Valid gzip of 0 bytes -> readBounded total == 0 -> 400
        assertThrows(
                DecisionCodecException.class,
                () -> injectedController.systemOne(
                        new ByteArrayInputStream(gzipBytes(new byte[0])),
                        HttpRequest.POST("/v1/systemone", "")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Content-Encoding", "gzip")));
    }

    @Test
    void directControllerUnsupportedEncodingEmptyNullStreamsChunkSizesAndMultipartEdges() throws Exception {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.5)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));

        // Stacked/unsupported encodings hitting controller directly -> 415 HttpStatusException
        for (String encoding : List.of("identity, zstd", "zstd, gzip")) {
            HttpStatusException unsupported = assertThrows(
                    HttpStatusException.class,
                    () -> injectedController.systemOne(
                            new ByteArrayInputStream(REQUEST.getBytes(StandardCharsets.UTF_8)),
                            HttpRequest.POST("/v1/systemone", "")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .header("Content-Encoding", encoding)));
            assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, unsupported.getStatus());
        }

        // Null and empty InputStream -> 400
        assertThrows(
                DecisionCodecException.class,
                () -> injectedController.systemOne(
                        null, HttpRequest.POST("/v1/systemone", "").contentType(MediaType.APPLICATION_JSON)));
        assertThrows(
                DecisionCodecException.class,
                () -> injectedController.systemOne(
                        new ByteArrayInputStream(new byte[0]),
                        HttpRequest.POST("/v1/systemone", "").contentType(MediaType.APPLICATION_JSON)));

        // Exact 64 KiB body (offset == 0 at EOF) and > 64 KiB multi-chunk body (chunks.size() > 1)
        String prefix = "{\"model\":\"jev-latest\",\"state\":\"";
        String suffix = "\",\"questions\":{\"refund\":{\"type\":\"noul\",\"instructions\":\"?\"}}}";
        int exactPadLen = 64 * 1024 - prefix.length() - suffix.length();
        String exact64k = prefix + "a".repeat(exactPadLen) + suffix;
        assertEquals(64 * 1024, exact64k.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(
                HttpStatus.OK,
                injectedController
                        .systemOne(
                                new ByteArrayInputStream(exact64k.getBytes(StandardCharsets.UTF_8)),
                                HttpRequest.POST("/v1/systemone", "").contentType(MediaType.APPLICATION_JSON))
                        .getStatus());

        String over64k = prefix + "b".repeat(70 * 1024) + suffix;
        assertEquals(
                HttpStatus.OK,
                injectedController
                        .systemOne(
                                new ByteArrayInputStream(over64k.getBytes(StandardCharsets.UTF_8)),
                                HttpRequest.POST("/v1/systemone", "").contentType(MediaType.APPLICATION_JSON))
                        .getStatus());

        // Multipart with zero parts -> 400
        String boundary = "empty-mp";
        DecisionCodecException noParts = assertThrows(
                DecisionCodecException.class,
                () -> injectedController.systemOne(
                        new ByteArrayInputStream(("--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1)),
                        HttpRequest.POST("/v1/systemone", "")
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)));
        assertTrue(noParts.getMessage().contains("no parts"), noParts.getMessage());

        // Multipart with duplicate file part name -> 400
        String dupFileMp = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"request\"\r\n\r\n"
                + "{\"model\":\"mixed-vision\",\"state\":\"x\",\"questions\":{\"q\":{\"type\":\"noul\"}}}\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"asset-0\"; filename=\"a.png\"\r\n\r\n1\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"asset-0\"; filename=\"b.png\"\r\n\r\n2\r\n"
                + "--" + boundary + "--\r\n";
        DecisionCodecException dupPart = assertThrows(
                DecisionCodecException.class,
                () -> injectedController.systemOne(
                        new ByteArrayInputStream(dupFileMp.getBytes(StandardCharsets.ISO_8859_1)),
                        HttpRequest.POST("/v1/systemone", "")
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)));
        assertTrue(dupPart.getMessage().contains("duplicate multipart file part"), dupPart.getMessage());
    }

    @Test
    void directControllerThrowingInputStreamOnReadAndCloseIs400() {
        InputStream failOnRead = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read boom");
            }
        };
        DecisionCodecException readErr = assertThrows(
                DecisionCodecException.class,
                () -> injectedController.systemOne(
                        failOnRead, HttpRequest.POST("/v1/systemone", "").contentType(MediaType.APPLICATION_JSON)));
        assertTrue(readErr.getMessage().contains("failed to read the request body"), readErr.getMessage());

        InputStream failOnCloseAfterValidBody =
                new FilterInputStream(new ByteArrayInputStream(REQUEST.getBytes(StandardCharsets.UTF_8))) {
                    @Override
                    public void close() throws IOException {
                        throw new IOException("close boom");
                    }
                };
        DecisionCodecException closeErr = assertThrows(
                DecisionCodecException.class,
                () -> injectedController.systemOne(
                        failOnCloseAfterValidBody,
                        HttpRequest.POST("/v1/systemone", "").contentType(MediaType.APPLICATION_JSON)));
        assertTrue(closeErr.getMessage().contains("failed to read the request body"), closeErr.getMessage());

        // Primary ContentLengthExceededException wins if close() also throws IOException
        InputStream failBoth = new InputStream() {
            @Override
            public int read() {
                return 'a';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                Arrays.fill(b, off, off + len, (byte) 'a');
                return len;
            }

            @Override
            public void close() throws IOException {
                throw new IOException("close after oversize");
            }
        };
        ContentLengthExceededException oversize = assertThrows(
                ContentLengthExceededException.class,
                () -> injectedController.systemOne(
                        failBoth, HttpRequest.POST("/v1/systemone", "").contentType(MediaType.APPLICATION_JSON)));
        assertEquals(1, oversize.getSuppressed().length);
    }

    @Test
    void directControllerUnexpectedRouterRuntimeExceptionAndThrowingMetricsRecorder() {
        AtomicInteger recorderCalls = new AtomicInteger();
        MetricsRecorder throwingRecorder = new MetricsRecorder() {
            @Override
            public void recordRequest(String face, int status, long durationMillis, String keyId) {
                recorderCalls.incrementAndGet();
                throw new IllegalStateException("recorder failure");
            }

            @Override
            public void recordUsage(String keyId, Usage usage, long costMicroUsd) {}

            @Override
            public void forgetKey(String keyId) {}
        };
        InMemoryCallStore localCalls = new InMemoryCallStore(TestKeyAuthFactory.CLOCK, 100);
        Governance localGovernance = new Governance(
                new FixedWindowRateLimiter(TestKeyAuthFactory.CLOCK),
                TestGovernanceFactory.PRICES,
                new InMemorySpendLedger(TestKeyAuthFactory.CLOCK, 100),
                TestGovernanceFactory.NOTIFIER,
                0.8,
                TestKeyAuthFactory.CLOCK,
                throwingRecorder,
                localCalls);

        FakeDecisionBackend backend = new FakeDecisionBackend("fake-decision");
        backend.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.9)),
                new Usage(10, 0, 10),
                Map.of(),
                Map.of()));
        SystemOneController controller = new SystemOneController(
                new DecisionRouter(Map.of("jev-latest", List.of(backend))), localGovernance, throwingRecorder);

        // 1. 200 OK with throwing MetricsRecorder still returns 200 and records OK call row
        HttpResponse<String> ok = controller.systemOne(
                new ByteArrayInputStream(REQUEST.getBytes(StandardCharsets.UTF_8)),
                HttpRequest.POST("/v1/systemone", "").contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.OK, ok.getStatus());
        assertEquals(1, recorderCalls.get());
        List<CallRecord> rows = localCalls.recentCalls(100);
        assertEquals(1, rows.size());
        assertEquals(CallStatus.OK, rows.get(0).status());

        // 2. 400 failure with throwing MetricsRecorder rethrows DecisionCodecException and writes ERROR_CLIENT row
        assertThrows(
                DecisionCodecException.class,
                () -> controller.systemOne(
                        new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                        HttpRequest.POST("/v1/systemone", "").contentType(MediaType.APPLICATION_JSON)));
        assertEquals(2, recorderCalls.get());
        rows = localCalls.recentCalls(100);
        assertEquals(2, rows.size());
        assertEquals(CallStatus.ERROR_CLIENT, rows.get(0).status());

        // 3. Unexpected RuntimeException from DecisionRouter with throwing MetricsRecorder
        RuntimeException boom = new IllegalStateException("unexpected router crash");
        DecisionBackend crashingBackend = new DecisionBackend() {
            @Override
            public String name() {
                return "crash";
            }

            @Override
            public String baseUrl() {
                return "http://crash";
            }

            @Override
            public boolean supportsImages() {
                return false;
            }

            @Override
            public DecisionResponse decide(DecisionRequest request) {
                throw boom;
            }
        };
        SystemOneController crashController = new SystemOneController(
                new DecisionRouter(Map.of("jev-latest", List.of(crashingBackend))), localGovernance, throwingRecorder);
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> crashController.systemOne(
                        new ByteArrayInputStream(REQUEST.getBytes(StandardCharsets.UTF_8)),
                        HttpRequest.POST("/v1/systemone", "").contentType(MediaType.APPLICATION_JSON)));
        assertSame(boom, thrown);
        assertEquals(3, recorderCalls.get());
        rows = localCalls.recentCalls(100);
        assertEquals(3, rows.size());
        assertEquals(CallStatus.ERROR_INTERNAL, rows.get(0).status());
    }

    private static byte[] gzipBytes(byte[] value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(value);
        }
        return out.toByteArray();
    }
}
