package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageTest {

    @Test
    void rolesAreDerivedPerSubtype() {
        assertEquals(ChatRole.SYSTEM, new SystemMessage("be brief").role());
        assertEquals(ChatRole.USER, new UserMessage("hi").role());
        assertEquals(ChatRole.ASSISTANT, new AssistantMessage("hello", null).role());
        assertEquals(ChatRole.TOOL, new ToolMessage("call_1", "42").role());
        assertEquals(ChatRole.DEVELOPER, new DeveloperMessage("be correct").role());
    }

    @Test
    void recordsProvideValueEquality() {
        assertEquals(new UserMessage("hi"), new UserMessage("hi"));
        assertEquals(new UserMessage("hi").hashCode(), new UserMessage("hi").hashCode());
        assertNotEquals(new UserMessage("hi"), new UserMessage("bye"));
        assertNotEquals(new SystemMessage("hi"), new UserMessage("hi"));
        assertNotEquals(new AssistantMessage("a", List.of()), new AssistantMessage("a", null));
    }

    @Test
    void sealedHierarchyIsExhaustivelyMatchable() {
        List<Message> messages = List.of(
                new SystemMessage("s"),
                new UserMessage("u"),
                new AssistantMessage("a", null),
                new ToolMessage("t", "r"),
                new DeveloperMessage("d"));
        for (Message message : messages) {
            String role =
                    switch (message) {
                        case SystemMessage sm -> "system:" + sm.content();
                        case UserMessage um -> "user:" + um.content();
                        case AssistantMessage am -> "assistant:" + am.content();
                        case ToolMessage tm -> "tool:" + tm.content();
                        case DeveloperMessage dm -> "developer:" + dm.content();
                    };
            assertNotNull(role);
        }
    }

    @Test
    void assistantMessageDefensivelyCopiesToolCalls() {
        List<ToolCall> original =
                new ArrayList<>(List.of(new ToolCall("call_1", "function", new FunctionCall("f", "{}"))));
        AssistantMessage message = new AssistantMessage("a", original);
        original.clear();
        assertEquals(1, message.toolCalls().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> message.toolCalls().add(new ToolCall("x", "function", new FunctionCall("g", "{}"))));
    }

    @Test
    void assistantMessageAllowsNullToolCalls() {
        assertNotNull(new AssistantMessage("plain", null));
    }

    @Test
    void assistantMessageToolCallsTolerateNullElements() {
        // Malformed wire input (a "tool_calls":[null] fragment) must not escape the
        // compact constructor as a raw NullPointerException from List.copyOf — the
        // null element survives (construct-and-fail-later) so the codecs can reject
        // it with a typed error.
        List<ToolCall> withNull = new ArrayList<>();
        withNull.add(new ToolCall("call_1", "function", new FunctionCall("f", "{}")));
        withNull.add(null);
        AssistantMessage message = new AssistantMessage("a", withNull);
        assertEquals(2, message.toolCalls().size());
        assertNull(message.toolCalls().get(1));
        assertThrows(
                UnsupportedOperationException.class, () -> message.toolCalls().add(null));
    }
}
