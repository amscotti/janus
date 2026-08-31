package io.amscotti.janus.core.util;

import java.util.ArrayList;
import java.util.List;

/**
 * String-based SSE frame splitter — the single shared grammar for fixture-driven tests
 * (janus-core's {@code SseTestFrames} and the janus-gateway {@code SseFixtureReplayTest}
 * both delegate here; the gateway test classpath cannot see core <em>test</em> sources,
 * so a main-source artifact is the only way to avoid two drifting copies of the wire
 * grammar). Mirrors the provider's production {@code SseFrameParser} (streaming, bounded):
 * {@code data:} lines with exactly one leading space stripped, consecutive {@code data:}
 * lines joined by {@code \n}, blank-line terminators, the {@code [DONE]} sentinel kept
 * as-is (trailing whitespace canonicalized), comment ({@code :...}) and unknown
 * ({@code id:}/{@code retry:}) lines ignored, CRLF, LF, or lone-CR endings (a lone
 * {@code \r} terminates a line exactly as in the production bounded reader), and a
 * leading UTF-8 BOM on the very first line tolerated. EOF-after-{@code [DONE]} is
 * tolerated (a pending sentinel without a trailing blank line still yields a frame).
 * Any other pending data at EOF is a truncated frame and fails fast — the exception
 * reports only the pending byte count, never the payload body (content-leak
 * discipline, matching the production parser).
 *
 * <p>/ event contract: frames are {@code (event, data)} pairs — the last
 * {@code event:} line in a frame wins ({@code "message"} when absent or empty), and at a
 * blank-line terminator any data-less frame naming a non-default event is dispatched as
 * that event (an empty-payload {@code event: error\n\n} must reach the adapter, which
 * classifies empty-data error frames as errors, just like the terminal data-less
 * {@code message_stop}); only a data-less <em>default</em> ({@code message}) frame
 * carries nothing and is skipped. At EOF the tolerance stays message_stop-only —
 * a data-less non-terminal event cut off by EOF without its blank line is a clean
 * end, not a frame (matching the production parser).
 *
 * <p>Truncation discipline: a lone trailing line terminator is a terminator, not a
 * blank line — {@code data: x\n} at EOF is a truncated frame exactly as in production
 * (a JDK-style line read consumes the terminator; only a second terminator forms the
 * blank line that dispatches the frame).
 */
public final class SseFrameSplitter {

    private static final String DONE = "[DONE]";
    private static final String DEFAULT_EVENT = "message";
    private static final String MESSAGE_STOP = "message_stop";

    private SseFrameSplitter() {}

    /** One decoded SSE frame: event name plus the joined data payload. */
    public record SseFrame(String event, String data) {}

    /** All data payloads of {@code rawSse} in wire order, {@code [DONE]} included. */
    public static List<String> dataPayloads(String rawSse) {
        return frames(rawSse).stream().map(SseFrame::data).toList();
    }

    /**
     * Event-aware split: every frame of {@code rawSse} as an {@code (event, data)} pair in
     * wire order, {@code [DONE]} included. Contract: the last {@code
     * event:} line in a frame wins; absent/empty → {@code "message"}; at a blank-line
     * terminator a data-less frame naming a non-default event is dispatched as that
     * event (data {@code ""}) — only a data-less default ({@code message}) frame is
     * skipped; at EOF only {@code message_stop} (data {@code ""} when data-less) and a
     * pending {@code [DONE]} are tolerated, anything else pending fails fast as a
     * truncated frame.
     */
    public static List<SseFrame> frames(String rawSse) {
        // Normalize every line terminator to LF: CRLF collapses to one LF, a lone CR is a
        // line boundary (mirrors the production bounded reader), and the strip below then
        // needs no per-line \r handling.
        String normalized = rawSse.replace("\r\n", "\n").replace("\r", "\n");
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1); // UTF-8 BOM at stream start — tolerated
        }
        // Iterate real lines (a trailing terminator ends a line, it does not open an
        // empty one): `data: x\n` must reach the EOF check as pending data — a phantom
        // trailing empty element from split("\n", -1) would masquerade as the blank-line
        // terminator and silently dispatch a frame production rejects as truncated.
        List<SseFrame> frames = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        String event = DEFAULT_EVENT;
        boolean sawData = false;
        for (String line : normalized.lines().toList()) {
            if (line.isEmpty()) {
                // Blank-line terminator: a data-less frame naming a non-default event is
                // that event and must be dispatched, not swallowed (the terminal data-less
                // `event: message_stop\n\n`, an empty-payload `event: error\n\n` — the
                // adapter classifies empty-data error frames as errors; a data-less
                // default `message` frame carries nothing and is a no-op). Matching
                // production: `sawData || !DEFAULT_EVENT.equals(event)`.
                if (sawData || !DEFAULT_EVENT.equals(event)) {
                    frames.add(new SseFrame(event, frameData(data.toString())));
                }
                // Frame boundary in every case (production starts each frame from clean
                // state): a skipped no-op frame must still clear the pending event so it
                // cannot mislabel the next frame.
                data.setLength(0);
                event = DEFAULT_EVENT;
                sawData = false;
                continue;
            }
            if (line.charAt(0) == ':') {
                continue; // comment line
            }
            if (line.startsWith("event:")) {
                String value = line.substring("event:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1); // SSE: strip exactly one leading space
                }
                // Last event: line wins; an empty value falls back to the default at
                // dispatch (SSE spec: empty event name buffer → "message").
                event = value.isEmpty() ? DEFAULT_EVENT : value;
            } else if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1); // SSE: strip exactly one leading space
                }
                if (sawData) {
                    data.append('\n');
                }
                data.append(value);
                sawData = true;
            }
            // other fields (id:, retry:) are ignored — they do not reset the frame
        }
        if (sawData || MESSAGE_STOP.equals(event)) {
            // EOF tolerance: a pending [DONE] (OpenAI) or message_stop frame (Anthropic,
            // — data-less and terminal) is a clean end; anything else is a
            // truncated frame (SseFrameParser contract). [DONE] with trailing whitespace
            // is canonicalized via frameData, mirroring the production parser's
            // strip-based tolerance (observed in the wild).
            if (DONE.equals(data.toString().strip()) || MESSAGE_STOP.equals(event)) {
                frames.add(new SseFrame(event, frameData(data.toString())));
            } else {
                // Truncation message reports only the pending byte count, never the payload
                // body — the same content-leak discipline as SseFrameParser.
                throw new IllegalArgumentException(
                        "truncated SSE frame at EOF (pending data: " + data.length() + " chars)");
            }
        }
        return List.copyOf(frames);
    }

    /**
     * Mirror of the production parser's {@code frameData}: joins only between data lines,
     * strips one trailing LF (a trailing empty {@code data:} line therefore adds nothing —
     * {@code data: a\ndata:\n\n} yields {@code "a"}, the deliberate divergence from
     * the spec), and canonicalizes a {@code [DONE]} sentinel carrying trailing whitespace.
     */
    private static String frameData(String value) {
        String joined = value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
        return DONE.equals(joined.strip()) ? DONE : joined;
    }
}
