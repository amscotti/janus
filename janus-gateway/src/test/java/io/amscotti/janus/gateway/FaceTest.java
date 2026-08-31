package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@link Face} route vocabulary: exact matching for the two
 * chat faces (anything else, including subpaths, is not the face), prefix matching
 * for the Responses face (sub-routes like {@code GET /v1/responses/{id}} are the
 * face), POST gating only for exact faces, and the fallback label for admin/unknown
 * paths.
 */
class FaceTest {

    @Test
    void chatFacesMatchExactlyAndOnlyTheirRoute() {
        assertTrue(Face.OPENAI.requiresVirtualKey("/v1/chat/completions"));
        assertTrue(Face.ANTHROPIC.requiresVirtualKey("/v1/messages"));
        assertFalse(Face.OPENAI.requiresVirtualKey("/v1/chat/completions/extra"));
        assertFalse(Face.ANTHROPIC.requiresVirtualKey("/v1/messagesfoo"));
        assertTrue(Face.OPENAI.gatesOnPostOnly(), "the chat faces 401 only on POST");
        assertTrue(Face.ANTHROPIC.gatesOnPostOnly());
    }

    @Test
    void responsesFaceMatchesItsPrefixOnAnyMethod() {
        assertTrue(Face.RESPONSES.requiresVirtualKey("/v1/responses"));
        assertTrue(Face.RESPONSES.requiresVirtualKey("/v1/responses/resp_123"));
        assertFalse(Face.RESPONSES.requiresVirtualKey("/v1/responsesfoo"), "prefix boundary respected");
        assertFalse(Face.RESPONSES.gatesOnPostOnly(), "the prefix face 401s on every method — stub routes too");
    }

    @Test
    void ofResolvesTheOwningFaceOrEmpty() {
        assertEquals(Face.OPENAI, Face.of("/v1/chat/completions").orElseThrow());
        assertEquals(Face.RESPONSES, Face.of("/v1/responses/resp_1").orElseThrow());
        assertTrue(Face.of("/key/generate").isEmpty(), "admin routes are not a face");
        assertTrue(Face.of("/v1/models").isEmpty(), "auth-off surface");
        assertTrue(Face.of(null).isEmpty());
    }

    @Test
    void labelsMatchTheTier1Enumeration() {
        assertEquals("openai", Face.OPENAI.label());
        assertEquals("anthropic", Face.ANTHROPIC.label());
        assertEquals("responses", Face.RESPONSES.label());
    }
}
