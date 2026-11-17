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

    @Test
    void createFactoryUsesStandardStrictMapper() throws Exception {
        GeminiDecisionsCodec created = GeminiDecisionsCodec.create();
        DecisionRequest req = new DecisionRequest(
                "gemini-decision-1",
                "hello",
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("Valid?", null)),
                Map.of(),
                Map.of());
        assertEquals(req, created.decodeRequest(created.encodeRequest(req)));

        DecisionCodecException dup = assertThrows(
                DecisionCodecException.class,
                () -> created.decodeRequest("{\"model\":\"a\",\"model\":\"b\",\"input\":\"s\","
                        + "\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}"));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, dup.type());
    }

    @Test
    void encodeAndDecodeInputVariantsAndErrors() throws Exception {
        // Null/blank request fields and bad question containers on encode
        Map<String, DecisionQuestion> validQuestions = Map.of("q", new DecisionQuestion.NoulQuestion("?", null));
        Map<String, DecisionQuestion> nullQuestionVal = new LinkedHashMap<>();
        nullQuestionVal.put("q", null);
        Map<String, DecisionQuestion> nullQuestionKey = new LinkedHashMap<>();
        nullQuestionKey.put(null, new DecisionQuestion.NoulQuestion("?", null));
        for (DecisionRequest badReq : new DecisionRequest[] {
            null,
            new DecisionRequest(null, "s", null, validQuestions, Map.of(), Map.of()),
            new DecisionRequest(" ", "s", null, validQuestions, Map.of(), Map.of()),
            new DecisionRequest("m", null, null, validQuestions, Map.of(), Map.of()),
            new DecisionRequest("m", 42, null, validQuestions, Map.of(), Map.of()),
            new DecisionRequest("m", "s", null, null, Map.of(), Map.of()),
            new DecisionRequest("m", "s", null, Map.of(), Map.of(), Map.of()),
            new DecisionRequest("m", "s", null, nullQuestionVal, Map.of(), Map.of()),
            new DecisionRequest("m", "s", null, nullQuestionKey, Map.of(), Map.of())
        }) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> gemini.encodeRequest(badReq));
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type());
        }

        // Empty images list behaves identically to null images
        JsonNode emptyImagesTree = readTree(
                gemini.encodeRequest(new DecisionRequest("m", "hello", List.of(), validQuestions, Map.of(), Map.of())));
        assertEquals("hello", emptyImagesTree.get("input").asString());

        // Empty string state WITH images emits image-only blocks and round-trips to "" + images
        DecisionRequest imagesOnly = new DecisionRequest("m", "", List.of(PNG_B64), validQuestions, Map.of(), Map.of());
        String encodedImagesOnly = gemini.encodeRequest(imagesOnly);
        JsonNode imagesOnlyInput = readTree(encodedImagesOnly).get("input");
        assertEquals(1, imagesOnlyInput.size());
        assertEquals("image", imagesOnlyInput.get(0).get("type").asString());
        assertEquals(imagesOnly, gemini.decodeRequest(encodedImagesOnly));

        // Map state that IS a single content block encodes directly as a 1-element block array
        JsonNode singleMapBlock = readTree(gemini.encodeRequest(new DecisionRequest(
                        "m", Map.of("type", "text", "text", "solo"), null, validQuestions, Map.of(), Map.of())))
                .get("input");
        assertEquals(1, singleMapBlock.size());
        assertEquals("solo", singleMapBlock.get(0).get("text").asString());

        // Non-block Map state combined with images[] emits text block + image block
        JsonNode mapPlusImg = readTree(gemini.encodeRequest(new DecisionRequest(
                        "m", Map.of("ticket", 1), List.of(PNG_B64), validQuestions, Map.of(), Map.of())))
                .get("input");
        assertEquals(2, mapPlusImg.size());
        assertEquals("{\"ticket\":1}", mapPlusImg.get(0).get("text").asString());
        assertEquals("image", mapPlusImg.get(1).get("type").asString());

        // Invalid content block list shapes & invalid images[] entries
        List<String> badImagesList = new java.util.ArrayList<>();
        badImagesList.add(null);
        for (DecisionRequest bad : List.of(
                new DecisionRequest(
                        "m",
                        List.of(Map.of("type", "text", "text", "ok"), "not-a-map"),
                        null,
                        validQuestions,
                        Map.of(),
                        Map.of()),
                new DecisionRequest(
                        "m",
                        List.of(Map.of("type", "text", "text", "ok"), Map.of("type", "")),
                        null,
                        validQuestions,
                        Map.of(),
                        Map.of()),
                new DecisionRequest(
                        "m",
                        List.of(Map.of("type", "text", "text", "ok"), Map.of("type", 123)),
                        null,
                        validQuestions,
                        Map.of(),
                        Map.of()),
                new DecisionRequest(
                        "m", List.of(Map.of("type", "text", "text", "")), null, validQuestions, Map.of(), Map.of()),
                new DecisionRequest(
                        "m", List.of(Map.of("type", "text", "text", 99)), null, validQuestions, Map.of(), Map.of()),
                new DecisionRequest("m", "s", badImagesList, validQuestions, Map.of(), Map.of()),
                new DecisionRequest("m", "s", List.of("  "), validQuestions, Map.of(), Map.of()),
                new DecisionRequest("m", "s", List.of("not-base64!"), validQuestions, Map.of(), Map.of()))) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> gemini.encodeRequest(bad));
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type());
        }

        // Decode request error branches: unknown top-level field, bad questions, bad input shapes
        StringBuilder over1000 = new StringBuilder("{\"model\":\"m\",\"input\":\"s\",\"questions\":{");
        for (int i = 0; i <= GeminiDecisionsCodec.MAX_QUESTIONS; i++) {
            if (i > 0) {
                over1000.append(',');
            }
            over1000.append("\"q").append(i).append("\":{\"type\":\"noul\",\"instructions\":\"?\"}");
        }
        over1000.append("}}");
        for (String badJson : List.of(
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}},\"temperature\":0.2}",
                "{\"model\":\"m\",\"input\":\"s\"}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":{}}",
                over1000.toString(),
                "{\"model\":\"m\",\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"input\":null,\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"input\":\"\",\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"input\":[],\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"input\":123,\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"input\":[\"str\"],\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"input\":[{\"type\":\"image_url\",\"image_url\":\"https://example.com/a.png\"}],"
                        + "\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}")) {
            DecisionCodecException e =
                    assertThrows(DecisionCodecException.class, () -> gemini.decodeRequest(badJson), badJson);
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), badJson);
        }

        // Non-extractable block sequences stay as List<Map<String, Object>> (not collapsed into images[])
        for (String preservedBlocksJson : List.of(
                "{\"model\":\"m\",\"input\":[{\"type\":\"image\",\"data\":\"" + PNG_B64
                        + "\",\"mime_type\":\"image/png\"},{\"type\":\"text\",\"text\":\"after\"}],"
                        + "\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"input\":[{\"type\":\"image\",\"uri\":\"https://example.com/a.png\"}],"
                        + "\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"input\":[{\"type\":\"image\",\"data\":\"" + PNG_B64
                        + "\",\"resolution\":\"high\"}],"
                        + "\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}",
                "{\"model\":\"m\",\"input\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}],"
                        + "\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}")) {
            DecisionRequest decoded = gemini.decodeRequest(preservedBlocksJson);
            assertNull(decoded.images());
            assertInstanceOf(List.class, decoded.state());
        }
    }

    @Test
    void sniffImageMimeAndImageBlockBranches() throws Exception {
        byte[] gif87a = new byte[] {'G', 'I', 'F', '8', '7', 'a', 1, 0};
        byte[] gif89a = new byte[] {'G', 'I', 'F', '8', '9', 'a', 1, 0};
        byte[] bmp = new byte[] {'B', 'M', 0, 0, 0, 0};
        byte[] tiffLe = new byte[] {'I', 'I', 42, 0, 8, 0};
        byte[] tiffBe = new byte[] {'M', 'M', 0, 42, 0, 8};
        byte[] unknownBytes = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};

        DecisionRequest req = new DecisionRequest(
                "m",
                "Inspect.",
                List.of(
                        Base64.getEncoder().encodeToString(gif87a),
                        Base64.getEncoder().encodeToString(gif89a),
                        Base64.getEncoder().encodeToString(bmp),
                        Base64.getEncoder().encodeToString(tiffLe),
                        Base64.getEncoder().encodeToString(tiffBe),
                        Base64.getEncoder().encodeToString(unknownBytes)),
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of());
        JsonNode blocks = readTree(gemini.encodeRequest(req)).get("input");
        assertEquals("image/gif", blocks.get(1).get("mime_type").asString());
        assertEquals("image/gif", blocks.get(2).get("mime_type").asString());
        assertEquals("image/bmp", blocks.get(3).get("mime_type").asString());
        assertEquals("image/tiff", blocks.get(4).get("mime_type").asString());
        assertEquals("image/tiff", blocks.get(5).get("mime_type").asString());
        assertEquals("image/png", blocks.get(6).get("mime_type").asString());

        // Image block variants: image/jpg alias, explicit uri + mime, ultra_high, input_image native fallback,
        // image_url string/map, detail auto/high, files/ URI, heic/heif explicit mime
        List<Map<String, Object>> imageVariants = List.of(
                Map.of("type", "image", "data", PNG_B64, "mime_type", " image/jpg ", "resolution", "ultra_high"),
                Map.of("type", "image", "data", "data:image/heic;base64," + PNG_B64),
                Map.of("type", "image", "data", PNG_B64, "mime_type", "image/heif"),
                Map.of("type", "image", "uri", "files/img-1", "mime_type", "image/png", "resolution", "medium"),
                Map.of("type", "input_image", "data", PNG_B64, "resolution", "low"),
                Map.of("type", "input_image", "uri", "https://example.com/native-uri.png"),
                Map.of("type", "image_url", "image_url", "files/img-2", "detail", "high"),
                Map.of("type", "image_url", "image_url", "https://example.com/x.png", "detail", "auto"),
                Map.of(
                        "type",
                        "image_url",
                        "image_url",
                        Map.of("url", "data:image/png;base64," + PNG_B64, "detail", "high")),
                Map.of(
                        "type",
                        "image_url",
                        "image_url",
                        Map.of("url", "https://example.com/z.png"),
                        "detail",
                        "medium"),
                Map.of("type", "input_image", "image_url", "https://example.com/y.png", "resolution", "ultra_high"));
        JsonNode out = readTree(gemini.encodeRequest(new DecisionRequest(
                        "m",
                        imageVariants,
                        null,
                        Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                        Map.of(),
                        Map.of())))
                .get("input");
        assertEquals("image/jpeg", out.get(0).get("mime_type").asString());
        assertEquals("ultra_high", out.get(0).get("resolution").asString());
        assertEquals("image/heic", out.get(1).get("mime_type").asString());
        assertEquals("image/heif", out.get(2).get("mime_type").asString());
        assertEquals("files/img-1", out.get(3).get("uri").asString());
        assertEquals("image/png", out.get(3).get("mime_type").asString());
        assertEquals("low", out.get(4).get("resolution").asString());
        assertEquals("https://example.com/native-uri.png", out.get(5).get("uri").asString());
        assertEquals("files/img-2", out.get(6).get("uri").asString());
        assertEquals("high", out.get(6).get("resolution").asString());
        assertFalse(out.get(7).has("resolution"));
        assertEquals("high", out.get(8).get("resolution").asString());
        assertEquals("medium", out.get(9).get("resolution").asString());
        assertEquals("ultra_high", out.get(10).get("resolution").asString());

        // Error branches for native & cross-dialect image blocks + data URL parsing
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("type", "image"),
                Map.of("type", "image", "data", "data:image/jpeg;base64," + PNG_B64, "mime_type", "image/png"),
                Map.of("type", "image", "data", "data:image/png;base64"),
                Map.of("type", "image", "data", "data:image/png," + PNG_B64),
                Map.of("type", "image", "data", "data:image/png;base64;extra," + PNG_B64),
                Map.of("type", "image", "data", "data:;base64," + PNG_B64),
                Map.of("type", "image", "data", "https://example.com/a.png"),
                Map.of("type", "image", "data", "file:///etc/passwd"),
                Map.of("type", "image", "data", "abc"),
                Map.of("type", "image_url", "image_url", 123),
                Map.of("type", "image_url", "image_url", "http://example.com/a.png"),
                Map.of("type", "image_url", "image_url", "https://example.com/a.png", "detail", "bogus"),
                Map.of("type", "input_image", "image_url", "https://example.com/a.png", "resolution", "bogus"))) {
            DecisionRequest reqBad = new DecisionRequest(
                    "m",
                    List.of(bad),
                    null,
                    Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                    Map.of(),
                    Map.of());
            DecisionCodecException e =
                    assertThrows(DecisionCodecException.class, () -> gemini.encodeRequest(reqBad), bad.toString());
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), bad.toString());
        }
    }

    @Test
    void crossDialectFileAndDocumentBlocksCoverAllExtensionsAndErrors() throws Exception {
        String rawTextB64 = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
        Map<String, String> extToMime = new LinkedHashMap<>();
        extToMime.put("report.pdf", "application/pdf");
        extToMime.put("data.csv", "text/csv");
        extToMime.put("notes.txt", "text/plain");
        extToMime.put("readme.md", "text/markdown");
        extToMime.put("page.html", "text/html");
        extToMime.put("index.htm", "text/html");
        extToMime.put("feed.xml", "text/xml");
        extToMime.put("payload.json", "application/json");
        extToMime.put("doc.rtf", "application/rtf");
        extToMime.put("legacy.doc", "application/msword");
        extToMime.put("modern.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        extToMime.put("sheet.xls", "application/vnd.ms-excel");
        extToMime.put("sheet.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        extToMime.put("slides.ppt", "application/vnd.ms-powerpoint");
        extToMime.put("slides.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        extToMime.put("open.odt", "application/vnd.oasis.opendocument.text");
        extToMime.put("open.ods", "application/vnd.oasis.opendocument.spreadsheet");
        extToMime.put("open.odp", "application/vnd.oasis.opendocument.presentation");
        extToMime.put("UPPER.PDF", "application/pdf");

        List<Map<String, Object>> fileBlocks = new java.util.ArrayList<>();
        for (String filename : extToMime.keySet()) {
            fileBlocks.add(Map.of("type", "file", "file", Map.of("file_data", rawTextB64, "filename", filename)));
        }
        // Also cover input_file, file_url (with/without explicit mime/filename), file_id files/..., and native document
        fileBlocks.add(Map.of("type", "input_file", "file_url", "https://example.com/a.csv", "filename", "a.csv"));
        fileBlocks.add(Map.of("type", "file", "file_url", "files/doc-1", "mime_type", "text/rtf"));
        fileBlocks.add(Map.of("type", "file", "file_url", "https://example.com/noext", "filename", "noext"));
        fileBlocks.add(Map.of("type", "file", "file_id", "files/doc-2"));
        fileBlocks.add(Map.of("type", "file", "file_id", "files/doc-3", "mime_type", "application/xml"));
        fileBlocks.add(Map.of("type", "document", "data", PDF_B64));
        fileBlocks.add(Map.of("type", "document", "data", "data:text/plain;base64," + rawTextB64));
        fileBlocks.add(Map.of("type", "document", "uri", "files/doc-4"));

        JsonNode out = readTree(gemini.encodeRequest(new DecisionRequest(
                        "m",
                        fileBlocks,
                        null,
                        Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                        Map.of(),
                        Map.of())))
                .get("input");
        int idx = 0;
        for (Map.Entry<String, String> entry : extToMime.entrySet()) {
            assertEquals("document", out.get(idx).get("type").asString(), entry.getKey());
            assertEquals(entry.getValue(), out.get(idx).get("mime_type").asString(), entry.getKey());
            idx++;
        }
        assertEquals("text/csv", out.get(idx).get("mime_type").asString());
        assertEquals("text/rtf", out.get(idx + 1).get("mime_type").asString());
        assertFalse(out.get(idx + 2).has("mime_type"));
        assertEquals("files/doc-2", out.get(idx + 3).get("uri").asString());
        assertEquals("application/xml", out.get(idx + 4).get("mime_type").asString());
        assertEquals("application/pdf", out.get(idx + 5).get("mime_type").asString());
        assertEquals("text/plain", out.get(idx + 6).get("mime_type").asString());
        assertEquals("files/doc-4", out.get(idx + 7).get("uri").asString());

        // Error branches for document & file blocks
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("type", "document"),
                Map.of("type", "document", "data", PDF_B64, "uri", "files/x"),
                Map.of("type", "document", "data", rawTextB64),
                Map.of("type", "document", "data", "data:text/csv;base64," + rawTextB64, "mime_type", "text/plain"),
                Map.of("type", "file"),
                Map.of("type", "file", "file_data", rawTextB64, "file_url", "https://example.com/a.pdf"),
                Map.of("type", "file", "file_data", "   "),
                Map.of("type", "file", "file_data", 123),
                Map.of("type", "file", "file_data", "not-b64!"),
                Map.of("type", "file", "file_data", rawTextB64, "filename", "unknown.bin"),
                Map.of("type", "file", "file_data", rawTextB64, "filename", "   "),
                Map.of("type", "file", "file_data", "data:image/png;base64," + PNG_B64),
                Map.of("type", "file", "file_data", rawTextB64, "mime_type", "audio/wav"),
                Map.of("type", "file", "file_data", rawTextB64, "mime_type", "video/mp4"),
                Map.of("type", "file", "file_url", "   "),
                Map.of("type", "file", "file_url", 123),
                Map.of("type", "file", "file_id", "file-non-gemini"))) {
            DecisionRequest reqBad = new DecisionRequest(
                    "m",
                    List.of(bad),
                    null,
                    Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                    Map.of(),
                    Map.of());
            DecisionCodecException e =
                    assertThrows(DecisionCodecException.class, () -> gemini.encodeRequest(reqBad), bad.toString());
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), bad.toString());
        }
    }

    @Test
    void audioAndInputAudioBlocksCoverAllFormatsOptionsAndErrors() throws Exception {
        List<String> inputAudioFormats = List.of(
                "wav",
                "mp3",
                "mpeg",
                "aiff",
                "aac",
                "ogg",
                "flac",
                "m4a",
                "l16",
                "pcm16",
                "opus",
                "alaw",
                "mulaw",
                "webm",
                "audio/x-wav",
                "audio/x-flac",
                "audio/mp4");
        List<Map<String, Object>> audioBlocks = new java.util.ArrayList<>();
        for (String fmt : inputAudioFormats) {
            audioBlocks.add(Map.of(
                    "type",
                    "input_audio",
                    "input_audio",
                    Map.of("data", WAV_B64, "format", fmt, "channels", 2, "sample_rate", 48000)));
        }
        // Flat input_audio, outer mime_type, data: URL inside audio/input_audio, native audio URI with/without mime
        audioBlocks.add(Map.of("type", "input_audio", "data", "data:audio/ogg;base64," + WAV_B64));
        audioBlocks.add(
                Map.of("type", "input_audio", "input_audio", Map.of("data", WAV_B64), "mime_type", "audio/aac"));
        audioBlocks.add(
                Map.of("type", "input_audio", "input_audio", Map.of("data", WAV_B64, "mime_type", "audio/webm")));
        audioBlocks.add(Map.of("type", "audio", "data", "data:audio/mp3;base64," + WAV_B64));
        audioBlocks.add(Map.of("type", "audio", "uri", "files/aud-1", "mime_type", "audio/opus"));
        audioBlocks.add(Map.of("type", "audio", "uri", "https://example.com/a.wav"));

        JsonNode out = readTree(gemini.encodeRequest(new DecisionRequest(
                        "m",
                        audioBlocks,
                        null,
                        Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                        Map.of(),
                        Map.of())))
                .get("input");
        assertEquals(audioBlocks.size(), out.size());
        assertEquals("audio/wav", out.get(0).get("mime_type").asString());
        assertEquals(2, out.get(0).get("channels").asInt());
        assertEquals(48000, out.get(0).get("sample_rate").asInt());
        assertEquals("audio/l16", out.get(9).get("mime_type").asString());
        assertEquals("audio/wav", out.get(14).get("mime_type").asString());
        assertEquals("audio/flac", out.get(15).get("mime_type").asString());
        assertEquals("audio/m4a", out.get(16).get("mime_type").asString());
        assertEquals("audio/ogg", out.get(17).get("mime_type").asString());
        assertEquals("audio/aac", out.get(18).get("mime_type").asString());
        assertEquals("audio/webm", out.get(19).get("mime_type").asString());
        assertEquals("audio/mp3", out.get(20).get("mime_type").asString());
        assertEquals("files/aud-1", out.get(21).get("uri").asString());
        assertFalse(out.get(22).has("mime_type"));

        // Error branches for audio & input_audio
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("type", "audio"),
                Map.of("type", "audio", "data", WAV_B64, "uri", "files/aud-1"),
                Map.of("type", "audio", "data", WAV_B64),
                Map.of("type", "audio", "data", "data:audio/mp3;base64," + WAV_B64, "mime_type", "audio/wav"),
                Map.of("type", "audio", "data", WAV_B64, "mime_type", "audio/midi"),
                Map.of("type", "audio", "data", WAV_B64, "mime_type", "audio/wav", "channels", 0),
                Map.of("type", "audio", "data", WAV_B64, "mime_type", "audio/wav", "channels", -1),
                Map.of("type", "audio", "data", WAV_B64, "mime_type", "audio/wav", "channels", "2"),
                Map.of("type", "audio", "data", WAV_B64, "mime_type", "audio/wav", "sample_rate", 1.5),
                Map.of("type", "audio", "data", WAV_B64, "mime_type", "audio/wav", "sample_rate", 3_000_000_000L),
                Map.of("type", "input_audio", "input_audio", Map.of("data", WAV_B64, "format", "wma")),
                Map.of("type", "input_audio", "input_audio", Map.of("data", WAV_B64)))) {
            DecisionRequest reqBad = new DecisionRequest(
                    "m",
                    List.of(bad),
                    null,
                    Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                    Map.of(),
                    Map.of());
            DecisionCodecException e =
                    assertThrows(DecisionCodecException.class, () -> gemini.encodeRequest(reqBad), bad.toString());
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), bad.toString());
        }
    }

    @Test
    void videoAndVideoUrlBlocksCoverAllFormatsOptionsAndUriValidation() throws Exception {
        String rawVidB64 = Base64.getEncoder().encodeToString("ftypmp42".getBytes(StandardCharsets.US_ASCII));
        List<String> videoMimes = List.of(
                "video/mp4",
                "video/mpeg",
                "video/mpg",
                "video/mov",
                "video/quicktime",
                "video/avi",
                "video/x-msvideo",
                "video/x-flv",
                "video/webm",
                "video/wmv",
                "video/x-ms-wmv",
                "video/3gpp");
        List<Map<String, Object>> videoBlocks = new java.util.ArrayList<>();
        for (String mime : videoMimes) {
            videoBlocks.add(Map.of("type", "video", "data", rawVidB64, "mime_type", mime));
        }
        videoBlocks.add(Map.of(
                "type",
                "video",
                "data",
                "data:video/webm;base64," + rawVidB64,
                "resolution",
                "low",
                "processing",
                "static"));
        videoBlocks.add(Map.of("type", "video", "uri", "https://example.com/clip.mp4", "processing", "agentic"));
        videoBlocks.add(Map.of(
                "type",
                "video_url",
                "video_url",
                "data:video/mp4;base64," + rawVidB64,
                "resolution",
                "high",
                "processing",
                Map.of("type", "static", "start_offset", "1s", "end_offset", "5s", "fps", 1),
                "name",
                "segment"));
        videoBlocks.add(Map.of("type", "video_url", "video_url", "files/vid-1", "mime_type", "video/mp4"));

        JsonNode out = readTree(gemini.encodeRequest(new DecisionRequest(
                        "m",
                        videoBlocks,
                        null,
                        Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                        Map.of(),
                        Map.of())))
                .get("input");
        assertEquals("video/mov", out.get(4).get("mime_type").asString());
        assertEquals("video/avi", out.get(6).get("mime_type").asString());
        assertEquals("video/wmv", out.get(10).get("mime_type").asString());
        assertEquals("static", out.get(12).get("processing").asString());
        assertEquals("agentic", out.get(13).get("processing").asString());
        assertEquals("video/mp4", out.get(14).get("mime_type").asString());
        assertEquals("segment", out.get(14).get("name").asString());
        assertEquals("files/vid-1", out.get(15).get("uri").asString());

        // Error paths for video, video_url, processing, and validateGeminiUri
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("type", "video"),
                Map.of("type", "video", "data", rawVidB64, "uri", "files/vid-1"),
                Map.of("type", "video", "data", rawVidB64),
                Map.of("type", "video", "data", "data:video/webm;base64," + rawVidB64, "mime_type", "video/mp4"),
                Map.of("type", "video", "data", rawVidB64, "mime_type", "video/x-matroska"),
                Map.of("type", "video", "uri", "https://example.com/a.mp4", "processing", "fast"),
                Map.of("type", "video", "uri", "https://example.com/a.mp4", "processing", 123),
                Map.of("type", "video_url", "video_url", 123),
                Map.of("type", "video_url", "video_url", "http://example.com/a.mp4"),
                Map.of("type", "video_url", "video_url", "https://youtube.com/watch?v=1"),
                Map.of("type", "video_url", "video_url", "https://youtu.be/abc"),
                Map.of("type", "video_url", "video_url", "https://m.youtu.be/abc"),
                Map.of("type", "video", "uri", "   "),
                Map.of("type", "video", "uri", "https://example.com/a b.mp4"),
                Map.of("type", "video", "uri", "files/"),
                Map.of("type", "video", "uri", "files/a..b"),
                Map.of("type", "video", "uri", "files/a?x=1"),
                Map.of("type", "video", "uri", "files/a#frag"),
                Map.of("type", "video", "uri", "https://[::1"),
                Map.of("type", "video", "uri", "https:opaque"),
                Map.of("type", "video", "uri", "https:///path"),
                Map.of("type", "video", "uri", "https://user:pass@example.com/a.mp4"))) {
            DecisionRequest reqBad = new DecisionRequest(
                    "m",
                    List.of(bad),
                    null,
                    Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                    Map.of(),
                    Map.of());
            DecisionCodecException e =
                    assertThrows(DecisionCodecException.class, () -> gemini.encodeRequest(reqBad), bad.toString());
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), bad.toString());
        }
    }

    @Test
    void instructionsQuestionsAndResponseBranchesAreValidated() throws Exception {
        // Encode instructions variants: text map, input_text map, input_text list, non-block list, empty list, map
        // without type
        DecisionRequest instrVariants = new DecisionRequest(
                "m",
                "state",
                null,
                Map.of(
                        "q_text_map",
                        new DecisionQuestion.NoulQuestion(
                                Map.of("type", "text", "text", "Is billing?"), Map.of("true", "yes")),
                        "q_input_text_map",
                        new DecisionQuestion.NoulQuestion(
                                Map.of("type", "input_text", "text", "Is urgent?"), Map.of("false", "no")),
                        "q_input_text_list",
                        new DecisionQuestion.NoulQuestion(
                                List.of(
                                        Map.of("type", "input_text", "text", "Line 1 "),
                                        Map.of("type", "text", "text", "Line 2")),
                                Map.of()),
                        "q_scalar_list",
                        new DecisionQuestion.NoulQuestion(List.of("one", "two"), null),
                        "q_empty_list",
                        new DecisionQuestion.NoulQuestion(List.of(), null),
                        "q_typed_custom",
                        new DecisionQuestion.NoulQuestion(Map.of("type", "custom", "k", 1), null)),
                Map.of(),
                Map.of());
        JsonNode qOut = readTree(gemini.encodeRequest(instrVariants)).get("questions");
        assertEquals("Is billing?", qOut.get("q_text_map").get("instructions").asString());
        assertEquals(
                "yes",
                qOut.get("q_text_map").get("criteria").get("true_description").asString());
        assertFalse(qOut.get("q_text_map").get("criteria").has("false_description"));
        assertEquals(
                "no",
                qOut.get("q_input_text_map")
                        .get("criteria")
                        .get("false_description")
                        .asString());
        assertFalse(qOut.get("q_input_text_list").has("criteria"));
        assertEquals(
                "Line 1 Line 2",
                qOut.get("q_input_text_list").get("instructions").asString());
        assertEquals(
                "[\"one\",\"two\"]",
                qOut.get("q_scalar_list").get("instructions").asString());
        assertEquals("[]", qOut.get("q_empty_list").get("instructions").asString());
        assertTrue(qOut.get("q_typed_custom").get("instructions").asString().contains("\"custom\""));

        // Encode instruction & question rejections (256 options/levels, null criteria, invalid noul key, bad choice
        // keys)
        Map<String, Object> tooManyChoices = new LinkedHashMap<>();
        for (int i = 0; i <= GeminiDecisionsCodec.MAX_CHOICE_OPTIONS; i++) {
            tooManyChoices.put("c" + i, "opt");
        }
        List<Object> tooManyScores = new java.util.ArrayList<>();
        for (int i = 0; i <= GeminiDecisionsCodec.MAX_SCORE_LEVELS; i++) {
            tooManyScores.add("s" + i);
        }
        List<Object> nullScoreLevel = new java.util.ArrayList<>();
        nullScoreLevel.add("low");
        nullScoreLevel.add(null);

        for (DecisionQuestion badQ : new DecisionQuestion[] {
            new DecisionQuestion.NoulQuestion(Map.of("type", "audio", "data", WAV_B64), null),
            new DecisionQuestion.NoulQuestion(Map.of("type", "text", "text", "   "), null),
            new DecisionQuestion.NoulQuestion(List.of(Map.of("type", "input_text", "text", 123)), null),
            new DecisionQuestion.NoulQuestion(List.of(Map.of("type", "text", "text", "   ")), null),
            new DecisionQuestion.NoulQuestion("?", Map.of("yes", "bad")),
            new DecisionQuestion.ChoiceQuestion("?", null),
            new DecisionQuestion.ChoiceQuestion("?", tooManyChoices),
            new DecisionQuestion.ChoiceQuestion("?", Map.of("", "a", "b", "b")),
            new DecisionQuestion.ChoiceQuestion("?", Map.of("k".repeat(129), "a", "b", "b")),
            new DecisionQuestion.ChoiceQuestion("?", Map.of(" lead", "a", "b", "b")),
            new DecisionQuestion.ChoiceQuestion("?", Map.of("a\nb", "a", "b", "b")),
            new DecisionQuestion.ScoreQuestion("?", null),
            new DecisionQuestion.ScoreQuestion("?", tooManyScores),
            new DecisionQuestion.ScoreQuestion("?", nullScoreLevel)
        }) {
            DecisionCodecException e = assertThrows(
                    DecisionCodecException.class,
                    () -> gemini.encodeRequest(
                            new DecisionRequest("m", "s", null, Map.of("q", badQ), Map.of(), Map.of())));
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type());
        }

        // Decode request: instructions as object/array, noul criteria edge cases, choice/score decoding
        String richDecode = "{\"model\":\"m\",\"input\":\"s\",\"questions\":{"
                + "\"n1\":{\"type\":\"noul\",\"instructions\":{\"type\":\"text\",\"text\":\"Obj instr\"},\"criteria\":{}},"
                + "\"n2\":{\"type\":\"noul\",\"instructions\":[{\"type\":\"text\",\"text\":\"Arr \"},{\"type\":\"text\",\"text\":\"instr\"}],\"criteria\":{\"true_description\":null}},"
                + "\"c1\":{\"type\":\"choice\",\"instructions\":\"Pick\",\"criteria\":{\"a\":null,\"b\":\"B\"}},"
                + "\"s1\":{\"type\":\"score\",\"instructions\":\"Rate\",\"criteria\":[\"Low\",\"High\"]}"
                + "}}";
        DecisionRequest decodedRich = gemini.decodeRequest(richDecode);
        assertEquals("Obj instr", decodedRich.questions().get("n1").instructions());
        assertNull(((DecisionQuestion.NoulQuestion) decodedRich.questions().get("n1")).criteria());
        assertEquals("Arr instr", decodedRich.questions().get("n2").instructions());
        assertNull(((DecisionQuestion.NoulQuestion) decodedRich.questions().get("n2"))
                .criteria()
                .get("true"));
        assertNull(((DecisionQuestion.ChoiceQuestion) decodedRich.questions().get("c1"))
                .criteria()
                .get("a"));

        // Decode request errors: instructions, noul/choice/score criteria, unknown type, non-object question
        StringBuilder tooManyChoicesJson =
                new StringBuilder("{\"type\":\"choice\",\"instructions\":\"?\",\"criteria\":{");
        for (int i = 0; i <= GeminiDecisionsCodec.MAX_CHOICE_OPTIONS; i++) {
            if (i > 0) {
                tooManyChoicesJson.append(',');
            }
            tooManyChoicesJson.append("\"c").append(i).append("\":\"opt\"");
        }
        tooManyChoicesJson.append("}}");

        StringBuilder tooManyScoresJson =
                new StringBuilder("{\"type\":\"score\",\"instructions\":\"?\",\"criteria\":[");
        for (int i = 0; i <= GeminiDecisionsCodec.MAX_SCORE_LEVELS; i++) {
            if (i > 0) {
                tooManyScoresJson.append(',');
            }
            tooManyScoresJson.append("\"s").append(i).append('"');
        }
        tooManyScoresJson.append("]}");

        for (String badQJson : List.of(
                "\"not-an-object\"",
                "{\"type\":\"noul\"}",
                "{\"type\":\"noul\",\"instructions\":null}",
                "{\"type\":\"noul\",\"instructions\":\"   \"}",
                "{\"type\":\"noul\",\"instructions\":{\"type\":\"image\",\"data\":\"AAAA\"}}",
                "{\"type\":\"noul\",\"instructions\":{\"type\":\"text\",\"text\":\"   \"}}",
                "{\"type\":\"noul\",\"instructions\":[]}",
                "{\"type\":\"noul\",\"instructions\":[123]}",
                "{\"type\":\"noul\",\"instructions\":[{\"type\":\"image\",\"data\":\"AAAA\"}]}",
                "{\"type\":\"noul\",\"instructions\":[{\"type\":\"text\",\"text\":\"  \"}]}",
                "{\"type\":\"noul\",\"instructions\":123}",
                "{\"type\":\"noul\",\"instructions\":\"?\",\"criteria\":[]}",
                "{\"type\":\"noul\",\"instructions\":\"?\",\"criteria\":{\"yes\":\"y\"}}",
                "{\"type\":\"noul\",\"instructions\":\"?\",\"criteria\":{\"true_description\":123}}",
                "{\"type\":\"choice\",\"instructions\":\"?\"}",
                "{\"type\":\"choice\",\"instructions\":\"?\",\"criteria\":[]}",
                "{\"type\":\"choice\",\"instructions\":\"?\",\"criteria\":{\"only\":\"one\"}}",
                tooManyChoicesJson.toString(),
                "{\"type\":\"choice\",\"instructions\":\"?\",\"criteria\":{\"a\":123,\"b\":\"ok\"}}",
                "{\"type\":\"score\",\"instructions\":\"?\"}",
                "{\"type\":\"score\",\"instructions\":\"?\",\"criteria\":{}}",
                "{\"type\":\"score\",\"instructions\":\"?\",\"criteria\":[\"only\"]}",
                tooManyScoresJson.toString(),
                "{\"type\":\"score\",\"instructions\":\"?\",\"criteria\":[\"low\",123]}",
                "{\"type\":\"score\",\"instructions\":\"?\",\"criteria\":[\"low\",\"  \"]}",
                "{\"type\":\"bogus\",\"instructions\":\"?\"}")) {
            DecisionCodecException e = assertThrows(
                    DecisionCodecException.class,
                    () -> gemini.decodeRequest(
                            "{\"model\":\"m\",\"input\":\"s\",\"questions\":{\"q\":" + badQJson + "}}"),
                    badQJson);
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), badQJson);
        }

        // Response encode & decode edge cases
        DecisionResponse withoutUsageOrExtras = new DecisionResponse(
                null,
                Map.of(
                        "c_noconf", new DecisionAnswer.ChoiceAnswer("a", Map.of("a", 0.7, "b", 0.3), null),
                        "s_noleg", new DecisionAnswer.ScoreAnswer(1.0, null, Map.of("0", 0.4, "1", 0.6), null)),
                null,
                Map.of(),
                Map.of());
        String encodedNoUsage = gemini.encodeResponse(withoutUsageOrExtras);
        DecisionResponse decodedNoUsage = gemini.decodeResponse(encodedNoUsage);
        assertNull(decodedNoUsage.usage());
        assertNull(((DecisionAnswer.ChoiceAnswer) decodedNoUsage.answers().get("c_noconf")).confidence());
        assertNull(((DecisionAnswer.ScoreAnswer) decodedNoUsage.answers().get("s_noleg")).legend());
        assertNull(((DecisionAnswer.ScoreAnswer) decodedNoUsage.answers().get("s_noleg")).confidence());

        // Decode response with total_output_tokens omitted & total_tokens omitted, plus noul confidence
        DecisionResponse computedTotal = gemini.decodeResponse(
                "{\"answers\":{\"n\":{\"type\":\"noul\",\"noul\":0.8,\"confidence\":0.8}},\"usage\":{\"total_input_tokens\":42}}");
        assertEquals(42L, computedTotal.usage().totalTokens());
        DecisionResponse withOutputSum = gemini.decodeResponse(
                "{\"answers\":{},\"usage\":{\"total_input_tokens\":40,\"total_output_tokens\":2}}");
        assertEquals(42L, withOutputSum.usage().totalTokens());

        // Encode response error paths
        Map<String, DecisionAnswer> blankKeyMap = new LinkedHashMap<>();
        blankKeyMap.put("  ", new DecisionAnswer.RefusalAnswer());
        Map<String, DecisionAnswer> nullValMap = new LinkedHashMap<>();
        nullValMap.put("q", null);
        for (DecisionResponse badResp : new DecisionResponse[] {
            null,
            new DecisionResponse(null, blankKeyMap, null, Map.of(), Map.of()),
            new DecisionResponse(null, nullValMap, null, Map.of(), Map.of()),
            new DecisionResponse(
                    null, Map.of("q", new DecisionAnswer.ChoiceAnswer("a", null, null)), null, Map.of(), Map.of()),
            new DecisionResponse(
                    null, Map.of("q", new DecisionAnswer.ChoiceAnswer("a", Map.of(), null)), null, Map.of(), Map.of()),
            new DecisionResponse(
                    null,
                    Map.of("q", new DecisionAnswer.ChoiceAnswer("a", Map.of("a", 0.5), 1.5)),
                    null,
                    Map.of(),
                    Map.of()),
            new DecisionResponse(
                    null, Map.of("q", new DecisionAnswer.ScoreAnswer(1.0, null, null, null)), null, Map.of(), Map.of()),
            new DecisionResponse(
                    null,
                    Map.of("q", new DecisionAnswer.ScoreAnswer(1.0, null, Map.of(), null)),
                    null,
                    Map.of(),
                    Map.of()),
            new DecisionResponse(
                    null,
                    Map.of("q", new DecisionAnswer.ScoreAnswer(1.0, null, Map.of("0", 0.5), -0.1)),
                    null,
                    Map.of(),
                    Map.of()),
            new DecisionResponse(null, Map.of("q", new DecisionAnswer.NoulAnswer(Double.NaN)), null, Map.of(), Map.of())
        }) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> gemini.encodeResponse(badResp));
            assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type());
        }

        // Encode response with null answers map produces empty answers object
        JsonNode nullAnswersEncoded =
                readTree(gemini.encodeResponse(new DecisionResponse(null, null, null, Map.of(), Map.of())));
        assertTrue(nullAnswersEncoded.get("answers").isObject());
        assertEquals(0, nullAnswersEncoded.get("answers").size());

        // Decode response error paths + usage null/non-object & score null legend
        DecisionResponse nullUsageNullLegend = gemini.decodeResponse(
                "{\"answers\":{\"s\":{\"type\":\"score\",\"score\":1.0,\"probabilities\":{\"0\":1.0},\"legend\":null}},\"usage\":[]}");
        assertNull(nullUsageNullLegend.usage());
        assertNull(((DecisionAnswer.ScoreAnswer) nullUsageNullLegend.answers().get("s")).legend());

        for (String badRespJson : List.of(
                "{\"answers\":[]}",
                "{\"answers\":{\" \":{\"type\":\"refusal\"}}}",
                "{\"answers\":{\"q\":123}}",
                "{\"answers\":{\"q\":null}}",
                "{\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.5,\"confidence\":1.5}}}",
                "{\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"a\"}}}",
                "{\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":null}}}",
                "{\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{}}}}",
                "{\"answers\":{\"q\":{\"type\":\"score\",\"score\":1.0}}}",
                "{\"answers\":{\"q\":{\"type\":\"score\",\"score\":1.0,\"probabilities\":null}}}",
                "{\"answers\":{\"q\":{\"type\":\"score\",\"score\":1.0,\"probabilities\":{}}}}",
                "{\"answers\":{\"q\":{\"type\":\"score\",\"score\":1.0,\"probabilities\":{\"0\":1.0},\"legend\":[]}}}")) {
            DecisionCodecException e =
                    assertThrows(DecisionCodecException.class, () -> gemini.decodeResponse(badRespJson), badRespJson);
            assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type(), badRespJson);
        }
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
