package io.amscotti.janus.router;

/**
 * Shared sanitization for client-controlled strings echoed into exception messages
 * (log-forgery hygiene): control characters become spaces (an embedded
 * newline must not forge a second log record) and the value is truncated, mirroring
 * the store module's {@code PriceTable.sanitizeForLog}. The router cannot import
 * janus-store (AGENTS.md: router depends on core only), so the rule is duplicated
 * here, deliberately. Typed accessors (e.g. {@link UnknownModelException#model})
 * keep the raw value; only messages/logs get the safe form.
 */
final class LogSafe {

    /** The max length of a client-controlled value echoed into a message. */
    static final int MAX_LENGTH = 128;

    private LogSafe() {}

    /** The message/log form of {@code value}: control chars replaced, truncated. */
    static String text(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        if (sb.length() <= MAX_LENGTH) {
            return sb.toString();
        }
        return sb.substring(0, MAX_LENGTH) + "...";
    }
}
