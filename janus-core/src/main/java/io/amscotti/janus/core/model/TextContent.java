package io.amscotti.janus.core.model;

/**
 * A text part of a multimodal user message ({@code {"type":"text","text":…}}).
 * {@code cacheControl} is the Anthropic-shaped block marker
 * ({@code {type: ephemeral}}) when the part carried an OpenAI
 * {@code prompt_cache_breakpoint} or an Anthropic {@code cache_control}.
 * Conversation text is excluded from log output unless content logging is
 * explicitly enabled ({@code [janus.privacy] log-content}).
 */
public record TextContent(String text, Object cacheControl) implements ContentPart {

    /** Plain text part with no cache marker. */
    public TextContent(String text) {
        this(text, null);
    }

    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "TextContent[text=" + text + ", cacheControl=" + cacheControl + "]";
        }
        return "TextContent[text not logged]";
    }
}
