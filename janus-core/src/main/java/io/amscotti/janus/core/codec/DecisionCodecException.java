package io.amscotti.janus.core.codec;

/**
 * Unchecked exception raised by the decision codecs ({@link SystemOneCodec},
 * {@link OpenAiDecisionsCodec}) for every decode/encode failure — the
 * {@link OpenAiCodecException} convention: a {@code type} discriminator
 * matching the gateway error-envelope vocabulary ({@link
 * #TYPE_INVALID_REQUEST} for client-side failures, {@link #TYPE_API_ERROR}
 * for upstream-side ones).
 */
public final class DecisionCodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Client request failed validation/parsing. */
    public static final String TYPE_INVALID_REQUEST = "invalid_request_error";

    /** Upstream decision response failed parsing/validation. */
    public static final String TYPE_API_ERROR = "api_error";

    private final String type;

    public DecisionCodecException(String type, String message) {
        super(message);
        this.type = type;
    }

    public DecisionCodecException(String type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    /** Error-type discriminator, ready for error-envelope mapping. */
    public String type() {
        return type;
    }
}
