package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.DecisionAnswer;
import io.amscotti.janus.core.model.DecisionQuestion;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * System One (Jev-shaped) codec replay against committed fixtures — real local
 * Ollama captures (see {@code fixtures/systemone/README.md}), no network.
 */
class SystemOneCodecTest {

    private static final String ROOT = "/fixtures/systemone";

    private final SystemOneCodec codec = new SystemOneCodec(JsonSupport.mapper());

    @Test
    void requestFixtureDecodesAndReEncodesSemantically() throws Exception {
        String json = read("decisions.request.json");
        DecisionRequest request = codec.decodeRequest(json);

        assertEquals("tev1:0.8b", request.model());
        assertEquals(
                List.of("is_billing", "team", "urgency"),
                List.copyOf(request.questions().keySet()));
        assertInstanceOf(
                DecisionQuestion.NoulQuestion.class, request.questions().get("is_billing"));
        DecisionQuestion.ChoiceQuestion team = assertInstanceOf(
                DecisionQuestion.ChoiceQuestion.class, request.questions().get("team"));
        assertEquals("App bugs", team.criteria().get("technical"));
        DecisionQuestion.ScoreQuestion urgency = assertInstanceOf(
                DecisionQuestion.ScoreQuestion.class, request.questions().get("urgency"));
        assertEquals(List.of("Can wait", "This week", "Today"), urgency.criteria());
        assertInstanceOf(Map.class, request.state());

        assertTrue(JsonSupport.treeEquals(readTree(json), readTree(codec.encodeRequest(request))));
    }

    @Test
    void ollamaResponsesDecodeWithUsageAndExtraFieldPreserved() throws Exception {
        for (String file : List.of("decisions.response.ollama-tev1.json", "decisions.response.ollama-nimble.json")) {
            DecisionResponse response = codec.decodeResponse(read(file));
            DecisionAnswer.NoulAnswer billing = assertInstanceOf(
                    DecisionAnswer.NoulAnswer.class, response.answers().get("is_billing"));
            assertTrue(billing.noul() >= 0.0 && billing.noul() <= 1.0, file);
            DecisionAnswer.ChoiceAnswer team = assertInstanceOf(
                    DecisionAnswer.ChoiceAnswer.class, response.answers().get("team"));
            // The two captured models judge differently (both answers are
            // defensible); the codec invariant is that the choice is the
            // probability argmax over the three options.
            assertEquals(3, team.probabilities().size(), file);
            double best = team.probabilities().values().stream()
                    .mapToDouble(Double::doubleValue)
                    .max()
                    .orElseThrow();
            assertEquals(best, team.probabilities().get(team.choice()), file);
            assertInstanceOf(
                    DecisionAnswer.ScoreAnswer.class, response.answers().get("urgency"));
            assertNotNull(response.usage(), file);
            assertTrue(response.usage().promptTokens() > 0, file);
            assertEquals(0, response.usage().completionTokens(), file);
            // The Ollama-only field rides extras and re-encodes (passthrough honesty).
            assertTrue(response.extras().containsKey("prompt_eval_cached_count"), file);
            JsonNode reEncoded = readTree(codec.encodeResponse(response));
            assertTrue(reEncoded.has("prompt_eval_cached_count"), file);
            assertTrue(JsonSupport.treeEquals(readTree(read(file)), reEncoded), file);
        }
    }

    @Test
    void refusalAnswerEncodesAsTypedRefusal() throws Exception {
        DecisionResponse response = new DecisionResponse(
                "gpt-6-luna", Map.of("policy_ok", new DecisionAnswer.RefusalAnswer()), null, Map.of(), Map.of());
        JsonNode encoded = readTree(codec.encodeResponse(response));
        assertEquals(
                "refusal", encoded.get("answers").get("policy_ok").get("type").asString());
        DecisionResponse decoded = codec.decodeResponse(codec.encodeResponse(response));
        assertInstanceOf(DecisionAnswer.RefusalAnswer.class, decoded.answers().get("policy_ok"));
    }

    @Test
    void stringStateAndImagesRoundTrip() throws Exception {
        DecisionRequest request = new DecisionRequest(
                "clef",
                "An employee submitted this receipt.",
                List.of("aGVsbG8="),
                Map.of("receipt", new DecisionQuestion.NoulQuestion("Is this a restaurant receipt?", null)),
                Map.of(),
                Map.of());
        String encoded = codec.encodeRequest(request);
        JsonNode tree = readTree(encoded);
        assertEquals("An employee submitted this receipt.", tree.get("state").asString());
        assertEquals("aGVsbG8=", tree.get("images").get(0).asString());
        assertEquals(request, codec.decodeRequest(encoded));
    }

