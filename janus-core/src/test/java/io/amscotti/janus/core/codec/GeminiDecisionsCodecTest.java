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
import io.amscotti.janus.core.model.Usage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Google Gemini Decisions ({@code POST /v1beta/decisions}) wire codec tests,
 * covering request/response fixture parity, native &amp; cross-dialect
 * multi-format blocks, validation boundaries, and symmetric decode/encode.
 */
class GeminiDecisionsCodecTest {

    private static final String ROOT = "/fixtures/systemone";
    private static final String PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    private static final String PDF_B64 =
            Base64.getEncoder().encodeToString("%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));
    private static final String WAV_B64 = Base64.getEncoder()
            .encodeToString("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".getBytes(StandardCharsets.US_ASCII));

    private final SystemOneCodec systemOne = new SystemOneCodec(JsonSupport.mapper());
    private final GeminiDecisionsCodec gemini = new GeminiDecisionsCodec(JsonSupport.mapper());

    @Test
    void canonicalRequestEncodesToGeminiDecisionsFixture() throws Exception {
        DecisionRequest canonical = systemOne.decodeRequest(read("decisions.request.json"));
        DecisionRequest forGemini = canonical.withModel("gemini-decision-1");
        String encoded = gemini.encodeRequest(forGemini);
        assertTrue(JsonSupport.treeEquals(readTree(read("gemini.decisions.request.json")), readTree(encoded)), encoded);
    }

    @Test
    void geminiRequestDecodesAndReEncodesSymmetrically() throws Exception {
        String raw = read("gemini.decisions.request.json");
        DecisionRequest canonical = gemini.decodeRequest(raw);
        assertEquals("gemini-decision-1", canonical.model());
        assertEquals(
                List.of("is_billing", "team", "urgency"),
                List.copyOf(canonical.questions().keySet()));
        DecisionQuestion.NoulQuestion noul = assertInstanceOf(
                DecisionQuestion.NoulQuestion.class, canonical.questions().get("is_billing"));
        assertEquals("About charges", noul.criteria().get("true"));
        assertEquals("Not about charges", noul.criteria().get("false"));
        DecisionQuestion.ChoiceQuestion team = assertInstanceOf(
                DecisionQuestion.ChoiceQuestion.class, canonical.questions().get("team"));
        assertEquals("App bugs", team.criteria().get("technical"));
        DecisionQuestion.ScoreQuestion urgency = assertInstanceOf(
                DecisionQuestion.ScoreQuestion.class, canonical.questions().get("urgency"));
        assertEquals(List.of("Can wait", "This week", "Today"), urgency.criteria());

        // Decoded canonical from text block restores the serialized JSON string state
        assertTrue(JsonSupport.treeEquals(readTree(raw), readTree(gemini.encodeRequest(canonical))));
    }

    @Test
    void geminiResponseFixtureDecodesWithNullModelAndInputOnlyUsage() throws Exception {
        String raw = read("gemini.decisions.response.json");
        DecisionResponse response = gemini.decodeResponse(raw);
        assertNull(response.model(), "Gemini omits model so adapter backend echoes the client alias");
        DecisionAnswer.NoulAnswer billing = assertInstanceOf(
                DecisionAnswer.NoulAnswer.class, response.answers().get("is_billing"));
        assertEquals(0.9591324941522285, billing.noul(), 1e-12);
        DecisionAnswer.ChoiceAnswer team = assertInstanceOf(
                DecisionAnswer.ChoiceAnswer.class, response.answers().get("team"));
        assertEquals("billing", team.choice());
        assertEquals(0.7242, team.confidence(), 1e-12);
        DecisionAnswer.ScoreAnswer urgency = assertInstanceOf(
                DecisionAnswer.ScoreAnswer.class, response.answers().get("urgency"));
        assertEquals(1.5011, urgency.score(), 1e-12);
        assertEquals("Today", urgency.legend().get("2"));
        assertEquals(0.5410, urgency.probabilities().get("2"), 1e-12);

        assertNotNull(response.usage());
        assertEquals(259L, response.usage().promptTokens());
        assertEquals(0L, response.usage().completionTokens());
        assertEquals(259L, response.usage().totalTokens());

        String reEncoded = gemini.encodeResponse(response);
        JsonNode reEncodedTree = readTree(reEncoded);
        assertFalse(reEncodedTree.has("model"));
        assertEquals(259L, reEncodedTree.get("usage").get("total_input_tokens").asLong());
        assertEquals(0L, reEncodedTree.get("usage").get("total_output_tokens").asLong());
        assertEquals(259L, reEncodedTree.get("usage").get("total_tokens").asLong());
    }

    @Test
    void plainStringStateUsesStringInputWithoutImagesAndBlocksWithImages() throws Exception {
        DecisionRequest textOnly = new DecisionRequest(
                "gemini-decision-1",
                "I was charged twice.",
                null,
                Map.of("refund", new DecisionQuestion.NoulQuestion("Refund?", null)),
                Map.of(),
                Map.of());
        JsonNode textTree = readTree(gemini.encodeRequest(textOnly));
        assertTrue(textTree.get("input").isString());
        assertEquals("I was charged twice.", textTree.get("input").asString());
        assertEquals(textOnly, gemini.decodeRequest(gemini.encodeRequest(textOnly)));

        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        byte[] webp = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0};
        byte[] gif = new byte[] {'G', 'I', 'F', '8', '9', 'a', 1, 0};
        DecisionRequest withImages = new DecisionRequest(
                "gemini-decision-1",
                "Inspect these.",
                List.of(
                        PNG_B64,
                        Base64.getEncoder().encodeToString(jpeg),
                        Base64.getEncoder().encodeToString(webp),
                        Base64.getEncoder().encodeToString(gif)),
                Map.of("ok", new DecisionQuestion.NoulQuestion("Valid?", null)),
                Map.of(),
                Map.of());
        JsonNode imgTree = readTree(gemini.encodeRequest(withImages));
        JsonNode blocks = imgTree.get("input");
        assertTrue(blocks.isArray());
        assertEquals(5, blocks.size());
        assertEquals("text", blocks.get(0).get("type").asString());
        assertEquals("Inspect these.", blocks.get(0).get("text").asString());
        assertEquals("image", blocks.get(1).get("type").asString());
        assertEquals("image/png", blocks.get(1).get("mime_type").asString());
        assertEquals("image/jpeg", blocks.get(2).get("mime_type").asString());
        assertEquals("image/webp", blocks.get(3).get("mime_type").asString());
        assertEquals("image/gif", blocks.get(4).get("mime_type").asString());
        assertEquals(withImages, gemini.decodeRequest(gemini.encodeRequest(withImages)));
    }

