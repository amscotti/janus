package io.amscotti.janus.provider;

import java.net.URI;

/**
 * Provider base-URL normalization (single home for the rule both {@link
 * OpenAiCompatibleAdapter} and {@link AnthropicAdapter} apply): the caller-supplied base
 * is the FULL prefix; trailing slashes are stripped and trailing {@code /v1} segments
 * are removed (case-insensitively and repeatedly), so {@code "https://api.deepseek.com"},
 * {@code "https://api.deepseek.com/v1"}, {@code "https://api.deepseek.com/v1/"},
 * {@code "https://api.deepseek.com/V1"} and {@code "https://api.deepseek.com/v1/v1"} all
 * normalize to {@code "https://api.deepseek.com"} and each adapter appends its own full
 * versioned path ({@code /v1/chat/completions} resp. {@code /v1/messages}). Mirrors LiteLLM
 * {@code llms/openai_like/openai.py:65-70} and the reference {@code openai_compatible.rs}.
 *
 * <p><b>Scheme guard ( SSRF re-pass).</b> {@code base-url} is operator TOML config —
 * never end-user input (no request path writes it) — but a misconfigured or
 * compromised config must not turn the gateway into an SSRF relay: only {@code http}
 * and {@code https} schemes are accepted, and a scheme-less URL (a bare hostname that
 * would silently resolve against the process default) fails fast at boot. Intranet
 * {@code http://} hosts remain operator-owned — that is the documented trust boundary.
 */
final class BaseUrls {

    private BaseUrls() {}

    /**
     * @param baseUrl caller-supplied provider base; must be non-blank, resolve to a
     * non-empty URL and name the {@code http} or {@code https} scheme
     * @return the normalized base — no trailing slash, trailing {@code /v1} stripped
     * (case-insensitive, repeatedly, so {@code "/v1"}, {@code "/V1"} and
     * {@code "/v1/v1"} all collapse to the host)
     * @throws IllegalArgumentException when {@code baseUrl} is blank, resolves to empty,
     * uses a non-{@code http(s)} scheme, or has no scheme at all
     */
    static String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must be non-blank");
        }
        String normalized = baseUrl.strip();
        while (true) {
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            // A trailing /v1 is stripped (case-insensitive, repeatedly): each adapter
            // appends its own versioned path, so an operator-supplied /v1 (or /V1, or a
            // doubled /v1/v1) must not leave a second versioned segment in the endpoint.
            if (normalized.length() >= 3 && normalized.regionMatches(true, normalized.length() - 3, "/v1", 0, 3)) {
                normalized = normalized.substring(0, normalized.length() - 3);
                continue;
            }
            break;
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("baseUrl must resolve to a non-empty URL");
        }
        URI uri = URI.create(normalized); // fail fast on malformed URLs
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                    "baseUrl must use the http or https scheme, got: " + (scheme == null ? "<none>" : scheme));
        }
        // An opaque or host-less http(s) URI ("http:example.com", "https:///path")
        // passes the scheme check but has no routable host — reject at boot, not first
        // dispatch (the adapters would only fail later with an unhelpful connect error).
        if (uri.isOpaque() || uri.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must carry a routable host, got: " + normalized);
        }
        return normalized;
    }
}
