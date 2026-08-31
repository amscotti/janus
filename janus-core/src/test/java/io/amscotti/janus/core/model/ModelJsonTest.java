package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidTypeIdException;

/**
 * JSON round-trip tests on a plain {@link ObjectMapper} (no Micronaut harness) — the
 * canonical model must not depend on framework-provided mappers. Committed golden
 * fixtures are {@code GoldenMatrixTest}'s job; these are inline strings.
 */
class ModelJsonTest {

    private final ObjectMapper mapper = JsonSupport.mapper();

    @Test
    void chatRequestRoundTripsAllMessageKinds() throws Exception {
        ChatRequest request = fullRequest(null);
        String json = mapper.writeValueAsString(request);
        assertTrue(json.contains("\"role\":\"system\""));
        assertTrue(json.contains("\"role\":\"user\""));
        assertTrue(json.contains("\"role\":\"assistant\""));
        assertTrue(json.contains("\"role\":\"tool\""));
        assertTrue(json.contains("\"model\":\"model-1\""));

        ChatRequest decoded = mapper.readValue(json, ChatRequest.class);
        assertEquals(request, decoded);
    }

    @Test
    void polymorphicMessageListRoundTrips() throws Exception {
        List<Message> messages = List.of(
                new SystemMessage("be brief"),
                new UserMessage("hi"),
                new AssistantMessage(
                        "calling",
                        List.of(new ToolCall(
                                "call_1", "function", new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                new ToolMessage("call_1", "{\"temp\":18}"),
                new DeveloperMessage("be correct", "author"));

        // Jackson 3 (tools.jackson 3.1.x) resolves bare-List elements by runtime type and
        // drops interface-level @JsonTypeInfo on that path (probe-verified), so serialize
        // with the declared element type. Codecs always serialize message lists inside
        // declared-typed containers (ChatRequest/ChatResponse/StreamChunk), which work —
        // this test pins the polymorphic round-trip contract itself.
        String json = mapper.writerFor(new TypeReference<List<Message>>() {}).writeValueAsString(messages);
        assertTrue(json.contains("\"role\":\"developer\""), json);
        List<Message> decoded = mapper.readValue(json, new TypeReference<List<Message>>() {});
        assertEquals(messages, decoded);
    }

    @Test
    void bareListOfMessagesDropsRoleDiscriminator() throws Exception {
        // Guard test for the documented bare-List hazard (see Message javadoc): Jackson 3
        // ignores interface-level @JsonTypeInfo when serializing by runtime type, so a bare
        // List<Message> emits no "role" and cannot round-trip. Any future code path that
        // serializes message lists as bare lists (e.g. a passthrough fast path building
        // Map.of("messages", canonicalRequest.messages)) must fail here in CI, not with
        // silently corrupt provider wire output in production.
        List<Message> messages = List.of(new SystemMessage("s"), new UserMessage("u"));
        String json = mapper.writeValueAsString(messages);
        assertFalse(json.contains("role"), "bare List<Message> must not emit a role discriminator: " + json);
        assertThrows(
                InvalidTypeIdException.class,
                () -> mapper.readValue(json, new TypeReference<List<Message>>() {}),
                "role-less output must fail to round-trip");
    }

    @Test
    void metaIsNeverSerialized() throws Exception {
        ChatRequest request = fullRequest(Map.of("key_id", "k1", "attempt", 2));
        String json = mapper.writeValueAsString(request);
        assertFalse(json.contains("key_id"));
        assertFalse(json.contains("\"meta\""));
    }

    @Test
    void chatResponseMetaIsNeverSerialized() throws Exception {
        // ChatResponse carries the same gateway-internal meta privacy contract as
        // ChatRequest — a regression (dropped @JsonIgnore) must be caught here, not leak
        // gateway context to a provider.
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "model-1",
                List.of(new ChatChoice(0, new AssistantMessage("hello", null), ChatResponse.STOP_REASON_STOP)),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of("provider", "deepseek"),
                Map.of("key_id", "k1", "attempt", 2));
        String json = mapper.writeValueAsString(response);
        assertFalse(json.contains("key_id"));
        assertFalse(json.contains("attempt"));
        assertFalse(json.contains("\"meta\""));
    }

    @Test
    void nullStreamPrimitiveDeserializesToFalse() throws Exception {
        // Pins the FAIL_ON_NULL_FOR_PRIMITIVES contract (JsonSupport): a provider request
        // with an explicit "stream": null deserializes to the Java default (false) instead
        // of failing. Codecs (+) must configure their mappers the same way.
        String json = """
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "stream": null
                }
                """;
        ChatRequest decoded = mapper.readValue(json, ChatRequest.class);
        assertFalse(decoded.stream());
    }

    @Test
    void chatRoleSerializesLowercase() throws Exception {
        assertEquals("\"system\"", mapper.writeValueAsString(ChatRole.SYSTEM));
        assertEquals(ChatRole.TOOL, mapper.readValue("\"tool\"", ChatRole.class));
    }

    @Test
    void unknownPropertiesAreTolerated() throws Exception {
        String json = """
                {
                  "model": "model-1",
                  "messages": [{"role": "user", "content": "hi"}],
                  "future_field": {"anything": [1, 2, 3]}
                }
                """;
        ChatRequest decoded = mapper.readValue(json, ChatRequest.class);
        assertEquals("model-1", decoded.model());
        assertEquals(List.of(new UserMessage("hi")), decoded.messages());
    }

    @Test
    void toolDescriptionAndUsageCacheFieldsRoundTripThroughCanonicalJson() throws Exception {
        // ToolDefinition.description: tool-definition descriptions must have a canonical
        // home (this replaced the extras hack) — pinned here on the model JSON path.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("hi")),
                null,
                List.of(new ToolDefinition("function", "get_weather", "current weather", "{\"type\":\"object\"}")),
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
        ChatRequest decoded = mapper.readValue(mapper.writeValueAsString(request), ChatRequest.class);
        assertEquals(request, decoded);

        // Usage cache fields: NON_NULL inclusion keeps canonical JSON byte-identical
        // when absent (pinned tests) and carries them when present.
        Usage plain = new Usage(10, 5, 15);
        String plainJson = mapper.writeValueAsString(plain);
        assertFalse(plainJson.contains("cache"), plainJson);
        assertEquals(plain, mapper.readValue(plainJson, Usage.class));

        Usage cached = new Usage(10, 5, 15, 2L, 3L);
        String cachedJson = mapper.writeValueAsString(cached);
        assertTrue(cachedJson.contains("\"cacheCreationInputTokens\":2"), cachedJson);
        assertTrue(cachedJson.contains("\"cacheReadInputTokens\":3"), cachedJson);
        assertEquals(cached, mapper.readValue(cachedJson, Usage.class));
    }

    @Test
    void responseAndStreamChunkRoundTrip() throws Exception {
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "model-1",
                List.of(new ChatChoice(0, new AssistantMessage("hello", null), ChatResponse.STOP_REASON_STOP)),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of("provider", "deepseek"),
                null);
        assertEquals(response, mapper.readValue(mapper.writeValueAsString(response), ChatResponse.class));

        StreamChunk chunk = new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "model-1",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "hel", null), null)),
                new Usage(1, 2, 3),
                Map.of());
        assertEquals(chunk, mapper.readValue(mapper.writeValueAsString(chunk), StreamChunk.class));
    }

    private static ChatRequest fullRequest(Map<String, Object> meta) {
        return new ChatRequest(
                "model-1",
                List.of(
                        new SystemMessage("be brief"),
                        new UserMessage("what is the weather in Paris?"),
                        new AssistantMessage(
                                null,
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        new ToolMessage("call_1", "{\"temp\":18}")),
                null, // system
                List.of(new ToolDefinition("function", "get_weather", null, "{\"city\":\"Paris\"}")),
                Map.of("type", "auto"), // toolChoice
                0.7, // temperature
                null, // topP
                null, // topK
                512, // maxTokens
                List.of("END"), // stop
                null, // seed
                1, // n
                0.0, // frequencyPenalty
                0.0, // presencePenalty
                null, // logitBias
                null, // responseFormat
                false, // stream
                Map.of("include_usage", true), // streamOptions
                null, // reasoning
                null, // cacheControl
                Map.of("custom", "pass-through"), // extras
                meta);
    }
}
