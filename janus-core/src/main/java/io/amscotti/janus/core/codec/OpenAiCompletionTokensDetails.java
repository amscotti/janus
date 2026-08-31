package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAI {@code usage.completion_tokens_details}. Only {@code reasoning_tokens} is
 * modeled (first-class reasoning accounting); other detail fields are dropped on
 * tolerant decode.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiCompletionTokensDetails(Long reasoningTokens) {}
