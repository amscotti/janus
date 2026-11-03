package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.airlift.compress.zstd.ZstdOutputStream;
import io.amscotti.janus.core.codec.SystemOneCodec;
import io.amscotti.janus.core.model.DecisionAnswer;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.provider.ProviderException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.multipart.MultipartBody;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
}
