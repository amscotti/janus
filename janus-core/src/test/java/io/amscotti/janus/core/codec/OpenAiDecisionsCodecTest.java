package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * OpenAI Decisions translation leg: canonical (Jev-shaped) ⇄ {@code POST
 * /v1/decisions} wire, replayed against the hand-authored documented-shape
 * fixtures in {@code fixtures/systemone/}.
 */
class OpenAiDecisionsCodecTest {

    private static final String ROOT = "/fixtures/systemone";

    private final SystemOneCodec systemOne = new SystemOneCodec(JsonSupport.mapper());
    private final OpenAiDecisionsCodec openAi = new OpenAiDecisionsCodec(JsonSupport.mapper());

    @Test
    void canonicalRequestEncodesToTheOpenAiDecisionsFixture() throws Exception {
        DecisionRequest canonical = systemOne.decodeRequest(read("decisions.request.json"));
        DecisionRequest forOpenAi = new DecisionRequest(
                "gpt-6-luna",
                canonical.state(),
                canonical.images(),
                canonical.questions(),
                canonical.extras(),
                canonical.meta());
        String encoded = openAi.encodeRequest(forOpenAi);
        assertTrue(JsonSupport.treeEquals(readTree(read("openai.decisions.request.json")), readTree(encoded)), encoded);
    }

    @Test
    void openAiRequestDecodesBackToCanonical() throws Exception {
        DecisionRequest canonical = openAi.decodeRequest(read("openai.decisions.request.json"));
        assertEquals("gpt-6-luna", canonical.model());
        assertEquals(
                List.of("is_billing", "team", "urgency"),
                List.copyOf(canonical.questions().keySet()));
        assertInstanceOf(
                DecisionQuestion.NoulQuestion.class, canonical.questions().get("is_billing"));
        DecisionQuestion.ChoiceQuestion team = assertInstanceOf(
                DecisionQuestion.ChoiceQuestion.class, canonical.questions().get("team"));
        assertEquals("App bugs", team.criteria().get("technical"));
        DecisionQuestion.ScoreQuestion urgency = assertInstanceOf(
                DecisionQuestion.ScoreQuestion.class, canonical.questions().get("urgency"));
        assertEquals(List.of("Can wait", "This week", "Today"), urgency.criteria());
    }

    @Test
    void requestExtrasRideToTheOpenAiLegBothWays() throws Exception {
        // Every other leg round-trips top-level extras; the OpenAI leg must too
        // (temperature/user/… must not vanish between decode and encode).
        DecisionRequest canonical = new DecisionRequest(
                "gpt-6-luna",
                "hello",
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of("temperature", 0.2, "user", "u1"),
                Map.of());
        JsonNode encoded = readTree(openAi.encodeRequest(canonical));
        assertEquals(0.2, encoded.get("temperature").asDouble());
        assertEquals("u1", encoded.get("user").asString());

        DecisionRequest decoded = openAi.decodeRequest(
                "{\"model\":\"m\",\"input\":\"hi\",\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}],\"temperature\":0.5,\"user\":\"u2\"}");
        assertEquals(0.5, decoded.extras().get("temperature"));
        assertEquals("u2", decoded.extras().get("user"));
        assertTrue(readTree(openAi.encodeRequest(decoded)).has("user"));
    }

    @Test
    void documentedCapsAreTypedClientErrors() {
        // The caps are documented constants (docs/system-one.md) — pin them so a
        // constant change or a deleted check fails here, not silently in prod.
        Map<String, DecisionQuestion> tooMany = new java.util.LinkedHashMap<>();
        for (int i = 0; i <= OpenAiDecisionsCodec.MAX_QUESTIONS; i++) {
            tooMany.put("q" + i, new DecisionQuestion.NoulQuestion("?", null));
        }
        DecisionCodecException questions = assertThrows(
                DecisionCodecException.class,
                () -> openAi.encodeRequest(new DecisionRequest("m", "s", null, tooMany, Map.of(), Map.of())));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, questions.type());
        assertTrue(questions.getMessage().contains(String.valueOf(OpenAiDecisionsCodec.MAX_QUESTIONS)));

