package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tool <em>definition</em> shape on {@link ChatRequest#tools}, kept
 * deliberately distinct from the {@link ToolCall} invocation shape on
 * {@link AssistantMessage#toolCalls}. The two types are unrelated records, so a
 * definition's {@code inputSchema} (a JSON <em>schema</em> string) can never be handed to
 * a codec in a tool-call {@code arguments} position at compile time, and vice-versa.
 */
class ToolDefinitionTest {

    @Test
    void typeIsNullableAndDefaultsOnTheWire() {
        assertEquals("function", new ToolDefinition("function", "f", "d", "{}").type());
        assertNull(new ToolDefinition("function", "f", "d", "{}").cacheControl());
        // The 3-arg convenience form carries no explicit type — the OpenAI encode defaults
        // it to "function" (mirrors the old ToolCall.type default).
        assertNull(new ToolDefinition("f", "d", "{}").type());
    }

    @Test
    void recordsProvideValueEquality() {
        assertEquals(new ToolDefinition("function", "f", "d", "{}"), new ToolDefinition("function", "f", "d", "{}"));
        assertNotEquals(
                new ToolDefinition("function", "f", "d", "{}"), new ToolDefinition("function", "f", "d", "{\"a\":1}"));
        assertNotEquals(
                new ToolDefinition("function", "f", "d", "{}"), new ToolDefinition("function", "f", "d2", "{}"));
    }

    @Test
    void definitionsAndInvocationsAreDistinctTypes() {
        // The conflation hazard the report flagged: an invocation carries real call
        // arguments under ToolCall.function.arguments and a definition carries a schema
        // under ToolDefinition.inputSchema. Because the types are unrelated, a schema
        // string cannot be passed where a ToolCall is expected (compile error) — pinned
        // here by exercising both shapes through their own homes.
        ToolCall invocation =
                new ToolCall("call_1", "function", new FunctionCall("get_weather", "{\"city\":\"Paris\"}"));
        ToolDefinition definition = new ToolDefinition("function", "get_weather", "weather", "{\"type\":\"object\"}");

        ChatRequest request = new ChatRequest(
                "m",
                List.of(new AssistantMessage(null, List.of(invocation))),
                null,
                List.of(definition),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        assertEquals(List.of(definition), request.tools());
        AssistantMessage assistant = (AssistantMessage) request.messages().get(0);
        assertEquals(List.of(invocation), assistant.toolCalls());
        assertEquals(invocation, assistant.toolCalls().get(0), "the invocation and definition must not compare equal");
        assertNotEquals(definition, invocation);
    }

    @Test
    void definitionRoundTripsThroughCanonicalJson() throws Exception {
        ToolDefinition definition = new ToolDefinition("function", "get_weather", "weather", "{\"type\":\"object\"}");
        ChatRequest request = new ChatRequest(
                "m",
                List.of(new UserMessage("hi")),
                null,
                List.of(definition),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        ChatRequest decoded =
                JsonSupport.mapper().readValue(JsonSupport.mapper().writeValueAsString(request), ChatRequest.class);
        assertEquals(request, decoded);
        assertEquals(definition, decoded.tools().get(0));
    }
}
