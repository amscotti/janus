package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * step 2: SSE frame decoding edge cases — {@code data:} lines, multi-line payloads,
 * the {@code [DONE]} sentinel, blank/comment lines, CRLF tolerance, empty data lines and
 * the truncated-frame error.
 */
class SseFrameParserTest {

    private static SseFrameParser parser(String sse) {
        return new SseFrameParser(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> drain(SseFrameParser parser) throws IOException {
        List<String> frames = new ArrayList<>();
        String frame;
        while ((frame = parser.nextFrame()) != null) {
            frames.add(frame);
        }
        return frames;
    }

    @Test
    void parsesSingleDataFrame() throws IOException {
        assertEquals(List.of("hello"), drain(parser("data: hello\n\n")));
    }

    @Test
    void joinsMultiLinePayloadsWithNewline() throws IOException {
        assertEquals(List.of("line1\nline2"), drain(parser("data: line1\ndata: line2\n\n")));
    }

    @Test
    void stripsExactlyOneLeadingSpaceAfterColon() throws IOException {
        // "data:" + three spaces + "hello" → one space stripped, two remain
        assertEquals(List.of("  hello"), drain(parser("data:   hello\n\n")));
    }

    @Test
    void toleratesCrlf() throws IOException {
        assertEquals(List.of("hello"), drain(parser("data: hello\r\n\r\n")));
    }

    @Test
    void skipsCommentLinesAndUnknownFields() throws IOException {
        String sse = ": keep-alive\nevent: message\nid: 1\nretry: 3000\ndata: hello\n\n";
        assertEquals(List.of("hello"), drain(parser(sse)));
    }

    @Test
    void ignoresCommentOnlyFrames() throws IOException {
        assertTrue(drain(parser(": ping\n\n: pong\n\n")).isEmpty());
    }

    @Test
    void recognizesDoneSentinel() throws IOException {
        assertEquals(List.of("[DONE]"), drain(parser("data: [DONE]\n\n")));
    }

    @Test
    void toleratesDoneSentinelWithoutTrailingBlankLine() throws IOException {
        assertEquals(List.of("[DONE]"), drain(parser("data: [DONE]")));
    }

    @Test
    void toleratesDoneSentinelWithTrailingSpaceAtEof() throws IOException {
        // The trailing-space tolerance is inconsistent — advance strips but the
        // parser's EOF-termination path compared exactly. "[DONE] " at EOF (no blank-line
        // terminator) must terminate cleanly and yield the canonical sentinel, not throw.
        assertEquals(List.of("[DONE]"), drain(parser("data: [DONE] ")));
    }

    @Test
    void canonicalizesDoneSentinelWithTrailingSpaceOnBlankLine() throws IOException {
        assertEquals(List.of("[DONE]"), drain(parser("data: [DONE] \n\n")));
    }

    @Test
    void parsesSequentialFrames() throws IOException {
        assertEquals(List.of("a", "b", "c"), drain(parser("data: a\n\ndata: b\n\ndata: c\n\n")));
    }

    @Test
    void emptyDataLineYieldsEmptyFrame() throws IOException {
        assertEquals(List.of(""), drain(parser("data:\n\n")));
    }

    @Test
    void cleanEofReturnsNull() throws IOException {
        assertNull(parser("").nextFrame());
        assertNull(parser(": comment only").nextFrame());
    }

    @Test
    void truncatedFrameThrows() throws IOException {
        SseFrameParser parser = parser("data: {\"partial");
        assertThrows(SseParseException.class, parser::nextFrame);
    }

    @Test
    void truncatedAfterFullyConsumedFrameThrowsOnNextCall() throws IOException {
        // frame "a" is complete; the trailing partial data line is truncated
        SseFrameParser parser = parser("data: a\n\ndata: {\"partial");
        assertEquals("a", parser.nextFrame());
        assertThrows(SseParseException.class, parser::nextFrame);
    }

    @Test
    void trailingEmptyDataLineDivergesFromSpecDeliberately() throws IOException {
        // Documented divergence, not a bug: the SSE spec appends a line-feed after
        // EVERY data: field (empty ones included) and strips exactly one at dispatch, so
        // `data: a\ndata:\n\n` should yield "a\n" per spec. This parser joins only BETWEEN
        // data lines and strips one trailing LF, yielding "a" — no real upstream emits a
        // trailing empty data: line inside a chunk frame, so spec fidelity buys nothing
        // (frameData's comment states the divergence).
        assertEquals(List.of("a"), drain(parser("data: a\ndata:\n\n")));
        assertEquals(List.of(""), drain(parser("data:\ndata:\n\n")));
    }

    @Test
    void frameAccumulationBeyondCapThrows() {
        // A stalled/hostile upstream must not grow an unbounded frame buffer — a
        // frame accumulating past the cap is a parse error. Each line stays under the line
        // cap, so it is the frame cap that trips.
        int lineValue = SseFrameParser.MAX_LINE_CHARS / 2;
        int lines = SseFrameParser.MAX_FRAME_CHARS / lineValue + 2;
        SseFrameParser parser = parser(("data: " + "x".repeat(lineValue) + "\n").repeat(lines));
        assertThrows(SseParseException.class, parser::nextFrame);
    }

    @Test
    void singleLineBeyondCapThrows() {
        // A single oversized SSE line (memory unbounded by BufferedReader) is a
        // parse error, not an unbounded allocation.
        SseFrameParser parser = parser("data: " + "x".repeat(SseFrameParser.MAX_LINE_CHARS + 1) + "\n\n");
        assertThrows(SseParseException.class, parser::nextFrame);
    }

    @Test
    void lineExactlyAtCapIsParsed() throws IOException {
        // Boundary: the line cap is strict ">", so a line exactly at the cap (an
        // unknown field with no colon — never a data frame) must parse, not throw.
        SseFrameParser parser = parser("x".repeat(SseFrameParser.MAX_LINE_CHARS) + "\n\ndata: ok\n\n");
        assertEquals(List.of("ok"), drain(parser));
    }

    @Test
    void lineBeyondCapByOneThrows() {
        // The bounded line reader trips mid-read, one char past the cap.
        SseFrameParser parser = parser("x".repeat(SseFrameParser.MAX_LINE_CHARS + 1) + "\n");
        assertThrows(SseParseException.class, parser::nextFrame);
    }

    @Test
    void frameExactlyAtCapIsParsed() throws IOException {
        // Boundary: a frame accumulating to exactly MAX_FRAME_CHARS must pass; the
        // frame cap is strict ">". Four lines at the per-line boundary accumulate to
        // 1,048,555; a final line of 20 chars (plus the join LF) lands exactly on the cap.
        int lineValue = SseFrameParser.MAX_LINE_CHARS - "data: ".length();
        String sse = ("data: " + "x".repeat(lineValue) + "\n").repeat(4) + "data: " + "x".repeat(20) + "\n\n";
        String frame = parser(sse).nextFrame();
        assertEquals(SseFrameParser.MAX_FRAME_CHARS, frame.length());
    }

    @Test
    void loneCarriageReturnEndsLines() throws IOException {
        // BufferedReader.readLine treats a lone \r as a line terminator — so does the
        // bounded reader. `data: a\r\r` is line "data: a" + blank line.
        assertEquals(List.of("a", "b"), drain(parser("data: a\r\rdata: b\r\r")));
    }

    @Test
    void utf8BomAtStreamStartIsTolerated() throws IOException {
        // A leading UTF-8 BOM (U+FEFF) decodes as a character; the bounded reader skips it
        // on the very first line so the first frame's data is not silently dropped.
        assertEquals(List.of("hello"), drain(parser("\uFEFFdata: hello\n\n")));
    }

    @Test
    void whitespacePrefixedFieldLinesAreIgnored() throws IOException {
        // Per the SSE grammar a line beginning with a space has an empty field name and is
        // ignored; the real data line below it still frames.
        assertEquals(List.of("real"), drain(parser(" data: hello\n\ndata: real\n\n")));
    }

    @Test
    void utf8NonBmpPayloadSurvivesDecode() throws IOException {
        // A non-BMP code point (surrogate pair in UTF-16) round-trips through the reader.
        assertEquals(List.of("\uD83D\uDE00"), drain(parser("data: \uD83D\uDE00\n\n")));
    }

    @Test
    void eventTypedDoneFramePayloadIsSentinel() throws IOException {
        // The OpenAI adapter strips frame text itself, but the parser must also surface a
        // `[DONE]` payload that arrived under an explicit (non-default) event name.
        assertEquals(List.of("[DONE]"), drain(parser("event: done\ndata: [DONE]\n\n")));
    }
}
