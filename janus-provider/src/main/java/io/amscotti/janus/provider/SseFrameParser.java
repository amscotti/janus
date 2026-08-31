package io.amscotti.janus.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Inbound SSE frame decoder for upstream streaming responses (package-private; outbound
 * {@code data: }/`[DONE]` framing to the client is the gateway's job). Mirrors the
 * SSE grammar the reference implementation {@code SSEBuffer} reassembles: a frame is a block of
 * {@code field: value} lines terminated by a blank line, with consecutive {@code data:}
 * lines joined by {@code \n}.
 *
 * <p>Supported: {@code data:} lines (exactly one leading space after the colon stripped),
 * multi-line payloads, the {@code [DONE]} sentinel, blank/comment ({@code :...}) lines,
 * unknown fields ({@code id:}, {@code retry:}) ignored, and CRLF, LF, or lone-CR line
 * endings. A leading UTF-8 BOM at stream start is tolerated.
 *
 * <p>Termination: {@link #nextFrame} returns null at a clean EOF (no pending frame). A
 * stream that ends mid-frame throws {@link SseParseException}; the sole tolerances are a
 * pending {@code data: [DONE]} at EOF (real upstreams commonly close the connection
 * right after the sentinel without a trailing blank line) and a {@code message_stop}
 * frame (Anthropic upstreams close right after the terminal event, sometimes with
 * no trailing blank line and no {@code data:} line at all; there is no {@code [DONE]}
 * equivalent), dispatched at both the blank-line terminator and EOF; the EOF
 * tolerance is message_stop-only, while at the blank-line terminator any data-less
 * frame naming a non-default event (e.g. an empty-payload {@code event: error})
 * is dispatched as that event. The {@code
 * message_stop} data payload (when present) is never validated — it is irrelevant to
 * termination, so a truncated payload under the terminal event is deliberately swallowed
 * (pinned by test).
 *
 * <p>Bounds: a single SSE line and an accumulated frame are both capped
 * ({@link #MAX_LINE_CHARS} / {@link #MAX_FRAME_CHARS}); a stream past either raises
 * {@link SseParseException} instead of growing memory without limit. The line cap is
 * enforced <em>while</em> the line is read (bounded reader), so an oversized line cannot
 * be fully buffered by the JDK reader before it trips.
 */
final class SseFrameParser {

    /** Cap on a single SSE line (chars), enforced mid-read by the bounded line reader. */
    static final int MAX_LINE_CHARS = 1 << 18; // 256 KiB
    /** Cap on one accumulated frame (chars), across all its {@code data:} lines. */
    static final int MAX_FRAME_CHARS = 1 << 20; // 1 Mi chars
    /** Char pump size; the line cap is enforced against the accumulated line, so this only
     * bounds how much is fetched from the underlying reader at once. */
    private static final int CHUNK_CHARS = 1 << 13; // 8 KiB

    private static final String DONE = "[DONE]";
    private static final String DEFAULT_EVENT = "message";
    private static final String MESSAGE_STOP = "message_stop";

    private final BufferedReader reader;
    private final char[] chunk = new char[CHUNK_CHARS];
    private int chunkPos;
    private int chunkLen;
    private boolean firstRead = true;
    private final StringBuilder data = new StringBuilder();

    SseFrameParser(InputStream in) {
        this.reader =
                new BufferedReader(new InputStreamReader(Objects.requireNonNull(in, "in"), StandardCharsets.UTF_8));
    }

    /**
     * @return the joined data payload of the next frame (possibly empty), or null at clean
     * EOF
     * @throws IOException on read failure
     * @throws SseParseException when the stream ends mid-frame (pending data lines without
     * a blank-line terminator)
     */
    String nextFrame() throws IOException {
        ParsedFrame frame = nextRawFrame();
        return frame == null ? null : frame.data;
    }

    /**
     * Event-aware variant: returns the next frame's {@code event} name (SSE spec: the last
     * {@code event:} line in the frame wins; absent or empty → {@code "message"}) with its
     * joined {@code data}. Shares the framing state with {@link #nextFrame} so both
     * accessors stay in lockstep over the same stream.
     *
     * @return the next frame's event + data, or null at clean EOF
     * @throws IOException on read failure
     * @throws SseParseException when the stream ends mid-frame without a terminal marker
     */
    SseEventFrame nextEventFrame() throws IOException {
        ParsedFrame frame = nextRawFrame();
        return frame == null ? null : new SseEventFrame(frame.event, frame.data);
    }

    /**
     * Bounded {@link BufferedReader#readLine} replacement: a single SSE line is
     * accumulated char-by-char with {@link #MAX_LINE_CHARS} enforced mid-read, so an
     * oversized line cannot be fully buffered by the JDK reader before it trips. Matches
     * the JDK line semantics ({@code \n}, {@code \r\n}, or a lone {@code \r} end the line)
     * and skips a leading UTF-8 BOM ({@code U+FEFF}) on the very first line of the stream.
     *
     * @return the next line with its terminator stripped, or null at EOF with no pending
     * characters
     * @throws IOException on read failure
     * @throws SseParseException when the line exceeds {@link #MAX_LINE_CHARS}
     */
    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        boolean eof = false;
        while (true) {
            int c = read();
            if (c < 0) {
                eof = true;
                break;
            }
            if (firstRead) {
                firstRead = false;
                if (c == '\uFEFF') {
                    continue; // UTF-8 BOM at stream start — tolerated, not part of the line
                }
            }
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                int next = read();
                if (next != '\n' && next != -1) {
                    chunkPos--; // lone \r — push the character back; it starts the next line
                }
                break;
            }
            line.append((char) c);
            if (line.length() > MAX_LINE_CHARS) {
                throw new SseParseException("SSE line too long (exceeds " + MAX_LINE_CHARS + " chars)");
            }
        }
        return eof && line.isEmpty() ? null : line.toString();
    }

    /** Next char from the stream, or -1 at EOF. */
    private int read() throws IOException {
        if (chunkPos >= chunkLen) {
            chunkLen = reader.read(chunk);
            chunkPos = 0;
            if (chunkLen < 0) {
                return -1;
            }
        }
        return chunk[chunkPos++];
    }

    /** Shared framing loop — the single source of truth for both accessors. */
    private ParsedFrame nextRawFrame() throws IOException {
        data.setLength(0);
        String event = DEFAULT_EVENT;
        boolean sawData = false;
        while (true) {
            String line = readLine();
            if (line == null) {
                if (sawData || MESSAGE_STOP.equals(event)) {
                    // Content-semantic termination tolerances: [DONE] (OpenAI) and
                    // message_stop (Anthropic) need no trailing blank line — real
                    // upstreams close right after them. message_stop is terminal
                    // even with no data: line at all.
                    if (DONE.equals(data.toString().strip()) || MESSAGE_STOP.equals(event)) {
                        // EOF [DONE] tolerance mirrors the adapters' trailing-space
                        // tolerance: "[DONE] " is terminal even without the
                        // blank-line terminator; frameData canonicalizes the sentinel.
                        return new ParsedFrame(event, frameData());
                    }
                    throw new SseParseException("truncated SSE frame: EOF before blank-line terminator (pending data: "
                            + data.length() + " chars)");
                }
                return null;
            }
            if (line.isEmpty()) {
                // A data-less frame that names a non-default event — the terminal
                // `event: message_stop\n\n`, or an empty-payload `event: error\n\n` —
                // is still that event and must be dispatched, not treated as a
                // comment-only no-op frame (the Anthropic adapter classifies
                // empty-data error frames as errors; swallowing them would surface
                // as a truncation later). A data-less default (`message`) frame
                // carries nothing and stays a no-op.
                if (sawData || !DEFAULT_EVENT.equals(event)) {
                    return new ParsedFrame(event, frameData());
                }
                continue; // blank line with no data lines (comments only) — no frame
            }
            if (line.charAt(0) == ':') {
                continue; // comment line
            }
            if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1); // SSE: strip exactly one leading space
                }
                int addition = value.length() + (sawData ? 1 : 0);
                if (data.length() + addition > MAX_FRAME_CHARS) {
                    // An unbounded frame (a stalled upstream dribbling data: lines
                    // forever) must not grow memory without limit.
                    throw new SseParseException("SSE frame too large (exceeds " + MAX_FRAME_CHARS + " chars)");
                }
                if (sawData) {
                    data.append('\n');
                }
                data.append(value);
                sawData = true;
            } else if (line.startsWith("event:")) {
                String value = line.substring("event:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1); // SSE: strip exactly one leading space
                }
                // Last event: line wins; an empty value falls back to the default at
                // dispatch (SSE spec: empty event name buffer → "message").
                event = value.isEmpty() ? DEFAULT_EVENT : value;
            }
            // other fields (id:, retry:) are ignored — they do not reset the frame
        }
    }

    /**
     * Joined data payload with one deliberate divergence from the SSE spec: the spec
     * appends a line-feed after every {@code data:} field (empty ones included) and strips
     * exactly one at dispatch, so a trailing empty {@code data:} line adds a final LF
     * ({@code data: a\ndata:\n\n} → {@code "a\n"}). This parser joins only <em>between</em>
     * data lines and strips one trailing LF, so that shape yields {@code "a"} — no real
     * upstream emits a trailing empty {@code data:} line inside a chunk frame, so spec
     * fidelity here buys nothing (deliberate; pinned by test). A {@code [DONE]}
     * sentinel carrying trailing whitespace is canonicalized to the bare sentinel (n3 —
     * mirrors the adapters' trailing-space tolerance) so both dispatch paths yield the same
     * frame.
     */
    private String frameData() {
        String value = data.toString();
        String joined = value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
        return DONE.equals(joined.strip()) ? DONE : joined;
    }

    private record ParsedFrame(String event, String data) {}
}
