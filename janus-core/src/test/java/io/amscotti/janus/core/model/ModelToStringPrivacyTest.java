package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Conversation content (prompts, completions, tool arguments, image payloads) is
 * excluded from log output by default: the string form of the canonical records
 * prints structure only. Operational detail (models, counts, stop reasons, token
 * usage presence) still prints so a log line remains diagnosable. With
 * {@link ContentLogging} explicitly enabled — the gateway's
 * {@code [janus.privacy] log-content} config — the full content prints, for local
 * debugging.
 */
class ModelToStringPrivacyTest {

    private static final String MARKER = "SECRET-PROMPT-MARKER-9f1c";

    @AfterEach
    void reset() {
        ContentLogging.disable();
    }

    @Test
    void contentIsExcludedFromLogOutputByDefault() {
        assertFalse(new UserMessage(MARKER).toString().contains(MARKER));
        assertFalse(new AssistantMessage(MARKER, null).toString().contains(MARKER));
        assertFalse(new SystemMessage(MARKER).toString().contains(MARKER));
        assertFalse(new DeveloperMessage(MARKER).toString().contains(MARKER));
        assertFalse(new ToolMessage("call_1", MARKER).toString().contains(MARKER));
        assertFalse(new Delta(null, MARKER, null).toString().contains(MARKER));
        assertFalse(new TextContent(MARKER).toString().contains(MARKER));
        assertFalse(new FunctionCall("f", MARKER).toString().contains(MARKER));
        assertFalse(
                new ImageUrlContent("https://x/" + MARKER + ".png").toString().contains(MARKER));
        assertTrue(new ImageUrlContent("https://x/y.png", "high").toString().contains("detail=high"));
        assertFalse(ImageSourceContent.base64("image/png", MARKER).toString().contains(MARKER));
        assertFalse(ImageSourceContent.url("https://x/" + MARKER).toString().contains(MARKER));
        // Hosted web-search invocation: the query is user conversation content.
        HostedToolCall.WebSearchCall search = new HostedToolCall.WebSearchCall(MARKER);
        assertFalse(search.toString().contains(MARKER));
        assertTrue(search.toString().contains("query not logged"));
        // Hosted web-search declaration: userLocation is a user-provided PII map.
        HostedToolDefinition.WebSearch webSearch = new HostedToolDefinition.WebSearch("high", Map.of("city", MARKER));
        assertFalse(webSearch.toString().contains(MARKER));
        assertTrue(webSearch.toString().contains("searchContextSize=high"));
        assertTrue(webSearch.toString().contains("userLocation not logged"));
        assertTrue(new DeveloperMessage(MARKER, "alice").toString().contains("name=alice"));
        assertFalse(new DeveloperMessage(MARKER, "alice").toString().contains(MARKER));

        // The default form is still structurally useful (operational fields print).
        assertTrue(new UserMessage(MARKER).toString().contains("content not logged"));
        assertTrue(new ToolMessage("call_1", MARKER).toString().contains("toolCallId=call_1"));
    }

    @Test
    void containersExcludeContentAndExtrasByDefault() {
        ChatRequest request = new ChatRequest(
                "m",
                List.of(new UserMessage(MARKER)),
                MARKER,
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
                null,
                false,
                null,
                null,
                null,
                null,
                Map.of("note", MARKER),
                null);
        assertFalse(request.toString().contains(MARKER), "request: messages, system, extras all excluded");

        ChatResponse response = new ChatResponse(
                "id",
                "chat.completion",
                1L,
                "m",
                List.of(new ChatChoice(0, new AssistantMessage(MARKER, null), "stop")),
                null,
                null,
                Map.of("note", MARKER),
                null);
        assertFalse(response.toString().contains(MARKER), "response: choices and extras excluded");

        StreamChunk chunk = new StreamChunk(
                "id",
                "chat.completion.chunk",
                1L,
                "m",
                List.of(new ChunkChoice(0, new Delta(null, MARKER, null), null)),
                null,
                Map.of("note", MARKER));
        assertFalse(chunk.toString().contains(MARKER), "chunk: deltas and extras excluded");
    }

    @Test
    void nullCollectionsDoNotThrowInDefaultForm() {
        // Construct-and-fail-later: messages/choices may be null before codec
        // validation, and a usage-only chunk carries null choices — toString
        // must not NPE on the log path.
        new ChatRequest(
                        "m", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        false, null, null, null, null, null, null)
                .toString();
        new ChatResponse("id", "chat.completion", 1L, "m", null, null, null, null, null).toString();
        new StreamChunk("id", "chat.completion.chunk", 1L, "m", null, null, null).toString();
    }

    @Test
    void explicitlyEnabledContentLoggingPrintsContent() {
        ContentLogging.enable();
        assertTrue(new UserMessage(MARKER).toString().contains(MARKER));
        assertTrue(new ImageUrlContent("https://x/" + MARKER, "high").toString().contains(MARKER));
        assertTrue(new ImageUrlContent("https://x/" + MARKER).toString().contains(MARKER));
        assertTrue(ImageSourceContent.base64("image/png", MARKER).toString().contains(MARKER));
        assertTrue(ImageSourceContent.url("https://x/" + MARKER).toString().contains(MARKER));
        assertTrue(new DeveloperMessage(MARKER, "alice").toString().contains(MARKER));
        assertTrue(new AssistantMessage(MARKER, null).toString().contains(MARKER));
        assertTrue(new SystemMessage(MARKER).toString().contains(MARKER));
        assertTrue(new ToolMessage("call_1", MARKER).toString().contains(MARKER));
        assertTrue(new Delta(null, MARKER, null).toString().contains(MARKER));
        assertTrue(new FunctionCall("f", MARKER).toString().contains(MARKER));
        assertTrue(new TextContent(MARKER).toString().contains(MARKER));
        assertTrue(new HostedToolCall.WebSearchCall(MARKER).toString().contains(MARKER));
        assertTrue(new HostedToolDefinition.WebSearch("high", Map.of("city", MARKER))
                .toString()
                .contains(MARKER));

        ChatRequest request = new ChatRequest(
                "m",
                List.of(new UserMessage(MARKER)),
                MARKER,
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
                null,
                false,
                null,
                null,
                null,
                null,
                Map.of("note", MARKER),
                null);
        assertTrue(request.toString().contains(MARKER));
    }
}
