package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Pins the shared {@link HttpSupport} plumbing (the single home for the
 * statics the OpenAI-compatible and Anthropic adapters used to borrow from each other):
 * the bounded body read's deadline / interrupt / transport-failure classification, and
 * the {@code Retry-After} capture.
 */
class HttpSupportTest {

    private static final Duration DEADLINE = Duration.ofMillis(500);

    @Test
    void readBodyReadsTheWholeStream() {
        String body =
                HttpSupport.readBody(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), DEADLINE);
        assertEquals("hello", body);
    }

    @Test
    void readBodyRejectsBodiesBeyondTheResponseCap() {
        // a 2xx body larger than MAX_RESPONSE_BODY_BYTES is an upstream-controlled
        // unbounded allocation — it must fail as bad_upstream_payload, never OOM the
        // gateway (the error-body-cap rationale applied to the success path).
        byte[] oversized = new byte[HttpSupport.MAX_RESPONSE_BODY_BYTES + 1];
        ProviderException e = assertThrows(
                ProviderException.class, () -> HttpSupport.readBody(new ByteArrayInputStream(oversized), DEADLINE));
        assertEquals(ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD, e.type());
    }

    @Test
    void readBodyAcceptsBodiesAtTheResponseCap() {
        byte[] atCap = new byte[HttpSupport.MAX_RESPONSE_BODY_BYTES];
        String body = HttpSupport.readBody(new ByteArrayInputStream(atCap), Duration.ofSeconds(30));
        assertEquals(HttpSupport.MAX_RESPONSE_BODY_BYTES, body.length());
    }

    @Test
    void readBodyTimesOutWhenTheStreamStalls() throws Exception {
        // a connected-but-empty pipe blocks readAllBytes forever — the deadline
        // surfaces as TYPE_TIMEOUT instead of a hang.
        PipedInputStream stalled = new PipedInputStream(new java.io.PipedOutputStream());
        ProviderException e = assertThrows(ProviderException.class, () -> HttpSupport.readBody(stalled, DEADLINE));
        assertEquals(ProviderException.TYPE_TIMEOUT, e.type());
        assertTrue(e.retryable());
    }

    @Test
    void interruptedBodyReadIsNonRetryableAndRestoresInterrupt() {
        // coverage: the readBody interrupt branch (caller interrupted mid-read) is a
        // local interrupt, not an upstream fault — non-retryable like the adapters'
        // send, with the interrupt restored.
        Thread.currentThread().interrupt();
        ProviderException e = assertThrows(
                ProviderException.class,
                () -> HttpSupport.readBody(new ByteArrayInputStream(new byte[0]), Duration.ofSeconds(5)));
        assertEquals(ProviderException.TYPE_UPSTREAM_4XX, e.type());
        assertFalse(e.retryable());
        assertTrue(Thread.interrupted(), "the interrupt must be restored for the caller");
    }

    @Test
    void midReadTransportFailureIsNetwork() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };
        ProviderException e = assertThrows(ProviderException.class, () -> HttpSupport.readBody(broken, DEADLINE));
        assertEquals(ProviderException.TYPE_NETWORK, e.type());
        assertTrue(e.retryable());
    }

    @Test
    void subMillisecondDeadlineStillTimesOut() throws Exception {
        // Duration.toMillis truncates, so a sub-millisecond deadline became
        // join(0) — "wait forever" in Thread.join semantics. The deadline is clamped to a
        // 1 ms minimum, so it must still fire promptly on a stalled stream.
        PipedInputStream stalled = new PipedInputStream(new java.io.PipedOutputStream());
        long start = System.nanoTime();
        ProviderException e =
                assertThrows(ProviderException.class, () -> HttpSupport.readBody(stalled, Duration.ofNanos(500)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(ProviderException.TYPE_TIMEOUT, e.type());
        assertTrue(e.retryable());
        assertTrue(elapsedMs < 5_000, "a sub-ms deadline must fire promptly, took " + elapsedMs + "ms");
    }

    @Test
    void readErrorBodyStopsAtTheCap() {
        // The non-2xx body is read only to probe the envelope type, which sits
        // at the top — a multi-MB upstream error body must not be buffered in full (the
        // success path needs the whole body for the codec; the error path does not).
        CountingInputStream counting = new CountingInputStream(2 * 1024 * 1024);
        String body = HttpSupport.readErrorBody(counting, Duration.ofSeconds(5));
        assertEquals(HttpSupport.MAX_ERROR_BODY_BYTES, body.length(), "must read exactly the cap");
        assertTrue(counting.bytesRead() <= HttpSupport.MAX_ERROR_BODY_BYTES);
        assertTrue(counting.bytesRead() < 1_000_000, "must not read anywhere near the whole 2 MB body");
    }

    @Test
    void readErrorBodyReadsAShortBodyInFull() {
        // a short error body (smaller than the cap) is read completely — the envelope
        // probe needs it, and there is no truncation below the cap.
        String body = HttpSupport.readErrorBody(
                new ByteArrayInputStream("{\"error\":{\"type\":\"api_error\"}}".getBytes(StandardCharsets.UTF_8)),
                DEADLINE);
        assertEquals("{\"error\":{\"type\":\"api_error\"}}", body);
    }

    @Test
    void runtimeFailureMidReadSurfacesNotAnNpe() {
        // Coverage: the reader thread previously captured only IOException — a
        // non-IO runtime failure mid-read left `result` null and NPE'd on the final
        // `new String(null, UTF_8)` after the join. It now surfaces as the original
        // runtime failure.
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IllegalStateException("boom");
            }
        };
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> HttpSupport.readBody(broken, DEADLINE));
        assertEquals("boom", e.getMessage());
    }

    @Test
    void isSuccessSpansThe2xxBand() {
        assertTrue(HttpSupport.isSuccess(200));
        assertTrue(HttpSupport.isSuccess(299));
        assertFalse(HttpSupport.isSuccess(199));
        assertFalse(HttpSupport.isSuccess(300));
        assertFalse(HttpSupport.isSuccess(500));
    }

    /** An endless-ish byte source that counts how many bytes were read. */
    private static final class CountingInputStream extends InputStream {
        private final int size;
        private int position;

        CountingInputStream(int size) {
            this.size = size;
        }

        @Override
        public int read() {
            return position < size ? position++ & 0xFF : -1;
        }

        int bytesRead() {
            return position;
        }
    }
}