    @Test
    void invalidRequestsAreTypedClientErrors() {
        for (String bad : List.of(
                "",
                "{}",
                "{\"model\":\" \",\"state\":\"x\",\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"state\":\"x\",\"questions\":{}}",
                "{\"model\":\"m\",\"state\":\"x\",\"questions\":{\"q\":{\"type\":\"bogus\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"state\":\"x\",\"questions\":{\"q\":{\"type\":\"choice\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"state\":\"x\",\"questions\":{\"q\":{\"type\":\"score\",\"instructions\":\"?\",\"criteria\":[]}}}")) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> codec.decodeRequest(bad), bad);
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), bad);
        }
    }

    @Test
    void noulCriteriaOutsideTrueFalseIsATypedClientError() {
        // The noul→predicate fold only knows the "true"/"false" sides; anything
        // else must be rejected loudly, never silently dropped from the rubric.
        for (String bad : List.of(
                "{\"model\":\"m\",\"state\":\"s\",\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\",\"criteria\":{\"yes\":\"y\",\"no\":\"n\"}}}}",
                "{\"model\":\"m\",\"state\":\"s\",\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\",\"criteria\":{\"True\":\"y\",\"False\":\"n\"}}}}")) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> codec.decodeRequest(bad), bad);
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), bad);
        }
        DecisionRequest canonical = new DecisionRequest(
                "m",
                "s",
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("?", Map.of("yes", "y", "no", "n"))),
                Map.of(),
                Map.of());
        DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> codec.encodeRequest(canonical));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type());
    }

    @Test
    void blankImageStringsAreTypedClientErrors() {
        DecisionCodecException decode = assertThrows(
                DecisionCodecException.class,
                () -> codec.decodeRequest(
                        "{\"model\":\"m\",\"state\":\"s\",\"images\":[\"\"],\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}"));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, decode.type());
        DecisionRequest canonical = new DecisionRequest(
                "m", "s", List.of(""), Map.of("q", new DecisionQuestion.NoulQuestion("?", null)), Map.of(), Map.of());
        DecisionCodecException encode =
                assertThrows(DecisionCodecException.class, () -> codec.encodeRequest(canonical));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, encode.type());
    }

    @Test
    void emptyImagesArrayNormalizesToNoImages() throws Exception {
        DecisionRequest request = codec.decodeRequest(
                "{\"model\":\"m\",\"state\":\"s\",\"images\":[],\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}");
        assertNull(request.images(), "images:[] is the same absence as no images key — one canonical shape");
        JsonNode reEncoded = readTree(codec.encodeRequest(request));
        assertTrue(JsonSupport.treeEquals(
                readTree(codec.encodeRequest(
                        new DecisionRequest("m", "s", null, request.questions(), Map.of(), Map.of()))),
                reEncoded));
    }

    @Test
    void encodeResponseNullIsTypedApiError() {
        // Encode-side defects are the server's fault (the chat codecs' convention):
        // api_error/500, never a client 400.
        DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> codec.encodeResponse(null));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type());
    }

    @Test
    void malformedUpstreamResponseIsTypedApiError() {
        DecisionCodecException e = assertThrows(
                DecisionCodecException.class,
                () -> codec.decodeResponse("{\"model\":\"m\",\"answers\":{\"q\":{\"type\":\"wat\"}}}"));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type());
    }

    @Test
    void structuredInstructionsAndCriteriaPassThroughOnTheSystemOneLeg() throws Exception {
        String json = "{\"model\":\"m\",\"state\":{\"ticket\":\"x\"},\"questions\":{"
                + "\"q\":{\"type\":\"choice\",\"instructions\":{\"task\":\"route\"},"
                + "\"criteria\":{\"a\":{\"desc\":\"A\"},\"b\":null}}}}";
        DecisionRequest decoded = codec.decodeRequest(json);
        DecisionQuestion.ChoiceQuestion q = assertInstanceOf(
                DecisionQuestion.ChoiceQuestion.class, decoded.questions().get("q"));
        assertEquals(Map.of("task", "route"), q.instructions());
        assertEquals(Map.of("desc", "A"), q.criteria().get("a"));
        assertNull(q.criteria().get("b"));
        assertTrue(JsonSupport.treeEquals(readTree(json), readTree(codec.encodeRequest(decoded))));
    }

    @Test
    void singleLevelScoreIsAcceptedForTypeSafeButStillRequiresOneLevel() throws Exception {
        DecisionRequest one = codec.decodeRequest(
                "{\"model\":\"m\",\"state\":\"s\",\"questions\":{\"q\":{\"type\":\"score\",\"instructions\":\"?\",\"criteria\":[\"only\"]}}}");
        DecisionQuestion.ScoreQuestion score = assertInstanceOf(
                DecisionQuestion.ScoreQuestion.class, one.questions().get("q"));
        assertEquals(List.of("only"), score.criteria());
        DecisionCodecException empty = assertThrows(
                DecisionCodecException.class,
                () -> codec.decodeRequest(
                        "{\"model\":\"m\",\"state\":\"s\",\"questions\":{\"q\":{\"type\":\"score\",\"instructions\":\"?\",\"criteria\":[]}}}"));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, empty.type());
    }

    @Test
    void nonFiniteNumbersNeverCrossTheClientWireAsStrings() {
        DecisionCodecException response = assertThrows(
                DecisionCodecException.class,
                () -> codec.decodeResponse("{\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":1e999}}}"));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, response.type());
        DecisionCodecException state = assertThrows(
                DecisionCodecException.class,
                () -> codec.decodeRequest(
                        "{\"model\":\"m\",\"state\":{\"threshold\":1e999},\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}"));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, state.type());
    }

    @Test
    void answerRangesAndConfidenceTypesAreValidated() {
        for (String bad : List.of(
                "{\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":2.0}}}",
                "{\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":-0.1}}}",
                "{\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"a\":1.0},\"confidence\":9}}}",
                "{\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"a\":-1.0}}}}",
                "{\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{}}}}",
                "{\"answers\":{\"q\":{\"type\":\"score\",\"score\":1.0,\"probabilities\":{\"0\":1.0},\"legend\":[]}}}",
                "{\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"a\":1.0},\"confidence\":\"high\"}}}")) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> codec.decodeResponse(bad), bad);
            assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type(), bad);
        }
    }

    @Test
    void usageIsStrictAndOverflowIsATypedUpstreamError() {
        for (String bad : List.of(
                "{\"answers\":{},\"usage\":{\"input_tokens\":-1,\"output_tokens\":0}}",
                "{\"answers\":{},\"usage\":{\"input_tokens\":\"999\",\"output_tokens\":0}}",
                "{\"answers\":{},\"usage\":{\"input_tokens\":1.5,\"output_tokens\":0}}",
                "{\"answers\":{},\"usage\":{\"input_tokens\":9223372036854775807,\"output_tokens\":1}}")) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> codec.decodeResponse(bad), bad);
            assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type(), bad);
        }
    }

    @Test
    void duplicateJsonKeysAndScalarStateAreTypedClientErrors() {
        for (String bad : List.of(
                "{\"model\":\"a\",\"model\":\"b\",\"state\":\"s\",\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"state\":42,\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"state\":true,\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"state\":\"s\",\"stream\":true,\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"state\":\"s\",\"box\":true,\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}")) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> codec.decodeRequest(bad), bad);
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), bad);
        }
    }

    @Test
    void imagesMustBeRawBase64NotUrlsOrDataUrls() {
        for (String bad :
                List.of("file:///etc/passwd", "http://169.254.169.254/latest", "data:image/png;base64,AAAA")) {
            DecisionCodecException e = assertThrows(
                    DecisionCodecException.class,
                    () -> codec.decodeRequest("{\"model\":\"m\",\"state\":\"s\",\"images\":[\"" + bad
                            + "\"],\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}"),
                    bad);
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), bad);
        }
        assertDoesNotThrow(
                () -> codec.decodeRequest(
                        "{\"model\":\"m\",\"state\":\"s\",\"images\":[\"iVBORw0KGgo=\"],\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}"));
    }

    @Test
    void encodeResponseWithANullAnswerIsATypedApiError() {
        Map<String, DecisionAnswer> answers = new java.util.LinkedHashMap<>();
        answers.put("q", null);
        DecisionCodecException e = assertThrows(
                DecisionCodecException.class,
                () -> codec.encodeResponse(new DecisionResponse("m", answers, null, Map.of(), Map.of())));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type());
    }

    @Test
    void boxQuestionAndAnswerRoundTripWithTypeSafeAssetsPassthrough() throws Exception {
        String json = "{\"model\":\"jev-latest\",\"state\":{\"photo\":\"p1\"},"
                + "\"assets\":{\"p1\":{\"type\":\"image\",\"format\":\"png\",\"data_base64\":\"iVBORw0KGgo=\"}},"
                + "\"questions\":{\"locate\":{\"type\":\"box\",\"instructions\":\"Find the mug in `photo`.\"}}}";
        DecisionRequest request = codec.decodeRequest(json);
        assertInstanceOf(DecisionQuestion.BoxQuestion.class, request.questions().get("locate"));
        assertTrue(request.hasImageEvidence(), "assets/Box are image evidence even without images[]");
        assertTrue(JsonSupport.treeEquals(readTree(json), readTree(codec.encodeRequest(request))));

        DecisionResponse response =
                codec.decodeResponse("{\"model\":\"jev-latest\",\"answers\":{\"locate\":{\"type\":\"box\","
                        + "\"box\":{\"top\":0.1,\"bottom\":0.9,\"left\":0.2,\"right\":0.8},\"confidence\":0.7}}}");
        DecisionAnswer.BoxAnswer box = assertInstanceOf(
                DecisionAnswer.BoxAnswer.class, response.answers().get("locate"));
        assertEquals(0.1, box.top());
        assertEquals(0.9, box.bottom());
        assertEquals(0.2, box.left());
        assertEquals(0.8, box.right());
        assertEquals(0.7, box.confidence());
        JsonNode encoded = readTree(codec.encodeResponse(response));
        assertEquals("box", encoded.get("answers").get("locate").get("type").asString());
        assertEquals(
                0.1, encoded.get("answers").get("locate").get("box").get("top").asDouble());
        assertEquals(
                0.8,
                encoded.get("answers").get("locate").get("box").get("right").asDouble());
    }

    @Test
    void boxAnswersOutsideUnitCoordinatesAreTypedApiErrors() {
        DecisionCodecException e = assertThrows(
                DecisionCodecException.class,
                () -> codec.decodeResponse(
                        "{\"answers\":{\"locate\":{\"type\":\"box\",\"box\":{\"top\":1.2,\"bottom\":0.9,"
                                + "\"left\":0.2,\"right\":0.8},\"confidence\":0.7}}}"));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type());
    }

    @Test
    void multipartAssetsAreInlinedAndValidated() throws Exception {
        String json = "{\"model\":\"jev-latest\",\"state\":{\"photo\":\"p1\"},"
                + "\"assets\":{\"p1\":{\"type\":\"image\",\"part\":\"photo\"}},"
                + "\"questions\":{\"locate\":{\"type\":\"box\",\"instructions\":\"Find it.\"}}}";
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        String inlined = codec.inlineMultipartAssets(json, Map.of("photo", png));
        JsonNode asset = readTree(inlined).get("assets").get("p1");
        assertEquals(
                Base64.getEncoder().encodeToString(png),
                asset.get("data_base64").asString());
        assertEquals("png", asset.get("format").asString());
        assertFalse(asset.has("part"), "the multipart reference is replaced, never forwarded");

        DecisionCodecException missing =
                assertThrows(DecisionCodecException.class, () -> codec.inlineMultipartAssets(json, Map.of()));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, missing.type());

        String noAssets = "{\"model\":\"m\",\"state\":\"s\",\"questions\":{\"q\":{\"type\":\"noul\"}}}";
        assertThrows(DecisionCodecException.class, () -> codec.inlineMultipartAssets(noAssets, Map.of("photo", png)));

        String duplicate = "{\"model\":\"m\",\"state\":\"s\",\"assets\":{"
                + "\"p1\":{\"type\":\"image\",\"part\":\"photo\"},"
                + "\"p2\":{\"type\":\"image\",\"part\":\"photo\"}},"
                + "\"questions\":{\"q\":{\"type\":\"noul\"}}}";
        assertThrows(DecisionCodecException.class, () -> codec.inlineMultipartAssets(duplicate, Map.of("photo", png)));

        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0};
        JsonNode jpegAsset = readTree(codec.inlineMultipartAssets(json, Map.of("photo", jpeg)))
                .get("assets")
                .get("p1");
        assertEquals("jpg", jpegAsset.get("format").asString());

        assertThrows(
                DecisionCodecException.class,
                () -> codec.inlineMultipartAssets(json, Map.of("photo", new byte[] {1, 2, 3})));
    }

    private static JsonNode readTree(String json) throws IOException {
        return JsonSupport.mapper().readTree(json);
    }

    private static String read(String relativePath) throws IOException {
        try (InputStream in = SystemOneCodecTest.class.getResourceAsStream(ROOT + "/" + relativePath)) {
            assertNotNull(in, ROOT + "/" + relativePath + " missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