    @Test
    void domainListStateSerializesAsCompactJsonInTextBlock() throws Exception {
        DecisionRequest listState = new DecisionRequest(
                "gemini-decision-1",
                List.of(Map.of("id", 1), Map.of("id", 2)),
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("Evaluate items?", null)),
                Map.of(),
                Map.of());
        JsonNode tree = readTree(gemini.encodeRequest(listState));
        assertTrue(tree.get("input").isArray());
        assertEquals(1, tree.get("input").size());
        assertEquals("text", tree.get("input").get(0).get("type").asString());
        assertEquals(
                "[{\"id\":1},{\"id\":2}]", tree.get("input").get(0).get("text").asString());
    }

    @Test
    void nativeMultiFormatBlocksEncodeAndRoundTrip() throws Exception {
        List<Map<String, Object>> stateBlocks = List.of(
                Map.of("type", "text", "text", "Analyze attachments."),
                Map.of(
                        "type", "image",
                        "data", "data:image/png;base64," + PNG_B64,
                        "resolution", "high"),
                Map.of(
                        "type", "document",
                        "data", PDF_B64,
                        "mime_type", "application/pdf"),
                Map.of("type", "audio", "data", WAV_B64, "mime_type", "audio/wav", "channels", 1, "sample_rate", 16000),
                Map.of(
                        "type", "video",
                        "uri", "files/video-123",
                        "mime_type", "video/mp4",
                        "resolution", "medium",
                        "processing", Map.of("type", "static", "fps", 2),
                        "name", "clip"));
        DecisionRequest request = new DecisionRequest(
                "gemini-decision-1",
                stateBlocks,
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("Ready?", null)),
                Map.of(),
                Map.of());
        String encoded = gemini.encodeRequest(request);
        JsonNode input = readTree(encoded).get("input");
        assertEquals(5, input.size());
        assertEquals(PNG_B64, input.get(1).get("data").asString(), "data URL prefix stripped");
        assertEquals("image/png", input.get(1).get("mime_type").asString());
        assertEquals("high", input.get(1).get("resolution").asString());
        assertEquals("document", input.get(2).get("type").asString());
        assertEquals("application/pdf", input.get(2).get("mime_type").asString());
        assertEquals("audio", input.get(3).get("type").asString());
        assertEquals(1, input.get(3).get("channels").asInt());
        assertEquals(16000, input.get(3).get("sample_rate").asInt());
        assertEquals("video", input.get(4).get("type").asString());
        assertEquals("files/video-123", input.get(4).get("uri").asString());
        assertEquals("clip", input.get(4).get("name").asString());

        DecisionRequest decoded = gemini.decodeRequest(encoded);
        assertTrue(JsonSupport.treeEquals(readTree(encoded), readTree(gemini.encodeRequest(decoded))));
    }

    @Test
    void crossDialectBlocksTranslateToNativeGeminiBlocks() throws Exception {
        List<Map<String, Object>> crossDialect = List.of(
                Map.of("type", "input_text", "text", "Review all media."),
                Map.of(
                        "type",
                        "image_url",
                        "image_url",
                        Map.of("url", "data:image/png;base64," + PNG_B64, "detail", "low")),
                Map.of("type", "input_image", "image_url", "https://example.com/photo.jpg"),
                Map.of("type", "input_audio", "input_audio", Map.of("data", WAV_B64, "format", "wav")),
                Map.of("type", "video_url", "video_url", Map.of("url", "https://example.com/clip.mp4")),
                Map.of(
                        "type",
                        "file",
                        "file",
                        Map.of("file_data", "data:text/csv;base64,YSwxCmIsMgo=", "filename", "data.csv")));
        DecisionRequest request = new DecisionRequest(
                "gemini-decision-1",
                crossDialect,
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("Valid?", null)),
                Map.of(),
                Map.of());
        JsonNode input = readTree(gemini.encodeRequest(request)).get("input");
        assertEquals(6, input.size());
        assertEquals("text", input.get(0).get("type").asString());
        assertEquals("image", input.get(1).get("type").asString());
        assertEquals(PNG_B64, input.get(1).get("data").asString());
        assertEquals("low", input.get(1).get("resolution").asString());
        assertEquals("image", input.get(2).get("type").asString());
        assertEquals("https://example.com/photo.jpg", input.get(2).get("uri").asString());
        assertEquals("audio", input.get(3).get("type").asString());
        assertEquals("audio/wav", input.get(3).get("mime_type").asString());
        assertEquals("video", input.get(4).get("type").asString());
        assertEquals("https://example.com/clip.mp4", input.get(4).get("uri").asString());
        assertEquals("document", input.get(5).get("type").asString());
        assertEquals("text/csv", input.get(5).get("mime_type").asString());
        assertEquals("YSwxCmIsMgo=", input.get(5).get("data").asString());
    }

    @Test
    void singleContentObjectWireDecodesSymmetrically() throws Exception {
        String singleBlock = "{\"model\":\"gemini-decision-1\","
                + "\"input\":{\"type\":\"document\",\"uri\":\"https://example.com/report.pdf\",\"mime_type\":\"application/pdf\"},"
                + "\"questions\":{\"ok\":{\"type\":\"noul\",\"instructions\":\"Valid?\"}}}";
        DecisionRequest decoded = gemini.decodeRequest(singleBlock);
        JsonNode encoded = readTree(gemini.encodeRequest(decoded));
        assertEquals("document", encoded.get("input").get(0).get("type").asString());
        assertEquals(
                "https://example.com/report.pdf",
                encoded.get("input").get(0).get("uri").asString());
    }

    @Test
    void invalidMultiFormatBlocksAndUrisAreRejected() {
        List<Object> invalidStates = List.of(
                "",
                List.of(Map.of("type", "image", "data", PNG_B64, "uri", "https://example.com/a.png")),
                List.of(Map.of("type", "image", "data", "not-base64!", "mime_type", "image/png")),
                List.of(Map.of("type", "image", "data", PNG_B64, "mime_type", "image/svg+xml")),
                List.of(Map.of("type", "image", "uri", "http://example.com/a.png")),
                List.of(Map.of("type", "image", "uri", "file:///etc/passwd")),
                List.of(Map.of("type", "image", "uri", "https://example.com/a.png", "resolution", "ultra")),
                List.of(Map.of(
                        "type", "document", "data", PDF_B64, "mime_type", "application/pdf", "resolution", "low")),
                List.of(Map.of("type", "document", "data", PDF_B64, "mime_type", "image/png")),
                List.of(Map.of("type", "audio", "data", WAV_B64, "mime_type", "video/mp4")),
                List.of(Map.of("type", "video", "uri", "gs://bucket/video.mp4", "mime_type", "video/mp4")),
                List.of(Map.of(
                        "type", "video", "uri", "https://www.youtube.com/watch?v=123", "mime_type", "video/mp4")),
                List.of(Map.of("type", "video", "uri", "https://example.com/a.mp4", "resolution", "ultra_high")),
                List.of(Map.of("type", "text", "text", "ok"), Map.of("type", "bogus", "text", "nope")));
        for (Object state : invalidStates) {
            DecisionRequest req = new DecisionRequest(
                    "gemini-decision-1",
                    state,
                    null,
                    Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                    Map.of(),
                    Map.of());
            DecisionCodecException e =
                    assertThrows(DecisionCodecException.class, () -> gemini.encodeRequest(req), state.toString());
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), state.toString());
        }
    }

    @Test
    void questionCapsKeysInstructionsAndTypesAreValidated() throws Exception {
        // Exactly 1000 questions allowed; 1001 rejected
        Map<String, DecisionQuestion> thousand = new LinkedHashMap<>();
        for (int i = 0; i < GeminiDecisionsCodec.MAX_QUESTIONS; i++) {
            thousand.put("q_" + i, new DecisionQuestion.NoulQuestion("?", null));
        }
        assertDoesNotThrow(
                () -> gemini.encodeRequest(new DecisionRequest("m", "s", null, thousand, Map.of(), Map.of())));
        thousand.put("q_extra", new DecisionQuestion.NoulQuestion("?", null));
        DecisionCodecException tooManyQuestions = assertThrows(
                DecisionCodecException.class,
                () -> gemini.encodeRequest(new DecisionRequest("m", "s", null, thousand, Map.of(), Map.of())));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, tooManyQuestions.type());

        // Bad question keys
        for (String badKey : List.of("bad key", "a.b", "k".repeat(65))) {
            DecisionCodecException keyErr = assertThrows(
                    DecisionCodecException.class,
                    () -> gemini.encodeRequest(new DecisionRequest(
                            "m",
                            "s",
                            null,
                            Map.of(badKey, new DecisionQuestion.NoulQuestion("?", null)),
                            Map.of(),
                            Map.of())),
                    badKey);
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, keyErr.type(), badKey);
        }

        // Null/blank instructions or media in instructions rejected
        for (Object badInstr : new Object[] {
            null,
            "   ",
            List.of(Map.of("type", "image", "data", PNG_B64)),
            List.of(Map.of("type", "input_image", "image_url", "data:image/png;base64," + PNG_B64))
        }) {
            DecisionCodecException instrErr = assertThrows(
                    DecisionCodecException.class,
                    () -> gemini.encodeRequest(new DecisionRequest(
                            "m",
                            "s",
                            null,
                            Map.of("q", new DecisionQuestion.NoulQuestion(badInstr, null)),
                            Map.of(),
                            Map.of())));
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, instrErr.type());
        }

        // Structured instructions fold to text; text blocks extract cleanly
        Map<String, Object> choiceCriteria = new java.util.LinkedHashMap<>();
        choiceCriteria.put("a", Map.of("d", 1));
        choiceCriteria.put("b", null);
        DecisionRequest structured = new DecisionRequest(
                "m",
                "s",
                null,
                Map.of(
                        "q1", new DecisionQuestion.ChoiceQuestion(Map.of("task", "route"), choiceCriteria),
                        "q2",
                                new DecisionQuestion.ScoreQuestion(
                                        List.of(Map.of("type", "text", "text", "Rate")), List.of("Low", "High"))),
                Map.of(),
                Map.of());
        JsonNode encodedQ = readTree(gemini.encodeRequest(structured)).get("questions");
        assertTrue(encodedQ.get("q1").get("instructions").asString().contains("\"task\":\"route\""));
        assertEquals("{\"d\":1}", encodedQ.get("q1").get("criteria").get("a").asString());
        assertTrue(encodedQ.get("q1").get("criteria").get("b").isNull());
        assertEquals("Rate", encodedQ.get("q2").get("instructions").asString());

        // Choice min 2, max 255; Score min 2, max 255 non-empty strings
        assertThrows(
                DecisionCodecException.class,
                () -> gemini.encodeRequest(new DecisionRequest(
                        "m",
                        "s",
                        null,
                        Map.of("q", new DecisionQuestion.ChoiceQuestion("?", Map.of("only", "one"))),
                        Map.of(),
                        Map.of())));
        assertThrows(
                DecisionCodecException.class,
                () -> gemini.encodeRequest(new DecisionRequest(
                        "m",
                        "s",
                        null,
                        Map.of("q", new DecisionQuestion.ScoreQuestion("?", List.of("only"))),
                        Map.of(),
                        Map.of())));
        assertThrows(
                DecisionCodecException.class,
                () -> gemini.encodeRequest(new DecisionRequest(
                        "m",
                        "s",
                        null,
                        Map.of("q", new DecisionQuestion.ScoreQuestion("?", List.of("low", ""))),
                        Map.of(),
                        Map.of())));

        // Box & non-empty assets & unknown top-level extras rejected; empty assets tolerated
        assertThrows(
                DecisionCodecException.class,
                () -> gemini.encodeRequest(new DecisionRequest(
                        "m", "s", null, Map.of("q", new DecisionQuestion.BoxQuestion("Find")), Map.of(), Map.of())));
        assertThrows(
                DecisionCodecException.class,
                () -> gemini.encodeRequest(new DecisionRequest(
                        "m",
                        "s",
                        null,
                        Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                        Map.of("assets", Map.of("p1", Map.of("type", "image"))),
                        Map.of())));
        assertThrows(
                DecisionCodecException.class,
                () -> gemini.encodeRequest(new DecisionRequest(
                        "m",
                        "s",
                        null,
                        Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                        Map.of("temperature", 0.2),
                        Map.of())));
        JsonNode emptyAssetsOut = readTree(gemini.encodeRequest(new DecisionRequest(
                "m",
                "s",
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of("assets", Map.of()),
                Map.of())));
        assertFalse(emptyAssetsOut.has("assets"));
    }

    @Test
    void responseValidationRefusalAndUsageOverflowAreHandledCleanly() throws Exception {
        DecisionResponse refusal = new DecisionResponse(
                "gemini-decision-1",
                Map.of("q", new DecisionAnswer.RefusalAnswer()),
                new Usage(12, 0, 12),
                Map.of(),
                Map.of());
        String encoded = gemini.encodeResponse(refusal);
        DecisionResponse decoded = gemini.decodeResponse(encoded);
        assertInstanceOf(DecisionAnswer.RefusalAnswer.class, decoded.answers().get("q"));

        for (String bad : List.of(
                "{\"usage\":{\"total_input_tokens\":10}}",
                "{\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":1.5}}}",
                "{\"answers\":{\"q\":{\"type\":\"box\",\"box\":{\"top\":0.1,\"bottom\":0.2,\"left\":0.3,\"right\":0.4}}}}",
                "{\"answers\":{},\"usage\":{\"total_input_tokens\":-1,\"total_tokens\":0}}",
                "{\"answers\":{},\"usage\":{\"total_input_tokens\":9223372036854775807,\"total_output_tokens\":1}}")) {
            DecisionCodecException e =
                    assertThrows(DecisionCodecException.class, () -> gemini.decodeResponse(bad), bad);
            assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type(), bad);
        }

        DecisionCodecException boxEncode = assertThrows(
                DecisionCodecException.class,
                () -> gemini.encodeResponse(new DecisionResponse(
                        null,
                        Map.of("q", new DecisionAnswer.BoxAnswer(0.1, 0.2, 0.3, 0.4, 0.5)),
                        null,
                        Map.of(),
                        Map.of())));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, boxEncode.type());
    }

    private static JsonNode readTree(String json) throws IOException {
        return JsonSupport.mapper().readTree(json);
    }

    private static String read(String relativePath) throws IOException {
        try (InputStream in = GeminiDecisionsCodecTest.class.getResourceAsStream(ROOT + "/" + relativePath)) {
            assertNotNull(in, ROOT + "/" + relativePath + " missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
