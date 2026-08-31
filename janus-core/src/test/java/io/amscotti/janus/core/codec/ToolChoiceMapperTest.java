package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The tool-choice matrix : canonical (OpenAI-idiomatic) ↔ Anthropic wire
 * forms per render_tool_choice, the Anthropic-only
 * {@code disable_parallel_tool_use} extras round-trip, verbatim passthrough for unknown
 * shapes (idempotence), and the OpenAI-direction normalization of Anthropic-idiomatic
 * values.
 */
class ToolChoiceMapperTest {

    // ------------------------------------------------- canonical → Anthropic

    @Test
    void canonicalToAnthropicMapsStrings() {
        assertEquals(Map.of("type", "auto"), ToolChoiceMapper.canonicalToAnthropic("auto"));
        assertEquals(Map.of("type", "none"), ToolChoiceMapper.canonicalToAnthropic("none"));
        assertEquals(Map.of("type", "any"), ToolChoiceMapper.canonicalToAnthropic("required"));
    }

    @Test
    void canonicalToAnthropicMapsOpenAiFunctionObjectToTool() {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("type", "function");
        choice.put("function", Map.of("name", "get_weather"));
        assertEquals(Map.of("type", "tool", "name", "get_weather"), ToolChoiceMapper.canonicalToAnthropic(choice));
    }

    @Test
    void canonicalToAnthropicPassesOtherObjectsVerbatim() {
        Map<String, Object> custom = Map.of("type", "custom", "value", 1);
        assertSame(custom, ToolChoiceMapper.canonicalToAnthropic(custom));
        assertSame("unknown_string", ToolChoiceMapper.canonicalToAnthropic("unknown_string"));
        assertNull(ToolChoiceMapper.canonicalToAnthropic(null));
    }

    // ------------------------------------------------- Anthropic → canonical

    @Test
    void anthropicToCanonicalMapsForms() {
        assertEquals("auto", ToolChoiceMapper.anthropicToCanonical(Map.of("type", "auto"), new HashMap<>()));
        assertEquals("none", ToolChoiceMapper.anthropicToCanonical(Map.of("type", "none"), new HashMap<>()));
        assertEquals("required", ToolChoiceMapper.anthropicToCanonical(Map.of("type", "any"), new HashMap<>()));
        assertEquals(
                Map.of("type", "function", "function", Map.of("name", "get_weather")),
                ToolChoiceMapper.anthropicToCanonical(Map.of("type", "tool", "name", "get_weather"), new HashMap<>()));
    }

    @Test
    void anthropicToCanonicalCapturesDisableParallelToolUseIntoExtras() {
        Map<String, Object> extras = new HashMap<>();
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("type", "auto");
        choice.put("disable_parallel_tool_use", true);
        assertEquals("auto", ToolChoiceMapper.anthropicToCanonical(choice, extras));
        assertEquals(Boolean.TRUE, extras.get(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL));

        // absent or false → nothing captured
        Map<String, Object> without = new HashMap<>();
        assertEquals("none", ToolChoiceMapper.anthropicToCanonical(Map.of("type", "none"), without));
        assertFalse(without.containsKey(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL));
        Map<String, Object> withFalse = new HashMap<>();
        assertEquals(
                "auto",
                ToolChoiceMapper.anthropicToCanonical(
                        Map.of("type", "auto", "disable_parallel_tool_use", false), withFalse));
        assertFalse(withFalse.containsKey(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL));
    }

    @Test
    void anthropicToCanonicalCapturesDisableParallelOnSpecificToolForm() {
        // Anthropic allows disable_parallel_tool_use on the specific-tool
        // {"type":"tool","name":N} form too — the flag was silently dropped before.
        Map<String, Object> extras = new HashMap<>();
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("type", "tool");
        choice.put("name", "get_weather");
        choice.put("disable_parallel_tool_use", true);
        assertEquals(
                Map.of("type", "function", "function", Map.of("name", "get_weather")),
                ToolChoiceMapper.anthropicToCanonical(choice, extras));
        assertEquals(Boolean.TRUE, extras.get(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL));
        // the flag survives a full decode → encode round trip (the codec re-emits it inside
        // the Anthropic tool_choice map from the extras flag)
        AnthropicMessageCodec codec = new AnthropicMessageCodec(JsonSupport.mapper());
        String wire = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":10,"
                + "\"tool_choice\":{\"type\":\"tool\",\"name\":\"get_weather\",\"disable_parallel_tool_use\":true}}";
        String reencoded = codec.encodeRequest(codec.decodeRequest(wire));
        assertTrue(
                reencoded.contains(
                        "\"tool_choice\":{\"type\":\"tool\",\"name\":\"get_weather\",\"disable_parallel_tool_use\":true}"),
                reencoded);
    }

    @Test
    void anthropicToCanonicalPassesUnknownShapesVerbatim() {
        Map<String, Object> custom = Map.of("type", "custom");
        assertSame(custom, ToolChoiceMapper.anthropicToCanonical(custom, new HashMap<>()));
        assertNull(ToolChoiceMapper.anthropicToCanonical(null, new HashMap<>()));
    }

    // ----------------------------------------------------- normalize (OpenAI)

    @Test
    void normalizeOpenAiIsIdentityForCanonicalForms() {
        assertSame("auto", ToolChoiceMapper.normalizeOpenAi("auto"));
        assertSame("none", ToolChoiceMapper.normalizeOpenAi("none"));
        assertSame("required", ToolChoiceMapper.normalizeOpenAi("required"));
        Map<String, Object> functionObject = Map.of("type", "function", "function", Map.of("name", "f"));
        assertSame(functionObject, ToolChoiceMapper.normalizeOpenAi(functionObject));
        Map<String, Object> custom = Map.of("type", "custom");
        assertSame(custom, ToolChoiceMapper.normalizeOpenAi(custom));
        assertNull(ToolChoiceMapper.normalizeOpenAi(null));
    }

    @Test
    void normalizeOpenAiConvertsAnthropicIdiomaticValues() {
        assertEquals("auto", ToolChoiceMapper.normalizeOpenAi(Map.of("type", "auto")));
        assertEquals("required", ToolChoiceMapper.normalizeOpenAi(Map.of("type", "any")));
        assertEquals(
                Map.of("type", "function", "function", Map.of("name", "f")),
                ToolChoiceMapper.normalizeOpenAi(Map.of("type", "tool", "name", "f")));
    }

    // ------------------------------------------------------------- round trips

    @Test
    void knownValuesRoundTripThroughBothDirections() {
        for (String canonical : new String[] {"auto", "none", "required"}) {
            assertEquals(
                    canonical,
                    ToolChoiceMapper.anthropicToCanonical(
                            ToolChoiceMapper.canonicalToAnthropic(canonical), new HashMap<>()));
        }
        Map<String, Object> functionObject = Map.of("type", "function", "function", Map.of("name", "f"));
        assertEquals(
                functionObject,
                ToolChoiceMapper.anthropicToCanonical(
                        ToolChoiceMapper.canonicalToAnthropic(functionObject), new HashMap<>()));
    }

    @Test
    void disableParallelToolUseRoundTripsThroughExtras() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", "any");
        wire.put("disable_parallel_tool_use", true);
        Map<String, Object> extras = new HashMap<>();
        String canonical = (String) ToolChoiceMapper.anthropicToCanonical(wire, extras);
        assertEquals("required", canonical);
        assertTrue(extras.containsKey(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL));
        // The codec re-emits the flag into the Anthropic tool_choice map on encode
        // (verified in AnthropicRequestCodecTest); the mapper's job is capture only.
    }
}
