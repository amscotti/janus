package io.amscotti.janus.core.codec;

import io.amscotti.janus.core.util.SseFrameSplitter;
import java.util.List;

/**
 * Test-side SSE frame splitter mirroring the wire grammar the provider's
 * {@code SseFrameParser} implements (package-private there; this helper is test-only).
 * The grammar lives in the shared main-source {@link SseFrameSplitter}
 * (janus-gateway's {@code SseFixtureReplayTest} delegates there too — the gateway test
 * classpath cannot see core <em>test</em> sources, so a main-source artifact is the only
 * way to avoid two drifting copies): {@code data:} lines with exactly one
 * leading space stripped, consecutive {@code data:} lines joined by {@code \n},
 * blank-line terminators, the {@code [DONE]} sentinel kept as-is, comment ({@code :...})
 * and unknown ({@code id:}/{@code retry:}) lines ignored, CRLF or LF endings, and
 * EOF-after-{@code [DONE]} tolerated (a pending sentinel without a trailing blank line
 * still yields a frame). Any other pending data at EOF is a truncated frame and fails
 * fast.
 *
 * <p> adds the event-aware {@link #frames(String)} accessor for the Anthropic side
 * (Anthropic SSE is event-typed: {@code AnthropicMessageCodec.decodeChunk(event, data)}
 * consumes the {@code event:} name). Contract: the last {@code event:}
 * line in a frame wins, {@code "message"} when absent, and at a blank-line terminator a
 * data-less frame naming a non-default event is dispatched as that event (e.g.
 * {@code event: message_stop\n\n}); at EOF only {@code message_stop} is tolerated.
 */
final class SseTestFrames {

    private SseTestFrames() {}

    /** One decoded SSE frame: the event name (last {@code event:} line wins per the SSE
     * spec; {@code "message"} when absent) plus the joined data payload. */
    record SseFrame(String event, String data) {}

    /** All data payloads of {@code rawSse} in wire order, {@code [DONE]} included. */
    static List<String> dataPayloads(String rawSse) {
        return frames(rawSse).stream().map(SseFrame::data).toList();
    }

    /** Event-aware split — delegates to the shared {@link SseFrameSplitter} grammar. */
    static List<SseFrame> frames(String rawSse) {
        return SseFrameSplitter.frames(rawSse).stream()
                .map(frame -> new SseFrame(frame.event(), frame.data()))
                .toList();
    }
}
