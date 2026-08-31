package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.SystemMessage;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.core.model.UserMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Full round-trip idempotence : canonical → wire → canonical for request,
 * response and stream chunk, plus the extras pass-through (null-valued entries
 * regression guard) and the pinned {@code system} + {@code SystemMessage} non-idempotence.
 */
class AnthropicRoundTripTest {

    private final AnthropicMessageCodec codec = new AnthropicMessageCodec(JsonSupport.mapper());

    @Test
    void requestRoundTripsIdempotently() {
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new UserMessage("what is the weather in Paris?"), new AssistantMessage("It is 18C.", null)),
                "be brief",
                null,
                null,
                0.7,
                0.9,
                50,
                512,
                List.of("END"),
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                // D1: streamOptions has no Anthropic wire home (the codec drops it —
                // a documented non-idempotence pinned by
                // CanonicalRoundTripPropertyTest.streamOptionsAreDroppedOnTheAnthropicWireAsDocumented);
                // the stream flag itself round-trips.
                null,
                Map.of("type", "enabled", "budget_tokens", 1024), // thinking ↔ reasoning (opaque)
                Map.of("type", "ephemeral"), // cache_control ↔ cacheControl (opaque)
                extrasWithNull("custom", "pass-through", "null_field", null),
                Map.of()); // meta — never emitted, never read back

        ChatRequest decoded = codec.decodeRequest(codec.encodeRequest(request));
        assertEquals(request, decoded);
    }

    @Test
    void responseRoundTripsIdempotently() {
        ChatResponse response = new ChatResponse(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                extrasWithNull("provider", "anthropic", "null_field", null),
                null);

        ChatResponse decoded = codec.decodeResponse(codec.encodeResponse(response));
        assertEquals(response, decoded);
    }

    @Test
    void chunkRoundTripsThroughEncoderAndDecoder() {
        StreamChunk start = new StreamChunk(
                "msg_1",
                "message",
                0L,
                "claude-3",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                null,
                Map.of());
        StreamChunk first = new StreamChunk(
                null, null, 0L, null, List.of(new ChunkChoice(0, new Delta(null, "Hel", null), null)), null, Map.of());
        StreamChunk second = new StreamChunk(
                null, null, 0L, null, List.of(new ChunkChoice(0, new Delta(null, "lo", null), null)), null, Map.of());
        StreamChunk terminal = new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(0, new Delta(null, null, null), "stop")),
                new Usage(10, 5, 15),
                Map.of());

        AnthropicStreamEncoder encoder = codec.newStreamEncoder();
        List<StreamChunk> decoded = new ArrayList<>();
        List.of(
                        encoder.feed(start),
                        encoder.feed(first),
                        encoder.feed(second),
                        encoder.feed(terminal),
                        encoder.finish())
                .forEach(events -> events.forEach(event -> {
                    StreamChunk chunk = codec.decodeChunk(event.event(), event.dataJson());
                    if (chunk != null) {
                        decoded.add(chunk);
                    }
                }));
        assertEquals(List.of(start, first, second, terminal), decoded);
    }

    @Test
    void extrasSurviveFullRoundTripsUnmodified() {
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
                null,
                null,
                false,
                null,
                null,
                null,
                extrasWithNull("opaque", Map.of("nested", List.of(1, 2)), "nil_value", null, "str", "v"),
                null);
        ChatRequest decoded = codec.decodeRequest(codec.encodeRequest(request));
        assertEquals(request.extras(), decoded.extras());
    }

    @Test
    void systemPlusSystemMessageIsNotRoundTripIdempotent() {
        // Pinned non-idempotence: encode merges system + SystemMessage
        // into one top-level system; decode yields the joined text with the SystemMessage gone.
        ChatRequest request = new ChatRequest(
                "model-1",
                List.of(new SystemMessage("be concise"), new UserMessage("hi")),
                "be brief",
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
                null);
        ChatRequest decoded = codec.decodeRequest(codec.encodeRequest(request));
        assertEquals("be brief\n\nbe concise", decoded.system());
        assertEquals(List.of(new UserMessage("hi")), decoded.messages());
    }

    /** {@link Map#of} forbids null values; build the extras map manually (m1 null-tolerance). */
    private static Map<String, Object> extrasWithNull(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
