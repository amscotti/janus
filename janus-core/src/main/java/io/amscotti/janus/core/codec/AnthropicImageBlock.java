package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An image content block. Wire shape:
 * {@code {"type":"image","source":{"type":"base64","media_type":"...","data":"..."}}}
 * (url sources: {@code source.type=url}). On user-message decode the {@code source}
 * maps structurally into a canonical image part — base64 requires {@code data}
 * (media_type defaults to {@code image/png}), url requires {@code url}, anything else
 * is a typed invalid-request error — and encode re-emits image blocks from canonical
 * image parts (data URLs convert to base64 sources, https URLs to url sources;
 * {@code cache_control} rides along). Response/stream decode drops image blocks
 * (assistant output has no canonical multimodal home — the streaming and
 * non-streaming decode paths must agree).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicImageBlock(String type, Object source, Object cacheControl) implements AnthropicContentBlock {

    /** Image block with no cache marker. */
    public AnthropicImageBlock(String type, Object source) {
        this(type, source, null);
    }
}
