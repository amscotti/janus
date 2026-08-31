package io.amscotti.janus.gateway;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.provider.ProviderAdapter;
import io.amscotti.janus.router.ChatBackend;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * hand-off: adapts a {@link ProviderAdapter} to the router's provider-agnostic
 * {@link ChatBackend} seam. Pure delegation — {@link #name} is {@code adapter.name},
 * {@link #complete(ChatRequest)} and {@link #stream(ChatRequest)} delegate without
 * reading, writing or wrapping anything, so {@code meta} passes through untouched and
 * {@code stream} returns the adapter's stream <b>by identity</b>: the
 * close-releases-the-connection contract survives (closing the returned stream closes
 * the upstream connection — the gateway's {@link SseChunkPublisher} owns that
 * lifecycle).
 *
 * <p>Thread-safe: the adapter is expected to be stateless and thread-safe ( SPI
 * contract); this wrapper adds no state.
 */
final class ProviderAdapterChatBackend implements ChatBackend {

    private final ProviderAdapter adapter;

    ProviderAdapterChatBackend(ProviderAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    @Override
    public String name() {
        return adapter.name();
    }

    @Override
    public String baseUrl() {
        return adapter.baseUrl();
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        return adapter.complete(request);
    }

    @Override
    public Stream<StreamChunk> stream(ChatRequest request) {
        return adapter.stream(request);
    }
}
