package io.amscotti.janus.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.util.SseFrameSplitter.SseFrame;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the shared SSE grammar (comment lines, CRLF, multi-data joins,
 * event-only terminal frames, truncated EOF). Fixture-driven suites exercise the happy
 * path; these pin the edge branches the aggregate JaCoCo report still flags.
 */
class SseFrameSplitterTest {

    @Test
    void dataPayloadsIncludesDone() {
        List<String> payloads = SseFrameSplitter.dataPayloads("data: {\"a\":1}\n\ndata: [DONE]\n\n");
        assertEquals(List.of("{\"a\":1}", "[DONE]"), payloads);
    }

    @Test
    void stripsExactlyOneLeadingSpaceOnDataAndEvent() {
        // two spaces after "data:" → strip exactly one, one leading space remains
        List<SseFrame> frames = SseFrameSplitter.frames("event: delta\ndata:  hello\n\n");
        assertEquals(1, frames.size());
        assertEquals("delta", frames.getFirst().event());
        // one space stripped → remaining leading space preserved
        assertEquals(" hello", frames.getFirst().data());
    }

    @Test
    void crlfLineEndingsAreAccepted() {
        List<SseFrame> frames = SseFrameSplitter.frames("data: a\r\n\r\ndata: [DONE]\r\n\r\n");
        assertEquals(List.of(new SseFrame("message", "a"), new SseFrame("message", "[DONE]")), frames);
    }

    @Test
    void commentAndUnknownFieldsAreIgnored() {
        List<SseFrame> frames =
                SseFrameSplitter.frames(": keep-alive\nid: 1\nretry: 1000\ndata: payload\n\ndata: [DONE]\n\n");
        assertEquals(List.of(new SseFrame("message", "payload"), new SseFrame("message", "[DONE]")), frames);
    }

    @Test
    void consecutiveDataLinesJoinWithNewline() {
        List<SseFrame> frames = SseFrameSplitter.frames("data: line1\ndata: line2\n\ndata: [DONE]\n\n");
        assertEquals(List.of(new SseFrame("message", "line1\nline2"), new SseFrame("message", "[DONE]")), frames);
    }

    @Test
    void emptyEventValueKeepsDefaultMessage() {
        List<SseFrame> frames = SseFrameSplitter.frames("event:\ndata: x\n\ndata: [DONE]\n\n");
        assertEquals("message", frames.getFirst().event());
        assertEquals("x", frames.getFirst().data());
    }

    @Test
    void lastEventLineWinsWithinFrame() {
        List<SseFrame> frames = SseFrameSplitter.frames("event: first\nevent: second\ndata: z\n\ndata: [DONE]\n\n");
        assertEquals("second", frames.getFirst().event());
    }

    @Test
    void dataLessMessageStopAtEofIsAFrame() {
        // Anthropic terminal: event-only frame without trailing blank before EOF
        List<SseFrame> frames = SseFrameSplitter.frames("event: message_stop\n");
        assertEquals(List.of(new SseFrame("message_stop", "")), frames);
    }

    @Test
    void pendingDoneAtEofWithoutTrailingBlankIsTolerated() {
        List<SseFrame> frames = SseFrameSplitter.frames("data: [DONE]");
        assertEquals(List.of(new SseFrame("message", "[DONE]")), frames);
    }

