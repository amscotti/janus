package io.amscotti.janus.core.model;

/**
 * Anthropic-style image source (and the intermediate form after converting an OpenAI
 * data URL). {@code type} is {@code "base64"} or {@code "url"}.
 *
 * <ul>
 * <li>{@code base64}: {@code mediaType} + {@code data} (raw base64, no data-URL prefix)
 * <li>{@code url}: {@code url} is the https location; {@code mediaType}/{@code data} null
 * </ul>
 */
public record ImageSourceContent(String type, String mediaType, String data, String url, Object cacheControl)
        implements ContentPart {

    /** Convenience without a cache marker. */
    public ImageSourceContent(String type, String mediaType, String data, String url) {
        this(type, mediaType, data, url, null);
    }

    public static ImageSourceContent base64(String mediaType, String data) {
        return new ImageSourceContent("base64", mediaType, data, null);
    }

    public static ImageSourceContent url(String url) {
        return new ImageSourceContent("url", null, null, url);
    }

    /** Image payloads are conversation content: excluded from log output unless
     * content logging is explicitly enabled ({@code [janus.privacy] log-content}). */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "ImageSourceContent[type=" + type + ", mediaType=" + mediaType + ", data=" + data + ", url=" + url
                    + "]";
        }
        return "ImageSourceContent[type=" + type + ", mediaType=" + mediaType + ", payload not logged]";
    }
}
