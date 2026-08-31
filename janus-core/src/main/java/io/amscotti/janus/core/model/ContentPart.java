package io.amscotti.janus.core.model;

/**
 * One part of a multimodal user message. String-only user messages keep
 * {@link UserMessage#content}; multimodal traffic uses an ordered list of these parts.
 *
 * <p>OpenAI wire: {@code text} / {@code image_url}. Anthropic wire: {@code text} /
 * {@code image} ({@link ImageSourceContent} base64 or url source). Codecs translate
 * between the two; unknown part types are rejected at decode.
 */
public sealed interface ContentPart permits TextContent, ImageUrlContent, ImageSourceContent {}
