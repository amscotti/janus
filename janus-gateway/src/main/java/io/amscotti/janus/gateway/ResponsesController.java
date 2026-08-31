package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.OpenAiResponsesCodec;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.gateway.dto.OpenAiErrorEnvelope;
import io.amscotti.janus.router.Router;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.reactivestreams.Publisher;

/**
 * OpenAI Responses face: {@code POST /v1/responses}. The
 * shared ladder ({@link ModelFaceControllerSupport#execute}) drives decode → scope →
 * governance → complete → request-aware encode; this subclass supplies the codec, the
 * OpenAI error mapper (the Responses error envelope IS the OpenAI envelope), the
 * {@code responses} Tier-1 label, and the streaming branch wires
 * {@link ResponsesSsePublisher} through the stateful stream encoder — the
 * ingress decode forces include_usage (decision B) so response.completed always
 * carries usage.
 *
 * <p><b>Stub retrieval routes.</b> {@code GET/DELETE /v1/responses/{id}} always 404
 * with the face's envelope ({@code response_not_found}) — with {@code store:false}
 * (this face's stateless contract) there is nothing to retrieve, and an unregistered
 * path would otherwise render Micronaut's default 404 body instead of the envelope.
 * Both routes sit under the {@link Face#RESPONSES} prefix: virtual-key auth on every
 * method (the filter's prefix branch), matching real OpenAI's 401-before-404.
 */
@Controller("/v1")
class ResponsesController extends ModelFaceControllerSupport {

    private static final String RESPONSE_NOT_FOUND_BODY = GatewayJson.errorBody(new OpenAiErrorEnvelope(
            "Response not found", ErrorMapper.TYPE_INVALID_REQUEST, null, "response_not_found"));

    private final OpenAiResponsesCodec codec;
    private final ErrorMapper errorMapper;

    /**
     * @param streamIdleTimeouts the per-dispatch SSE idle-watchdog resolver
     * (global default from {@code [janus.timeouts] stream-idle-timeout-seconds} +
     * the {@code [janus.providers.<name>]} stream-idle overrides; the
     * {@code RouterFactory} producer bean) — the ladder resolves it from the
     * dispatched provider after {@code router.stream(...)} returns and threads
     * the {@code Duration} into the {@link ResponsesSsePublisher} on every stream
     */
    ResponsesController(
            Router router,
            Governance governance,
            MetricsRecorder metricsRecorder,
            StreamIdleTimeoutResolver streamIdleTimeouts) {
        super(router, governance, metricsRecorder, streamIdleTimeouts);
        this.codec = OpenAiResponsesCodec.create();
        this.errorMapper = new ErrorMapper();
    }

    @Post(
            value = "/responses",
            consumes = MediaType.APPLICATION_JSON,
            produces = {MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM})
    @ExecuteOn(TaskExecutors.BLOCKING)
    HttpResponse<?> responses(@Body @Nullable String rawBody, HttpRequest<?> httpRequest) {
        return execute(rawBody, httpRequest);
    }

    /** Stub: nothing is stored, so retrieval is always the envelope 404 (auth'd). */
    @Get("/responses/{id}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    HttpResponse<String> getResponse(String id) {
        return notFound();
    }

    /** Stub: the delete analogue — idempotent 404 (nothing was ever stored). */
    @Delete("/responses/{id}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    HttpResponse<String> deleteResponse(String id) {
        return notFound();
    }

    private static HttpResponse<String> notFound() {
        return HttpResponse.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(RESPONSE_NOT_FOUND_BODY);
    }

    @Override
    protected String face() {
        return Face.RESPONSES.label();
    }

    @Override
    protected ChatRequest decode(String rawBody) {
        return codec.decodeRequest(rawBody);
    }

    @Override
    protected String encode(ChatResponse response) {
        throw new AssertionError("the Responses face overrides the request-aware encode");
    }

    @Override
    protected String encode(ChatRequest request, ChatResponse response) {
        return codec.encodeResponse(request, response);
    }

    @Override
    protected Publisher<Event<String>> newPublisher(
            Stream<StreamChunk> metered, Duration idleTimeout, AtomicReference<Integer> terminalStatus) {
        // Unreachable: the request-aware overload below is the one the ladder calls.
        throw new AssertionError("the Responses face overrides the request-aware publisher seam");
    }

    @Override
    protected Publisher<Event<String>> newPublisher(
            ChatRequest request,
            Stream<StreamChunk> metered,
            Duration idleTimeout,
            AtomicReference<Integer> terminalStatus) {
        return new ResponsesSsePublisher(
                metered, codec.newStreamEncoder(request), errorMapper, idleTimeout, terminalStatus);
    }

    @Override
    protected int statusOf(Throwable throwable) {
        return errorMapper.map(throwable).status().getCode();
    }
}
