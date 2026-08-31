package io.amscotti.janus.core.codec;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.core.model.ToolDefinition;
import io.amscotti.janus.core.model.ToolMessage;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.core.model.UserMessage;
import java.util.List;
import java.util.Map;

/**
 * the single source of truth for the {@code fixtures/matrix/canonical/} shapes
 * (plain/tools/stream requests + the stream chunk sequence). The {@link
 * MatrixFixtureGeneratorTest} constructs exactly these records, serializes them with
 * {@link JsonSupport#mapper} into {@code canonical/*.json}, and derives every
 * Janus-generated wire leg from them; {@link GoldenMatrixTest} and the property suites
 * read the committed files back and compare by record equality. A codec change that
 * alters wire bytes therefore fails {@code GoldenMatrixTest} against the committed
 * corpus, and re-running {@code./gradlew :janus-core:captureFixtures} regenerates it.
 *
 * <p>The shapes follow the round-trip-idempotence rules verbatim: exactly one system
 * message (in the canonical {@code system} field — never a {@code SystemMessage} in
 * {@code messages}), no {@code system}-field + {@code SystemMessage} duplication, and no
 * tool {@code description} that could fold into extras ( gave {@code description} a
 * canonical home, but the golden set stays minimal — see the matrix README). The model
 * alias {@code "deepseek-v4-flash"} rides through unchanged on every wire (model remapping
 * is the router's concern, out of scope for the codec matrix).
 */
final class MatrixCanonicals {

    /** The corpus-wide conversation ('s), shared by every mode. */
    static final String MODEL = "deepseek-v4-flash";

    static final String SYSTEM = "You are a helpful assistant.";
    static final String USER_PROMPT = "What is the weather in Paris?";

    /** The corpus chunk id + timestamps (shared synthetic identifiers). */
    static final String CHUNK_ID = "chatcmpl-2f4e1c1b9c8a4f2f9c1b2d3e4f5a6b7c";

    static final long CREATED = 1785715200L;

    /** Tool schema ('s, verbatim) — the canonical {@code FunctionCall.arguments}. */
    static final String TOOL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}";

    /** The Anthropic codec's {@code max_tokens} default — carried explicitly so the
     * Anthropic wire round-trips idempotently (documented in the matrix README). */
    static final int MAX_TOKENS = 4096;

    private MatrixCanonicals() {}

    /**
     * Plain (system + user, no tools) non-streaming canonical request. {@code maxTokens}
     * is set explicitly to the Anthropic codec's default (4096) so the Anthropic wire
     * round-trips idempotently (a {@code null} canonical would re-decode as 4096 — the
     * documented default; the golden set stays round-trip idempotent, rule).
     */
    static ChatRequest plainRequest() {
        return new ChatRequest(
                MODEL,
                List.of(new UserMessage(USER_PROMPT)),
                SYSTEM,
                null,
                null,
                null,
                null,
                null,
                MAX_TOKENS,
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
    }

    /**
     * Tools canonical request: system + user + assistant tool call + tool result +
     * tool definition + {@code tool_choice "auto"} ('s richest shape). Round-trip
     * idempotent through both codecs.
     */
    static ChatRequest toolsRequest() {
        return new ChatRequest(
                MODEL,
                List.of(
                        new UserMessage(USER_PROMPT),
                        new AssistantMessage(
                                null,
                                List.of(new ToolCall(
                                        "call_1",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
                        new ToolMessage("call_1", "{\"temp\":18}")),
                SYSTEM,
                List.of(new ToolDefinition("function", "get_weather", null, TOOL_SCHEMA)),
                "auto",
                null,
                null,
                null,
                MAX_TOKENS,
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
    }

    /**
     * Streaming canonical request: system + user, {@code stream: true} and
     * {@code stream_options.include_usage: true} (a client that already requested
     * include_usage — the / adapter never injects it, it only preserves
     * {@code request.streamOptions} on the streaming path; the canonical carries the
     * flag so OpenAI-outbound streams emit it).
     */
    static ChatRequest streamRequest() {
        return new ChatRequest(
                MODEL,
                List.of(new UserMessage(USER_PROMPT)),
                SYSTEM,
                null,
                null,
                null,
                null,
                null,
                MAX_TOKENS,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                Map.of("include_usage", true),
                null,
                null,
                null,
                null);
    }

    /**
     * The canonical stream chunk sequence (8 chunks) — the exact decode of 's
     * {@code openai/chat.stream.sse}: role chunk (content {@code ""}), 5 content deltas,
     * terminal finish chunk, terminal usage chunk.
     */
    static List<StreamChunk> streamChunks() {
        return List.of(
                new StreamChunk(
                        CHUNK_ID,
                        "chat.completion.chunk",
                        CREATED,
                        MODEL,
                        List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "", null), null)),
                        null,
                        Map.of()),
                contentChunk("The"),
                contentChunk(" weather"),
                contentChunk(" in Paris"),
                contentChunk(" is 18"),
                contentChunk(" degrees with light rain."),
                new StreamChunk(
                        CHUNK_ID,
                        "chat.completion.chunk",
                        CREATED,
                        MODEL,
                        List.of(new ChunkChoice(0, new Delta(null, null, null), "stop")),
                        null,
                        Map.of()),
                new StreamChunk(
                        CHUNK_ID, "chat.completion.chunk", CREATED, MODEL, List.of(), new Usage(14, 12, 26), Map.of()));
    }

    private static StreamChunk contentChunk(String content) {
        return new StreamChunk(
                CHUNK_ID,
                "chat.completion.chunk",
                CREATED,
                MODEL,
                List.of(new ChunkChoice(0, new Delta(null, content, null), null)),
                null,
                Map.of());
    }
}
