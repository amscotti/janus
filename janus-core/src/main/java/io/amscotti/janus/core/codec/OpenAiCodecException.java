package io.amscotti.janus.core.codec;

/**
 * Unchecked exception raised by {@link OpenAiMessageCodec} for every decode/encode
 * failure. Carries a {@code type} discriminator matching the OpenAI error-envelope
 * {@code error.type} vocabulary so the gateway can map it without re-parsing:
 *
 * <ul>
 * <li>{@link #TYPE_INVALID_REQUEST} — client-request-side failures: malformed JSON,
 * missing/blank model, empty messages, unknown roles, tool messages without
 * {@code tool_call_id}, array-form content, encode of an invalid canonical request.
 * <li>{@link #TYPE_API_ERROR} — upstream-side failures: malformed provider responses,
 * unknown roles in responses/chunks.
 * </ul>
 */
public final class OpenAiCodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** OpenAI {@code invalid_request_error} — client request failed validation/parsing. */
    public static final String TYPE_INVALID_REQUEST = "invalid_request_error";

    /** OpenAI {@code api_error} — upstream response/chunk failed parsing/validation. */
    public static final String TYPE_API_ERROR = "api_error";

    private final String type;

    public OpenAiCodecException(String type, String message) {
        super(message);
        this.type = type;
    }

    public OpenAiCodecException(String type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    /** Error-type discriminator, ready for error-envelope mapping. */
    public String type() {
        return type;
    }
}
