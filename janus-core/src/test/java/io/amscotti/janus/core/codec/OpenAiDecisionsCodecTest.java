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

    @Test
    void decodeUsageRejectsLongOverflowOnCacheSplit() {
        DecisionCodecException e = assertThrows(
                DecisionCodecException.class,
                () -> openAi.decodeResponse(
                        "{\"answers\":[],\"usage\":{\"input_tokens\":9223372036854775807,"
                                + "\"input_tokens_details\":{\"cached_tokens\":9223372036854775807,\"cache_write_tokens\":1}}}"));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type());
    }

    @Test
    void scoreAnswerRejectsDuplicateOrNegativeIndices() {
        DecisionCodecException duplicate = assertThrows(
                DecisionCodecException.class,
                () -> openAi.decodeResponse(
                        "{\"answers\":[{\"type\":\"score\",\"name\":\"q\",\"score\":1.0,\"probabilities\":["
                                + "{\"label\":\"a\",\"value\":0,\"probability\":0.5},"
                                + "{\"label\":\"b\",\"value\":0,\"probability\":0.5}]}]}"));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, duplicate.type());

        DecisionCodecException negative = assertThrows(
                DecisionCodecException.class,
                () -> openAi.decodeResponse(
                        "{\"answers\":[{\"type\":\"score\",\"name\":\"q\",\"score\":0.0,\"probabilities\":["
                                + "{\"label\":\"a\",\"value\":-1,\"probability\":0.5},"
                                + "{\"label\":\"b\",\"value\":0,\"probability\":0.5}]}]}"));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, negative.type());
    }

    @Test
    void decodeInputRejectsConflictingTextAndJsonOrMultipleJsonParts() {
        DecisionCodecException mixed = assertThrows(
                DecisionCodecException.class,
                () -> openAi.decodeRequest("{\"model\":\"m\",\"input\":[{\"content\":["
                        + "{\"type\":\"input_text\",\"text\":\"hi\"},"
                        + "{\"type\":\"input_json\",\"data\":{\"a\":1}}]}],"
                        + "\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}"));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, mixed.type());

        DecisionCodecException multipleJson = assertThrows(
                DecisionCodecException.class,
                () -> openAi.decodeRequest("{\"model\":\"m\",\"input\":[{\"content\":["
                        + "{\"type\":\"input_json\",\"data\":{\"a\":1}},"
                        + "{\"type\":\"input_json\",\"data\":{\"b\":2}}]}],"
                        + "\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}"));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, multipleJson.type());
    }

    @Test
    void decodeAndEncodeRejectNonBase64Images() {
        for (String bad : List.of(
                "not-base64!",
                "data:image/png;base64,",
                "data:image/png;base64,not-base64!",
                "file:///etc/passwd",
                "https://example.com/a.png")) {
            DecisionCodecException decoded = assertThrows(
                    DecisionCodecException.class,
                    () -> openAi.decodeRequest("{\"model\":\"m\",\"input\":[{\"content\":["
                            + "{\"type\":\"input_image\",\"image_url\":\"" + bad + "\"}]}],"
                            + "\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}"),
                    bad);
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, decoded.type(), bad);
        }
        DecisionRequest nonBase64 = new DecisionRequest(
                "m",
                "s",
                List.of("https://example.com/a.png"),
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of());
        DecisionCodecException encoded =
                assertThrows(DecisionCodecException.class, () -> openAi.encodeRequest(nonBase64));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, encoded.type());
    }

    @Test
    void unsupportedMultiFormatMediaBlocksAreRejectedInsteadOfPassedAsInputJson() {
        for (Map<String, Object> block : List.<Map<String, Object>>of(
                Map.of("type", "document", "data", "JVBERi0xLjQK", "mime_type", "application/pdf"),
                Map.of("type", "audio", "data", "UklGRg==", "mime_type", "audio/wav"),
                Map.of("type", "video", "uri", "https://example.com/clip.mp4", "mime_type", "video/mp4"),
                Map.of(
                        "type",
                        "file",
                        "file",
                        Map.of("file_data", "data:application/pdf;base64,JVBERi0=", "filename", "a.pdf")),
                Map.of("type", "input_audio", "input_audio", Map.of("data", "UklGRg==", "format", "wav")),
                Map.of("type", "video_url", "video_url", Map.of("url", "https://example.com/clip.mp4")))) {
            DecisionRequest listReq = new DecisionRequest(
                    "gpt-6-luna",
                    List.of(Map.of("type", "text", "text", "Analyze"), block),
                    null,
                    Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                    Map.of(),
                    Map.of());
            DecisionCodecException listErr =
                    assertThrows(DecisionCodecException.class, () -> openAi.encodeRequest(listReq), block.toString());
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, listErr.type(), block.toString());

            DecisionRequest singleReq = new DecisionRequest(
                    "gpt-6-luna",
                    block,
                    null,
                    Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                    Map.of(),
                    Map.of());
            DecisionCodecException singleErr =
                    assertThrows(DecisionCodecException.class, () -> openAi.encodeRequest(singleReq), block.toString());
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, singleErr.type(), block.toString());
        }
    }

    @Test
    void createFactoryUsesStandardStrictMapper() throws Exception {
        OpenAiDecisionsCodec created = OpenAiDecisionsCodec.create();
        DecisionRequest req = new DecisionRequest(
                "gpt-6-luna",
                "hello",
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of());
        assertEquals(req, created.decodeRequest(created.encodeRequest(req)));

        DecisionCodecException dup = assertThrows(
                DecisionCodecException.class,
                () -> created.decodeRequest("{\"model\":\"a\",\"model\":\"b\",\"input\":\"s\","
                        + "\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}"));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, dup.type());
    }

    @Test
    void encodeRequestEdgeLimitsAndQuestionCriteriaBranches() throws Exception {
        // Depth 32 accepted; depth 33 rejected
        Map<String, Object> depth32 = Map.of("leaf", 1);
        for (int i = 2; i < OpenAiDecisionsCodec.MAX_INPUT_JSON_DEPTH; i++) {
            depth32 = Map.of("n", depth32);
        }
        Map<String, Object> validDepthState = depth32;
        assertDoesNotThrow(() -> openAi.encodeRequest(new DecisionRequest(
                "m",
                validDepthState,
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of())));
        Map<String, Object> tooDeep = Map.of("n", depth32);
        DecisionCodecException depthErr = assertThrows(
                DecisionCodecException.class,
                () -> openAi.encodeRequest(new DecisionRequest(
                        "m",
                        tooDeep,
                        null,
                        Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                        Map.of(),
                        Map.of())));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, depthErr.type());

        // > MAX_INPUT_JSON_VALUES (10,000) under 256 KiB rejected
        List<Integer> values10000 = new java.util.ArrayList<>(OpenAiDecisionsCodec.MAX_INPUT_JSON_VALUES);
        for (int i = 0; i < OpenAiDecisionsCodec.MAX_INPUT_JSON_VALUES; i++) {
            values10000.add(1);
        }
        Map<String, Object> tooManyValues = Map.of("arr", values10000);
        DecisionCodecException valuesErr = assertThrows(
                DecisionCodecException.class,
                () -> openAi.encodeRequest(new DecisionRequest(
                        "m",
                        tooManyValues,
                        null,
                        Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                        Map.of(),
                        Map.of())));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, valuesErr.type());

        // Empty list state -> input_json []; mixed scalar list -> input_text; empty images list -> string input
        JsonNode emptyListJson = readTree(openAi.encodeRequest(new DecisionRequest(
                "m",
                List.of(),
                List.of(),
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of())));
        assertEquals(
                "input_json",
                emptyListJson
                        .get("input")
                        .get(0)
                        .get("content")
                        .get(0)
                        .get("type")
                        .asString());

        JsonNode mixedListJson = readTree(openAi.encodeRequest(new DecisionRequest(
                "m",
                List.of("line", 42, true, Map.of("k", "v")),
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of())));
        assertEquals(
                "line\n42\ntrue\n{\"k\":\"v\"}",
                mixedListJson
                        .get("input")
                        .get(0)
                        .get("content")
                        .get(0)
                        .get("text")
                        .asString());

        // Noul one-sided & empty criteria, Choice null description, openAi part instructions with URL/data/non-image
        Map<String, Object> choiceCriteriaWithNull = new java.util.LinkedHashMap<>();
        choiceCriteriaWithNull.put("yes", null);
        choiceCriteriaWithNull.put("no", "No");
        DecisionRequest questionVariants = new DecisionRequest(
                "m",
                "state",
                null,
                Map.of(
                        "n_empty", new DecisionQuestion.NoulQuestion("Empty?", Map.of()),
                        "n_true_only", new DecisionQuestion.NoulQuestion(null, Map.of("true", "Is true")),
                        "n_false_only", new DecisionQuestion.NoulQuestion("False?", Map.of("false", "Is false")),
                        "c_null_desc", new DecisionQuestion.ChoiceQuestion(null, choiceCriteriaWithNull),
                        "n_empty_instr_list", new DecisionQuestion.NoulQuestion(List.of(), null),
                        "n_scalar_instr_list", new DecisionQuestion.NoulQuestion(List.of("a", "b"), null),
                        "n_bad_text_part",
                                new DecisionQuestion.NoulQuestion(
                                        List.of(Map.of("type", "input_text", "text", 123)), null),
                        "n_bad_img_part",
                                new DecisionQuestion.NoulQuestion(
                                        List.of(Map.of("type", "input_image", "image_url", 123)), null),
                        "n_other_part", new DecisionQuestion.NoulQuestion(List.of(Map.of("type", "audio")), null),
                        "n_url_parts",
                                new DecisionQuestion.NoulQuestion(
                                        List.of(
                                                Map.of("type", "input_image", "image_url", "https://example.com/a.png"),
                                                Map.of("type", "input_image", "image_url", "http://example.com/b.png"),
                                                Map.of(
                                                        "type",
                                                        "input_image",
                                                        "image_url",
                                                        "data:image/png;base64,AAAA"),
                                                Map.of("type", "input_image", "image_url", "aGVsbG8="),
                                                Map.of("type", "input_image", "image_url", "not!base64")),
                                        null)),
                Map.of(),
                Map.of());
        JsonNode encodedQ = readTree(openAi.encodeRequest(questionVariants)).get("questions");
        Map<String, JsonNode> byName = new java.util.LinkedHashMap<>();
        encodedQ.forEach(q -> byName.put(q.get("name").asString(), q));

        assertEquals("Empty?", byName.get("n_empty").get("instructions").asString());
        assertEquals(
                "\nTrue when: Is true",
                byName.get("n_true_only").get("instructions").asString());
        assertEquals(
                "False?\nFalse when: Is false",
                byName.get("n_false_only").get("instructions").asString());
        assertFalse(byName.get("c_null_desc").has("instructions"));
        assertFalse(byName.get("c_null_desc").get("choices").get(0).has("description"));
        assertEquals("[]", byName.get("n_empty_instr_list").get("instructions").asString());
        assertEquals(
                "[\"a\",\"b\"]",
                byName.get("n_scalar_instr_list").get("instructions").asString());
        assertTrue(byName.get("n_bad_text_part").get("instructions").isString());
        assertTrue(byName.get("n_bad_img_part").get("instructions").isString());
        assertTrue(byName.get("n_other_part").get("instructions").isString());
        JsonNode urlParts = byName.get("n_url_parts").get("instructions");
        assertEquals(
                "https://example.com/a.png", urlParts.get(0).get("image_url").asString());
        assertEquals(
                "http://example.com/b.png", urlParts.get(1).get("image_url").asString());
        assertEquals(
                "data:image/png;base64,AAAA", urlParts.get(2).get("image_url").asString());
        assertTrue(urlParts.get(3).get("image_url").asString().startsWith("data:image/png;base64,"));
        assertTrue(urlParts.get(4).get("image_url").asString().startsWith("data:image/png;base64,"));

        // Request validation rejections
        Map<String, DecisionQuestion> validQ = Map.of("q", new DecisionQuestion.NoulQuestion("?", null));
        Map<String, DecisionQuestion> blankKeyQ = new java.util.LinkedHashMap<>();
        blankKeyQ.put("  ", new DecisionQuestion.NoulQuestion("?", null));
        Map<String, DecisionQuestion> nullValQ = new java.util.LinkedHashMap<>();
        nullValQ.put("q", null);
        List<String> nullImgList = new java.util.ArrayList<>();
        nullImgList.add(null);

        for (DecisionRequest bad : new DecisionRequest[] {
            null,
            new DecisionRequest(null, "s", null, validQ, Map.of(), Map.of()),
            new DecisionRequest(" ", "s", null, validQ, Map.of(), Map.of()),
            new DecisionRequest("m", null, null, validQ, Map.of(), Map.of()),
            new DecisionRequest("m", 42, null, validQ, Map.of(), Map.of()),
            new DecisionRequest("m", "s", null, null, Map.of(), Map.of()),
            new DecisionRequest("m", "s", null, Map.of(), Map.of(), Map.of()),
            new DecisionRequest("m", "s", null, blankKeyQ, Map.of(), Map.of()),
            new DecisionRequest("m", "s", null, nullValQ, Map.of(), Map.of()),
            new DecisionRequest(
                    "m", "s", null, Map.of("q", new DecisionQuestion.ChoiceQuestion("?", null)), Map.of(), Map.of()),
            new DecisionRequest(
                    "m",
                    "s",
                    null,
                    Map.of("q", new DecisionQuestion.ChoiceQuestion("?", Map.of("a", "A"))),
                    Map.of(),
                    Map.of()),
            new DecisionRequest(
                    "m", "s", null, Map.of("q", new DecisionQuestion.ScoreQuestion("?", null)), Map.of(), Map.of()),
            new DecisionRequest(
                    "m",
                    "s",
                    null,
                    Map.of("q", new DecisionQuestion.ScoreQuestion("?", List.of("a"))),
                    Map.of(),
                    Map.of()),
            new DecisionRequest(
                    "m",
                    "s",
                    null,
                    Map.of("q", new DecisionQuestion.NoulQuestion("?", Map.of("yes", "y"))),
                    Map.of(),
                    Map.of()),
            new DecisionRequest("m", "s", nullImgList, validQ, Map.of(), Map.of()),
            new DecisionRequest("m", "s", List.of("data:image/png;base64,AAAA"), validQ, Map.of(), Map.of()),
            new DecisionRequest("m", "s", List.of("file:///a.png"), validQ, Map.of(), Map.of()),
            new DecisionRequest("m", "s", List.of("not!b64!"), validQ, Map.of(), Map.of())
        }) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> openAi.encodeRequest(bad));
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type());
        }
    }

    @Test
    void decodeRequestCoversAllInputQuestionInstructionBranchesAndErrors() throws Exception {
        // Valid decode cases: output_text, unknown part ignored, non-array content skipped, boolean choice values,
        // missing/null/array/object instructions, score level description preference
        String rich = "{"
                + "\"model\":\"m\","
                + "\"input\":["
                + "  {\"content\":123},"
                + "  {\"content\":[{\"type\":\"reasoning\",\"text\":\"ignored\"},{\"type\":\"output_text\",\"text\":\"out\"}]}"
                + "],"
                + "\"questions\":["
                + "  {\"type\":\"predicate\",\"name\":\"p_null\",\"instructions\":null},"
                + "  {\"type\":\"predicate\",\"name\":\"p_arr\",\"instructions\":[{\"type\":\"input_text\",\"text\":\"A \"},{\"type\":\"input_text\",\"text\":\"B\"}]},"
                + "  {\"type\":\"predicate\",\"name\":\"p_obj\",\"instructions\":{\"task\":\"route\"}},"
                + "  {\"type\":\"choice\",\"name\":\"c_bool\",\"choices\":[{\"value\":true,\"description\":\"Yes\"},{\"value\":false}]},"
                + "  {\"type\":\"score\",\"name\":\"s_desc\",\"instructions\":\"Rate\",\"levels\":[{\"label\":\"L0\",\"description\":\"  \"},{\"label\":\"L1\",\"description\":\"High\"},{\"label\":\"L2\",\"description\":123}]}"
                + "]}";
        DecisionRequest decoded = openAi.decodeRequest(rich);
        assertEquals("out", decoded.state());
        assertNull(decoded.questions().get("p_null").instructions());
        assertEquals("A B", decoded.questions().get("p_arr").instructions());
        assertEquals("{\"task\":\"route\"}", decoded.questions().get("p_obj").instructions());
        DecisionQuestion.ChoiceQuestion cBool = assertInstanceOf(
                DecisionQuestion.ChoiceQuestion.class, decoded.questions().get("c_bool"));
        assertEquals("Yes", cBool.criteria().get("true"));
        assertNull(cBool.criteria().get("false"));
        DecisionQuestion.ScoreQuestion sDesc = assertInstanceOf(
                DecisionQuestion.ScoreQuestion.class, decoded.questions().get("s_desc"));
        assertEquals(List.of("L0", "High", "L2"), sDesc.criteria());

        // > MAX_QUESTIONS on decode
        StringBuilder over400 = new StringBuilder("{\"model\":\"m\",\"input\":\"s\",\"questions\":[");
        for (int i = 0; i <= OpenAiDecisionsCodec.MAX_QUESTIONS; i++) {
            if (i > 0) {
                over400.append(',');
            }
            over400.append("{\"type\":\"predicate\",\"name\":\"q").append(i).append("\",\"instructions\":\"?\"}");
        }
        over400.append("]}");

        for (String bad : List.of(
                "{\"model\":\"m\",\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":null,\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":123,\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":[{\"content\":[{\"type\":\"input_json\",\"data\":{\"a\":1}}]},{\"content\":\"after\"}],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":[{\"content\":[{\"type\":\"input_json\",\"data\":{\"a\":1}},{\"type\":\"output_text\",\"text\":\"after\"}]}],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":[{\"content\":[{\"type\":\"input_json\",\"data\":null}]}],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":[{\"content\":[{\"type\":\"input_image\"}]}],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":[{\"content\":[{\"type\":\"input_image\",\"image_url\":\"data:image/png,abc\"}]}],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":[{\"content\":[{\"type\":\"input_image\",\"image_url\":\"image/png;base64,AAAA\"}]}],\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":\"?\"}]}",
                "{\"model\":\"m\",\"input\":\"s\"}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":{}}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[\"not-obj\"]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"predicate\",\"name\":\"q\"},{\"type\":\"predicate\",\"name\":\"q\"}]}",
                over400.toString(),
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":[{\"type\":\"input_image\",\"image_url\":\"x\"}]}]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"predicate\",\"name\":\"q\",\"instructions\":123}]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"choice\",\"name\":\"q\"}]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"choice\",\"name\":\"q\",\"choices\":{}}]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"choice\",\"name\":\"q\",\"choices\":[{\"value\":\"only\"}]}]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"choice\",\"name\":\"q\",\"choices\":[{\"value\":1},{\"value\":2}]}]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"score\",\"name\":\"q\"}]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"score\",\"name\":\"q\",\"levels\":{}}]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"score\",\"name\":\"q\",\"levels\":[{\"label\":\"only\"}]}]}",
                "{\"model\":\"m\",\"input\":\"s\",\"questions\":[{\"type\":\"box\",\"name\":\"q\",\"instructions\":\"Find\"}]}")) {
            DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> openAi.decodeRequest(bad), bad);
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type(), bad);
        }
    }

    @Test
    void decodeAndEncodeResponseAllVariantsAndErrors() throws Exception {
        // Encode all answer variants (with/without confidence, legend, usage, model, extras)
        DecisionResponse full = new DecisionResponse(
                "gpt-6-luna",
                Map.of(
                        "p", new DecisionAnswer.NoulAnswer(0.75),
                        "r", new DecisionAnswer.RefusalAnswer(),
                        "c_conf", new DecisionAnswer.ChoiceAnswer("a", Map.of("a", 0.8, "b", 0.2), 0.9),
                        "c_no_conf", new DecisionAnswer.ChoiceAnswer("b", null, null),
                        "s_full",
                                new DecisionAnswer.ScoreAnswer(
                                        1.5, Map.of("0", "Low", "1", "High"), Map.of("0", 0.3, "1", 0.7), 0.85),
                        "s_noleg", new DecisionAnswer.ScoreAnswer(0.0, null, Map.of("0", 1.0), null)),
                new io.amscotti.janus.core.model.Usage(10, 5, 15, 2L, 3L, 1L),
                Map.of("trace_id", "t1"),
                Map.of());
        String encodedFull = openAi.encodeResponse(full);
        JsonNode fullTree = readTree(encodedFull);
        assertEquals("gpt-6-luna", fullTree.get("model").asString());
        assertEquals("t1", fullTree.get("trace_id").asString());
        assertEquals(
                3,
                fullTree.get("usage")
                        .get("input_tokens_details")
                        .get("cached_tokens")
                        .asInt());
        assertEquals(
                2,
                fullTree.get("usage")
                        .get("input_tokens_details")
                        .get("cache_write_tokens")
                        .asInt());
        assertEquals(
                1,
                fullTree.get("usage")
                        .get("output_tokens_details")
                        .get("reasoning_tokens")
                        .asInt());

        // Encode without model, answers, or usage
        JsonNode emptyRespTree =
                readTree(openAi.encodeResponse(new DecisionResponse(null, null, null, Map.of(), Map.of())));
        assertFalse(emptyRespTree.has("model"));
        assertFalse(emptyRespTree.has("usage"));
        assertEquals(0, emptyRespTree.get("answers").size());

        // BoxAnswer rejected on encode
        DecisionCodecException boxErr = assertThrows(
                DecisionCodecException.class,
                () -> openAi.encodeResponse(new DecisionResponse(
                        "m",
                        Map.of("q", new DecisionAnswer.BoxAnswer(0.1, 0.2, 0.3, 0.4, 0.5)),
                        null,
                        Map.of(),
                        Map.of())));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, boxErr.type());

        // Decode response: boolean choice & probabilities, choice confidence, score without explicit score (weighted),
        // missing score label, score confidence, non-string model, omitted usage total_tokens & details
        String richResp = "{"
                + "\"model\":123,"
                + "\"answers\":["
                + "  {\"type\":\"choice\",\"name\":\"c_bool\",\"choice\":true,\"confidence\":0.9,\"probabilities\":[{\"value\":true,\"probability\":0.9},{\"value\":false,\"probability\":0.1}]},"
                + "  {\"type\":\"score\",\"name\":\"s_weighted\",\"confidence\":0.8,\"probabilities\":[{\"value\":0,\"probability\":0.25},{\"value\":2,\"label\":\"Two\",\"probability\":0.75}]}"
                + "],"
                + "\"usage\":{\"input_tokens\":50,\"output_tokens\":10}"
                + "}";
        DecisionResponse decoded = openAi.decodeResponse(richResp);
        assertNull(decoded.model());
        DecisionAnswer.ChoiceAnswer cBool = assertInstanceOf(
                DecisionAnswer.ChoiceAnswer.class, decoded.answers().get("c_bool"));
        assertEquals("true", cBool.choice());
        assertEquals(0.9, cBool.confidence());
        DecisionAnswer.ScoreAnswer sWeighted = assertInstanceOf(
                DecisionAnswer.ScoreAnswer.class, decoded.answers().get("s_weighted"));
        assertEquals(1.5, sWeighted.score(), 1e-12);
        assertNull(sWeighted.legend().get("0"));
        assertEquals("Two", sWeighted.legend().get("2"));
        assertEquals(0.8, sWeighted.confidence());
        assertEquals(60L, decoded.usage().totalTokens());

        // Decode response error paths + non-object usage
        assertNull(openAi.decodeResponse("{\"answers\":[],\"usage\":[]}").usage());
        for (String bad : List.of(
                "{}",
                "{\"answers\":{}}",
                "{\"answers\":[{\"type\":\"refusal\"}]}",
                "{\"answers\":[{\"type\":\"refusal\",\"name\":123}]}",
                "{\"answers\":[{\"type\":\"refusal\",\"name\":\"  \"}]}",
                "{\"answers\":[{\"type\":\"box\",\"name\":\"q\"}]}",
                "{\"answers\":[{\"type\":\"choice\",\"name\":\"q\",\"probabilities\":[{\"value\":\"a\",\"probability\":1}]}]}",
                "{\"answers\":[{\"type\":\"choice\",\"name\":\"q\",\"choice\":123,\"probabilities\":[{\"value\":\"a\",\"probability\":1}]}]}",
                "{\"answers\":[{\"type\":\"choice\",\"name\":\"q\",\"choice\":\"a\",\"probabilities\":null}]}",
                "{\"answers\":[{\"type\":\"choice\",\"name\":\"q\",\"choice\":\"a\",\"probabilities\":[{\"probability\":1}]}]}",
                "{\"answers\":[{\"type\":\"choice\",\"name\":\"q\",\"choice\":\"a\",\"probabilities\":[{\"value\":123,\"probability\":1}]}]}",
                "{\"answers\":[{\"type\":\"choice\",\"name\":\"q\",\"choice\":\"a\",\"probabilities\":[{\"value\":\"a\",\"probability\":0.5},{\"value\":\"a\",\"probability\":0.5}]}]}",
                "{\"answers\":[{\"type\":\"choice\",\"name\":\"q\",\"choice\":\"a\",\"confidence\":1.5,\"probabilities\":[{\"value\":\"a\",\"probability\":1}]}]}",
                "{\"answers\":[{\"type\":\"score\",\"name\":\"q\",\"probabilities\":null}]}",
                "{\"answers\":[{\"type\":\"score\",\"name\":\"q\",\"confidence\":-0.1,\"probabilities\":[{\"value\":0,\"probability\":1}]}]}",
                "{\"answers\":[{\"type\":\"predicate\",\"name\":\"q\",\"probability\":-0.1}]}")) {
            DecisionCodecException e =
                    assertThrows(DecisionCodecException.class, () -> openAi.decodeResponse(bad), bad);
            assertEquals(DecisionCodecException.TYPE_API_ERROR, e.type(), bad);
        }
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
