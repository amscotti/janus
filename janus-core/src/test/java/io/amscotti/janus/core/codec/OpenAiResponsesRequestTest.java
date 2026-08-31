package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Compact-constructor pins for {@link OpenAiResponsesRequest}: the codec decodes
 * from the JSON tree, but the record still copies collections (callers and
 * Jackson must not be able to mutate a constructed instance).
 */
class OpenAiResponsesRequestTest {

    @Test
    void absentCollectionsStayNullAndExtrasDefaultEmpty() {
        OpenAiResponsesRequest request = new OpenAiResponsesRequest(
                "m", "hello", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
        assertEquals("m", request.model());
        assertEquals("hello", request.input());
        assertEquals(Map.of(), request.extras());
    }

    @Test
    void collectionsAreCopiedAndUnmodifiable() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(new HashMap<>(Map.of("type", "function")));
        List<String> include = new ArrayList<>(List.of("file_search_call.results"));
        Map<String, Object> reasoning = new HashMap<>(Map.of("effort", "none"));
        Map<String, Object> extras = new HashMap<>(Map.of("unknown", 1));

        OpenAiResponsesRequest request = new OpenAiResponsesRequest(
                "m",
                List.of(),
                null,
                tools,
                null,
                null,
                null,
                16,
                reasoning,
                null,
                false,
                false,
                "disabled",
                false,
                null,
                null,
                include,
                null,
                "user",
                extras);

        tools.clear();
        include.clear();
        reasoning.put("effort", "high");
        extras.put("unknown", 2);

        assertEquals(1, request.tools().size());
        assertEquals(List.of("file_search_call.results"), request.include());
        assertEquals("none", request.reasoning().get("effort"));
        assertEquals(1, request.extras().get("unknown"));
        assertThrows(UnsupportedOperationException.class, () -> request.tools().add(Map.of()));
        assertThrows(
                UnsupportedOperationException.class, () -> request.include().add("x"));
        assertThrows(
                UnsupportedOperationException.class, () -> request.reasoning().put("effort", "low"));
        assertThrows(UnsupportedOperationException.class, () -> request.extras().put("x", 1));
    }

    @Test
    void jacksonBindsTheRecordThroughTheCodecMapper() throws Exception {
        OpenAiResponsesRequest request = JsonSupport.mapper().readValue("""
                        {"model":"m","instructions":"be brief","max_output_tokens":8,
                         "reasoning":{"effort":"low"},"store":false,"extra_field":true}
                        """, OpenAiResponsesRequest.class);
        assertEquals("m", request.model());
        assertEquals("be brief", request.instructions());
        assertEquals(8, request.maxOutputTokens());
        assertEquals("low", request.reasoning().get("effort"));
        assertEquals(Boolean.FALSE, request.store());
        assertTrue(request.extras().containsKey("extra_field"));
    }
}
