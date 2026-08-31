package io.amscotti.janus.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Nullable;
import java.util.List;

/**
 * {@code POST /key/generate} body — {@code models} scopes the key (absent = allow
 * all); {@code name} is a non-blank label ≤ 256 chars (validation lives in
 * {@link io.amscotti.janus.gateway.AdminKeysController#generate}); {@code budget_usd}/
 * {@code rpm}/{@code tpm} are optional caps (absent = no cap); {@code
 * budget_duration} is the budget reset window in <em>seconds</em> (absent = the
 * budget is lifetime; e.g. {@code 2592000} = 30 days — plain integer seconds, no
 * "30d" strings, consistent with {@code duration}); {@code duration} is the
 * key's lifetime in <em>seconds</em> from creation (absent = never expires) — the
 * controller computes {@code expires_at} against the store clock (LiteLLM's
 * {@code /key/generate} {@code duration} parity).
 */
public record KeyGenerateRequest(
        @JsonProperty("models") @Nullable List<String> models,
        @JsonProperty("name") @Nullable String name,
        @JsonProperty("budget_usd") @Nullable Double budgetUsd,
        @JsonProperty("budget_duration") @Nullable Long budgetDuration,
        @JsonProperty("rpm") @Nullable Integer rpm,
        @JsonProperty("tpm") @Nullable Integer tpm,
        @JsonProperty("duration") @Nullable Long duration) {}
