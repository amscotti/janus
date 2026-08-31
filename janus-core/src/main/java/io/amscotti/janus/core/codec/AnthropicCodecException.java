package io.amscotti.janus.core.codec;

/**
 * Unchecked exception raised by {@link AnthropicMessageCodec} for every decode/encode
 * failure. Carries a {@code type} discriminator matching the Anthropic error-envelope
 * {@code error.type} vocabulary (both values below are valid Anthropic envelope types) so
 * the gateway can map it via its {@code @type_map} without re-parsing:
 *
 * <ul>
 * <li>{@link #TYPE_INVALID_REQUEST} — client-request-side failures: malformed JSON,
 * missing/blank model, empty messages, unknown roles, malformed wire
 * {@code tools}/{@code tool_choice}, non-text system/content blocks, invalid
 * canonical tool arguments (raw JSON that fails to parse into an
 * {@code input_schema}/{@code input} object).
 * <li>{@link #TYPE_API_ERROR} — upstream-side failures: malformed provider responses,
 * unsupported response content blocks (multimodal out of scope), tool messages in
 * response choices.
 * </ul>
 */
public final class AnthropicCodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Anthropic {@code invalid_request_error} — client request failed validation/parsing. */
    public static final String TYPE_INVALID_REQUEST = "invalid_request_error";

    /** Anthropic {@code api_error} — upstream response/chunk failed parsing/validation. */
    public static final String TYPE_API_ERROR = "api_error";

    private final String type;

    public AnthropicCodecException(String type, String message) {
        super(message);
        this.type = type;
    }

    public AnthropicCodecException(String type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    /** Error-type discriminator, ready for error-envelope mapping. */
    public String type() {
        return type;
    }
}
