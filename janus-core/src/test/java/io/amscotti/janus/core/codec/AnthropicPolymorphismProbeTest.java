package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Step 1 probe: Jackson 3 (tools.jackson 3.1.x) polymorphic records where the
 * discriminator {@code type} is a <em>record component</em> (unlike the OpenAI codec's derived
 * {@code role} accessor, which is not a component and forced the {@code As.PROPERTY}
 * fallback). {@code @JsonTypeInfo(As.EXISTING_PROPERTY)} should both serialize the
 * component naturally (exactly once) and use its value for subtype resolution on read.
 *
 * <p>Pins the mechanism the Anthropic DTO families (content blocks, deltas, SSE
 * payloads) rely on. If this ever regresses — or the plan's fallbacks (As.PROPERTY /
 * explicit JsonNode dispatch) are ever needed — the DTOs must change first.
 */
class AnthropicPolymorphismProbeTest {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ProbeText.class, name = "text"),
        @JsonSubTypes.Type(value = ProbeToolUse.class, name = "tool_use"),
    })
    sealed interface ProbeBlock permits ProbeText, ProbeToolUse {}

    /** type is a real component — the wire discriminator and the record field are one. */
    record ProbeText(String type, String text) implements ProbeBlock {}

    record ProbeToolUse(String type, String id, Map<String, Object> input) implements ProbeBlock {}

    /** Declared-typed container: the safe way to serialize/deserialize the family. */
    record ProbeEnvelope(String type, List<ProbeBlock> content) {}

    private final ObjectMapper mapper = JsonSupport.mapper();

    @Test
    void serializesThroughDeclaredTypedContainerWithSingleTypeField() throws Exception {
        ProbeEnvelope envelope = new ProbeEnvelope(
                "probe",
                List.of(new ProbeText("text", "hi"), new ProbeToolUse("tool_use", "tu_1", Map.of("city", "Paris"))));
        String json = mapper.writeValueAsString(envelope);
        assertTrue(json.contains("\"type\":\"text\",\"text\":\"hi\""), json);
        assertTrue(json.contains("\"type\":\"tool_use\",\"id\":\"tu_1\",\"input\":{\"city\":\"Paris\"}"), json);
        // The discriminator appears exactly once per block — no duplicate type keys.
        assertEquals(1, countOccurrences(json, "\"type\":\"text\""));
        assertEquals(1, countOccurrences(json, "\"type\":\"tool_use\""));
    }

    @Test
    void deserializesThroughDeclaredTypedContainer() throws Exception {
        String json = "{\"type\":\"probe\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"},"
                + "{\"type\":\"tool_use\",\"id\":\"tu_1\",\"input\":{\"city\":\"Paris\"}}]}";
        ProbeEnvelope envelope = mapper.readValue(json, ProbeEnvelope.class);
        assertInstanceOf(ProbeText.class, envelope.content().get(0));
        assertInstanceOf(ProbeToolUse.class, envelope.content().get(1));
        assertEquals("hi", ((ProbeText) envelope.content().get(0)).text());
    }

    @Test
    void convertValueHonorsTheTypeDiscriminator() throws Exception {
        // Request DTOs model content as Object; the codec converts each map item.
        Object item = mapper.readValue("{\"type\":\"tool_use\",\"id\":\"tu_1\",\"input\":{\"x\":1}}", Object.class);
        ProbeBlock block = mapper.convertValue(item, ProbeBlock.class);
        assertInstanceOf(ProbeToolUse.class, block);
        assertEquals("tu_1", ((ProbeToolUse) block).id());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
