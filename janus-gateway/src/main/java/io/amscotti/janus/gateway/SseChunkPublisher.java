package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.OpenAiMessageCodec;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.gateway.dto.OpenAiErrorEnvelope;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.sse.Event;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.reactivestreams.Publisher;

/**
 * The OpenAI face's SSE publisher — a thin {@link SseStreamPublisher} subclass (review
 * L1 dedup: all concurrency machinery lives once in the base). The OpenAI wire shape:
 * one canonical chunk → exactly one {@code data:} frame ({@code codec.encodeChunk}),
 * clean exhaustion → the {@code [DONE]} sentinel, failures → the OpenAI error envelope
 * via {@link ErrorMapper}. The contract (demand-exact emission, watchdog stall close,
 * cancel → upstream release + 499, terminal-status threading, violation onError) is
 * the base class's, pinned by {@code SseChunkPublisherTest}.
 */
final class SseChunkPublisher extends SseStreamPublisher implements Publisher<Event<String>> {

    static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 60;

    private static final String DONE_SENTINEL = "[DONE]";

    private final OpenAiMessageCodec codec;
    private final ErrorMapper errorMapper;

    SseChunkPublisher(Stream<StreamChunk> upstream, OpenAiMessageCodec codec, ErrorMapper errorMapper) {
        this(upstream, codec, errorMapper, new AtomicReference<>(HttpStatus.OK.getCode()));
    }

    SseChunkPublisher(
            Stream<StreamChunk> upstream,
            OpenAiMessageCodec codec,
            ErrorMapper errorMapper,
            AtomicReference<Integer> terminalStatus) {
        this(upstream, codec, errorMapper, Duration.ofSeconds(DEFAULT_IDLE_TIMEOUT_SECONDS), terminalStatus);
    }

    SseChunkPublisher(
            Stream<StreamChunk> upstream, OpenAiMessageCodec codec, ErrorMapper errorMapper, Duration idleTimeout) {
        this(upstream, codec, errorMapper, idleTimeout, new AtomicReference<>(HttpStatus.OK.getCode()));
    }

    SseChunkPublisher(
            Stream<StreamChunk> upstream,
            OpenAiMessageCodec codec,
            ErrorMapper errorMapper,
            Duration idleTimeout,
            AtomicReference<Integer> terminalStatus) {
        super(upstream, idleTimeout, terminalStatus);
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
        this.errorMapper = java.util.Objects.requireNonNull(errorMapper, "errorMapper");
    }

    @Override
    protected void feedFrames(StreamChunk chunk, Deque<SseFrame> pending) {
        pending.addLast(new SseFrame(null, codec.encodeChunk(chunk)));
    }

    @Override
    protected void finishFrames(Deque<SseFrame> pending) {
        pending.addLast(new SseFrame(null, DONE_SENTINEL));
    }

    @Override
    protected ErrorOutcome errorOutcome(Throwable failure) {
        // A mapper crash degrades to a fixed 500 frame — never a hang.
        try {
            ErrorMapper.ErrorMapping mapping = errorMapper.map(failure);
            return new ErrorOutcome(
                    mapping.status().getCode(), new SseFrame(null, GatewayJson.errorBody(mapping.envelope())));
        } catch (Throwable mappingFailure) {
            return new ErrorOutcome(HttpStatus.INTERNAL_SERVER_ERROR.getCode(), new SseFrame(null, fixedErrorFrame()));
        }
    }

    @Override
    protected String faceName() {
        return "openai";
    }

    /** The fixed 500 error frame a mapper crash degrades to (never a hang). */
    private static String fixedErrorFrame() {
        return GatewayJson.errorBody(
                new OpenAiErrorEnvelope("internal server error", ErrorMapper.TYPE_API_ERROR, null, null));
    }
}
