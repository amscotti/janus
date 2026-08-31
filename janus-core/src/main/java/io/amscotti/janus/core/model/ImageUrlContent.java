package io.amscotti.janus.core.model;

/**
 * OpenAI-style image reference. {@code url} may be an {@code https://…} link or a
 * {@code data:image/…;base64,…} data URL. {@code detail} is optional ({@code low}/
 * {@code high}/{@code auto}) and OpenAI-only (dropped on the Anthropic leg).
 *
 * <p>On Anthropic encode: data URLs become {@link ImageSourceContent} base64 sources;
 * http(s) URLs become Anthropic {@code source.type=url}.
 */
public record ImageUrlContent(String url, String detail, Object cacheControl) implements ContentPart {

    /** Convenience without detail or cache marker. */
    public ImageUrlContent(String url) {
        this(url, null, null);
    }

    /** Convenience without a cache marker. */
    public ImageUrlContent(String url, String detail) {
        this(url, detail, null);
    }

    /** Image locations are conversation content: excluded from log output unless
     * content logging is explicitly enabled ({@code [janus.privacy] log-content}). */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "ImageUrlContent[url=" + url + ", detail=" + detail + "]";
        }
        return "ImageUrlContent[detail=" + detail + ", url not logged]";
    }
}
