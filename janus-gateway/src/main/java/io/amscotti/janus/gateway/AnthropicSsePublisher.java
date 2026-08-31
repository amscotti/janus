package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.AnthropicErrorBody;
import io.amscotti.janus.core.codec.AnthropicErrorPayload;
import io.amscotti.janus.core.codec.AnthropicSseEvent;
import io.amscotti.janus.core.codec.AnthropicStreamEncoder;
import io.amscotti.janus.core.model.StreamChunk;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.sse.Event;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.reactivestreams.Publisher;

/**
 * The Anthropic face's SSE publisher — a thin {@link SseStreamPublisher} subclass
 * (review L1 dedup: all concurrency machinery lives once in the base). The Anthropic
 * wire shape through the stateful {@link AnthropicStreamEncoder}: one canonical chunk
 * → {@code encoder.feed(chunk)} → zero-or-more <b>named</b> frames, clean exhaustion →
 * the {@code encoder.finish} family ({@code content_block_stop} → {@code
 * message_delta} → {@code message_stop} — no {@code [DONE]} sentinel), failures → the
 * Anthropic error envelope via {@link AnthropicErrorMapper} as an {@code event: error}
 * frame; {@code finish} frames are skipped on the failure path (the upstream is
 * dead, the encoder state discarded — mirrors render_stream_event).
 * The contract (frame-exact demand, watchdog stall close, cancel → upstream release +
 * 499, terminal-status threading, violation onError) is the base class's, pinned by
 * {@code AnthropicSsePublisherTest}.
 */
final class AnthropicSsePublisher extends SseStreamPublisher implements Publisher<Event<String>> {

    static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 60;

    private static final String ERROR_EVENT = "error";

    private final AnthropicStreamEncoder encoder;
    private final AnthropicErrorMapper errorMapper;

    AnthropicSsePublisher(
            Stream<StreamChunk> upstream, AnthropicStreamEncoder encoder, AnthropicErrorMapper errorMapper) {
        this(upstream, encoder, errorMapper, Duration.ofSeconds(DEFAULT_IDLE_TIMEOUT_SECONDS));
    }

    AnthropicSsePublisher(
            Stream<StreamChunk> upstream,
            AnthropicStreamEncoder encoder,
            AnthropicErrorMapper errorMapper,
            AtomicReference<Integer> terminalStatus) {
        this(upstream, encoder, errorMapper, Duration.ofSeconds(DEFAULT_IDLE_TIMEOUT_SECONDS), terminalStatus);
    }

    AnthropicSsePublisher(
            Stream<StreamChunk> upstream,
            AnthropicStreamEncoder encoder,
            AnthropicErrorMapper errorMapper,
            Duration idleTimeout) {
        this(upstream, encoder, errorMapper, idleTimeout, new AtomicReference<>(HttpStatus.OK.getCode()));
    }

    AnthropicSsePublisher(
            Stream<StreamChunk> upstream,
            AnthropicStreamEncoder encoder,
            AnthropicErrorMapper errorMapper,
            Duration idleTimeout,
            AtomicReference<Integer> terminalStatus) {
        super(upstream, idleTimeout, terminalStatus);
        this.encoder = java.util.Objects.requireNonNull(encoder, "encoder");
        this.errorMapper = java.util.Objects.requireNonNull(errorMapper, "errorMapper");
    }

    @Override
    protected void feedFrames(StreamChunk chunk, Deque<SseFrame> pending) {
        for (AnthropicSseEvent frame : encoder.feed(chunk)) {
            pending.addLast(new SseFrame(frame.event(), frame.dataJson()));
        }
    }

    @Override
    protected void finishFrames(Deque<SseFrame> pending) {
        for (AnthropicSseEvent frame : encoder.finish()) {
            pending.addLast(new SseFrame(frame.event(), frame.dataJson()));
        }
    }

    @Override
    protected ErrorOutcome errorOutcome(Throwable failure) {
        // A mapper crash degrades to a fixed 500 frame — never a hang.
        try {
            AnthropicErrorMapper.ErrorMapping mapping = errorMapper.map(failure);
            return new ErrorOutcome(
                    mapping.status().getCode(),
                    new SseFrame(ERROR_EVENT, GatewayJson.anthropicErrorBody(mapping.envelope())));
        } catch (Throwable mappingFailure) {
            return new ErrorOutcome(
                    HttpStatus.INTERNAL_SERVER_ERROR.getCode(), new SseFrame(ERROR_EVENT, fixedErrorFrame()));
        }
    }

    @Override
    protected String faceName() {
        return "anthropic";
    }

    /** The fixed 500 error frame a mapper crash degrades to (never a hang). */
    private static String fixedErrorFrame() {
        return GatewayJson.anthropicErrorBody(new AnthropicErrorPayload(
                "error", new AnthropicErrorBody(AnthropicErrorMapper.TYPE_API_ERROR, "internal server error")));
    }
}
