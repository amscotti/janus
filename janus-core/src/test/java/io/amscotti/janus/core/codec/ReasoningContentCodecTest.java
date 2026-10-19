package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ReasoningContent;
import io.amscotti.janus.core.model.StreamChunk;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Signed thinking round-trip: Anthropic {@code thinking}/{@code redacted_thinking}
 * blocks and {@code thinking_delta}/{@code signature_delta} frames ride the
 * canonical {@link ReasoningContent} slots so tool-loop replay keeps the
 * signature Anthropic requires, and reasoning text crosses to the OpenAI face
 * as {@code reasoning_content} instead of disappearing.
 */
class ReasoningContentCodecTest {

    private final AnthropicMessageCodec anthropic = AnthropicMessageCodec.create();
    private final OpenAiMessageCodec openAi = OpenAiMessageCodec.create();
    private final OpenAiResponsesCodec responses = OpenAiResponsesCodec.create();
    private final ObjectMapper mapper = JsonSupport.mapper();

    @Test
    void anthropicRequestThinkingAndToolUseReplayKeepsSignature() throws Exception {
        ChatRequest decoded = anthropic.decodeRequest("""
                {
                  "model": "claude-sonnet-5-5",
                  "max_tokens": 16,
                  "messages": [
                    {"role": "user", "content": "weather?"},
                    {"role": "assistant", "content": [
                      {"type": "thinking", "thinking": "need the tool", "signature": "sig_abc"},
                      {"type": "tool_use", "id": "t1", "name": "get", "input": {}}
                    ]},
                    {"role": "user", "content": [
                      {"type": "tool_result", "tool_use_id": "t1", "content": "sunny"}
                    ]}
                  ]
                }
                """);
        AssistantMessage assistant =
                assertInstanceOf(AssistantMessage.class, decoded.messages().get(1));
        assertEquals(List.of(new ReasoningContent("need the tool", "sig_abc", null)), assistant.reasoningContents());

        JsonNode content = mapper.readTree(anthropic.encodeRequest(decoded))
                .get("messages")
                .get(1)
                .get("content");
        assertEquals("thinking", content.get(0).get("type").asString());
        assertEquals("need the tool", content.get(0).get("thinking").asString());
        assertEquals("sig_abc", content.get(0).get("signature").asString());
        assertEquals("tool_use", content.get(1).get("type").asString());
    }

    @Test
    void anthropicRedactedThinkingRoundTrips() throws Exception {
        ChatRequest decoded = anthropic.decodeRequest("""
                {
                  "model": "claude-opus-5-5",
                  "max_tokens": 16,
                  "messages": [
                    {"role": "assistant", "content": [
                      {"type": "redacted_thinking", "data": "opaque-blob"},
                      {"type": "text", "text": "done"}
                    ]},
                    {"role": "user", "content": "next"}
                  ]
                }
                """);
        AssistantMessage assistant =
                assertInstanceOf(AssistantMessage.class, decoded.messages().get(0));
        assertEquals(List.of(new ReasoningContent(null, null, "opaque-blob")), assistant.reasoningContents());
        JsonNode content = mapper.readTree(anthropic.encodeRequest(decoded))
                .get("messages")
                .get(0)
                .get("content");
        assertEquals("redacted_thinking", content.get(0).get("type").asString());
        assertEquals("opaque-blob", content.get(0).get("data").asString());
    }