    @Test
    void truncatedDataAtEofFailsFastWithoutLeakingPayload() {
        String payload = "a-payload-that-must-not-leak";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SseFrameSplitter.frames("data: " + payload));
        assertTrue(ex.getMessage().contains("truncated"), ex.getMessage());
        // Content-leak discipline: the message carries the pending byte count, never
        // the payload body (mirrors SseFrameParser's length-only truncation message).
        assertTrue(ex.getMessage().contains(String.valueOf(payload.length())), ex.getMessage());
        assertTrue(ex.getMessage().contains("chars"), ex.getMessage());
        assertFalse(ex.getMessage().contains(payload), ex.getMessage());
    }

    @Test
    void emptyEventAfterNonEmptyEventResetsToMessage() {
        // The last event: line wins, and an empty value falls back to "message" — an empty
        // event: after a non-empty one must not keep the stale event (mirrors
        // SseFrameParser.nextRawFrame's event = value.isEmpty ? DEFAULT : value).
        assertEquals(List.of(new SseFrame("message", "x")), SseFrameSplitter.frames("event: foo\nevent:\ndata: x\n\n"));
        // ...and it must cancel a pending message_stop: no phantom terminal at EOF.
        assertTrue(SseFrameSplitter.frames("event: message_stop\nevent:\n").isEmpty());
    }

    @Test
    void loneCarriageReturnEndsLines() {
        // A lone \r is a line terminator, exactly as in the production bounded reader
        // (SseFrameParserTest.loneCarriageReturnEndsLines): `data: a\r\rdata: b\r\r` is
        // line + blank-line per frame.
        assertEquals(
                List.of(new SseFrame("message", "a"), new SseFrame("message", "b")),
                SseFrameSplitter.frames("data: a\r\rdata: b\r\r"));
    }

    @Test
    void utf8BomAtStreamStartIsTolerated() {
        // A leading UTF-8 BOM on the first line is skipped so the first frame's data is
        // not silently dropped (mirrors SseFrameParserTest.utf8BomAtStreamStartIsTolerated).
        assertEquals(List.of(new SseFrame("message", "hello")), SseFrameSplitter.frames("\uFEFFdata: hello\n\n"));
    }

    @Test
    void emptyInputAndCommentOnlyInputYieldNothing() {
        assertTrue(SseFrameSplitter.frames("").isEmpty());
        assertTrue(SseFrameSplitter.frames(": ping\n\n").isEmpty());
    }

    @Test
    void whitespacePrefixedFieldLinesAreIgnored() {
        // SSE: a line beginning with a space has an empty field name and is ignored; the
        // real data line below it still frames (mirrors
        // SseFrameParserTest.whitespacePrefixedFieldLinesAreIgnored).
        assertEquals(
                List.of(new SseFrame("message", "real")), SseFrameSplitter.frames(" data: hello\n\ndata: real\n\n"));
    }

    @Test
    void eventTypedDoneFramePayloadIsSentinel() {
        // [DONE] under a custom event name still surfaces as the sentinel payload
        // (mirrors SseFrameParserTest.eventTypedDoneFramePayloadIsSentinel).
        assertEquals(List.of(new SseFrame("done", "[DONE]")), SseFrameSplitter.frames("event: done\ndata: [DONE]\n\n"));
    }

    @Test
    void messageStopWithDataPayloadIsDispatched() {
        // The committed anthropic fixture's terminal-frame shape: message_stop with a data
        // payload — the payload is irrelevant to termination, so it is dispatched, not
        // treated as a truncated frame.
        assertEquals(
                List.of(new SseFrame("message_stop", "{\"a\":1}")),
                SseFrameSplitter.frames("event: message_stop\ndata: {\"a\":1}\n\n"));
    }

    @Test
    void dataLineBeforeEventAppliesEventToWholeFrame() {
        // An event: line inside data lines belongs to the same frame (it does not reset
        // the frame), and id:/retry: interleaved between data: lines are ignored.
        assertEquals(
                List.of(new SseFrame("delta", "a\nb")),
                SseFrameSplitter.frames("data: a\nevent: delta\nid: 1\nretry: 3\ndata: b\n\n"));
    }

    @Test
    void streamContinuesAfterTerminalFrame() {
        // A caller that keeps draining past message_stop still sees subsequent frames.
        assertEquals(
                List.of(new SseFrame("message_stop", ""), new SseFrame("message", "x")),
                SseFrameSplitter.frames("event: message_stop\n\ndata: x\n\n"));
    }

    @Test
    void blankLinesWithoutPendingFrameAreSkipped() {
        List<SseFrame> frames = SseFrameSplitter.frames("\n\ndata: ok\n\n\ndata: [DONE]\n\n");
        assertEquals(List.of(new SseFrame("message", "ok"), new SseFrame("message", "[DONE]")), frames);
    }

    @Test
    void doneSentinelWithTrailingSpaceAtEofIsTolerated() {
        // "[DONE] " at EOF (no blank-line terminator) must yield the
        // canonical sentinel, not throw — mirrors the production parser's strip-based
        // EOF tolerance (observed in the wild).
        List<SseFrame> frames = SseFrameSplitter.frames("data: [DONE] ");
        assertEquals(List.of(new SseFrame("message", "[DONE]")), frames);
    }

    @Test
    void doneSentinelWithTrailingSpaceOnBlankLineIsCanonicalized() {
        List<SseFrame> frames = SseFrameSplitter.frames("data: [DONE] \n\n");
        assertEquals(List.of(new SseFrame("message", "[DONE]")), frames);
    }

    @Test
    void dataLessNamedEventFramesAreDispatchedAtBlankLine() {
        // Production parity (SseFrameParserEventTest.dataLessNamedEventFramesDispatchedAtBlankLine):
        // a data-less frame naming a non-default event is that event — an empty-payload
        // `event: error\n\n` must be dispatched (the Anthropic adapter classifies
        // empty-data error frames as errors), just like the terminal message_stop and a
        // no-op ping. Only a data-less default (`message`) frame is skipped.
        assertEquals(List.of(new SseFrame("error", "")), SseFrameSplitter.frames("event: error\n\n"));
        assertEquals(List.of(new SseFrame("ping", "")), SseFrameSplitter.frames("event: ping\n\n"));
        assertTrue(SseFrameSplitter.frames(": ping\n\nevent: message\n\n").isEmpty());
    }

    @Test
    void dataLessNonTerminalEventFrameAtEofIsStillSkipped() {
        // The EOF tolerance stays message_stop-only (production:
        // SseFrameParserEventTest.dataLessNamedEventFrameAtEofStillNoFrame) — an
        // `event: ping` cut off by EOF without its blank-line terminator is a clean end.
        assertTrue(SseFrameSplitter.frames("event: ping\n").isEmpty());
    }

    @Test
    void eventDoesNotLeakAcrossFrameBoundary() {
        // A skipped or dispatched frame resets the pending event — a stale `event:` must
        // never mislabel the next frame (production reads each frame from clean state).
        assertEquals(
                List.of(new SseFrame("ping", ""), new SseFrame("message", "x")),
                SseFrameSplitter.frames("event: ping\n\ndata: x\n\n"));
    }

    @Test
    void singleTrailingLineFeedIsNotABlankLine() {
        // A lone trailing terminator ends the line, it does not open a blank one —
        // `data: {"a":1}\n` at EOF is a truncated frame exactly as in production
        // (SseFrameParser's line read consumes the terminator; only a second terminator
        // forms the blank line that dispatches the frame).
        assertThrows(IllegalArgumentException.class, () -> SseFrameSplitter.frames("data: {\"a\":1}\n"));
        assertEquals(List.of(new SseFrame("message", "{\"a\":1}")), SseFrameSplitter.frames("data: {\"a\":1}\n\n"));
    }

    @Test
    void trailingEmptyDataLineStripsFinalLineFeed() {
        // `data: a\ndata:\n\n` yields "a" (one trailing LF stripped),
        // matching the production parser's deliberate divergence from the SSE spec.
        assertEquals(List.of(new SseFrame("message", "a")), SseFrameSplitter.frames("data: a\ndata:\n\n"));
        assertEquals(List.of(new SseFrame("message", "")), SseFrameSplitter.frames("data:\ndata:\n\n"));
    }
}
