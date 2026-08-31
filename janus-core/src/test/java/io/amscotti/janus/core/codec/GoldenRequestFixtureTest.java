package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ToolDefinition;
import io.amscotti.janus.core.model.UserMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * byte-golden request fixtures: {@code encodeRequest(canonical)} must reproduce the
 * committed {@code chat.request.json}/{@code chat.request.stream.json} bytes exactly
 * (deterministic DTO output order, {@code @JsonInclude(NON_NULL)}), the same canonical
 * must decode from the fixture, and decode → re-encode must be byte-identical. The
 * fixtures are constructed to be round-trip idempotent (exactly one system message, no
 * {@code system}-field + {@code SystemMessage} duplication, no tool {@code description}
 * — pinned edges, see the fixture README).
 */
class GoldenRequestFixtureTest {

    private final OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());

    @Test
    void nonStreamingRequestIsByteGoldenAndRoundTrips() throws Exception {
        ChatRequest canonical = nonStreamingCanonical();
        String fixture = read("chat.request.json");

        assertEquals(fixture, codec.encodeRequest(canonical), "encode must reproduce the committed bytes exactly");
        assertEquals(canonical, codec.decodeRequest(fixture), "the committed fixture must decode to the canonical");
        assertEquals(
                fixture,
                codec.encodeRequest(codec.decodeRequest(fixture)),
                "decode → re-encode must be byte-identical");
    }

    @Test
    void streamingRequestIsByteGoldenAndRoundTrips() throws Exception {
        ChatRequest canonical = streamingCanonical();
        String fixture = read("chat.request.stream.json");

        assertEquals(fixture, codec.encodeRequest(canonical), "encode must reproduce the committed bytes exactly");
        assertEquals(canonical, codec.decodeRequest(fixture), "the committed fixture must decode to the canonical");
        assertEquals(
                fixture,
                codec.encodeRequest(codec.decodeRequest(fixture)),
                "decode → re-encode must be byte-identical");
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * The canonical behind {@code chat.request.json}: system + user + tools +
     * {@code tool_choice}, {@code stream=false} — the adapter contract sends the
     * non-streaming shape without a {@code stream} member.
     */
    private static ChatRequest nonStreamingCanonical() {
        return new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("What is the weather in Paris?")),
                "You are a helpful assistant.",
                List.of(
                        new ToolDefinition(
                                "function",
                                "get_weather",
                                null,
                                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}")),
                "auto", // toolChoice
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
                false, // stream — omitted on the wire
                null,
                null,
                null,
                null,
                null);
    }

    /** The canonical behind {@code chat.request.stream.json}: {@code stream: true} +
     * {@code stream_options.include_usage: true}. */
    private static ChatRequest streamingCanonical() {
        return new ChatRequest(
                "deepseek-v4-flash",
                List.of(new UserMessage("What is the weather in Paris?")),
                "You are a helpful assistant.",
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
                true, // stream
                Map.of("include_usage", true), // streamOptions
                null,
                null,
                null,
                null);
    }

    private static String read(String relativePath) throws IOException {
        try (InputStream in = GoldenRequestFixtureTest.class.getResourceAsStream("/fixtures/openai/" + relativePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