    @Test
    void anthropicResponseThinkingCrossesToOpenAiReasoningContent() throws Exception {
        ChatResponse decoded = anthropic.decodeResponse("""
                {
                  "id": "msg_1", "type": "message", "role": "assistant",
                  "model": "claude-sonnet-5-5",
                  "content": [
                    {"type": "thinking", "thinking": "pondering", "signature": "sig_x"},
                    {"type": "text", "text": "answer"}
                  ],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 3, "output_tokens": 5}
                }
                """);
        AssistantMessage assistant = assertInstanceOf(
                AssistantMessage.class, decoded.choices().get(0).message());
        assertEquals(List.of(new ReasoningContent("pondering", "sig_x", null)), assistant.reasoningContents());

        JsonNode anthropicBack = mapper.readTree(anthropic.encodeResponse(decoded));
        assertEquals(
                "sig_x", anthropicBack.get("content").get(0).get("signature").asString());

        JsonNode openAiWire = mapper.readTree(openAi.encodeResponse(decoded));
        assertEquals(
                "pondering",
                openAiWire
                        .get("choices")
                        .get(0)
                        .get("message")
                        .get("reasoning_content")
                        .asString());
        assertEquals(
                "answer",
                openAiWire.get("choices").get(0).get("message").get("content").asString());

        // Responses face: the reasoning rides a reasoning output item ahead
        // of the message item, as summary text.
        JsonNode responsesWire = mapper.readTree(responses.encodeResponse(null, decoded));
        JsonNode item = responsesWire.get("output").get(0);
        assertEquals("reasoning", item.get("type").asString());
        assertEquals("summary_text", item.get("summary").get(0).get("type").asString());
        assertEquals("pondering", item.get("summary").get(0).get("text").asString());
        assertEquals("message", responsesWire.get("output").get(1).get("type").asString());
    }

    @Test
    void anthropicThinkingStreamReEmitsOnBothFaces() throws Exception {
        StreamChunk thinking = anthropic.decodeChunk(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Let me\"}}");
        assertEquals(
                new ReasoningContent("Let me", null, null),
                thinking.choices().get(0).delta().reasoningContent());
        StreamChunk signature = anthropic.decodeChunk(
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_1\"}}");
        assertEquals(
                new ReasoningContent(null, "sig_1", null),
                signature.choices().get(0).delta().reasoningContent());

        // OpenAI face: reasoning text rides delta.reasoning_content.
        String openAiChunk = openAi.encodeChunk(thinking);
        assertTrue(openAiChunk.contains("\"reasoning_content\":\"Let me\""), openAiChunk);

        // Anthropic face: a thinking block opens and carries both deltas.
        AnthropicStreamEncoder encoder = anthropic.newStreamEncoder();
        List<AnthropicSseEvent> events = new java.util.ArrayList<>();
        events.addAll(encoder.feed(thinking));
        events.addAll(encoder.feed(signature));
        events.addAll(encoder.finish());
        String wire = events.stream().map(AnthropicSseEvent::dataJson).reduce("", String::concat);
        assertTrue(wire.contains("\"type\":\"thinking\""), wire);
        assertTrue(wire.contains("\"type\":\"thinking_delta\",\"thinking\":\"Let me\""), wire);
        assertTrue(wire.contains("\"type\":\"signature_delta\",\"signature\":\"sig_1\""), wire);
    }

    @Test
    void openAiReasoningContentDecodesUnsignedAndIsNotForgedOnAnthropicEncode() {
        ChatRequest decoded = openAi.decodeRequest("""
                {
                  "model": "claude-sonnet-5-5",
                  "messages": [
                    {"role": "user", "content": "hi"},
                    {"role": "assistant", "content": "answer", "reasoning_content": "foreign thought"}
                  ]
                }
                """);
        AssistantMessage assistant =
                assertInstanceOf(AssistantMessage.class, decoded.messages().get(1));
        assertEquals(List.of(new ReasoningContent("foreign thought", null, null)), assistant.reasoningContents());
        // No signature exists to replay; the Anthropic leg must not invent one.
        String json = anthropic.encodeRequest(decoded);
        assertFalse(json.contains("\"thinking\""), json);
        assertTrue(json.contains("answer"), json);
        // The OpenAI leg keeps it.
        assertTrue(openAi.encodeRequest(decoded).contains("\"reasoning_content\":\"foreign thought\""));
    }
}
