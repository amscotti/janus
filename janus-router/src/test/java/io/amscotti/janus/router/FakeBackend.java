package io.amscotti.janus.router;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * test double: records calls and returns canned values. The stream fake observes
 * {@code onClose} to pin the pass-through-by-identity contract (no wrap layer in the
 * router). No network, no Jackson, no real adapters.
 */
final class FakeBackend implements ChatBackend {

    private final String name;
    private final String baseUrl;
    private final ChatResponse response;
    final Stream<StreamChunk> stream;
    final List<ChatRequest> completeCalls = new ArrayList<>();
    final List<ChatRequest> streamCalls = new ArrayList<>();
    final AtomicBoolean streamClosed = new AtomicBoolean();

    FakeBackend(String name, ChatResponse response, Stream<StreamChunk> stream) {
        this.name = name;
        this.baseUrl = "http://fake/" + name;
        this.response = response;
        this.stream = stream == null ? null : stream.onClose(() -> streamClosed.set(true));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        completeCalls.add(request);
        return response;
    }

    @Override
    public Stream<StreamChunk> stream(ChatRequest request) {
        streamCalls.add(request);
        return stream;
    }
}
