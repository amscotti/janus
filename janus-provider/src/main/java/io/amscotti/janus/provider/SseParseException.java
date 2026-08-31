package io.amscotti.janus.provider;

/**
 * Raised by {@link SseFrameParser} when the upstream stream ends mid-frame — EOF before the
 * blank-line frame terminator with data lines still pending (a well-formed stream either
 * terminates with {@code data: [DONE]} or ends cleanly between frames). Package-private: the
 * adapter maps it to {@link ProviderException#TYPE_BAD_UPSTREAM_PAYLOAD}; the gateway
 * never sees it.
 */
final class SseParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SseParseException(String message) {
        super(message);
    }
}
