package io.amscotti.janus.router;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * test double ('s {@link FakeBackend} is final, so this is a sibling): returns
 * canned values but throws the configured failures in order — one per call, then succeeds
 * once exhausted. Counts attempts via {@link #completeCalls}/{@link #streamCalls} and
 * observes {@code onClose} like FakeBackend so stream lifecycle assertions keep working.
 * No network, no Jackson, no real adapters.
 */
final class FailingBackend implements ChatBackend {

    private final String name;
    private final String baseUrl;
    private final ChatResponse response;
    final Stream<StreamChunk> stream;
    private final List<RuntimeException> failures;
    final List<ChatRequest> completeCalls = new ArrayList<>();
    final List<ChatRequest> streamCalls = new ArrayList<>();
    final AtomicBoolean streamClosed = new AtomicBoolean();

    FailingBackend(String name, ChatResponse response, Stream<StreamChunk> stream, List<RuntimeException> failures) {
        this.name = name;
        this.baseUrl = "http://fake/" + name;
        this.response = response;
        this.stream = stream == null ? null : stream.onClose(() -> streamClosed.set(true));
        this.failures = new ArrayList<>(failures);
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
        if (!failures.isEmpty()) {
            throw failures.removeFirst();
        }
        return response;
    }

    @Override
    public Stream<StreamChunk> stream(ChatRequest request) {
        streamCalls.add(request);
        if (!failures.isEmpty()) {
            throw failures.removeFirst();
        }
        return stream;
    }
}
