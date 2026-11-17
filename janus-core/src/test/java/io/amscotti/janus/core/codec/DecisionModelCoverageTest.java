package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ContentLogging;
import io.amscotti.janus.core.model.DecisionQuestion;
import io.amscotti.janus.core.model.DecisionRequest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Targeted branch coverage for {@link DecisionRequest}, {@link DecisionJson},
 * and {@link SystemOneCodec#create()}.
 */
class DecisionModelCoverageTest {

    @AfterEach
    void resetContentLogging() {
        ContentLogging.disable();
    }

    @Test
    void decisionRequestNullArgsWithMetaHasAssetsAndHasImageEvidence() {
        ContentLogging.disable();
        DecisionRequest allNulls = new DecisionRequest("m", "s", null, null, null, null);
        assertNull(allNulls.images());
        assertNull(allNulls.questions());
        assertEquals(Map.of(), allNulls.extras());
        assertEquals(Map.of(), allNulls.meta());
        assertFalse(allNulls.hasAssets());
        assertFalse(allNulls.hasImageEvidence());
        assertTrue(allNulls.toString().contains("questions=0"));
        assertTrue(allNulls.toString().contains("images=0"));

        // withMeta: null and empty return this; non-empty merges without mutating original
        assertSame(allNulls, allNulls.withMeta(null));
        assertSame(allNulls, allNulls.withMeta(Map.of()));
        DecisionRequest withOne = allNulls.withMeta(Map.of("k1", "v1"));
        assertNotSame(allNulls, withOne);
        assertEquals(Map.of("k1", "v1"), withOne.meta());
        DecisionRequest withTwo = withOne.withMeta(Map.of("k2", 2));
        assertEquals(Map.of("k1", "v1", "k2", 2), withTwo.meta());
        assertEquals(Map.of("k1", "v1"), withOne.meta());

        // hasAssets across every switch branch
        assertFalse(new DecisionRequest("m", "s", null, null, Map.of("assets", List.of()), null).hasAssets());
        assertTrue(new DecisionRequest("m", "s", null, null, Map.of("assets", List.of("p1")), null).hasAssets());
        assertFalse(new DecisionRequest("m", "s", null, null, Map.of("assets", "   "), null).hasAssets());
        assertTrue(new DecisionRequest("m", "s", null, null, Map.of("assets", "p1"), null).hasAssets());
        assertTrue(new DecisionRequest("m", "s", null, null, Map.of("assets", 42), null).hasAssets());

        // hasImageEvidence: images[] empty vs non-empty
        assertFalse(new DecisionRequest("m", "s", List.of(), null, null, null).hasImageEvidence());
        assertTrue(new DecisionRequest("m", "s", List.of("aGVsbG8="), null, null, null).hasImageEvidence());

        // hasImageEvidence: questions with null entry, BoxQuestion without assets,
        // NoulQuestion/ScoreQuestion/ChoiceQuestion
        Map<String, DecisionQuestion> withNullEntry = new LinkedHashMap<>();
        withNullEntry.put("q_null", null);
        withNullEntry.put("q_noul_plain", new DecisionQuestion.NoulQuestion("?", Map.of("true", "yes")));
        withNullEntry.put("q_score_plain", new DecisionQuestion.ScoreQuestion("Rate", List.of("low", "high")));
        assertFalse(new DecisionRequest("m", "s", null, withNullEntry, null, null).hasImageEvidence());

        assertTrue(
                new DecisionRequest("m", "s", null, Map.of("q", new DecisionQuestion.BoxQuestion("Find")), null, null)
                        .hasImageEvidence());

        assertTrue(new DecisionRequest(
                        "m",
                        "s",
                        null,
                        Map.of(
                                "q",
                                new DecisionQuestion.NoulQuestion(
                                        "?",
                                        Map.of("true", Map.of("type", "image_url", "image_url", "https://a/b.png")))),
                        null,
                        null)
                .hasImageEvidence());

        assertTrue(new DecisionRequest(
                        "m",
                        "s",
                        null,
                        Map.of(
                                "q",
                                new DecisionQuestion.ScoreQuestion(
                                        List.of(Map.of(
                                                "type", "input_image", "image_url", "data:image/png;base64,AA==")),
                                        List.of("low", "high"))),
                        null,
                        null)
                .hasImageEvidence());

        assertTrue(new DecisionRequest(
                        "m",
                        "s",
                        null,
                        Map.of(
                                "q",
                                new DecisionQuestion.ScoreQuestion(
                                        "Rate", List.of(Map.of("type", "image", "data", "AA=="), "high"))),
                        null,
                        null)
                .hasImageEvidence());

        // containsImagePart via image_url string, image_url Map, and non-string/non-map image_url
        assertTrue(new DecisionRequest(
                        "m",
                        Map.of("nested", List.of(Map.of("image_url", "https://example.com/a.png"))),
                        null,
                        null,
                        null,
                        null)
                .hasImageEvidence());
        assertTrue(new DecisionRequest(
                        "m", Map.of("image_url", Map.of("url", "https://example.com/a.png")), null, null, null, null)
                .hasImageEvidence());
        assertFalse(new DecisionRequest("m", Map.of("image_url", 123), null, null, null, null).hasImageEvidence());
    }

    @Test
    void decisionJsonCoversMapperDuplicatesFiniteNumbersBoundsAndErrors() throws Exception {
        ObjectMapper mapper = DecisionJson.mapper();
        SystemOneCodec codec = SystemOneCodec.create();
        DecisionRequest req = new DecisionRequest(
                "m", "state", null, Map.of("q", new DecisionQuestion.NoulQuestion("?", null)), Map.of(), Map.of());
        assertEquals(req, codec.decodeRequest(codec.encodeRequest(req)));

        // readTree null, blank, non-object, invalid JSON, duplicate keys
        for (String bad : new String[] {null, "", "  ", "[]", "123", "null", "{bad", "{\"a\":1,\"a\":2}"}) {
            DecisionCodecException e = assertThrows(
                    DecisionCodecException.class,
                    () -> DecisionJson.readTree(mapper, bad, DecisionCodecException.TYPE_INVALID_REQUEST, "request"));
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type());
        }

        // write failure via self-referential POJONode
        Map<String, Object> cycle = new HashMap<>();
        cycle.put("self", cycle);
        ObjectNode badNode = mapper.createObjectNode();
        badNode.set("cycle", mapper.getNodeFactory().pojoNode(cycle));
        DecisionCodecException writeErr =
                assertThrows(DecisionCodecException.class, () -> DecisionJson.write(mapper, badNode));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, writeErr.type());

        // textOf failure on cyclic map
        DecisionCodecException textErr =
                assertThrows(DecisionCodecException.class, () -> DecisionJson.textOf(mapper, cycle));
        assertEquals(DecisionCodecException.TYPE_API_ERROR, textErr.type());

        // toPlain 2-arg and 3-arg, null/NullNode, valueOrNull
        assertNull(DecisionJson.toPlain(mapper, null));
        assertNull(DecisionJson.toPlain(mapper, mapper.nullNode()));
        assertNull(DecisionJson.valueOrNull(mapper, null, DecisionCodecException.TYPE_INVALID_REQUEST));
        assertNull(DecisionJson.valueOrNull(mapper, mapper.nullNode(), DecisionCodecException.TYPE_INVALID_REQUEST));
        assertEquals(Map.of("a", 1), DecisionJson.toPlain(mapper, mapper.readTree("{\"a\":1}")));

        // requireFiniteTree: Double, Float, BigDecimal, nested Map/List
        DecisionJson.requireFiniteTree(null, DecisionCodecException.TYPE_INVALID_REQUEST, "v");
        DecisionJson.requireFiniteTree(1.5f, DecisionCodecException.TYPE_INVALID_REQUEST, "v");
        DecisionJson.requireFiniteTree(new BigDecimal("1.5"), DecisionCodecException.TYPE_INVALID_REQUEST, "v");
        for (Object nonFinite : List.of(Double.NaN, Double.POSITIVE_INFINITY, Float.NaN, Float.NEGATIVE_INFINITY)) {
            DecisionCodecException e = assertThrows(
                    DecisionCodecException.class,
                    () -> DecisionJson.requireFiniteTree(
                            Map.of("k", List.of(nonFinite)), DecisionCodecException.TYPE_INVALID_REQUEST, "v"));
            assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type());
        }

        // mergeExtras: null extras and mapped key ignored
        ObjectNode target = mapper.createObjectNode();
        target.put("model", "winner");
        DecisionJson.mergeExtras(mapper, target, null, Set.of("model"));
        DecisionJson.mergeExtras(mapper, target, Map.of("model", "loser", "extra", "kept"), Set.of("model"));
        assertEquals("winner", target.get("model").asString());
        assertEquals("kept", target.get("extra").asString());

        // requireModel: missing, non-string, blank, > MAX_MODEL_CHARS, exact MAX_MODEL_CHARS
        String maxModel = "m".repeat(DecisionJson.MAX_MODEL_CHARS);
        assertEquals(
                maxModel,
                DecisionJson.requireModel(
                        mapper.readTree("{\"model\":\"" + maxModel + "\"}"),
                        DecisionCodecException.TYPE_INVALID_REQUEST));
        for (String badModel : List.of(
                "{}",
                "{\"model\":123}",
                "{\"model\":\"  \"}",
                "{\"model\":\"" + "m".repeat(DecisionJson.MAX_MODEL_CHARS + 1) + "\"}")) {
            JsonNode tree = mapper.readTree(badModel);
            assertThrows(
                    DecisionCodecException.class,
                    () -> DecisionJson.requireModel(tree, DecisionCodecException.TYPE_INVALID_REQUEST));
        }

        // stringField, numberField, optionalNumber, optionalLongField, integralField, doubleMap
        JsonNode sample = mapper.readTree(
                "{\"s\":123,\"n\":\"1\",\"inf\":1e999,\"big\":9223372036854775808,\"badprob\":{\"a\":\"0.5\"}}");
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionJson.stringField(sample, "missing", DecisionCodecException.TYPE_API_ERROR, "w"));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionJson.stringField(sample, "s", DecisionCodecException.TYPE_API_ERROR, "w"));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionJson.numberField(sample, "missing", DecisionCodecException.TYPE_API_ERROR, "w"));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionJson.numberField(sample, "n", DecisionCodecException.TYPE_API_ERROR, "w"));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionJson.numberField(sample, "inf", DecisionCodecException.TYPE_API_ERROR, "w"));
        assertNull(DecisionJson.optionalNumber(sample, "missing", DecisionCodecException.TYPE_API_ERROR, "w"));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionJson.optionalNumber(sample, "n", DecisionCodecException.TYPE_API_ERROR, "w"));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionJson.optionalNumber(sample, "inf", DecisionCodecException.TYPE_API_ERROR, "w"));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionJson.optionalLongField(sample, "big", DecisionCodecException.TYPE_API_ERROR, "w"));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionJson.integralField(sample, "missing", DecisionCodecException.TYPE_API_ERROR, "w"));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionJson.integralField(sample, "big", DecisionCodecException.TYPE_API_ERROR, "w"));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionJson.doubleMap(sample.get("badprob"), DecisionCodecException.TYPE_API_ERROR, "w"));
    }
}
