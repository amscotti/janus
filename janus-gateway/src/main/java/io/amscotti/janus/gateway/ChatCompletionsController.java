package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.OpenAiMessageCodec;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.router.Router;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.reactivestreams.Publisher;

/**
 * OpenAI face: {@code POST /v1/chat/completions}. Raw {@code @Body String} →
 * {@link OpenAiMessageCodec#decodeRequest} (the codec owns every wire byte — no
 * Micronaut DTO binding, no second JSON path), then the shared ladder
 * ({@link ModelFaceControllerSupport#execute}): governance pre-dispatch, the
 * stream/complete branch split, the SSE publisher wrap ({@link SseChunkPublisher})
 * or encode-before-finalize settle, and the catch-path release/meter/ledger
 * backstop. The full contract is the base class's; the face pieces (codec, error
 * mapper, publisher, Tier-1 label) are the overrides below.
 *
 * <p>{@code @ExecuteOn(TaskExecutors.BLOCKING)}: the whole invocation — including the
 * blocking upstream send inside {@code router.stream}/{@code router.complete} — runs
 * on the blocking pool, never on the Netty event loop (Micronaut 5.1 has no virtual
 * dispatch; see package javadoc).
 *
 * <p><b>Return type.</b> One route serves both branches, so the declared type is
 * the erased {@code HttpResponse<?>}: the non-streaming branch returns a {@code String}
 * JSON body, the streaming branch a {@code Publisher<Event<String>>}. Micronaut 5.1's
 * {@code RouteExecutor} dispatches to the SSE writer at runtime from the actual body
 * (publisher + {@code text/event-stream} content type); the byte-shape drift risk this
 * creates is pinned by {@code ChatCompletionsControllerTest}.
 */
@Controller("/v1")
class ChatCompletionsController extends ModelFaceControllerSupport {

    private final OpenAiMessageCodec codec;
    private final ErrorMapper errorMapper;

    /**
     * @param streamIdleTimeouts the per-dispatch SSE idle-watchdog resolver
     * (global default from {@code [janus.timeouts] stream-idle-timeout-seconds} +
     * the {@code [janus.providers.<name>]} stream-idle overrides; the
     * {@code RouterFactory} producer bean) — the ladder resolves it from the
     * dispatched provider after {@code router.stream(...)} returns and threads
     * the {@code Duration} into the {@link SseChunkPublisher} on every stream
     */
    ChatCompletionsController(
            Router router,
            Governance governance,
            MetricsRecorder metricsRecorder,
            StreamIdleTimeoutResolver streamIdleTimeouts) {
        super(router, governance, metricsRecorder, streamIdleTimeouts);
        // Thread-safe per the codec's contract; one codec serves concurrent requests.
        this.codec = OpenAiMessageCodec.create();
        this.errorMapper = new ErrorMapper();
    }

    @Post(
            value = "/chat/completions",
            consumes = MediaType.APPLICATION_JSON,
            produces = {MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM})
    @ExecuteOn(TaskExecutors.BLOCKING)
    HttpResponse<?> chat(@Body @Nullable String rawBody, HttpRequest<?> httpRequest) {
        return execute(rawBody, httpRequest);
    }

    @Override
    protected String face() {
        return Face.OPENAI.label();
    }

    @Override
    protected ChatRequest decode(String rawBody) {
        return codec.decodeRequest(rawBody);
    }

    @Override
    protected String encode(ChatResponse response) {
        return codec.encodeResponse(response);
    }

    @Override
    protected Publisher<Event<String>> newPublisher(
            Stream<StreamChunk> metered, Duration idleTimeout, AtomicReference<Integer> terminalStatus) {
        return new SseChunkPublisher(metered, codec, errorMapper, idleTimeout, terminalStatus);
    }

    @Override
    protected int statusOf(Throwable throwable) {
        // Reuses the controller's single mapper instance — no per-error allocation.
        return errorMapper.map(throwable).status().getCode();
    }
}
