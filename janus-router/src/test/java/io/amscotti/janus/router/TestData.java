package io.amscotti.janus.router;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Shared builders for router/strategy tests: canonical records constructed directly,
 * no network, no Jackson, no real adapters (AGENTS.md boundary). {@link RouterTest}'s
 * private helpers stay untouched — this class serves the test classes only.
 */
final class TestData {

    private TestData() {}

    static ChatRequest request(String model) {
        return new ChatRequest(
                model, List.of(), // messages
                null, // system
                null, // tools
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
                null, // extras
                null); // meta
    }

    static ChatResponse response(String model) {
        return response("resp-" + model, model, null);
    }

    static ChatResponse response(String model, Usage usage) {
        return response("resp-" + model, model, usage);
    }

    static ChatResponse response(String id, String model, Usage usage) {
        return new ChatResponse(
                id, "chat.completion", 0L, model, List.of(), usage, ChatResponse.STOP_REASON_STOP, Map.of(), Map.of());
    }

    static StreamChunk chunk() {
        return new StreamChunk("chunk-1", "chat.completion.chunk", 0L, "m", List.of(), null, Map.of());
    }

    static FakeBackend fake(String name) {
        return new FakeBackend(name, response(name), null);
    }

    static FakeBackend fake(String name, ChatResponse response) {
        return new FakeBackend(name, response, null);
    }

    static FakeBackend fake(String name, ChatResponse response, Stream<StreamChunk> stream) {
        return new FakeBackend(name, response, stream);
    }
}
