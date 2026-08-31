package io.amscotti.janus.gateway;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.router.ChatBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Gateway test double: records calls and returns canned values; the complete
 * path can be switched to a canned failure and the stream path to a canned stream
 * (observed via {@code onClose} to pin the close-releases-connection contract). No
 * network, no Jackson, no real adapters.
 */
final class FakeBackend implements ChatBackend {

    private final String name;
    private final String baseUrl;
    private ChatResponse response;
    private Throwable completeFailure;
    private Stream<StreamChunk> stream;
    private final AtomicBoolean streamClosed = new AtomicBoolean();
    final List<ChatRequest> completeCalls = new ArrayList<>();
    final List<ChatRequest> streamCalls = new ArrayList<>();

    FakeBackend(String name) {
        this(name, "http://fake/" + name);
    }

    FakeBackend(String name, String baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl;
    }

    void completeReturns(ChatResponse response) {
        this.response = response;
        this.completeFailure = null;
    }

    void completeFails(Throwable failure) {
        this.completeFailure = failure;
        this.response = null;
    }

    void streamReturns(Stream<StreamChunk> stream) {
        this.stream = stream;
        this.streamClosed.set(false);
    }

    boolean streamClosed() {
        return streamClosed.get();
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
        if (completeFailure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (completeFailure != null) {
            throw new IllegalStateException(completeFailure);
        }
        return response;
    }

    @Override
    public Stream<StreamChunk> stream(ChatRequest request) {
        streamCalls.add(request);
        if (stream == null) {
            throw new IllegalStateException("no stream configured for fake backend " + name);
        }
        return stream.onClose(() -> streamClosed.set(true));
    }
}
