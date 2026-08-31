package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.AnthropicMessageCodec;
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
 * Anthropic face: {@code POST /v1/messages}. Raw {@code @Body String} →
 * {@link AnthropicMessageCodec#decodeRequest} (the codec owns every wire byte — no
 * Micronaut DTO binding, no second JSON path), then the shared ladder
 * ({@link ModelFaceControllerSupport#execute}): governance pre-dispatch, the
 * stream/complete branch split, the SSE publisher wrap (fed through the stateful
 * {@link io.amscotti.janus.core.codec.AnthropicStreamEncoder} inside
 * {@link AnthropicSsePublisher}) or encode-before-finalize settle, and the catch-path
 * release/meter/ledger backstop. The full contract is the base class's; the face
 * pieces (codec, error mapper, encoder+publisher, Tier-1 label) are the overrides.
 *
 * <p>Request {@code Content-Type} is always {@code application/json}; SSE is
 * response-side only (matching the Anthropic API). The {@code anthropic-version} and
 * {@code anthropic-beta} headers are accepted without validation; {@code anthropic-beta}
 * is not ignored — {@link #decorate} copies it into the {@code anthropic-beta} meta
 * entry so the Anthropic adapter forwards it as the upstream {@code anthropic-beta}
 * header (a per-request client opt-in; see
 * {@link io.amscotti.janus.provider.ProviderAdapter}).
 *
 * <p>{@code @ExecuteOn(TaskExecutors.BLOCKING)}: the whole invocation — including the
 * blocking upstream send inside {@code router.stream}/{@code router.complete} — runs
 * on the blocking pool, never on the Netty event loop (Micronaut 5.1 has no virtual
 * dispatch; see package javadoc).
 *
 * <p><b>Return type.</b> One route serves both branches, so the declared
 * type is the erased {@code HttpResponse<?>}: the non-streaming branch returns a
 * {@code String} JSON body, the streaming branch a {@code Publisher<Event<String>>}.
 * Micronaut 5.1's {@code RouteExecutor} dispatches to the SSE writer at runtime from
 * the actual body; the byte-shape drift risk is pinned by {@code MessagesControllerTest}.
 */
@Controller("/v1")
class MessagesController extends ModelFaceControllerSupport {

    private final AnthropicMessageCodec codec;
    private final AnthropicErrorMapper errorMapper;

    /**
     * @param streamIdleTimeouts the per-dispatch SSE idle-watchdog resolver
     * (global default from {@code [janus.timeouts] stream-idle-timeout-seconds} +
     * the {@code [janus.providers.<name>]} stream-idle overrides; the
     * {@code RouterFactory} producer bean) — the ladder resolves it from the
     * dispatched provider after {@code router.stream(...)} returns and threads
     * the {@code Duration} into the {@link AnthropicSsePublisher} on every stream
     */
    MessagesController(
            Router router,
            Governance governance,
            MetricsRecorder metricsRecorder,
            StreamIdleTimeoutResolver streamIdleTimeouts) {
        super(router, governance, metricsRecorder, streamIdleTimeouts);
        // Thread-safe per the codec's contract; one codec serves concurrent requests.
        // The controller never reads env/config — provider construction stays in
        // RouterFactory.
        this.codec = AnthropicMessageCodec.create();
        this.errorMapper = new AnthropicErrorMapper();
    }

    @Post(
            value = "/messages",
            consumes = MediaType.APPLICATION_JSON,
            produces = {MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM})
    @ExecuteOn(TaskExecutors.BLOCKING)
    HttpResponse<?> messages(@Body @Nullable String rawBody, HttpRequest<?> httpRequest) {
        return execute(rawBody, httpRequest);
    }

    @Override
    protected String face() {
        return Face.ANTHROPIC.label();
    }

    @Override
    protected ChatRequest decode(String rawBody) {
        return codec.decodeRequest(rawBody);
    }

    @Override
    protected ChatRequest decorate(HttpRequest<?> httpRequest, ChatRequest request) {
        String beta = httpRequest.getHeaders().get("anthropic-beta");
        if (beta == null || beta.isBlank()) {
            return request;
        }
        return request.withMetaEntry("anthropic-beta", beta);
    }

    @Override
    protected String encode(ChatResponse response) {
        return codec.encodeResponse(response);
    }

    @Override
    protected Publisher<Event<String>> newPublisher(
            Stream<StreamChunk> metered, Duration idleTimeout, AtomicReference<Integer> terminalStatus) {
        // The stateful per-stream encoder (the codec's factory — one encoder per
        // stream, never shared).
        return new AnthropicSsePublisher(metered, codec.newStreamEncoder(), errorMapper, idleTimeout, terminalStatus);
    }

    @Override
    protected int statusOf(Throwable throwable) {
        // Reuses the controller's single mapper instance — no per-error allocation.
        return errorMapper.map(throwable).status().getCode();
    }
}
