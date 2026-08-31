package io.amscotti.janus.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * User message. Content is either:
 *
 * <ul>
 * <li><b>String</b> — classic chat: {@link #content} non-null, {@link #parts} null
 * <li><b>Multimodal parts</b> — {@link #parts} non-empty (text + image parts);
 * {@link #content} may be null
 * </ul>
 *
 * <p>{@code name} is the optional OpenAI participant name. Use {@link #plainText} for
 * governance prompt estimates and any path that needs a single string view.
 */
public record UserMessage(String content, String name, List<ContentPart> parts) implements Message {

    public UserMessage {
        // Null-tolerant unmodifiable copy (matches ChatRequest's list contract): a null
        // element is malformed wire input, but it must not escape construction as a raw
        // NullPointerException from List.copyOf — the codecs reject it with a typed
        // error.
        parts = parts == null ? null : Collections.unmodifiableList(new ArrayList<>(parts));
    }

    /**
     * Conversation text is excluded from log output unless content logging is
     * explicitly enabled ({@code [janus.privacy] log-content}).
     */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "UserMessage[content=" + content + ", name=" + name + ", parts=" + parts + "]";
        }
        return "UserMessage[name=" + name + ", parts=" + (parts == null ? 0 : parts.size()) + ", content not logged]";
    }

    /** Plain-text user message without a participant name. */
    public UserMessage(String content) {
        this(content, null, null);
    }

    /** Plain-text user message with optional participant name. */
    public UserMessage(String content, String name) {
        this(content, name, null);
    }

    /** Multimodal user message (ordered text/image parts). */
    public static UserMessage multimodal(List<ContentPart> parts) {
        return new UserMessage(null, null, parts);
    }

    /** Multimodal user message with OpenAI participant name. */
    public static UserMessage multimodal(String name, List<ContentPart> parts) {
        return new UserMessage(null, name, parts);
    }

    public boolean isMultimodal() {
        return parts != null && !parts.isEmpty();
    }

    /**
     * Best-effort plain text: the string content when present, otherwise concatenated
     * {@link TextContent} parts (images contribute nothing). Never null.
     */
    public String plainText() {
        if (content != null) {
            return content;
        }
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentPart part : parts) {
            if (part instanceof TextContent text && text.text() != null) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    @Override
    public ChatRole role() {
        return ChatRole.USER;
    }
}
