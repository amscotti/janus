package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolCallTest {

    @Test
    void argumentsStayRawJsonStrings() {
        String raw = "{\"city\":\"Paris\",\"units\":\"metric\"}";
        FunctionCall function = new FunctionCall("get_weather", raw);
        assertEquals("get_weather", function.name());
        assertEquals(raw, function.arguments());
    }

    @Test
    void typeIsNullable() {
        ToolCall withType = new ToolCall("call_1", "function", new FunctionCall("f", "{}"));
        ToolCall withoutType = new ToolCall("call_1", null, new FunctionCall("f", "{}"));
        assertEquals("function", withType.type());
        assertNull(withoutType.type());
    }

    @Test
    void recordsProvideValueEquality() {
        FunctionCall f = new FunctionCall("f", "{\"a\":1}");
        assertEquals(new ToolCall("call_1", "function", f), new ToolCall("call_1", "function", f));
        assertNotEquals(new ToolCall("call_1", "function", f), new ToolCall("call_2", "function", f));
        assertNotEquals(new FunctionCall("f", "{}"), new FunctionCall("f", "{\"a\":1}"));
    }

    @Test
    void descriptionIsNullableAndPartOfEquality() {
        FunctionCall plain = new FunctionCall("f", "{}");
        assertNull(plain.description());
        FunctionCall described = new FunctionCall("f", "{}", "does stuff");
        assertEquals("does stuff", described.description());
        // The 2-arg convenience constructor delegates to the 3-arg form.
        assertEquals(plain, new FunctionCall("f", "{}", null));
        assertNotEquals(plain, described);
    }

    @Test
    void descriptionSurvivesCanonicalJsonRoundTrip() throws Exception {
        ToolCall tool = new ToolCall("call_1", "function", new FunctionCall("f", "{\"a\":1}", "does stuff"));
        String json = JsonSupport.mapper().writeValueAsString(tool);
        assertTrue(json.contains("\"description\":\"does stuff\""), json);
        assertEquals(tool, JsonSupport.mapper().readValue(json, ToolCall.class));
    }
}
