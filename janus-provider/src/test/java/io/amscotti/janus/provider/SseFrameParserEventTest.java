package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * step 2: {@link SseFrameParser#nextEventFrame} — the event-aware accessor the
 * {@link AnthropicAdapter} streams with (Anthropic SSE carries {@code event:} lines and
 * terminates on {@code event: message_stop}, with no {@code data: [DONE]}). Pins the SSE
 * spec's event semantics — the last {@code event:} line in a frame wins, absent
 * {@code event:} defaults to {@code "message"} — plus the shared framing rules
 * (multi-line data joining, comments, CRLF) and the truncated-frame error. Purely
 * additive: {@code nextFrame} is untouched (the OpenAI path is byte-identical —
 * {@link SseFrameParserTest} stays green unchanged).
 */
class SseFrameParserEventTest {

    private static SseFrameParser parser(String sse) {
        return new SseFrameParser(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<SseEventFrame> drainEvents(SseFrameParser parser) throws IOException {
        List<SseEventFrame> frames = new ArrayList<>();
        SseEventFrame frame;
        while ((frame = parser.nextEventFrame()) != null) {
            frames.add(frame);
        }
        return frames;
    }

    @Test
    void capturesEventNameAndData() throws IOException {
        assertEquals(
                List.of(new SseEventFrame("message_start", "{\"a\":1}")),
                drainEvents(parser("event: message_start\ndata: {\"a\":1}\n\n")));
    }

    @Test
    void lastEventLineWinsPerSseSpec() throws IOException {
        assertEquals(
                List.of(new SseEventFrame("message_delta", "{\"b\":2}")),
                drainEvents(parser("event: message_start\nevent: message_delta\ndata: {\"b\":2}\n\n")));
    }

    @Test
    void absentEventDefaultsToMessage() throws IOException {
        assertEquals(List.of(new SseEventFrame("message", "hello")), drainEvents(parser("data: hello\n\n")));
    }

    @Test
    void eventLinesDoNotResetTheFrame() throws IOException {
        // An event: line in the middle of data lines belongs to the same frame
        assertEquals(
                List.of(new SseEventFrame("message_delta", "a\nb")),
                drainEvents(parser("event: message_delta\ndata: a\n: comment\ndata: b\n\n")));
    }

    @Test
    void multiLineDataJoinsWithNewline() throws IOException {
        assertEquals(
                List.of(new SseEventFrame("message", "line1\nline2")),
                drainEvents(parser("data: line1\ndata: line2\n\n")));
    }

    @Test
    void toleratesCrlf() throws IOException {
        assertEquals(
                List.of(new SseEventFrame("message_stop", "")),
                drainEvents(parser("event: message_stop\r\ndata:\r\n\r\n")));
    }

    @Test
    void toleratesMessageStopAtEofWithoutTrailingBlankLine() throws IOException {
        // real Anthropic upstreams close the connection right after message_stop
        assertEquals(List.of(new SseEventFrame("message_stop", "")), drainEvents(parser("event: message_stop\ndata:")));
    }

    @Test
    void dataLessMessageStopFrameDispatchedAtBlankLine() throws IOException {
        // Anthropic may send the terminal event with no data: line at all
        assertEquals(List.of(new SseEventFrame("message_stop", "")), drainEvents(parser("event: message_stop\n\n")));
    }

    @Test
    void dataLessMessageStopFrameDispatchedAtEof() throws IOException {
        // ...and close the connection right after it, without a trailing blank line either
        assertEquals(List.of(new SseEventFrame("message_stop", "")), drainEvents(parser("event: message_stop\n")));
    }

    @Test
    void dataLessNamedEventFramesDispatchedAtBlankLine() throws IOException {
        // A data-less frame naming a non-default event is that event: an empty-payload
        // `event: error\n\n` must be dispatched (the Anthropic adapter classifies
        // empty-data error frames as errors), just like the terminal message_stop.
        assertEquals(List.of(new SseEventFrame("error", "")), drainEvents(parser("event: error\n\n")));
        assertEquals(List.of(new SseEventFrame("ping", "")), drainEvents(parser("event: ping\n\n")));
    }

    @Test
    void dataLessNamedEventFrameAtEofStillNoFrame() throws IOException {
        // The EOF tolerance stays message_stop-only: an `event: ping` frame cut off
        // by EOF without a blank-line terminator is not dispatched.
        assertNull(parser("event: ping\n").nextEventFrame());
    }

    @Test
    void skipsCommentOnlyFrames() throws IOException {
        assertNull(parser(": ping\n\n").nextEventFrame());
    }

    @Test
    void cleanEofReturnsNull() throws IOException {
        assertNull(parser("").nextEventFrame());
    }

    @Test
    void truncatedFrameThrows() throws IOException {
        SseFrameParser parser = parser("event: message_start\ndata: {\"partial");
        assertThrows(SseParseException.class, parser::nextEventFrame);
    }

    @Test
    void eventAccessorAndDataAccessorStayInLockstep() throws IOException {
        // The two accessors must agree on frame boundaries (shared framing state).
        SseFrameParser parser = parser("event: message_start\ndata: {\"a\":1}\n\n" + "data: [DONE]\n\n");
        assertEquals(new SseEventFrame("message_start", "{\"a\":1}"), parser.nextEventFrame());
        assertEquals("[DONE]", parser.nextFrame());
        assertNull(parser.nextEventFrame());
    }

    @Test
    void truncatedDataUnderMessageStopDispatchedAtEof() throws IOException {
        // The message_stop tolerance is content-blind — the terminal event's data
        // payload is irrelevant to termination, so a truncated payload under it is
        // deliberately dispatched (and swallowed by the adapter), not a truncation error.
        // Pins the tolerance as intentional.
        assertEquals(
                List.of(new SseEventFrame("message_stop", "{\"partial")),
                drainEvents(parser("event: message_stop\ndata: {\"partial")));
    }

    @Test
    void truncatedAfterConsumedFrameThrowsOnEventPath() throws IOException {
        // Coverage: the event path must fail like the data path after a fully consumed
        // frame — the trailing partial data line has no terminal marker.
        SseFrameParser parser =
                parser("event: message_start\ndata: {\"a\":1}\n\nevent: message_delta\ndata: {\"partial");
        assertEquals(new SseEventFrame("message_start", "{\"a\":1}"), parser.nextEventFrame());
        assertThrows(SseParseException.class, parser::nextEventFrame);
    }

    @Test
    void eventTypedDoneFrameCarriesSentinelPayload() throws IOException {
        assertEquals(
                List.of(new SseEventFrame("done", "[DONE]")), drainEvents(parser("event: done\ndata: [DONE]\n\n")));
    }
}
