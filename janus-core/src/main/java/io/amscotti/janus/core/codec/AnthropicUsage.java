package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Anthropic Messages token accounting. Wire names serialize snake_case
 * ({@code input_tokens}/{@code output_tokens}/{@code cache_creation_input_tokens}/
 * {@code cache_read_input_tokens}) via the codec mapper's {@code SNAKE_CASE} naming
 * strategy. The codec maps {@code input_tokens}/{@code output_tokens} to the canonical
 * base fields ({@code total} = input + output, derived — never emitted) and the cache
 * fields to the canonical {@code Usage} cache fields (/cache pricing); the OpenAI
 * wire has no equivalent (dropped there by design — documented).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicUsage(
        Long inputTokens, Long outputTokens, Long cacheCreationInputTokens, Long cacheReadInputTokens) {}
