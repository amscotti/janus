package io.amscotti.janus.router;

import java.util.Objects;

/**
 * Unchecked exception raised by {@link Router#countTokens} when a route exists
 * for the alias but no candidate backend can count tokens (a provider without
 * an upstream token-counting endpoint). Carries the requested alias
 * ({@link #model}, raw) so the gateway can map it to the face's error envelope
 * without message sniffing; the message form is sanitized ({@link LogSafe})
 * because the alias is client-controlled.
 */
public final class UnsupportedTokenCountingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String model;

    public UnsupportedTokenCountingException(String model) {
        super(sanitizedMessage(model));
        this.model = model; // non-null: sanitizedMessage threw otherwise
    }

    private static String sanitizedMessage(String model) {
        Objects.requireNonNull(model, "model");
        return "token counting is not supported for model: " + LogSafe.text(model);
    }

    /** The model alias whose backends cannot count tokens (raw, unsanitized). */
    public String model() {
        return model;
    }
}
