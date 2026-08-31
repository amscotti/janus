package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamingTest {

    @Test
    void usageIsNullable() {
        assertNull(new StreamChunk("id", "chat.completion.chunk", 1L, "m", List.of(), null, null).usage());
        Usage usage = new Usage(1, 2, 3);
        StreamChunk finalChunk = new StreamChunk("id", "chat.completion.chunk", 1L, "m", List.of(), usage, null);
        assertEquals(usage, finalChunk.usage());
    }

    @Test
    void deltaAllowsPartialContent() {
        assertNull(new Delta(ChatRole.ASSISTANT, null, null).content());
        assertNull(new Delta(null, "hi", null).role());
        assertNull(new Delta(null, null, null).toolCalls());
        List<ToolCall> calls = List.of(new ToolCall("call_1", "function", new FunctionCall("f", "{}")));
        assertEquals(calls, new Delta(null, null, calls).toolCalls());
    }

    @Test
    void deltaToolCallsTolerateNullElements() {
        // Malformed wire input (a streamed "tool_calls":[null] fragment) must not
        // escape the compact constructor as a raw NullPointerException from
        // List.copyOf (same class as ChatRequest's component lists).
        List<ToolCall> withNull = new ArrayList<>();
        withNull.add(new ToolCall("call_1", "function", new FunctionCall("f", "{}")));
        withNull.add(null);
        Delta delta = new Delta(ChatRole.ASSISTANT, "hi", withNull);
        assertEquals(2, delta.toolCalls().size());
        assertNull(delta.toolCalls().get(1));
        assertThrows(
                UnsupportedOperationException.class, () -> delta.toolCalls().add(null));
    }

    @Test
    void chunkChoiceFinishReasonIsNullable() {
        assertNull(new ChunkChoice(0, new Delta(null, "hi", null), null).finishReason());
        assertEquals("stop", new ChunkChoice(0, new Delta(null, "hi", null), "stop").finishReason());
    }

    @Test
    void chunkExtrasDefaultToEmpty() {
        StreamChunk chunk = new StreamChunk("id", "chat.completion.chunk", 1L, "m", List.of(), null, null);
        assertEquals(Map.of(), chunk.extras());
    }
}
