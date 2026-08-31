package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.OpenAiResponsesStreamEvent;
import io.amscotti.janus.core.model.StreamChunk;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.sse.Event;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.reactivestreams.Publisher;

/**
 * The Responses face's SSE publisher — a thin
 * {@link SseStreamPublisher} subclass feeding through the stateful
 * {@link io.amscotti.janus.core.codec.OpenAiResponsesCodec.OpenAiResponsesStreamEncoder}.
 * One canonical chunk → zero-or-more <b>named</b> events; the created/in_progress
 * prefix drains before the first feed; clean exhaustion appends the encoder's
 * completed/incomplete terminal events.
 *
 * <p><b>The failure path is the reason this subclass exists:</b> {@code errorOutcome}
 * runs on the worker AND the watchdog-spawned stall thread, so it touches ONLY the
 * encoder's immutable failure snapshot — {@code encoder.failed(t)} builds the full
 * {@code response.failed} event (complete response object, synthesized ids, error
 * payload) from that snapshot, never the encoder's mutable internals. Status: the
 * mapped {@link ErrorMapper} row (504 for a stall, 502/500 otherwise) — a stream that
 * failed mid-flight is never counted in the 2xx bucket.
 */
final class ResponsesSsePublisher extends SseStreamPublisher implements Publisher<Event<String>> {

    static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 60;

    private static final String FAILED_EVENT = "response.failed";

    private final io.amscotti.janus.core.codec.OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder;
    private final ErrorMapper errorMapper;
    private boolean drainedInitial = false;

    ResponsesSsePublisher(
            Stream<StreamChunk> upstream,
            io.amscotti.janus.core.codec.OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder,
            ErrorMapper errorMapper,
            AtomicReference<Integer> terminalStatus) {
        this(upstream, encoder, errorMapper, Duration.ofSeconds(DEFAULT_IDLE_TIMEOUT_SECONDS), terminalStatus);
    }

    ResponsesSsePublisher(
            Stream<StreamChunk> upstream,
            io.amscotti.janus.core.codec.OpenAiResponsesCodec.OpenAiResponsesStreamEncoder encoder,
            ErrorMapper errorMapper,
            Duration idleTimeout,
            AtomicReference<Integer> terminalStatus) {
        super(upstream, idleTimeout, terminalStatus);
        this.encoder = java.util.Objects.requireNonNull(encoder, "encoder");
        this.errorMapper = java.util.Objects.requireNonNull(errorMapper, "errorMapper");
    }

    @Override
    protected void feedFrames(StreamChunk chunk, Deque<SseFrame> pending) {
        if (!drainedInitial) {
            drainedInitial = true;
            for (OpenAiResponsesStreamEvent event : encoder.initialEvents()) {
                pending.addLast(new SseFrame(event.event(), event.dataJson()));
            }
        }
        for (OpenAiResponsesStreamEvent event : encoder.feed(chunk)) {
            pending.addLast(new SseFrame(event.event(), event.dataJson()));
        }
    }

    @Override
    protected void finishFrames(Deque<SseFrame> pending) {
        if (!drainedInitial) {
            // Zero-chunk upstream: the created prefix still precedes the terminal event.
            drainedInitial = true;
            for (OpenAiResponsesStreamEvent event : encoder.initialEvents()) {
                pending.addLast(new SseFrame(event.event(), event.dataJson()));
            }
        }
        for (OpenAiResponsesStreamEvent event : encoder.finish()) {
            pending.addLast(new SseFrame(event.event(), event.dataJson()));
        }
    }

    @Override
    protected ErrorOutcome errorOutcome(Throwable failure) {
        // The WHOLE outcome is guarded: the base class invokes errorOutcome unguarded
        // on the worker, so both the mapping and the failed-event serialization
        // (encoder.failed → Jackson can throw OpenAiCodecException) must degrade to a
        // fixed 500 response.failed frame like the other two faces — never an escape
        // that leaves the subscriber with neither onError nor onComplete (a hang) and
        // never a failure whose terminal status stays 200.
        try {
            int status = errorMapper.map(failure).status().getCode();
            // The failed event is built from the encoder's immutable snapshot — callable
            // from the watchdog thread while the worker sits mid-feed.
            OpenAiResponsesStreamEvent failed = encoder.failed(failure);
            return new ErrorOutcome(status, new SseFrame(failed.event(), failed.dataJson()));
        } catch (Throwable degraded) {
            return new ErrorOutcome(
                    HttpStatus.INTERNAL_SERVER_ERROR.getCode(), new SseFrame(FAILED_EVENT, FIXED_500_FRAME));
        }
    }

    @Override
    protected String faceName() {
        return "responses";
    }

    /**
     * The fixed 500 {@code response.failed} frame a mapper/encoder crash degrades to
     * (never a hang). A literal payload: the degraded frame must not depend on the
     * failed encoder/mapper or on another serialization to render; field order mirrors
     * the encoder's own response object (the snapshot's partial output is lost by
     * definition — the encoder that would carry it just crashed).
     */
    private static final String FIXED_500_FRAME =
            "{\"type\":\"response.failed\",\"sequence_number\":0,\"response\":{\"id\":\"resp_stream\",\"object\":\"response\","
                    + "\"created_at\":0,\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"internal server error\"},\"output\":[]}}";
}
