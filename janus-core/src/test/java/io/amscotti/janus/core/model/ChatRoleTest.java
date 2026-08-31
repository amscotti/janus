package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChatRoleTest {

    @Test
    void wireFormsAreLowercase() {
        assertEquals("system", ChatRole.SYSTEM.wire());
        assertEquals("user", ChatRole.USER.wire());
        assertEquals("assistant", ChatRole.ASSISTANT.wire());
        assertEquals("tool", ChatRole.TOOL.wire());
        assertEquals("developer", ChatRole.DEVELOPER.wire());
        assertEquals(5, ChatRole.values().length);
    }

    @Test
    void fromWireRoundTrips() {
        assertEquals(ChatRole.USER, ChatRole.fromWire("user"));
        assertEquals(ChatRole.TOOL, ChatRole.fromWire("tool"));
        assertEquals(ChatRole.DEVELOPER, ChatRole.fromWire("developer"));
    }

    @Test
    void unknownWireValueFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> ChatRole.fromWire("function"));
    }
}
