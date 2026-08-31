package io.amscotti.janus.provider;

/**
 * How a {@link ProviderAdapter} authenticates to its upstream.
 *
 * <p>{@code type} is one of {@value #TYPE_BEARER}, {@value #TYPE_X_API_KEY} or
 * {@value #TYPE_NONE}; {@code secret} holds the credential (the API key for bearer /
 * x-api-key auth; null for {@value #TYPE_NONE}). The composition root (gateway/CLI,
 * /) resolves the secret — the SPI never reads environment variables or
 * configuration files.
 *
 * <p><b>Why {@value #TYPE_X_API_KEY} exists.</b> Anthropic authenticates with a
 * literal {@code x-api-key: <key>} header, not {@code Authorization: Bearer}; the SPI's
 * {@code auth} description must be honest so a caller building upstream requests from
 * the record (rather than from an adapter's hardcoded header logic) sends the right
 * scheme.
 *
 * @param type auth scheme: {@code "bearer"}, {@code "x-api-key"} or {@code "none"}
 * @param secret credential; null when no credential is needed
 */
public record ProviderAuth(String type, String secret) {

    public static final String TYPE_BEARER = "bearer";
    /** Anthropic-style upstreams authenticate with the {@code x-api-key} header. */
    public static final String TYPE_X_API_KEY = "x-api-key";

    public static final String TYPE_NONE = "none";

    public ProviderAuth {
        if (!TYPE_BEARER.equals(type) && !TYPE_X_API_KEY.equals(type) && !TYPE_NONE.equals(type)) {
            throw new IllegalArgumentException("unsupported auth type: " + type + " (expected \"" + TYPE_BEARER
                    + "\", \"" + TYPE_X_API_KEY + "\" or \"" + TYPE_NONE + "\")");
        }
        // The record's contract ("secret holds the credential; null for
        // TYPE_NONE") is enforced, not just documented — an invalid pair must fail fast
        // instead of silently reaching an adapter (the adapters null-tolerate the
        // secret, so this closes the gap without a runtime behavior change).
        if (TYPE_NONE.equals(type) && secret != null) {
            throw new IllegalArgumentException("auth type \"" + TYPE_NONE + "\" must carry a null secret");
        }
        if (!TYPE_NONE.equals(type) && secret == null) {
            throw new IllegalArgumentException("auth type \"" + type + "\" must carry a secret");
        }
    }
}
