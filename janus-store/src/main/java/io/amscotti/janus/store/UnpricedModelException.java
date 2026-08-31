package io.amscotti.janus.store;

/**
 * Thrown when {@link PriceTable} is in require-priced mode and the alias has no
 * {@code [[janus.pricing.models]]} row. The gateway maps this to a 400 so the
 * request never dispatches (and never meters at $0).
 */
public final class UnpricedModelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String model;

    public UnpricedModelException(String model) {
        super("no price configured for model \"" + PriceTable.sanitizeForLog(model == null ? "" : model) + "\"");
        this.model = model;
    }

    public String model() {
        return model;
    }
}
