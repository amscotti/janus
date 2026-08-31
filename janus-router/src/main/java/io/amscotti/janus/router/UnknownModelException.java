package io.amscotti.janus.router;

import java.util.Objects;

/**
 * Unchecked exception raised by {@link Router#route(String)} (and therefore {@link
 * Router#complete} / {@link Router#stream}) for an unknown model alias — no backend is
 * registered for the requested name. Carries the un-routable alias ({@link #model},
 * the <b>raw</b> value) so the gateway can map it to the OpenAI error envelope without
 * message sniffing. The {@link #getMessage} form is sanitized ({@link LogSafe} —
 * control characters stripped, truncated): the alias is client-controlled, so an
 * embedded newline must never forge a log record if the message is ever logged.
 * Deliberately no HTTP status baked in (the reference/LiteLLM map no-deployment to 503; the
 * OpenAI-shaped status decision is the gateway's).
 */
public final class UnknownModelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String model;

    public UnknownModelException(String model) {
        // The guard must run BEFORE the super message is built: LogSafe.text
        // dereferences its input, so evaluating the message first would surface a null
        // model as a raw NPE from the sanitizer and make this constructor's own
        // requireNonNull unreachable. A static helper is the only code that runs before
        // super in Java.
        super(sanitizedMessage(model));
        this.model = model; // non-null: sanitizedMessage threw otherwise
    }

    /** Validates {@code model} and builds the sanitized message (runs before {@code super}). */
    private static String sanitizedMessage(String model) {
        Objects.requireNonNull(model, "model");
        return "unknown model: " + LogSafe.text(model);
    }

    /** The un-routable model alias (raw, unsanitized). */
    public String model() {
        return model;
    }
}
