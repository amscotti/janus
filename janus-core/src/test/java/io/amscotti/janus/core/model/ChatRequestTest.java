package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatRequestTest {

    @Test
    void extrasAndMetaDefaultToEmptyMaps() {
        ChatRequest request = minimal(null, null);
        assertEquals(Map.of(), request.extras());
        assertEquals(Map.of(), request.meta());
        assertFalse(request.stream());
    }

    @Test
    void nullExtrasAndMetaBecomeEmptyMaps() {
        ChatRequest request = minimal(null, null);
        assertEquals(Map.of(), request.extras());
        assertEquals(Map.of(), request.meta());
    }

    @Test
    void extrasAndMetaAreDefensivelyCopied() {
        Map<String, Object> extras = new HashMap<>();
        extras.put("custom", "field");
        Map<String, Object> meta = new HashMap<>();
        meta.put("key_id", "k1");
        ChatRequest request = minimal(extras, meta);
        extras.put("mutated", true);
        meta.put("mutated", true);
        assertEquals(1, request.extras().size());
        assertEquals(1, request.meta().size());
        assertThrows(UnsupportedOperationException.class, () -> request.extras().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> request.meta().put("x", "y"));
    }

    @Test
    void extrasTolerateNullValuedFields() {
        // The pass-through contract allows JSON objects with null values
        // ({"custom_field": null}) — construction must not NPE.
        Map<String, Object> extras = new HashMap<>();
        extras.put("custom_field", null);
        extras.put("another", "value");
        ChatRequest request = minimal(extras, Map.of());
        assertEquals(2, request.extras().size());
        assertTrue(request.extras().containsKey("custom_field"));
        assertNull(request.extras().get("custom_field"));
        assertThrows(UnsupportedOperationException.class, () -> request.extras().put("x", "y"));
    }

    @Test
    void payloadMapsTolerateNullValuedFields() {
        // reasoning/logitBias/responseFormat/streamOptions are the same
        // class of pass-through payload as extras — a wire object with a null-valued
        // field ({"effort": null}) is legitimate and construction must not NPE
        // (Map.copyOf on a null value throws NullPointerException).
        Map<String, Object> reasoning = new HashMap<>();
        reasoning.put("effort", null);
        reasoning.put("budget_tokens", 1024);
        Map<String, Object> logitBias = new HashMap<>();
        logitBias.put("50256", null);
        Map<String, Object> responseFormat = new HashMap<>();
        responseFormat.put("type", null);
        Map<String, Object> streamOptions = new HashMap<>();
        streamOptions.put("include_usage", null);
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
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
                logitBias,
                responseFormat,
                false,
                streamOptions,
                reasoning,
                null,
                null,
                null);
        // the null-valued fields survive construction and remain visible (pass-through)
        assertTrue(request.reasoning().containsKey("effort"));
        assertNull(request.reasoning().get("effort"));
        assertEquals(1024, request.reasoning().get("budget_tokens"));
        assertTrue(request.logitBias().containsKey("50256"));
        assertNull(request.logitBias().get("50256"));
        assertNull(request.responseFormat().get("type"));
        assertNull(request.streamOptions().get("include_usage"));
        // immutability still holds on the null-tolerant copies
        assertThrows(
                UnsupportedOperationException.class, () -> request.reasoning().put("x", "y"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.streamOptions().put("x", "y"));
    }

    @Test
    void stopListToleratesNullElements() {
        // A client "stop":[null] is malformed, but the null-tolerance contract covers
        // list elements too: construction must not NPE (List.copyOf throws a raw
        // NullPointerException on a null element, which previously escaped
        // OpenAiMessageCodec.decodeRequest straight from the wire). The element
        // pass-through-survives so the upstream can reject it with a typed error.
        ChatRequest request = minimal(null, null);
        assertNull(request.stop()); // null stays null
        ChatRequest withNullStop = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Arrays.asList("END", null),
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
        assertEquals(Arrays.asList("END", null), withNullStop.stop());
        assertNull(withNullStop.stop().get(1));
        // immutability still holds on the null-tolerant copy
        assertThrows(
                UnsupportedOperationException.class, () -> withNullStop.stop().add("x"));
    }

    @Test
    void componentListsTolerateNullElements() {
        // The same null-element contract covers the typed component lists: a client
        // "messages":[null] / "tools":[null] / hosted "tools":[null] is malformed, but
        // construction must not NPE (List.copyOf throws a raw NullPointerException on
        // a null element). The null element survives so the codecs — which validate
        // shape at the decode/encode boundary — and the upstream reject it with a
        // typed error instead of a raw NullPointerException escaping the record.
        List<HostedToolDefinition> hostedWithNull = new ArrayList<>();
        hostedWithNull.add(new HostedToolDefinition.WebSearch("high", null));
        hostedWithNull.add(null);
        ChatRequest request = new ChatRequest(
                "model-1",
                Arrays.asList(new UserMessage("hi"), null),
                null,
                Arrays.asList(new ToolDefinition("get_weather", "current weather", "{}"), null),
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
                hostedWithNull,
                null,
                null);
        assertEquals(new UserMessage("hi"), request.messages().get(0));
        assertNull(request.messages().get(1));
        assertEquals(
                new ToolDefinition("get_weather", "current weather", "{}"),
                request.tools().get(0));
        assertNull(request.tools().get(1));
        assertNull(request.hostedTools().get(1));
        // immutability still holds on the null-tolerant copies
        assertThrows(
                UnsupportedOperationException.class, () -> request.messages().add(null));
        assertThrows(UnsupportedOperationException.class, () -> request.tools().add(null));
        assertThrows(
                UnsupportedOperationException.class, () -> request.hostedTools().add(null));
    }

    @Test
    void messagesAreNotValidatedAtTheRecordBoundary() {
        // The IR does not enforce the non-null/non-empty messages invariant —
        // validation lives in the codecs (decode/encode), which reject null/empty with a
        // typed error. The record itself constructs (construct-and-fail-later policy),
        // pinned so the documented contract stays honest.
        ChatRequest empty = new ChatRequest(
                "model-1", List.of(), null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, false, null, null, null, null, null);
        assertEquals(List.of(), empty.messages());
        ChatRequest nullMessages = new ChatRequest(
                "model-1", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                false, null, null, null, null, null);
        assertNull(nullMessages.messages());
    }

    @Test
    void listComponentsAreDefensivelyCopied() {
        List<Message> original = new java.util.ArrayList<>(List.of(new UserMessage("hi")));
        ChatRequest request = new ChatRequest(
                "model-1", original, null, // system
                null, // tools
                null, null, null, null, null, null, null, null, null, null, null, null, false, null, null, null, null,
                null);
        original.add(new UserMessage("mutated"));
        assertEquals(1, request.messages().size());
        assertThrows(
                UnsupportedOperationException.class, () -> request.messages().add(new UserMessage("more")));
    }

    @Test
    void reservedFieldsArePresentForPhaseTwo() {
        Map<String, Object> reasoning = Map.of("effort", "high");
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                null,
                Map.of("type", "auto"),
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
                Map.of("include_usage", true),
                reasoning,
                "disable",
                null,
                null);
        assertEquals(reasoning, request.reasoning());
        assertEquals("disable", request.cacheControl());
        assertTrue(request.streamOptions().containsKey("include_usage"));
    }

    @Test
    void nullableSamplingParamsStayNull() {
        ChatRequest request = minimal(null, null);
        assertNull(request.system());
        assertNull(request.temperature());
        assertNull(request.toolChoice());

        // minimal passes a non-null empty tools list (defensive-copy case); the
        // null-tools case must be constructed explicitly.
        ChatRequest noTools = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                null, // tools — null stays null
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
        assertNull(noTools.tools());
    }

    private static ChatRequest minimal(Map<String, Object> extras, Map<String, Object> meta) {
        return new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null, // system
                java.util.List.of(), // tools — empty, non-null for defensive-copy assertion
                null, // toolChoice
                null, // temperature
                null, // topP
                null, // topK
                null, // maxTokens
                null, // stop
                null, // seed
                null, // n
                null, // frequencyPenalty
                null, // presencePenalty
                null, // logitBias
                null, // responseFormat
                false, // stream
                null, // streamOptions
                null, // reasoning
                null, // cacheControl
                extras,
                meta);
    }
}
