package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.provider.ProviderAdapter;
import io.amscotti.janus.provider.ProviderAuth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * step 4: {@link ProviderAdapterChatBackend} — the hand-off adapter. Pins
 * name/complete delegation and, critically, that {@code stream} returns the
 * adapter's stream <b>by identity</b> so the close-releases-the-connection
 * contract survives the wrapper (closing the routed stream closes the upstream).
 */
class ProviderAdapterChatBackendTest {

    @Test
    void nameDelegatesToAdapter() {
        ProviderAdapterChatBackend backend = new ProviderAdapterChatBackend(new FakeProviderAdapter("deepseek", null));
        assertEquals("deepseek", backend.name());
    }

    @Test
    void completeDelegatesByIdentity() {
        ChatRequest request = request("deepseek-v4-flash");
        ChatResponse response = response("deepseek-v4-flash");
        ProviderAdapterChatBackend backend =
                new ProviderAdapterChatBackend(new FakeProviderAdapter("deepseek", response));
        assertSame(response, backend.complete(request));
    }

    @Test
    void streamReturnsAdapterStreamByIdentityAndClosePropagates() {
        AtomicBoolean closed = new AtomicBoolean();
        Stream<StreamChunk> upstream = Stream.of(chunk()).onClose(() -> closed.set(true));
        ProviderAdapterChatBackend backend =
                new ProviderAdapterChatBackend(new FakeProviderAdapter("deepseek", null, upstream));
        ChatRequest request = request("deepseek-v4-flash");
        Stream<StreamChunk> routed = backend.stream(request);
        assertSame(upstream, routed); // no wrap layer: close-releases-connection by identity
        assertFalse(closed.get());
        routed.close();
        assertTrue(closed.get());
    }

    @Test
    void rejectsNullAdapter() {
        assertThrows(NullPointerException.class, () -> new ProviderAdapterChatBackend(null));
    }

    /** Minimal {@link ProviderAdapter} double — no network, canned response. */
    private static final class FakeProviderAdapter implements ProviderAdapter {

        private final String name;
        private final ChatResponse response;
        private final Stream<StreamChunk> stream;

        FakeProviderAdapter(String name, ChatResponse response) {
            this(name, response, null);
        }

        FakeProviderAdapter(String name, ChatResponse response, Stream<StreamChunk> stream) {
            this.name = name;
            this.response = response;
            this.stream = stream;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String baseUrl() {
            return "https://fake.example";
        }

        @Override
        public ProviderAuth auth() {
            return new ProviderAuth(ProviderAuth.TYPE_NONE, null);
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            return response;
        }

        @Override
        public Stream<StreamChunk> stream(ChatRequest request) {
            return stream != null ? stream : Stream.of(chunk());
        }
    }

    private static ChatRequest request(String model) {
        return new ChatRequest(
                model, List.of(), null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                false, null, null, null, null, null);
    }

    private static ChatResponse response(String model) {
        return new ChatResponse(
                "resp-1",
                "chat.completion",
                0L,
                model,
                List.of(),
                null,
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static StreamChunk chunk() {
        return new StreamChunk("chunk-1", "chat.completion.chunk", 0L, "deepseek-v4-flash", List.of(), null, Map.of());
    }
}