        Map<String, Object> hugeState = Map.of("blob", "x".repeat(OpenAiDecisionsCodec.MAX_INPUT_JSON_BYTES));
        DecisionCodecException bytes = assertThrows(
                DecisionCodecException.class,
                () -> openAi.encodeRequest(new DecisionRequest(
                        "m",
                        hugeState,
                        null,
                        Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                        Map.of(),
                        Map.of())));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, bytes.type());
        assertTrue(bytes.getMessage().contains(String.valueOf(OpenAiDecisionsCodec.MAX_INPUT_JSON_BYTES)));

        List<Object> levels = new java.util.ArrayList<>(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"));
        levels.add("k");
        DecisionCodecException levelCount = assertThrows(
                DecisionCodecException.class,
                () -> openAi.encodeRequest(new DecisionRequest(
                        "m",
                        "s",
                        null,
                        Map.of("q", new DecisionQuestion.ScoreQuestion("?", levels)),
                        Map.of(),
                        Map.of())));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, levelCount.type());
        assertTrue(levelCount.getMessage().contains("2–10"));
    }

    @Test
    void nonIntegralScoreLevelValuesAreTypedApiErrors() {
        // A stringified or fractional level index must never silently collapse
        // to level 0 (two levels merging, one label lost, score corrupted).
        for (String badValue : List.of("\"2\"", "2.5")) {
            DecisionCodecException e = assertThrows(
                    DecisionCodecException.class,
                    () -> openAi.decodeResponse(
                            "{\"answers\":[{\"type\":\"score\",\"name\":\"q\",\"score\":1.0,\"probabilities\":["
                                    + "{\"label\":\"a\",\"value\":" + badValue + ",\"probability\":0.5},"
                                    + "{\"label\":\"b\",\"value\":1,\"probability\":0.5}]}]}"),
                    badValue);
            assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type(), badValue);
        }
        // Integral doubles are fine (2.0 is level 2).
        DecisionResponse integral = openAi.decodeResponse(
                "{\"answers\":[{\"type\":\"score\",\"name\":\"q\",\"score\":2.0,\"probabilities\":["
                        + "{\"label\":\"a\",\"value\":2.0,\"probability\":1.0}]}]}");
        DecisionAnswer.ScoreAnswer score = assertInstanceOf(
                DecisionAnswer.ScoreAnswer.class, integral.answers().get("q"));
        assertTrue(score.probabilities().containsKey("2"));
    }

    @Test
    void duplicateAnswerNamesAreTypedApiErrors() {
        DecisionCodecException e = assertThrows(
                DecisionCodecException.class,
                () -> openAi.decodeResponse("{\"answers\":["
                        + "{\"type\":\"predicate\",\"name\":\"q\",\"probability\":0.9},"
                        + "{\"type\":\"predicate\",\"name\":\"q\",\"probability\":0.1}]}"));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type());
    }

    @Test
    void inputWithNoTextOrJsonIsATypedClientError() {
        for (String bad : List.of(
                "{\"model\":\"m\",\"input\":[],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":[{\"role\":\"user\"}],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":[\"hi\"],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":[{\"content\":[{\"type\":\"input_json\"}]}],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}")) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> openAi.decodeRequest(bad), bad);
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), bad);
        }
    }

    @Test
    void imageOnlyInputKeepsAnEmptyStateAndMultipleTextsJoinWithNewlines() throws Exception {
        DecisionRequest imageOnly = openAi.decodeRequest(
                "{\"model\":\"m\",\"input\":[{\"content\":[{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,aGVsbG8=\"}]}],"
                        + "\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}");
        assertEquals("", imageOnly.state());
        assertEquals(List.of("aGVsbG8="), imageOnly.images());

        DecisionRequest joined = openAi.decodeRequest(
                "{\"model\":\"m\",\"input\":[{\"content\":\"hello\"},{\"content\":[{\"type\":\"input_text\",\"text\":\"world\"}]}],"
                        + "\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}");
        assertEquals("hello\nworld", joined.state());
    }

    @Test
    void blankOrNonStringImageUrlsAreTypedClientErrors() {
        for (String bad : List.of(
                "{\"model\":\"m\",\"input\":[{\"content\":[{\"type\":\"input_image\",\"image_url\":\"\"}]}],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":[{\"content\":[{\"type\":\"input_image\",\"image_url\":{\"url\":\"x\"}}]}],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}")) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> openAi.decodeRequest(bad), bad);
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), bad);
        }
        DecisionRequest canonical = new DecisionRequest(
                "m", "s", List.of(""), Map.of("q", new DecisionQuestion.NoulQuestion("?", null)), Map.of(), Map.of());
        DecisionCodecException encode =
                assertThrows(DecisionCodecException.class, () -> openAi.encodeRequest(canonical));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, encode.type());
    }

    @Test
    void nonNumericScoreKeysAreTypedApiErrorsOnEncode() {
        // A SystemOne-leg canonical keys score probabilities by label; the OpenAI
        // leg must fail loudly (typed 500) instead of a raw NumberFormatException.
        DecisionResponse canonical = new DecisionResponse(
                "m",
                Map.of(
                        "q",
                        new DecisionAnswer.ScoreAnswer(
                                1.2, Map.of("low", "L", "high", "H"), Map.of("low", 0.2, "high", 0.8), null)),
                null,
                Map.of(),
                Map.of());
        DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> openAi.encodeResponse(canonical));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type());
    }

    @Test
    void openAiResponseDecodesAnswersUsageAndRefusal() throws Exception {
        DecisionResponse response = openAi.decodeResponse(read("openai.decisions.response.json"));
        DecisionAnswer.NoulAnswer billing = assertInstanceOf(
                DecisionAnswer.NoulAnswer.class, response.answers().get("is_billing"));
        assertEquals(0.87, billing.noul());
        DecisionAnswer.ChoiceAnswer team = assertInstanceOf(
                DecisionAnswer.ChoiceAnswer.class, response.answers().get("team"));
        assertEquals("technical", team.choice());
        assertEquals(0.77, team.probabilities().get("technical"));
        DecisionAnswer.ScoreAnswer urgency = assertInstanceOf(
                DecisionAnswer.ScoreAnswer.class, response.answers().get("urgency"));
        assertEquals(1.4, urgency.score());
        assertEquals("Today", urgency.legend().get("2"));
        assertEquals(0.5, urgency.probabilities().get("2"));
        assertInstanceOf(DecisionAnswer.RefusalAnswer.class, response.answers().get("policy_ok"));
        // OpenAI input_tokens includes the cache splits; canonical promptTokens
        // is regular-only (500 − 100 read − 50 write — the Usage convention).
        assertEquals(350, response.usage().promptTokens());
        assertEquals(100L, response.usage().cacheReadInputTokens());
        assertEquals(50L, response.usage().cacheCreationInputTokens());
        assertEquals(12, response.usage().completionTokens());
        assertEquals(4L, response.usage().reasoningTokens());
    }

    @Test
    void stringStateUsesBareInputAndImagesBecomeDataUrls() throws Exception {
        // PNG magic bytes ("iVBORw0KGgo" is the base64 of the PNG signature).
        DecisionRequest request = new DecisionRequest(
                "gpt-6-luna",
                "Inspect this photo.",
                List.of("iVBORw0KGgoAAAANSUhEUg=="),
                Map.of("damaged", new DecisionQuestion.NoulQuestion("Is it damaged?", null)),
                Map.of(),
                Map.of());
        JsonNode encoded = readTree(openAi.encodeRequest(request));
        JsonNode content = encoded.get("input").get(0).get("content");
        assertEquals("input_text", content.get(0).get("type").asString());
        assertEquals("input_image", content.get(1).get("type").asString());
        assertTrue(content.get(1).get("image_url").asString().startsWith("data:image/png;base64,"));
    }

    @Test
    void malformedAnswerObjectsAreTypedApiErrorsNotFabricatedDecisions() {
        for (String bad : List.of(
                "{\"answers\":[{\"type\":\"choice\",\"name\":\"q\",\"choice\":\"a\"}]}",
                "{\"answers\":[{\"type\":\"choice\",\"name\":\"q\",\"choice\":\"a\",\"probabilities\":[]}]}",
                "{\"answers\":[{\"type\":\"score\",\"name\":\"q\"}]}",
                "{\"answers\":[{\"type\":\"score\",\"name\":\"q\",\"probabilities\":[]}]}",
                "{\"answers\":[{\"type\":\"score\",\"name\":\"q\",\"score\":\"9\",\"probabilities\":[{\"value\":0,\"probability\":1}]}]}",
                "{\"answers\":[{\"type\":\"predicate\",\"name\":\"q\",\"probability\":1e999}]}")) {
            DecisionCodecException e =
                    assertThrows(DecisionCodecException.class, () -> openAi.decodeResponse(bad), bad);
            assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type(), bad);
        }
    }

    @Test
    void structuredEntriesFoldToTextOnTheOpenAiLeg() throws Exception {
        java.util.Map<String, Object> criteria = new java.util.LinkedHashMap<>();
        criteria.put("a", Map.of("desc", "A"));
        criteria.put("b", "B");
        DecisionQuestion.ChoiceQuestion choice = new DecisionQuestion.ChoiceQuestion(Map.of("task", "route"), criteria);
        String json = openAi.encodeRequest(
                new DecisionRequest("gpt-6-luna", "s", null, Map.of("q", choice), Map.of(), Map.of()));
        JsonNode encoded = readTree(json);
        assertTrue(
                encoded.get("questions").get(0).get("instructions").asString().contains("\"task\":\"route\""));
        assertTrue(encoded.get("questions")
                .get(0)
                .get("choices")
                .get(0)
                .get("description")
                .asString()
                .contains("\"desc\":\"A\""));
    }

    @Test
    void scalarArrayStateBecomesTextInsteadOfInvalidInputJson() throws Exception {
        DecisionRequest request = new DecisionRequest(
                "gpt-6-luna",
                List.of("line one", "line two"),
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of());
        JsonNode encoded = readTree(openAi.encodeRequest(request));
        JsonNode part = encoded.get("input").get(0).get("content").get(0);
        assertEquals("input_text", part.get("type").asString());
        assertEquals("line one\nline two", part.get("text").asString());
    }

    @Test
    void objectArrayStateUsesInputJson() throws Exception {
        DecisionRequest request = new DecisionRequest(
                "gpt-6-luna",
                List.of(Map.of("a", 1), Map.of("b", 2)),
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of());
        JsonNode encoded = readTree(openAi.encodeRequest(request));
        assertEquals(
                "input_json",
                encoded.get("input").get(0).get("content").get(0).get("type").asString());
    }

    @Test
    void duplicateChoiceValuesAndBlankNamesAreTypedClientErrors() {
        DecisionCodecException duplicate = assertThrows(
                DecisionCodecException.class,
                () -> openAi.decodeRequest("{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"choice\","
                        + "\"name\":\"q\",\"instructions\":\"?\",\"choices\":["
                        + "{\"value\":\"a\"},{\"value\":\"a\"}]}]}"));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, duplicate.type());
        DecisionCodecException blank = assertThrows(
                DecisionCodecException.class,
                () -> openAi.decodeRequest("{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"predicate\","
                        + "\"name\":\"\",\"instructions\":\"?\"}]}"));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, blank.type());
    }

    @Test
    void exactQuestionAndLevelCapsAreAcceptedAndOneMoreIsRejected() throws Exception {
        Map<String, DecisionQuestion> questions = new java.util.LinkedHashMap<>();
        for (int i = 0; i < OpenAiDecisionsCodec.MAX_QUESTIONS; i++) {
            questions.put("q" + i, new DecisionQuestion.NoulQuestion("?", null));
        }
        assertDoesNotThrow(
                () -> openAi.encodeRequest(new DecisionRequest("m", "s", null, questions, Map.of(), Map.of())));
        questions.put("one-too-many", new DecisionQuestion.NoulQuestion("?", null));
        DecisionCodecException tooMany = assertThrows(
                DecisionCodecException.class,
                () -> openAi.encodeRequest(new DecisionRequest("m", "s", null, questions, Map.of(), Map.of())));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, tooMany.type());

        assertDoesNotThrow(() -> openAi.encodeRequest(new DecisionRequest(
                "m",
                "s",
                null,
                Map.of("q", new DecisionQuestion.ScoreQuestion("?", List.of("a", "b"))),
                Map.of(),
                Map.of())));
        assertDoesNotThrow(() -> openAi.encodeRequest(new DecisionRequest(
                "m",
                "s",
                null,
                Map.of(
                        "q",
                        new DecisionQuestion.ScoreQuestion(
                                "?", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"))),
                Map.of(),
                Map.of())));
    }

    @Test
    void openAiUsagePreservesTheWireTotalAndRejectsOverflow() throws Exception {
        DecisionResponse response = openAi.decodeResponse(
                "{\"answers\":[],\"usage\":{\"input_tokens\":100,\"output_tokens\":10,\"total_tokens\":999}}");
        assertEquals(999, response.usage().totalTokens());
        DecisionCodecException overflow = assertThrows(
                DecisionCodecException.class,
                () -> openAi.decodeResponse(
                        "{\"answers\":[],\"usage\":{\"input_tokens\":9223372036854775807,\"output_tokens\":1}}"));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, overflow.type());
    }

    @Test
    void assetsAndBoxQuestionsAreTypeSafeOnlyCapabilityFailures() {
        DecisionRequest withAssets = new DecisionRequest(
                "gpt-6-luna",
                "s",
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of("assets", Map.of("p1", Map.of("type", "image"))),
                Map.of());
        DecisionCodecException assets =
                assertThrows(DecisionCodecException.class, () -> openAi.encodeRequest(withAssets));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, assets.type());

        DecisionRequest withBox = new DecisionRequest(
                "gpt-6-luna",
                "s",
                null,
                Map.of("q", new DecisionQuestion.BoxQuestion("Find the mug.")),
                Map.of(),
                Map.of());
        DecisionCodecException box = assertThrows(DecisionCodecException.class, () -> openAi.encodeRequest(withBox));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, box.type());
    }

    @Test
    void openAiQuestionLevelImagePartsPassThrough() throws Exception {
        DecisionRequest request = new DecisionRequest(
                "gpt-6-luna",
                "shared evidence",
                null,
                Map.of(
                        "compare",
                        new DecisionQuestion.NoulQuestion(
                                List.of(
                                        Map.of("type", "input_text", "text", "Compare the candidate."),
                                        Map.of("type", "input_image", "image_url", "iVBORw0KGgo=")),
                                null)),
                Map.of(),
                Map.of());
        JsonNode encoded = readTree(openAi.encodeRequest(request));
        JsonNode instructions = encoded.get("questions").get(0).get("instructions");
        assertTrue(instructions.isArray(), instructions.toString());
        assertEquals("input_text", instructions.get(0).get("type").asString());
        assertEquals("input_image", instructions.get(1).get("type").asString());
        assertTrue(instructions.get(1).get("image_url").asString().startsWith("data:image/png;base64,"));
    }

    @Test
    void usageCacheSplitGreaterThanInputIsATypedApiError() {
        DecisionCodecException e = assertThrows(
                DecisionCodecException.class,
                () -> openAi.decodeResponse(
                        "{\"answers\":[],\"usage\":{\"input_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":20}}}"));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type());
        assertTrue(e.getMessage().contains("cache tokens exceed input_tokens"), e.getMessage());
    }

    @Test
    void encodeResponseNullIsATypedApiError() {
        DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> openAi.encodeResponse(null));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type());
    }

    @Test
    void imageSniffingCoversJpegAndWebp() throws Exception {
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        JsonNode encoded = readTree(openAi.encodeRequest(new DecisionRequest(
                "m",
                "state",
                List.of(Base64.getEncoder().encodeToString(jpeg)),
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of())));
        assertTrue(encoded.get("input")
                .get(0)
                .get("content")
                .get(1)
                .get("image_url")
                .asString()
                .startsWith("data:image/jpeg;base64,"));

        byte[] webp = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0};
        JsonNode webpEncoded = readTree(openAi.encodeRequest(new DecisionRequest(
                "m",
                "state",
                List.of(Base64.getEncoder().encodeToString(webp)),
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of())));
        assertTrue(webpEncoded
                .get("input")
                .get(0)
                .get("content")
                .get(1)
                .get("image_url")
                .asString()
                .startsWith("data:image/webp;base64,"));
    }

    @Test
    void choiceCapsAreExactAt255() {
        Map<String, Object> criteria = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 255; i++) {
            criteria.put("c" + i, "option " + i);
        }
        assertDoesNotThrow(() -> openAi.encodeRequest(new DecisionRequest(
                "m", "s", null, Map.of("q", new DecisionQuestion.ChoiceQuestion("?", criteria)), Map.of(), Map.of())));
        criteria.put("c255", "one too many");
        DecisionCodecException tooMany = assertThrows(
                DecisionCodecException.class,
                () -> openAi.encodeRequest(new DecisionRequest(
                        "m",
                        "s",
                        null,
                        Map.of("q", new DecisionQuestion.ChoiceQuestion("?", criteria)),
                        Map.of(),
                        Map.of())));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, tooMany.type());
    }

    @Test
    void oversizedPlainStringStateIsATypedClientError() {
        String huge = "x".repeat(OpenAiDecisionsCodec.MAX_INPUT_STRING_BYTES + 1);
        DecisionCodecException e = assertThrows(
                DecisionCodecException.class,
                () -> openAi.encodeRequest(new DecisionRequest(
                        "m",
                        huge,
                        null,
                        Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                        Map.of(),
                        Map.of())));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type());
        assertTrue(e.getMessage().contains("input limit"), e.getMessage());
    }

    private static JsonNode readTree(String json) throws IOException {
        return JsonSupport.mapper().readTree(json);
    }

    private static String read(String relativePath) throws IOException {
        try (InputStream in = OpenAiDecisionsCodecTest.class.getResourceAsStream(ROOT + "/" + relativePath)) {
            assertNotNull(in, ROOT + "/" + relativePath + " missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
