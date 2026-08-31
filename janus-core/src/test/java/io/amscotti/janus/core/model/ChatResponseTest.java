package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatResponseTest {

    @Test
    void stopReasonConstantsCoverNormalizedValues() {
        assertEquals("stop", ChatResponse.STOP_REASON_STOP);
        assertEquals("length", ChatResponse.STOP_REASON_LENGTH);
        assertEquals("tool_calls", ChatResponse.STOP_REASON_TOOL_CALLS);
        assertEquals("content_filter", ChatResponse.STOP_REASON_CONTENT_FILTER);
        assertEquals("error", ChatResponse.STOP_REASON_ERROR);
    }

    @Test
    void usageIsAValueRecord() {
        Usage usage = new Usage(10, 5, 15);
        assertEquals(new Usage(10, 5, 15), usage);
        assertEquals(new Usage(10, 5, 15).hashCode(), usage.hashCode());
        assertNotEquals(new Usage(10, 6, 16), usage);
        assertEquals(10, usage.promptTokens());
        assertEquals(5, usage.completionTokens());
        assertEquals(15, usage.totalTokens());
    }

    @Test
    void usageCacheFieldsAreNullableAndPartOfEquality() {
        Usage cached = new Usage(10, 5, 15, 2L, 3L);
        assertEquals(2L, cached.cacheCreationInputTokens());
        assertEquals(3L, cached.cacheReadInputTokens());
        assertNull(new Usage(10, 5, 15).cacheCreationInputTokens());
        assertNull(new Usage(10, 5, 15).cacheReadInputTokens());
        assertEquals(new Usage(10, 5, 15), new Usage(10, 5, 15, null, null)); // 3-arg convenience form
        assertNotEquals(new Usage(10, 5, 15), cached);
    }

    @Test
    void responseDefaultsExtrasAndMeta() {
        ChatResponse response = minimal();
        assertEquals(Map.of(), response.extras());
        assertEquals(Map.of(), response.meta());
        assertEquals("stop", response.stopReason());
    }

    @Test
    void choicesAreDefensivelyCopied() {
        ChatResponse response = minimal();
        assertEquals(1, response.choices().size());
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> response.choices().clear());
    }

    @Test
    void extrasTolerateNullValuedFields() {
        // The pass-through contract allows JSON objects with null values
        // ({"provider_field": null}) — construction must not NPE.
        Map<String, Object> extras = new HashMap<>();
        extras.put("provider_field", null);
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "model-1",
                List.of(new ChatChoice(0, new AssistantMessage("hello", null), ChatResponse.STOP_REASON_STOP)),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                extras,
                null);
        assertTrue(response.extras().containsKey("provider_field"));
        assertNull(response.extras().get("provider_field"));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> response.extras().put("x", "y"));
    }

    private static ChatResponse minimal() {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "model-1",
                List.of(new ChatChoice(0, new AssistantMessage("hello", null), ChatResponse.STOP_REASON_STOP)),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                null,
                null);
    }
}
