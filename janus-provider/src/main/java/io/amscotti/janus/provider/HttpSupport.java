package io.amscotti.janus.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Package-private HTTP plumbing shared by {@link OpenAiCompatibleAdapter} and
 * {@link AnthropicAdapter}: the success/error-status predicate, the upstream
 * {@code Retry-After} capture, the best-effort body close, and the wall-clock-bounded
 * body read. One obvious home for the shared statics (the Anthropic
 * adapter previously borrowed {@code isSuccess}/{@code closeQuietly}/{@code retryAfterSeconds}
 * from the OpenAI adapter's package-private statics — a silent, undocumented coupling).
 *
 * <p><b>The body-read deadline.</b> Header arrival is bounded by the request timeout
 * (the JDK {@link java.net.http.HttpClient} request timeout); once headers have
 * arrived, a non-streaming body is read under a wall-clock deadline so a stalled
 * upstream cannot pin a worker thread forever. Timeout → {@link
 * ProviderException#TYPE_TIMEOUT}; a mid-read transport failure → {@link
 * ProviderException#TYPE_NETWORK}. The read itself runs on a fresh <b>virtual</b> thread
 * per call ({@code Thread.ofVirtual}, native-image clean on JDK 25, matching the
 * gateway's virtual-thread SSE workers) — a blocking socket read is not interruptible,
 * so the deadline is enforced by the bounded {@link Thread#join(long)} and the input is
 * closed to unblock the reader; virtual threads are cheap enough that per-call creation
 * (previously a fresh platform thread + executor per call) is no scalability smell.
 *
 * <p><b>The error-body read cap.</b> The non-2xx error body is read only to probe the
 * error envelope's {@code error.type}, which sits at the top of the payload — the
 * {@link #readErrorBody} read is capped at {@value #MAX_ERROR_BODY_BYTES} bytes so a
 * misbehaving/malicious upstream (or a large proxy HTML error page) cannot force an
 * unbounded heap allocation for a one-member probe (the success body must be read
 * whole for the codec, but is itself capped at {@value #MAX_RESPONSE_BODY_BYTES}
 * bytes — over-cap fails as {@link ProviderException#TYPE_BAD_UPSTREAM_PAYLOAD}). A
 * body larger than the cap is truncated; the caller's envelope probe either finds
 * the type in the prefix or falls back to the status mapping.
 */
final class HttpSupport {

    private HttpSupport() {}

    static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    /**
     * The upstream {@code Retry-After} delta-seconds from a non-2xx response, or null
     * when absent or in the HTTP-date form (only the delta-seconds form is forwarded;
     * an unparseable value is dropped, never a 500). Carried on the
     * {@link ProviderException} so the gateway can forward the provider's precise
     * backoff window on 429 passthrough.
     */
    static Long retryAfterSeconds(HttpResponse<?> response) {
        var header = response.headers().firstValue("Retry-After");
        if (header.isEmpty()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.get().strip());
            return seconds >= 0 ? seconds : null;
        } catch (NumberFormatException e) {
            return null; // HTTP-date form — dropped, never a 500
        }
    }

    /** Best-effort release; the connection is gone either way. */
    static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // best-effort release; the connection is gone either way
        }
    }

    /**
     * Reads the response body under a wall-clock deadline. Timeout →
     * {@link ProviderException#TYPE_TIMEOUT} (the input is closed to unblock the
     * reader); a mid-read transport failure → {@link ProviderException#TYPE_NETWORK}.
     *
     * <p>The read is capped at {@link #MAX_RESPONSE_BODY_BYTES}: a body larger than the
     * cap is an upstream-controlled unbounded allocation (the error-body-cap
     * rationale applied to the success path — a fast-dribbling 200 response could OOM
     * the gateway well within the 300 s deadline). Over-cap fails as
     * {@link ProviderException#TYPE_BAD_UPSTREAM_PAYLOAD} rather than truncating: a
     * truncated JSON body would only fail later in the codec with a confusing parse
     * error.
     */
    static String readBody(InputStream in, Duration timeout) {
        byte[] bytes = readBodyBytes(in, timeout, MAX_RESPONSE_BODY_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BODY_BYTES) {
            throw new ProviderException(
                    ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD,
                    "upstream 2xx body exceeded " + (MAX_RESPONSE_BODY_BYTES / (1024 * 1024)) + " MiB read cap",
                    null,
                    null);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Reads the non-2xx error body under the same wall-clock deadline, but capped at
     * {@link #MAX_ERROR_BODY_BYTES}: the body is read only to probe the error envelope's
     * {@code error.type}, which sits at the top of the payload — reading past the cap
     * would retain an unbounded, upstream-controlled buffer (a misbehaving upstream or a
     * large proxy 503 HTML page) for a one-member probe. A body larger than the cap is
     * truncated; the caller's envelope probe either finds the type in the prefix or
     * falls back to the status mapping.
     */
    static String readErrorBody(InputStream in, Duration timeout) {
        return new String(readBodyBytes(in, timeout, MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
    }

    /** The error-body probe read cap — the envelope sits at the top of the payload. */
    static final int MAX_ERROR_BODY_BYTES = 8 * 1024;

    /** Unique body-reader names (review L2 — see {@link #readBodyBytes}). */
    private static final java.util.concurrent.atomic.AtomicLong BODY_READER_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * The success-body read cap — {@value #MAX_RESPONSE_BODY_BYTES} bytes ({@code 64
     * MiB}). Real chat-completion JSON bodies are kilobytes-to-low-megabytes even at
     * frontier output limits and large {@code n}; the cap is an OOM defense against a
     * misbehaving/malicious 2xx upstream, not a tuning knob. Over-cap reads fail as
     * {@link ProviderException#TYPE_BAD_UPSTREAM_PAYLOAD}.
     */
    static final int MAX_RESPONSE_BODY_BYTES = 64 * 1024 * 1024;

    private static byte[] readBodyBytes(InputStream in, Duration timeout, int limit) {
        // The reader must be a separate thread: a blocking socket read is not
        // interruptible, so the deadline is enforced by the bounded join and the input
        // close, not by an interrupt. A virtual thread is cheap and never occupies a
        // platform carrier while blocked (native-image clean on JDK 25).
        var result = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        // Review L2: a per-call Thread.ofVirtual.name(prefix, 0) builder restarts its
        // counter every call, so every reader was named "janus-body-read-0" — a static
        // sequence keeps thread dumps / flight recordings distinguishable.
        Thread reader = Thread.ofVirtual()
                .name("janus-body-read-", BODY_READER_SEQ.incrementAndGet())
                .start(() -> {
                    try {
                        result.set(readUpTo(in, limit));
                    } catch (IOException e) {
                        failure.set(new UncheckedIOException(e));
                    } catch (RuntimeException e) {
                        // A non-IO runtime failure mid-read must surface after the join — never
                        // a `new String(null, UTF_8)` NPE from the unset result.
                        failure.set(e);
                    }
                });
        // Duration.toMillis truncates, so a sub-millisecond deadline would become
        // join(0) — "wait forever" in Thread.join semantics. Clamp to a 1 ms minimum so
        // the deadline always fires.
        long deadlineMillis = Math.max(1, timeout.toMillis());
        try {
            reader.join(deadlineMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(in);
            // A locally interrupted read is not an upstream fault — the
            // terminal non-retryable catch-all (parity with the adapters' send), so
            // the router does not burn a retry slot on a sticky interrupt.
            throw new ProviderException(
                    ProviderException.TYPE_UPSTREAM_4XX, "interrupted while reading upstream response body", null, e);
        }
        if (reader.isAlive()) {
            // Grace re-check: wall-clock deadlines are approximate — a read
            // completing at the instant the deadline expires can still report isAlive
            // for a few microseconds. Re-join briefly before declaring a timeout so a
            // fully-read body is not wasted by a spurious close + timeout.
            try {
                reader.join(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closeQuietly(in);
                throw new ProviderException(
                        ProviderException.TYPE_UPSTREAM_4XX,
                        "interrupted while reading upstream response body",
                        null,
                        e);
            }
            if (reader.isAlive()) {
                // Deadline exceeded — close the input to unblock the stalled read, then
                // surface the timeout (parity with the pre-virtual-thread bounded read).
                closeQuietly(in);
                throw new ProviderException(
                        ProviderException.TYPE_TIMEOUT,
                        "upstream response body read timed out after " + timeout.toSeconds() + "s",
                        null,
                        new TimeoutException());
            }
        }
        Throwable cause = failure.get();
        if (cause != null) {
            if (cause instanceof UncheckedIOException uio && uio.getCause() != null) {
                cause = uio.getCause();
            }
            if (cause instanceof IOException io) {
                throw new ProviderException(
                        ProviderException.TYPE_NETWORK,
                        "failed reading upstream response body: " + io.getMessage(),
                        null,
                        io);
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ProviderException(
                    ProviderException.TYPE_NETWORK, "failed reading upstream response body", null, cause);
        }
        byte[] bytes = result.get();
        if (bytes == null) {
            // Reader died without recording a result or failure (e.g. an Error thrown
            // from a misbehaving InputStream) — a typed network failure, never a
            // confusing `new String(null, UTF_8)` NPE.
            throw new ProviderException(
                    ProviderException.TYPE_NETWORK, "failed reading upstream response body", null, null);
        }
        return bytes;
    }

    private static byte[] readUpTo(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(limit, 4096));
        byte[] chunk = new byte[8192];
        int remaining = limit;
        int n;
        while (remaining > 0 && (n = in.read(chunk, 0, Math.min(chunk.length, remaining))) != -1) {
            buffer.write(chunk, 0, n);
            remaining -= n;
        }
        return buffer.toByteArray();
    }
}
