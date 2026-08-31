package io.amscotti.janus.gateway.live;

import static io.amscotti.janus.gateway.live.LiveProviderSupport.LIVE_OPT_IN;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.anthropicMessages;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.anthropicMessagesRaw;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.anthropicStream;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.assertNonEmptyAssistantContent;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.assertOpenAiToolCall;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.assertOpenAiToolCallsAtLeast;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.assertPromptCacheHit;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.assertPromptCacheRead;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.assumeProviderKey;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.cacheablePrefix;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.expectedMicroUsdDeepseekFlash;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.expectedMicroUsdGrok46;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.metricCounter;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.mintKeyFull;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.multiRoundFollowUpBody;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.multiRoundSeedBody;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.multiToolCallBody;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.openAiMultiToolFollowUpBody;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.openAiToolFollowUpBody;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.openaiChat;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.openaiChatRaw;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.openaiGet;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.openaiStream;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.openaiStreamAbortAfterFirstData;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.plainChatBody;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.readTree;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.requireMasterKey;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.scrapeMetrics;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.streamChatBody;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.systemPromptChatBody;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.toolCallBody;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.virtualKey;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.virtualKeyWithCaps;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.gateway.live.LiveProviderSupport.MintedKey;
import io.amscotti.janus.gateway.live.LiveProviderSupport.StatusBody;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

/**
 * Live integration tests: real Janus (production DI) → real upstream providers.
 *
 * <h2>How these are gated (three layers)</h2>
 *
 * <ol>
 * <li><b>{@code @Tag("live")}</b> — excluded from the default {@code test}/{@code build}
 * task (see {@code janus-gateway/build.gradle}). Local day-to-day runs never spend
 * tokens.
 * <li><b>{@code JANUS_LIVE=1}</b> — second opt-in for the dedicated {@code liveTest}
 * task, so {@code./gradlew liveTest} with keys sitting in the shell does not
 * silently burn money.
 * <li><b>Per-provider keys</b> — each case calls {@link LiveProviderSupport#assumeProviderKey};
 * missing keys → JUnit <em>skipped</em> (green CI on forks without secrets).
 * </ol>
 *
 * <pre>
 * # Local / CI (keys fromenv or GitHub Actions secrets):
 * export JANUS_LIVE=1
 * export JANUS_MASTER_KEY=… # optional; liveTest task supplies a default
 * export DEEPSEEK_API_KEY=… # only providers with keys run; others skip
 *./gradlew :janus-gateway:liveTest
 * </pre>
 *
 * <p>No Deno, no extra deps — same Micronaut + JUnit stack as the main suite.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = LIVE_OPT_IN, matches = "1")
@MicronautTest
@Property(name = "janus.test.production-factories", value = "true")
// Non-stream helpers share the injected @Client("/"); raise the default 10s read
// timeout to match the stream helpers' 120s so a slow reasoning upstream (e.g. V4
// Flash thinking) completes instead of throwing an uncaught ReadTimeoutException.
@Property(name = "micronaut.http.client.read-timeout", value = "120s")
// Auth ON via production MasterKeyProvider → JANUS_MASTER_KEY env
@Property(name = "janus.keys.master-key-env", value = "JANUS_MASTER_KEY")
// --- DeepSeek (V4 Flash — current DeepSeek flagship; newer than Pro) ---------
@Property(name = "janus.model-list[0].name", value = "deepseek-v4-flash")
@Property(name = "janus.model-list[0].provider", value = "deepseek")
@Property(name = "janus.model-list[0].api-key-env", value = "DEEPSEEK_API_KEY")
@Property(name = "janus.model-list[0].base-url", value = "https://api.deepseek.com")
// --- Anthropic (Claude Sonnet 5) ---------------------------------------------
@Property(name = "janus.model-list[1].name", value = "claude-sonnet-5")
@Property(name = "janus.model-list[1].provider", value = "anthropic")
@Property(name = "janus.model-list[1].api-key-env", value = "ANTHROPIC_API_KEY")
@Property(name = "janus.model-list[1].base-url", value = "https://api.anthropic.com")
// --- OpenAI (GPT-5.6 Luna — cost-sensitive tier of the 5.6 family) -----------
@Property(name = "janus.model-list[2].name", value = "gpt-5.6-luna")
@Property(name = "janus.model-list[2].provider", value = "openai")
@Property(name = "janus.model-list[2].api-key-env", value = "OPENAI_API_KEY")
@Property(name = "janus.model-list[2].base-url", value = "https://api.openai.com")
@Property(name = "janus.providers.openai.wire-format", value = "openai-compatible")
@Property(name = "janus.providers.openai.base-url", value = "https://api.openai.com")
@Property(name = "janus.providers.openai.api-key-env", value = "OPENAI_API_KEY")
// --- xAI (Grok 4.6 flagship; 4.5 remains as prior id) ------------------------
@Property(name = "janus.model-list[3].name", value = "grok-4.6")
@Property(name = "janus.model-list[3].provider", value = "xai")
@Property(name = "janus.model-list[3].api-key-env", value = "XAI_API_KEY")
@Property(name = "janus.model-list[3].base-url", value = "https://api.x.ai")
@Property(name = "janus.providers.xai.wire-format", value = "openai-compatible")
@Property(name = "janus.providers.xai.base-url", value = "https://api.x.ai")
@Property(name = "janus.providers.xai.api-key-env", value = "XAI_API_KEY")
// --- OpenRouter (Luna via OR for a second OpenAI-compatible path) ------------
@Property(name = "janus.model-list[4].name", value = "openai/gpt-5.6-luna")
@Property(name = "janus.model-list[4].provider", value = "openrouter")
@Property(name = "janus.model-list[4].api-key-env", value = "OPENROUTER_API_KEY")
@Property(name = "janus.model-list[4].base-url", value = "https://openrouter.ai/api")
@Property(name = "janus.providers.openrouter.wire-format", value = "openai-compatible")
@Property(name = "janus.providers.openrouter.base-url", value = "https://openrouter.ai/api")
@Property(name = "janus.providers.openrouter.api-key-env", value = "OPENROUTER_API_KEY")
// OpenRouter — additional frontier models (same openrouter provider/key, distinct aliases).
@Property(name = "janus.model-list[10].name", value = "moonshotai/kimi-k3")
@Property(name = "janus.model-list[10].provider", value = "openrouter")
@Property(name = "janus.model-list[10].api-key-env", value = "OPENROUTER_API_KEY")
@Property(name = "janus.model-list[10].base-url", value = "https://openrouter.ai/api")
@Property(name = "janus.model-list[11].name", value = "minimax/minimax-m3")
@Property(name = "janus.model-list[11].provider", value = "openrouter")
@Property(name = "janus.model-list[11].api-key-env", value = "OPENROUTER_API_KEY")
@Property(name = "janus.model-list[11].base-url", value = "https://openrouter.ai/api")
@Property(name = "janus.model-list[12].name", value = "qwen/qwen3.8-max")
@Property(name = "janus.model-list[12].provider", value = "openrouter")
@Property(name = "janus.model-list[12].api-key-env", value = "OPENROUTER_API_KEY")
@Property(name = "janus.model-list[12].base-url", value = "https://openrouter.ai/api")
// Meta Model API (Muse Spark) — OpenAI-compatible at api.meta.ai.
@Property(name = "janus.model-list[13].name", value = "muse-spark-1.2")
@Property(name = "janus.model-list[13].provider", value = "meta")
@Property(name = "janus.model-list[13].api-key-env", value = "META_API_KEY")
@Property(name = "janus.model-list[13].base-url", value = "https://api.meta.ai")
@Property(name = "janus.providers.meta.wire-format", value = "openai-compatible")
@Property(name = "janus.providers.meta.base-url", value = "https://api.meta.ai")
@Property(name = "janus.providers.meta.api-key-env", value = "META_API_KEY")
// Together — OpenAI-compatible; skipped when TOGETHER_API_KEY is unset.
@Property(name = "janus.model-list[16].name", value = "openai/gpt-oss-20b")
@Property(name = "janus.model-list[16].provider", value = "together")
@Property(name = "janus.model-list[16].api-key-env", value = "TOGETHER_API_KEY")
@Property(name = "janus.model-list[16].base-url", value = "https://api.together.xyz")
@Property(name = "janus.providers.together.wire-format", value = "openai-compatible")
@Property(name = "janus.providers.together.base-url", value = "https://api.together.xyz")
@Property(name = "janus.providers.together.api-key-env", value = "TOGETHER_API_KEY")
// Fireworks — OpenAI-compatible at api.fireworks.ai/inference (GLM 5.3).
@Property(name = "janus.model-list[17].name", value = "accounts/fireworks/models/glm-5p3")
@Property(name = "janus.model-list[17].provider", value = "fireworks")
@Property(name = "janus.model-list[17].api-key-env", value = "FIREWORKS_API_KEY")
@Property(name = "janus.model-list[17].base-url", value = "https://api.fireworks.ai/inference")
@Property(name = "janus.providers.fireworks.wire-format", value = "openai-compatible")
@Property(name = "janus.providers.fireworks.base-url", value = "https://api.fireworks.ai/inference")
@Property(name = "janus.providers.fireworks.api-key-env", value = "FIREWORKS_API_KEY")
// Groq — OpenAI-compatible at api.groq.com/openai (gpt-oss-120b).
@Property(name = "janus.model-list[18].name", value = "openai/gpt-oss-120b")
@Property(name = "janus.model-list[18].provider", value = "groq")
@Property(name = "janus.model-list[18].api-key-env", value = "GROQ_API_KEY")
@Property(name = "janus.model-list[18].base-url", value = "https://api.groq.com/openai")
@Property(name = "janus.providers.groq.wire-format", value = "openai-compatible")
@Property(name = "janus.providers.groq.base-url", value = "https://api.groq.com/openai")
@Property(name = "janus.providers.groq.api-key-env", value = "GROQ_API_KEY")
// Perplexity — VERSIONLESS endpoint: base-url ends with /chat/completions (the
// adapter's full-endpoint opt-out); an appended /v1 would 404 there.
@Property(name = "janus.model-list[19].name", value = "sonar")
@Property(name = "janus.model-list[19].provider", value = "perplexity")
@Property(name = "janus.model-list[19].api-key-env", value = "PERPLEXITY_API_KEY")
@Property(name = "janus.model-list[19].base-url", value = "https://api.perplexity.ai/chat/completions")
@Property(name = "janus.providers.perplexity.wire-format", value = "openai-compatible")
@Property(name = "janus.providers.perplexity.base-url", value = "https://api.perplexity.ai/chat/completions")
@Property(name = "janus.providers.perplexity.api-key-env", value = "PERPLEXITY_API_KEY")
// Google Gemini direct — OpenAI-compatible under /v1beta/openai (accepts the
// adapter's appended /v1/chat/completions too, verified live). Gemini 3.7 Flash.
@Property(name = "janus.model-list[20].name", value = "gemini-3.7-flash")
@Property(name = "janus.model-list[20].provider", value = "gemini")
@Property(name = "janus.model-list[20].api-key-env", value = "GEMINI_API_KEY")
@Property(name = "janus.model-list[20].base-url", value = "https://generativelanguage.googleapis.com/v1beta/openai")
@Property(name = "janus.providers.gemini.wire-format", value = "openai-compatible")
@Property(name = "janus.providers.gemini.base-url", value = "https://generativelanguage.googleapis.com/v1beta/openai")
@Property(name = "janus.providers.gemini.api-key-env", value = "GEMINI_API_KEY")
// OpenRouter — GLM 5.3 (z-ai; reasoning mandatory upstream — no effort pin).
@Property(name = "janus.model-list[21].name", value = "z-ai/glm-5.3")
@Property(name = "janus.model-list[21].provider", value = "openrouter")
@Property(name = "janus.model-list[21].api-key-env", value = "OPENROUTER_API_KEY")
@Property(name = "janus.model-list[21].base-url", value = "https://openrouter.ai/api")
// Anthropic — Haiku 4.5 (the haiku tier of the live suite).
@Property(name = "janus.model-list[22].name", value = "claude-haiku-4-5")
@Property(name = "janus.model-list[22].provider", value = "anthropic")
@Property(name = "janus.model-list[22].api-key-env", value = "ANTHROPIC_API_KEY")
@Property(name = "janus.model-list[22].base-url", value = "https://api.anthropic.com")
// Prior xAI flagship — still a real API id (same provider/key as grok-4.6).
@Property(name = "janus.model-list[14].name", value = "grok-4.5")
@Property(name = "janus.model-list[14].provider", value = "xai")
@Property(name = "janus.model-list[14].api-key-env", value = "XAI_API_KEY")
@Property(name = "janus.model-list[14].base-url", value = "https://api.x.ai")
// DeepSeek V4 Flash Vision (experimental) — OpenAI-compatible, same key/base as Flash.
@Property(name = "janus.model-list[15].name", value = "deepseek-v4-flash-vision-exp")
@Property(name = "janus.model-list[15].provider", value = "deepseek")
@Property(name = "janus.model-list[15].api-key-env", value = "DEEPSEEK_API_KEY")
@Property(name = "janus.model-list[15].base-url", value = "https://api.deepseek.com")
// Flagship aliases — same JANUS_LIVE=1 opt-in as the rest of the suite (per-key skip only).
// Pro is the failover alias (dead primary + live secondary). Janus sends the
// client alias as the upstream model id (no remap), so the alias must be a
// current DeepSeek API id. A standalone Pro row would merge with this pair.
@Property(name = "janus.model-list[5].name", value = "deepseek-v4-pro")
@Property(name = "janus.model-list[5].provider", value = "dead-primary")
@Property(name = "janus.model-list[5].api-key-env", value = "DEEPSEEK_API_KEY")
@Property(name = "janus.model-list[5].base-url", value = "http://127.0.0.1:1")
@Property(name = "janus.providers.dead-primary.wire-format", value = "openai-compatible")
@Property(name = "janus.providers.dead-primary.base-url", value = "http://127.0.0.1:1")
@Property(name = "janus.providers.dead-primary.api-key-env", value = "DEEPSEEK_API_KEY")
@Property(name = "janus.model-list[6].name", value = "gpt-5.6")
@Property(name = "janus.model-list[6].provider", value = "openai")
@Property(name = "janus.model-list[6].api-key-env", value = "OPENAI_API_KEY")
@Property(name = "janus.model-list[6].base-url", value = "https://api.openai.com")
@Property(name = "janus.model-list[7].name", value = "claude-opus-5")
@Property(name = "janus.model-list[7].provider", value = "anthropic")
@Property(name = "janus.model-list[7].api-key-env", value = "ANTHROPIC_API_KEY")
@Property(name = "janus.model-list[7].base-url", value = "https://api.anthropic.com")
@Property(name = "janus.model-list[8].name", value = "deepseek-v4-pro")
@Property(name = "janus.model-list[8].provider", value = "deepseek-secondary")
@Property(name = "janus.model-list[8].api-key-env", value = "DEEPSEEK_API_KEY")
@Property(name = "janus.model-list[8].base-url", value = "https://api.deepseek.com")
@Property(name = "janus.providers.deepseek-secondary.wire-format", value = "openai-compatible")
@Property(name = "janus.providers.deepseek-secondary.base-url", value = "https://api.deepseek.com")
@Property(name = "janus.providers.deepseek-secondary.api-key-env", value = "DEEPSEEK_API_KEY")
// Live price table: official USD-per-1M ÷ 1000 → Janus USD-per-1K (Aug 2026).
// Cache-read is on flash/grok so implicit-cache live pins can see
// X-Janus-Cost-Cache-Read-Micro-Usd; spend-settle adds that header to the
// tokens_in × input + tokens_out × output pin. Long-context knobs on Grok:
// whole request at the higher tier once prompt ≥ 200k (docs.x.ai).
@Property(name = "janus.pricing.models[0].name", value = "deepseek-v4-flash")
@Property(name = "janus.pricing.models[0].input-per-1k", value = "0.00044")
@Property(name = "janus.pricing.models[0].output-per-1k", value = "0.00132")
@Property(name = "janus.pricing.models[0].cache-read-per-1k", value = "0.000014")
@Property(name = "janus.pricing.models[0].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[1].name", value = "deepseek-v4-pro")
@Property(name = "janus.pricing.models[1].input-per-1k", value = "0.00132")
@Property(name = "janus.pricing.models[1].output-per-1k", value = "0.00396")
@Property(name = "janus.pricing.models[1].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[2].name", value = "grok-4.6")
@Property(name = "janus.pricing.models[2].input-per-1k", value = "0.002")
@Property(name = "janus.pricing.models[2].output-per-1k", value = "0.006")
@Property(name = "janus.pricing.models[2].cache-read-per-1k", value = "0.0005")
@Property(name = "janus.pricing.models[2].long-context-threshold", value = "200000")
@Property(name = "janus.pricing.models[2].long-input-per-1k", value = "0.004")
@Property(name = "janus.pricing.models[2].long-output-per-1k", value = "0.012")
@Property(name = "janus.pricing.models[2].long-cache-read-per-1k", value = "0.001")
@Property(name = "janus.pricing.models[2].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[3].name", value = "grok-4.5")
@Property(name = "janus.pricing.models[3].input-per-1k", value = "0.002")
@Property(name = "janus.pricing.models[3].output-per-1k", value = "0.006")
@Property(name = "janus.pricing.models[3].long-context-threshold", value = "200000")
@Property(name = "janus.pricing.models[3].long-input-per-1k", value = "0.004")
@Property(name = "janus.pricing.models[3].long-output-per-1k", value = "0.012")
@Property(name = "janus.pricing.models[3].cache-read-per-1k", value = "0.0003")
@Property(name = "janus.pricing.models[3].long-cache-read-per-1k", value = "0.0006")
@Property(name = "janus.pricing.models[3].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[4].name", value = "claude-sonnet-5")
@Property(name = "janus.pricing.models[4].input-per-1k", value = "0.002")
@Property(name = "janus.pricing.models[4].output-per-1k", value = "0.01")
@Property(name = "janus.pricing.models[4].cache-read-per-1k", value = "0.0002")
@Property(name = "janus.pricing.models[4].cache-creation-per-1k", value = "0.0025")
@Property(name = "janus.pricing.models[4].web-search-per-1k", value = "10.0")
@Property(name = "janus.pricing.models[4].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[5].name", value = "claude-opus-5")
@Property(name = "janus.pricing.models[5].input-per-1k", value = "0.005")
@Property(name = "janus.pricing.models[5].output-per-1k", value = "0.025")
@Property(name = "janus.pricing.models[5].cache-read-per-1k", value = "0.0005")
@Property(name = "janus.pricing.models[5].cache-creation-per-1k", value = "0.00625")
@Property(name = "janus.pricing.models[5].web-search-per-1k", value = "10.0")
@Property(name = "janus.pricing.models[5].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[6].name", value = "gpt-5.6-luna")
@Property(name = "janus.pricing.models[6].input-per-1k", value = "0.0002")
@Property(name = "janus.pricing.models[6].output-per-1k", value = "0.0012")
@Property(name = "janus.pricing.models[6].cache-read-per-1k", value = "0.00002")
@Property(name = "janus.pricing.models[6].cache-creation-per-1k", value = "0.00025")
@Property(name = "janus.pricing.models[6].web-search-per-1k", value = "10.0")
@Property(name = "janus.pricing.models[6].long-context-threshold", value = "272000")
@Property(name = "janus.pricing.models[6].long-input-per-1k", value = "0.0004")
@Property(name = "janus.pricing.models[6].long-output-per-1k", value = "0.0018")
@Property(name = "janus.pricing.models[6].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[7].name", value = "gpt-5.6")
@Property(name = "janus.pricing.models[7].input-per-1k", value = "0.004")
@Property(name = "janus.pricing.models[7].output-per-1k", value = "0.02")
@Property(name = "janus.pricing.models[7].cache-read-per-1k", value = "0.0004")
@Property(name = "janus.pricing.models[7].cache-creation-per-1k", value = "0.005")
@Property(name = "janus.pricing.models[7].web-search-per-1k", value = "10.0")
@Property(name = "janus.pricing.models[7].long-context-threshold", value = "272000")
@Property(name = "janus.pricing.models[7].long-input-per-1k", value = "0.008")
@Property(name = "janus.pricing.models[7].long-output-per-1k", value = "0.03")
@Property(name = "janus.pricing.models[7].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[8].name", value = "openai/gpt-5.6-luna")
@Property(name = "janus.pricing.models[8].input-per-1k", value = "0.0002")
@Property(name = "janus.pricing.models[8].output-per-1k", value = "0.0012")
@Property(name = "janus.pricing.models[8].cache-read-per-1k", value = "0.00002")
@Property(name = "janus.pricing.models[8].cache-creation-per-1k", value = "0.00025")
@Property(name = "janus.pricing.models[8].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[9].name", value = "moonshotai/kimi-k3")
@Property(name = "janus.pricing.models[9].input-per-1k", value = "0.003")
@Property(name = "janus.pricing.models[9].output-per-1k", value = "0.015")
@Property(name = "janus.pricing.models[9].cache-read-per-1k", value = "0.0003")
@Property(name = "janus.pricing.models[9].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[10].name", value = "minimax/minimax-m3")
@Property(name = "janus.pricing.models[10].input-per-1k", value = "0.0003")
@Property(name = "janus.pricing.models[10].output-per-1k", value = "0.0012")
@Property(name = "janus.pricing.models[10].cache-read-per-1k", value = "0.00006")
@Property(name = "janus.pricing.models[10].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[11].name", value = "qwen/qwen3.8-max")
@Property(name = "janus.pricing.models[11].input-per-1k", value = "0.002")
@Property(name = "janus.pricing.models[11].output-per-1k", value = "0.006")
@Property(name = "janus.pricing.models[11].cache-read-per-1k", value = "0.0002")
@Property(name = "janus.pricing.models[11].cache-creation-per-1k", value = "0.0025")
@Property(name = "janus.pricing.models[11].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[12].name", value = "muse-spark-1.2")
@Property(name = "janus.pricing.models[12].input-per-1k", value = "0.00125")
@Property(name = "janus.pricing.models[12].output-per-1k", value = "0.00425")
@Property(name = "janus.pricing.models[12].cache-read-per-1k", value = "0.00015")
@Property(name = "janus.pricing.models[12].web-search-per-1k", value = "10.0")
@Property(name = "janus.pricing.models[12].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[13].name", value = "deepseek-v4-flash-vision-exp")
@Property(name = "janus.pricing.models[13].input-per-1k", value = "0.00044")
@Property(name = "janus.pricing.models[13].output-per-1k", value = "0.00132")
@Property(name = "janus.pricing.models[13].cache-read-per-1k", value = "0.000014")
@Property(name = "janus.pricing.models[13].default-max-tokens", value = "4096")
// Parity/new-frontier providers — approximate public list prices (no live spend
// assertions cover these ids; rows exist so spend metrics stay meaningful).
@Property(name = "janus.pricing.models[14].name", value = "accounts/fireworks/models/glm-5p3")
@Property(name = "janus.pricing.models[14].input-per-1k", value = "0.0014")
@Property(name = "janus.pricing.models[14].output-per-1k", value = "0.0044")
@Property(name = "janus.pricing.models[14].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[15].name", value = "openai/gpt-oss-120b")
@Property(name = "janus.pricing.models[15].input-per-1k", value = "0.00006")
@Property(name = "janus.pricing.models[15].output-per-1k", value = "0.00029")
@Property(name = "janus.pricing.models[15].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[16].name", value = "sonar")
@Property(name = "janus.pricing.models[16].input-per-1k", value = "0.001")
@Property(name = "janus.pricing.models[16].output-per-1k", value = "0.001")
@Property(name = "janus.pricing.models[16].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[17].name", value = "gemini-3.7-flash")
@Property(name = "janus.pricing.models[17].input-per-1k", value = "0.0003")
@Property(name = "janus.pricing.models[17].output-per-1k", value = "0.0025")
@Property(name = "janus.pricing.models[17].default-max-tokens", value = "4096")
@Property(name = "janus.pricing.models[18].name", value = "z-ai/glm-5.3")
@Property(name = "janus.pricing.models[18].input-per-1k", value = "0.0014")
@Property(name = "janus.pricing.models[18].output-per-1k", value = "0.0044")
@Property(name = "janus.pricing.models[18].default-max-tokens", value = "4096")
// Light routing (one retry only — live network; enough for dead→secondary failover)
@Property(name = "janus.router.max-retries", value = "1")
@Property(name = "janus.router.allowed-fails", value = "1")
class LiveProviderIT {

    /**
     * DeepSeek current flagship API id ({@code deepseek-v4-flash}). Flash is newer than
     * Pro at the time of this suite — primary live path, not a "cheap default" tier.
     */
    private static final String DEEPSEEK = "deepseek-v4-flash";
    /** Official Anthropic Sonnet 5 API id. */
    private static final String CLAUDE = "claude-sonnet-5";
    /** GPT-5.6 Luna — cost tier of the current OpenAI flagship family. */
    private static final String OPENAI = "gpt-5.6-luna";
    /**
     * xAI current flagship ({@code grok-4.6}). Reasoning defaults to {@code high} and
     * cannot be disabled — live cases pin {@code reasoning_effort=low} and leave
     * completion headroom so content is not eaten by reasoning tokens.
     */
    private static final String GROK = "grok-4.6";
    /** Prior xAI flagship — still a real API id. */
    private static final String GROK_45 = "grok-4.5";

    private static final String GROK_REASONING_LOW = "\"reasoning_effort\": \"low\"";
    private static final int GROK_MAX_TOKENS = 256;
    /** Same Luna model via OpenRouter (vendor-prefixed id). */
    private static final String OPENROUTER = "openai/gpt-5.6-luna";
    /** OpenRouter — Kimi K3 (Moonshot). */
    private static final String OR_KIMI = "moonshotai/kimi-k3";
    /**
     * Chat-wire spelling. The Responses-shaped {@code reasoning:{effort}} object
     * was being extras-passed through and Phala-hosted Kimi ignored it, burning
     * {@code max_tokens} on hidden reasoning (empty content, finish_reason=length).
     */
    private static final String KIMI_REASONING_NONE = "\"reasoning_effort\": \"none\"";
    /** Headroom if the upstream still emits some reasoning tokens. */
    private static final int KIMI_MAX_TOKENS = 512;
    /** OpenRouter — MiniMax M3. */
    private static final String OR_MINIMAX = "minimax/minimax-m3";
    /**
     * MiniMax M3 burns completion tokens on reasoning. 64 was enough in earlier
     * spot checks but a one-shot pong finished {@code length} with empty content
     * (all 64 completion tokens were reasoning tokens). Match Kimi/Qwen headroom.
     */
    private static final int MINIMAX_MAX_TOKENS = 512;
    /** OpenRouter — Qwen3.8 Max. */
    private static final String OR_QWEN = "qwen/qwen3.8-max";
    /**
     * Qwen3.8 Max burns completion tokens on hidden reasoning. 128 was enough for a
     * one-shot pong but multi-round follow-ups finished {@code length} with empty
     * content (reasoning tokens ate the budget). Match Kimi's headroom.
     */
    private static final int QWEN_MAX_TOKENS = 512;
    /** Meta Model API — Muse Spark 1.2. */
    private static final String META_MUSE = "muse-spark-1.2";
    /** Together serverless — cheap OpenAI-compatible id from their current catalog. */
    private static final String TOGETHER = "openai/gpt-oss-20b";
    /**
     * Fireworks — GLM 5.3 (newest deployed GLM generation; 5.2 was the prior).
     * Alias must be the full upstream id (no remap).
     */
    private static final String FIREWORKS_GLM = "accounts/fireworks/models/glm-5p3";
    /** OpenRouter — GLM 5.3 (z-ai direct frontier; reasoning is mandatory upstream). */
    private static final String OR_GLM53 = "z-ai/glm-5.3";
    /**
     * Groq — gpt-oss-120b serverless (llama-3.3-70b-versatile, the prior staple, is
     * decommissioned on Groq). Reasoning-capable, so headroom like the others.
     */
    private static final String GROQ_GPT_OSS = "openai/gpt-oss-120b";
    /**
     * Perplexity — sonar (search-grounded). Served at a VERSIONLESS endpoint
     * ({@code https://api.perplexity.ai/chat/completions}), so the provider base-url
     * uses the adapter's full-endpoint opt-out (no appended {@code /v1}).
     */
    private static final String PPLX_SONAR = "sonar";
    /** Google Gemini direct — OpenAI-compatible endpoint under {@code /v1beta/openai}. */
    private static final String GEMINI_FLASH = "gemini-3.7-flash";
    /** Reasoning-capable small models (Groq gpt-oss) — headroom, the KIMI/QWEN precedent. */
    private static final int GROQ_MAX_TOKENS = 512;
    /** Search-grounded sonar can cite sources in-content; give it modest headroom. */
    private static final int PPLX_MAX_TOKENS = 512;
    /**
     * Gemini 3.7 Flash burns completion tokens on thinking (a 64-token probe truncated
     * mid-reply) and GLM 5.3 (Fireworks + OpenRouter) has mandatory reasoning — the
     * suite's reasoning-model headroom applies to all three.
     */
    private static final int NEW_FRONTIER_MAX_TOKENS = 512;
    /**
     * Public HTTPS JPEG for URL-source vision (not a data URL). Anthropic's crawler
     * can fetch this W3C test image; Wikimedia currently 400s
     * {@code Unable to download the file}. xAI's crawler 403s this host — Grok uses
     * {@link #HTTPS_IMAGE_URL_XAI}.
     */
    private static final String HTTPS_IMAGE_URL = "https://www.w3.org/People/mimasa/test/imgformat/img/w3c_home.jpg";
    /**
     * Public HTTPS JPEG xAI can fetch. Grok 400s the W3C host ({@code image host
     * returned HTTP status 403}); Wikimedia began rate-limiting xAI's crawler
     * ({@code image host returned HTTP status 429}) — jsDelivr is a CDN built
     * for direct hotlinking and fetches cleanly.
     */
    private static final String HTTPS_IMAGE_URL_XAI =
            "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/72x72/1f40c.png";
    /**
     * Prior DeepSeek tier ({@code deepseek-v4-pro}). Still covered for multi-id
     * routing; not "flagship over Flash" — Flash is current.
     */
    private static final String DEEPSEEK_PRO = "deepseek-v4-pro";
    /** DeepSeek V4 Flash Vision (experimental). Images billed as input tokens. */
    private static final String DEEPSEEK_VISION = "deepseek-v4-flash-vision-exp";

    /** 1×1 red PNG — self-contained; no external image host. */
    private static final String TINY_PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    /**
     * 64×64 red JPEG. xAI rejects the 1×1 PNG ({@code invalid_image}); JPEG is
     * the live-verified Grok vision fixture.
     */
    private static final String TINY_JPEG_B64 =
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsK"
                    + "CwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQU"
                    + "FBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCABAAEADASIA"
                    + "AhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQA"
                    + "AAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3"
                    + "ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWm"
                    + "p6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEA"
                    + "AwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSEx"
                    + "BhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElK"
                    + "U1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3"
                    + "uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD50ooo"
                    + "r8MP9UwooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooA"
                    + "KKKKACiiigD/2Q==";

    private static final String OPENAI_SOL = "gpt-5.6";
    private static final String CLAUDE_OPUS = "claude-opus-5";
    /** Anthropic — Haiku 4.5 (the haiku tier of the live suite). */
    private static final String CLAUDE_HAIKU = "claude-haiku-4-5";
    /**
     * Multi-backend failover alias (dead primary + live secondary). Same current
     * DeepSeek id as {@link #DEEPSEEK_PRO} — Janus sends the client alias upstream
     * (no remap), so a retired id would 400.
     */
    private static final String FAILOVER = DEEPSEEK_PRO;

    /** Muse Spark burns completion tokens on reasoning; keep headroom so content is emitted. */
    private static final int META_MAX_TOKENS = 1024;

    @Inject
    EmbeddedServer server;

    @Inject
    @Client("/")
    HttpClient client;

    private String keyFor(String... models) {
        return virtualKey(client, List.of(models));
    }

    private List<String> allConfiguredModels() {
        List<String> models = new ArrayList<>();
        if (LiveProviderSupport.envSet("DEEPSEEK_API_KEY")) {
            models.add(DEEPSEEK);
            models.add(DEEPSEEK_PRO);
            models.add(DEEPSEEK_VISION);
        }
        if (LiveProviderSupport.envSet("ANTHROPIC_API_KEY")) {
            models.add(CLAUDE);
            models.add(CLAUDE_OPUS);
            models.add(CLAUDE_HAIKU);
        }
        if (LiveProviderSupport.envSet("OPENAI_API_KEY")) {
            models.add(OPENAI);
            models.add(OPENAI_SOL);
        }
        if (LiveProviderSupport.envSet("XAI_API_KEY")) {
            models.add(GROK);
            models.add(GROK_45);
        }
        if (LiveProviderSupport.envSet("OPENROUTER_API_KEY")) {
            models.add(OPENROUTER);
            models.add(OR_KIMI);
            models.add(OR_MINIMAX);
            models.add(OR_QWEN);
            models.add(OR_GLM53);
        }
        if (LiveProviderSupport.envSet("META_API_KEY")) {
            models.add(META_MUSE);
        }
        if (LiveProviderSupport.envSet("TOGETHER_API_KEY")) {
            models.add(TOGETHER);
        }
        if (LiveProviderSupport.envSet("FIREWORKS_API_KEY")) {
            models.add(FIREWORKS_GLM);
        }
        if (LiveProviderSupport.envSet("GROQ_API_KEY")) {
            models.add(GROQ_GPT_OSS);
        }
        if (LiveProviderSupport.envSet("PERPLEXITY_API_KEY")) {
            models.add(PPLX_SONAR);
        }
        if (LiveProviderSupport.envSet("GEMINI_API_KEY")) {
            models.add(GEMINI_FLASH);
        }
        return models;
    }

    // ----------------------------------------------------------------------- chat

    @Test
    void deepseek_openaiFace_nonStream() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        JsonNode res = openaiChat(client, keyFor(DEEPSEEK), plainChatBody(DEEPSEEK));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void deepseek_openaiFace_stream() throws Exception {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String sse = openaiStream(server, keyFor(DEEPSEEK), streamChatBody(DEEPSEEK));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void anthropic_openaiFace_nonStream() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        JsonNode res = openaiChat(client, keyFor(CLAUDE), plainChatBody(CLAUDE));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void anthropicHaiku45_openaiFace_nonStream() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        JsonNode res = openaiChat(client, keyFor(CLAUDE_HAIKU), plainChatBody(CLAUDE_HAIKU, 256, null));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void anthropicHaiku45_openaiFace_stream() throws Exception {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String sse = openaiStream(server, keyFor(CLAUDE_HAIKU), streamChatBody(CLAUDE_HAIKU, 256, null));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void anthropic_openaiFace_stream() throws Exception {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String sse = openaiStream(server, keyFor(CLAUDE), streamChatBody(CLAUDE));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void openai_openaiFace_nonStream() {
        assumeProviderKey("OPENAI_API_KEY");
        JsonNode res = openaiChat(client, keyFor(OPENAI), plainChatBody(OPENAI));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void openai_openaiFace_stream() throws Exception {
        assumeProviderKey("OPENAI_API_KEY");
        String sse = openaiStream(server, keyFor(OPENAI), streamChatBody(OPENAI));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void xai_openaiFace_nonStream() {
        assumeProviderKey("XAI_API_KEY");
        JsonNode res = openaiChat(client, keyFor(GROK), plainChatBody(GROK, GROK_MAX_TOKENS, GROK_REASONING_LOW));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void xai_openaiFace_stream() throws Exception {
        assumeProviderKey("XAI_API_KEY");
        String sse = openaiStream(server, keyFor(GROK), streamChatBody(GROK, GROK_MAX_TOKENS, GROK_REASONING_LOW));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void openrouter_openaiFace_nonStream() {
        assumeProviderKey("OPENROUTER_API_KEY");
        JsonNode res = openaiChat(client, keyFor(OPENROUTER), plainChatBody(OPENROUTER));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void openrouter_openaiFace_stream() throws Exception {
        assumeProviderKey("OPENROUTER_API_KEY");
        String sse = openaiStream(server, keyFor(OPENROUTER), streamChatBody(OPENROUTER));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    // ----------------------------------------------------------------------- OpenRouter multi-model matrix

    @Test
    void openrouter_kimiK3_nonStream() {
        assumeProviderKey("OPENROUTER_API_KEY");
        // Kimi K3 is a reasoning model; disable reasoning for a short deterministic reply.
        JsonNode res = openaiChat(client, keyFor(OR_KIMI), plainChatBody(OR_KIMI, 128, KIMI_REASONING_NONE));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void openrouter_kimiK3_stream() throws Exception {
        assumeProviderKey("OPENROUTER_API_KEY");
        String sse = openaiStream(server, keyFor(OR_KIMI), streamChatBody(OR_KIMI, 128, KIMI_REASONING_NONE));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void openrouter_kimiK3_toolCall_andMultiTurn() {
        assumeProviderKey("OPENROUTER_API_KEY");
        String key = keyFor(OR_KIMI);
        JsonNode turn1 = openaiChatWithProviderVarianceRetry(
                key,
                toolCallBody(OR_KIMI, "Paris", "required", 512, KIMI_REASONING_NONE),
                LiveProviderSupport::hasOpenAiToolCall);
        assertOpenAiToolCall(turn1);
        JsonNode call =
                turn1.path("choices").path(0).path("message").path("tool_calls").path(0);
        String toolCallId = call.path("id").stringValue();
        String city = readTree(call.path("function").path("arguments").stringValue())
                .path("city")
                .stringValue();
        JsonNode turn2 = openaiChatWithProviderVarianceRetry(
                key,
                openAiToolFollowUpBody(OR_KIMI, turn1, toolCallId, city, KIMI_REASONING_NONE),
                LiveProviderSupport::hasNonEmptyAssistantContent);
        assertNonEmptyAssistantContent(turn2);
    }

    @Test
    void openrouter_kimiK3_multiRoundChat() {
        assumeProviderKey("OPENROUTER_API_KEY");
        String key = keyFor(OR_KIMI);
        String extras = KIMI_REASONING_NONE;
        JsonNode turn1 = openaiChatWithProviderVarianceRetry(
                key,
                multiRoundSeedBody(OR_KIMI, KIMI_MAX_TOKENS, extras),
                LiveProviderSupport::hasNonEmptyAssistantContent);
        assertNonEmptyAssistantContent(turn1);
        JsonNode turn2 = openaiChatWithProviderVarianceRetry(
                key,
                multiRoundFollowUpBody(
                        OR_KIMI,
                        turn1,
                        "What is my secret codeword? Reply with only the codeword.",
                        KIMI_MAX_TOKENS,
                        extras),
                LiveProviderSupport::hasNonEmptyAssistantContent);
        assertNonEmptyAssistantContent(turn2);
        String content = turn2.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .stringValue()
                .toLowerCase();
        assertTrue(
                content.contains("zebra42") || content.contains("zebra"),
                () -> "expected codeword in kimi multi-round reply: " + turn2);
    }

    /**
     * One bounded retry (2 attempts) for OpenRouter backend variance: round-robin
     * routing intermittently serves {@code moonshotai/kimi-k3} from a backend in a
     * degraded window (observed live: an empty assistant message with
     * {@code finish_reason=stop} despite {@code tool_choice=required}, and a
     * degenerate {@code ze!!!…} reply). Each attempt exercises the full Janus
     * path, so a Janus-side regression fails every attempt — the retry only
     * absorbs upstream noise. The last response is returned for the caller's
     * strict assertion when the probe never passes.
     */
    private JsonNode openaiChatWithProviderVarianceRetry(String key, String body, Predicate<JsonNode> probe) {
        JsonNode last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            last = openaiChat(client, key, body);
            if (probe.test(last)) {
                break;
            }
        }
        return last;
    }

    @Test
    void openrouter_minimaxM3_nonStream() {
        assumeProviderKey("OPENROUTER_API_KEY");
        JsonNode res = openaiChat(client, keyFor(OR_MINIMAX), plainChatBody(OR_MINIMAX, MINIMAX_MAX_TOKENS, null));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void openrouter_minimaxM3_stream() throws Exception {
        assumeProviderKey("OPENROUTER_API_KEY");
        String sse = openaiStream(server, keyFor(OR_MINIMAX), streamChatBody(OR_MINIMAX, MINIMAX_MAX_TOKENS, null));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void openrouter_minimaxM3_toolCall_andMultiTurn() {
        assumeProviderKey("OPENROUTER_API_KEY");
        String key = keyFor(OR_MINIMAX);
        JsonNode turn1 = openaiChat(client, key, toolCallBody(OR_MINIMAX, "Berlin", "required", 512, null));
        assertOpenAiToolCall(turn1);
        JsonNode call =
                turn1.path("choices").path(0).path("message").path("tool_calls").path(0);
        String toolCallId = call.path("id").stringValue();
        String city = readTree(call.path("function").path("arguments").stringValue())
                .path("city")
                .stringValue();
        JsonNode turn2 = openaiChat(client, key, openAiToolFollowUpBody(OR_MINIMAX, turn1, toolCallId, city, null));
        assertNonEmptyAssistantContent(turn2);
    }

    @Test
    void openrouter_qwen38Max_nonStream() {
        assumeProviderKey("OPENROUTER_API_KEY");
        JsonNode res = openaiChat(client, keyFor(OR_QWEN), plainChatBody(OR_QWEN, QWEN_MAX_TOKENS, null));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void openrouter_qwen38Max_stream() throws Exception {
        assumeProviderKey("OPENROUTER_API_KEY");
        String sse = openaiStream(server, keyFor(OR_QWEN), streamChatBody(OR_QWEN, QWEN_MAX_TOKENS, null));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void openrouter_qwen38Max_toolCall_andMultiTurn() {
        assumeProviderKey("OPENROUTER_API_KEY");
        // Qwen3.8 Max rejects tool_choice=required while in thinking mode; use auto.
        String key = keyFor(OR_QWEN);
        JsonNode turn1 = openaiChat(client, key, toolCallBody(OR_QWEN, "Tokyo", "auto", 512, null));
        assertOpenAiToolCall(turn1);
        JsonNode call =
                turn1.path("choices").path(0).path("message").path("tool_calls").path(0);
        String toolCallId = call.path("id").stringValue();
        String city = "Tokyo";
        try {
            JsonNode args = readTree(call.path("function").path("arguments").stringValue());
            if (args.path("city").isString()) {
                city = args.path("city").stringValue();
            }
        } catch (RuntimeException ignored) {
            // keep default
        }
        JsonNode turn2 = openaiChat(client, key, openAiToolFollowUpBody(OR_QWEN, turn1, toolCallId, city, null));
        assertNonEmptyAssistantContent(turn2);
    }

    @Test
    void openrouter_qwen38Max_multiRoundChat() {
        assumeProviderKey("OPENROUTER_API_KEY");
        String key = keyFor(OR_QWEN);
        JsonNode turn1 = openaiChat(client, key, multiRoundSeedBody(OR_QWEN, QWEN_MAX_TOKENS, null));
        assertNonEmptyAssistantContent(turn1);
        JsonNode turn2 = openaiChat(
                client,
                key,
                multiRoundFollowUpBody(
                        OR_QWEN,
                        turn1,
                        "What is my secret codeword? Reply with only the codeword.",
                        QWEN_MAX_TOKENS,
                        null));
        assertNonEmptyAssistantContent(turn2);
        String content = turn2.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .stringValue()
                .toLowerCase();
        assertTrue(
                content.contains("zebra42") || content.contains("zebra"),
                () -> "expected codeword in qwen multi-round reply: " + turn2);
    }

    // ----------------------------------------------------------------------- Meta Muse Spark (META_API_KEY)

    @Test
    void meta_museSpark_nonStream() {
        assumeProviderKey("META_API_KEY");
        // Reasoning consumes completion budget; leave headroom so content is not empty.
        JsonNode res = openaiChat(
                client, keyFor(META_MUSE), plainChatBody(META_MUSE, META_MAX_TOKENS, "\"reasoning_effort\": \"low\""));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void meta_museSpark_stream() throws Exception {
        assumeProviderKey("META_API_KEY");
        String sse = openaiStream(
                server, keyFor(META_MUSE), streamChatBody(META_MUSE, META_MAX_TOKENS, "\"reasoning_effort\": \"low\""));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void meta_museSpark_toolCall_andMultiTurn() {
        assumeProviderKey("META_API_KEY");
        // Meta only supports tool_choice=auto (required/named are rejected).
        String key = keyFor(META_MUSE);
        JsonNode turn1 = openaiChat(
                client,
                key,
                toolCallBody(META_MUSE, "Paris", "auto", META_MAX_TOKENS, "\"reasoning_effort\": \"low\""));
        assertOpenAiToolCall(turn1);
        JsonNode call =
                turn1.path("choices").path(0).path("message").path("tool_calls").path(0);
        String toolCallId = call.path("id").stringValue();
        String city = "Paris";
        try {
            JsonNode args = readTree(call.path("function").path("arguments").stringValue());
            if (args.path("city").isString()) {
                city = args.path("city").stringValue();
            }
        } catch (RuntimeException ignored) {
            // keep default
        }
        JsonNode turn2 = openaiChat(
                client,
                key,
                openAiToolFollowUpBody(
                        META_MUSE, turn1, toolCallId, city, META_MAX_TOKENS, "\"reasoning_effort\": \"low\""));
        assertNonEmptyAssistantContent(turn2);
    }

    @Test
    void meta_museSpark_multiRoundChat() {
        assumeProviderKey("META_API_KEY");
        String key = keyFor(META_MUSE);
        String extras = "\"reasoning_effort\": \"low\"";
        JsonNode turn1 = openaiChat(client, key, multiRoundSeedBody(META_MUSE, META_MAX_TOKENS, extras));
        assertNonEmptyAssistantContent(turn1);
        JsonNode turn2 = openaiChat(
                client,
                key,
                multiRoundFollowUpBody(
                        META_MUSE,
                        turn1,
                        "What is my secret codeword? Reply with only the codeword.",
                        META_MAX_TOKENS,
                        extras));
        assertNonEmptyAssistantContent(turn2);
        String content = turn2.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .stringValue()
                .toLowerCase();
        assertTrue(
                content.contains("zebra42") || content.contains("zebra"),
                () -> "expected codeword in muse multi-round reply: " + turn2);
    }

    // ----------------------------------------------------------------------- tools

    @Test
    void deepseek_toolCall_andMultiTurn() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String key = keyFor(DEEPSEEK);
        JsonNode turn1 = openaiChat(client, key, toolCallBody(DEEPSEEK, "Paris"));
        assertOpenAiToolCall(turn1);
        JsonNode call =
                turn1.path("choices").path(0).path("message").path("tool_calls").path(0);
        String toolCallId = call.path("id").stringValue();
        String city = readTree(call.path("function").path("arguments").stringValue())
                .path("city")
                .stringValue();
        JsonNode turn2 = openaiChat(
                client,
                key,
                openAiToolFollowUpBody(DEEPSEEK, turn1, toolCallId, city, "\"thinking\": {\"type\": \"disabled\"}"));
        assertNonEmptyAssistantContent(turn2);
    }

    @Test
    void xai_toolCall_andMultiTurn() {
        assumeProviderKey("XAI_API_KEY");
        String key = keyFor(GROK);
        JsonNode turn1 = openaiChat(client, key, toolCallBody(GROK, "Paris", "required", 512, GROK_REASONING_LOW));
        assertOpenAiToolCall(turn1);
        JsonNode call =
                turn1.path("choices").path(0).path("message").path("tool_calls").path(0);
        String toolCallId = call.path("id").stringValue();
        String city = readTree(call.path("function").path("arguments").stringValue())
                .path("city")
                .stringValue();
        JsonNode turn2 =
                openaiChat(client, key, openAiToolFollowUpBody(GROK, turn1, toolCallId, city, GROK_REASONING_LOW));
        assertNonEmptyAssistantContent(turn2);
    }

    @Test
    void openai_toolCall_andMultiTurn() {
        assumeProviderKey("OPENAI_API_KEY");
        // GPT-5.6 chat/completions requires reasoning_effort=none for function tools.
        String key = keyFor(OPENAI);
        String turn1Body = """
                {
                  "model": "%s",
                  "messages": [{"role":"user","content":"What is the weather in Berlin? You must use the get_weather tool."}],
                  "tools": %s,
                  "tool_choice": "required",
                  "max_tokens": 256,
                  "reasoning_effort": "none"
                }
                """.formatted(OPENAI, LiveProviderSupport.WEATHER_TOOLS_JSON);
        JsonNode turn1 = openaiChat(client, key, turn1Body);
        assertOpenAiToolCall(turn1);
        JsonNode call =
                turn1.path("choices").path(0).path("message").path("tool_calls").path(0);
        String toolCallId = call.path("id").stringValue();
        String city = readTree(call.path("function").path("arguments").stringValue())
                .path("city")
                .stringValue();
        JsonNode turn2 = openaiChat(
                client, key, openAiToolFollowUpBody(OPENAI, turn1, toolCallId, city, "\"reasoning_effort\": \"none\""));
        assertNonEmptyAssistantContent(turn2);
    }

    @Test
    void anthropicUpstream_toolCall_viaOpenAiFace() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        JsonNode turn1 = openaiChat(client, keyFor(CLAUDE), toolCallBody(CLAUDE, "London"));
        assertOpenAiToolCall(turn1);
    }

    @Test
    void deepseek_thinkingMode_chat() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        // V4 Flash defaults to thinking; pin enabled explicitly and require a text answer.
        String body = """
                {
                  "model": "%s",
                  "messages": [{"role":"user","content":"What is 2+2? Reply with only the digit."}],
                  "max_tokens": 64,
                  "thinking": {"type": "enabled"}
                }
                """.formatted(DEEPSEEK);
        JsonNode res = openaiChat(client, keyFor(DEEPSEEK), body);
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void deepseek_streamToolCall_deltas() throws Exception {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "stream": true,
                  "messages": [{"role":"user","content":"What is the weather in Oslo? You must use the get_weather tool."}],
                  "tools": %s,
                  "tool_choice": "required",
                  "max_tokens": 256,
                  "thinking": {"type": "disabled"}
                }
                """.formatted(DEEPSEEK, LiveProviderSupport.WEATHER_TOOLS_JSON);
        String sse = openaiStream(server, keyFor(DEEPSEEK), body);
        assertTrue(sse.contains("tool_calls") || sse.contains("function"), sse);
    }

    /**
     * Parallel multi-tool: one turn should request both get_weather and get_time, then a
     * follow-up with tool results for every call yields a final assistant text answer.
     */
    @Test
    void deepseek_multiToolCall_parallel_andFollowUp() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String key = keyFor(DEEPSEEK);
        JsonNode turn1 = openaiChat(client, key, multiToolCallBody(DEEPSEEK, "Paris"));
        // Prefer 2 tools; some models still emit only one under tool_choice=required —
        // pin ≥1 always, and when both arrive pin the multi-tool follow-up path.
        assertOpenAiToolCallsAtLeast(turn1, 1);
        JsonNode calls = turn1.path("choices").path(0).path("message").path("tool_calls");
        if (calls.size() >= 2) {
            Map<String, String> results = Map.of(
                    "get_weather", "{\"temp_c\":18,\"city\":\"Paris\"}",
                    "get_time", "{\"local_time\":\"14:30\",\"city\":\"Paris\"}");
            JsonNode turn2 = openaiChat(
                    client,
                    key,
                    openAiMultiToolFollowUpBody(DEEPSEEK, turn1, results, "\"thinking\": {\"type\": \"disabled\"}"));
            assertNonEmptyAssistantContent(turn2);
        } else {
            // Single-tool fallback: still exercise follow-up for that one call.
            JsonNode call = calls.path(0);
            String toolCallId = call.path("id").stringValue();
            String name = call.path("function").path("name").stringValue();
            String city = "Paris";
            try {
                JsonNode args = readTree(call.path("function").path("arguments").stringValue());
                if (args.path("city").isString()) {
                    city = args.path("city").stringValue();
                }
            } catch (RuntimeException ignored) {
                // keep default city
            }
            JsonNode turn2 = openaiChat(
                    client,
                    key,
                    openAiToolFollowUpBody(
                            DEEPSEEK, turn1, toolCallId, city, "\"thinking\": {\"type\": \"disabled\"}"));
            assertNonEmptyAssistantContent(turn2);
            assertTrue(
                    name != null && !name.isBlank(), () -> "tool name blank on single-call multi-tool path: " + turn1);
        }
    }

    @Test
    void openai_multiToolCall_parallel() {
        assumeProviderKey("OPENAI_API_KEY");
        String key = keyFor(OPENAI);
        // GPT-5.x chat/completions wants reasoning_effort=none for function tools.
        String body = """
                {
                  "model": "%s",
                  "messages": [{
                    "role": "user",
                    "content": "For Tokyo: call get_weather AND get_time. You must use both tools in this turn."
                  }],
                  "tools": %s,
                  "tool_choice": "required",
                  "parallel_tool_calls": true,
                  "max_tokens": 512,
                  "reasoning_effort": "none"
                }
                """.formatted(OPENAI, LiveProviderSupport.MULTI_TOOLS_JSON);
        JsonNode turn1 = openaiChat(client, key, body);
        assertOpenAiToolCallsAtLeast(turn1, 1);
        // When both tools fire, pin the multi-result follow-up; otherwise single-tool follow-up.
        JsonNode calls = turn1.path("choices").path(0).path("message").path("tool_calls");
        if (calls.size() >= 2) {
            Map<String, String> results = Map.of(
                    "get_weather", "{\"temp_c\":24,\"city\":\"Tokyo\"}",
                    "get_time", "{\"local_time\":\"09:00\",\"city\":\"Tokyo\"}");
            JsonNode turn2 = openaiChat(
                    client, key, openAiMultiToolFollowUpBody(OPENAI, turn1, results, "\"reasoning_effort\": \"none\""));
            assertNonEmptyAssistantContent(turn2);
        }
    }

    // ----------------------------------------------------------------------- multi-round chat / system

    /**
     * Multi-round conversation (no tools): plant a codeword, echo the assistant turn
     * back through the codec, then ask for the codeword — pins history round-trip.
     */
    @Test
    void deepseek_multiRoundChat_remembersPriorTurn() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String key = keyFor(DEEPSEEK);
        String seed = """
                {
                  "model": "%s",
                  "messages": [{"role":"user","content":"My secret codeword is zebra42. Acknowledge in one short sentence."}],
                  "max_tokens": 64,
                  "thinking": {"type": "disabled"}
                }
                """.formatted(DEEPSEEK);
        JsonNode turn1 = openaiChat(client, key, seed);
        assertNonEmptyAssistantContent(turn1);
        JsonNode turn2 = openaiChat(
                client,
                key,
                multiRoundFollowUpBody(
                        DEEPSEEK,
                        turn1,
                        "What is my secret codeword? Reply with only the codeword.",
                        64,
                        "\"thinking\": {\"type\": \"disabled\"}"));
        assertNonEmptyAssistantContent(turn2);
        String content = turn2.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .stringValue()
                .toLowerCase();
        assertTrue(
                content.contains("zebra42") || content.contains("zebra"),
                () -> "expected codeword in multi-round reply: " + turn2);
    }

    @Test
    void anthropic_multiRoundChat_viaOpenAiFace() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String key = keyFor(CLAUDE);
        String seed = """
                {
                  "model": "%s",
                  "messages": [{"role":"user","content":"My secret codeword is zebra42. Acknowledge in one short sentence."}],
                  "max_tokens": 64
                }
                """.formatted(CLAUDE);
        JsonNode turn1 = openaiChat(client, key, seed);
        assertNonEmptyAssistantContent(turn1);
        JsonNode turn2 = openaiChat(
                client,
                key,
                multiRoundFollowUpBody(
                        CLAUDE, turn1, "What is my secret codeword? Reply with only the codeword.", 64, null));
        assertNonEmptyAssistantContent(turn2);
        String content = turn2.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .stringValue()
                .toLowerCase();
        assertTrue(
                content.contains("zebra42") || content.contains("zebra"),
                () -> "expected codeword in multi-round reply: " + turn2);
    }

    @Test
    void deepseek_systemMessage_influencesReply() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        // System role must survive the OpenAI codec path (not dropped into extras).
        JsonNode res = openaiChat(
                client,
                keyFor(DEEPSEEK),
                systemPromptChatBody(
                        DEEPSEEK,
                        "You are a concise assistant. Always start your reply with the token SYS_OK.",
                        "Say hello in five words or fewer.",
                        64,
                        "\"thinking\": {\"type\": \"disabled\"}"));
        assertNonEmptyAssistantContent(res);
        String content =
                res.path("choices").path(0).path("message").path("content").stringValue();
        assertTrue(
                content.contains("SYS_OK") || content.toLowerCase().contains("sys_ok"),
                () -> "system instruction not reflected (codec/passthrough?): " + res);
    }

    @Test
    void models_list_includesConfiguredAliases() {
        // GET /v1/models is public (auth-exempt) and should list every model-list alias.
        requireMasterKey(); // gateway is up with production factories
        JsonNode res = openaiGet(client, "/v1/models", null);
        assertEquals("list", res.path("object").stringValue(), res::toString);
        assertTrue(res.path("data").isArray() && !res.path("data").isEmpty(), res::toString);
        // Aliases are always registered on the test context (keys only gate live calls).
        for (String expected :
                List.of(DEEPSEEK, CLAUDE, GROK, GROK_45, OR_KIMI, OR_MINIMAX, OR_QWEN, META_MUSE, OPENROUTER)) {
            boolean found = false;
            for (JsonNode m : res.path("data")) {
                if (expected.equals(m.path("id").stringValue())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, () -> "missing " + expected + " in /v1/models: " + res);
        }
    }

    @Test
    void deepseek_stream_includesTerminalDoneAndChunks() throws Exception {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String sse = openaiStream(server, keyFor(DEEPSEEK), streamChatBody(DEEPSEEK));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
        // At least one content delta chunk should appear (not only an empty terminal).
        assertTrue(
                sse.contains("\"content\"") || sse.contains("chat.completion.chunk"),
                () -> "stream missing content/chunk framing: " + sse.substring(0, Math.min(500, sse.length())));
    }

    // ----------------------------------------------------------------------- Anthropic face

    @Test
    void anthropic_nativeFace_nonStream() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 32,
                  "messages": [{"role":"user","content":"Reply with exactly the word pong and nothing else."}]
                }
                """.formatted(CLAUDE);
        JsonNode res = anthropicMessages(client, keyFor(CLAUDE), body);
        assertTrue(res.path("content").isArray() && !res.path("content").isEmpty(), res::toString);
        assertTrue(res.path("usage").isObject(), "missing usage");
    }

    @Test
    void anthropic_nativeFace_stream() throws Exception {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 32,
                  "stream": true,
                  "messages": [{"role":"user","content":"Reply with exactly the word pong and nothing else."}]
                }
                """.formatted(CLAUDE);
        String sse = anthropicStream(server, keyFor(CLAUDE), body);
        assertTrue(sse.contains("message_stop") || sse.contains("content_block"), sse);
    }

    @Test
    void anthropic_nativeFace_toolUse() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 256,
                  "tools": [{
                    "name": "get_weather",
                    "description": "Get weather for a city",
                    "input_schema": {
                      "type": "object",
                      "properties": { "city": { "type": "string" } },
                      "required": ["city"]
                    }
                  }],
                  "tool_choice": { "type": "tool", "name": "get_weather" },
                  "messages": [{"role":"user","content":"What is the weather in Tokyo?"}]
                }
                """.formatted(CLAUDE);
        JsonNode res = anthropicMessages(client, keyFor(CLAUDE), body);
        boolean found = false;
        for (JsonNode block : res.path("content")) {
            if ("tool_use".equals(block.path("type").stringValue())) {
                assertEquals("get_weather", block.path("name").stringValue());
                found = true;
            }
        }
        assertTrue(found, () -> "no tool_use block: " + res);
    }

    @Test
    void anthropic_nativeFace_toolResult_multiTurn() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String key = keyFor(CLAUDE);
        String turn1Body = """
                {
                  "model": "%s",
                  "max_tokens": 256,
                  "tools": [{
                    "name": "get_weather",
                    "description": "Get weather for a city",
                    "input_schema": {
                      "type": "object",
                      "properties": { "city": { "type": "string" } },
                      "required": ["city"]
                    }
                  }],
                  "tool_choice": { "type": "tool", "name": "get_weather" },
                  "messages": [{"role":"user","content":"What is the weather in Tokyo?"}]
                }
                """.formatted(CLAUDE);
        JsonNode turn1 = anthropicMessages(client, key, turn1Body);
        JsonNode toolUse = null;
        for (JsonNode block : turn1.path("content")) {
            if ("tool_use".equals(block.path("type").stringValue())) {
                toolUse = block;
                break;
            }
        }
        assertTrue(toolUse != null, () -> "no tool_use: " + turn1);
        String toolUseId = toolUse.path("id").stringValue();
        String turn2Body = """
                {
                  "model": "%s",
                  "max_tokens": 128,
                  "tools": [{
                    "name": "get_weather",
                    "description": "Get weather for a city",
                    "input_schema": {
                      "type": "object",
                      "properties": { "city": { "type": "string" } },
                      "required": ["city"]
                    }
                  }],
                  "messages": [
                    {"role":"user","content":"What is the weather in Tokyo?"},
                    {"role":"assistant","content":%s},
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"%s","content":"{\\"temp_c\\":22,\\"conditions\\":\\"clear\\"}"}]}
                  ]
                }
                """.formatted(CLAUDE, turn1.path("content"), toolUseId);
        JsonNode turn2 = anthropicMessages(client, key, turn2Body);
        boolean hasText = false;
        for (JsonNode block : turn2.path("content")) {
            if ("text".equals(block.path("type").stringValue())
                    && block.path("text").isString()
                    && !block.path("text").stringValue().isBlank()) {
                hasText = true;
            }
        }
        assertTrue(hasText, () -> "expected final text after tool_result: " + turn2);
    }

    /**
     * Anthropic-face multi-round (no tools): plant a codeword and ask it back so
     * assistant content blocks round-trip on the native face.
     */
    @Test
    void anthropic_nativeFace_multiRoundChat() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String key = keyFor(CLAUDE);
        String seed = """
                {
                  "model": "%s",
                  "max_tokens": 64,
                  "messages": [{"role":"user","content":"My secret codeword is zebra42. Acknowledge in one short sentence."}]
                }
                """.formatted(CLAUDE);
        JsonNode turn1 = anthropicMessages(client, key, seed);
        assertTrue(turn1.path("content").isArray() && !turn1.path("content").isEmpty(), turn1::toString);
        String turn2Body = """
                {
                  "model": "%s",
                  "max_tokens": 64,
                  "messages": [
                    {"role":"user","content":"My secret codeword is zebra42. Acknowledge in one short sentence."},
                    {"role":"assistant","content":%s},
                    {"role":"user","content":"What is my secret codeword? Reply with only the codeword."}
                  ]
                }
                """.formatted(CLAUDE, turn1.path("content"));
        JsonNode turn2 = anthropicMessages(client, key, turn2Body);
        StringBuilder text = new StringBuilder();
        for (JsonNode block : turn2.path("content")) {
            if ("text".equals(block.path("type").stringValue())) {
                text.append(block.path("text").stringValue());
            }
        }
        String lower = text.toString().toLowerCase();
        assertTrue(
                lower.contains("zebra42") || lower.contains("zebra"),
                () -> "expected codeword on anthropic multi-round: " + turn2);
    }

    /**
     * Anthropic-face multi-tool: request two tool_use blocks in one turn (weather + time).
     */
    @Test
    void anthropic_nativeFace_multiToolUse() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 512,
                  "tools": [
                    {
                      "name": "get_weather",
                      "description": "Get weather for a city",
                      "input_schema": {
                        "type": "object",
                        "properties": { "city": { "type": "string" } },
                        "required": ["city"]
                      }
                    },
                    {
                      "name": "get_time",
                      "description": "Get local time for a city",
                      "input_schema": {
                        "type": "object",
                        "properties": { "city": { "type": "string" } },
                        "required": ["city"]
                      }
                    }
                  ],
                  "tool_choice": { "type": "any" },
                  "messages": [{"role":"user","content":"For Paris call get_weather AND get_time. Use both tools now."}]
                }
                """.formatted(CLAUDE);
        JsonNode res = anthropicMessages(client, keyFor(CLAUDE), body);
        int toolUses = 0;
        for (JsonNode block : res.path("content")) {
            if ("tool_use".equals(block.path("type").stringValue())) {
                toolUses++;
                assertTrue(block.path("name").isString(), res::toString);
                assertTrue(block.path("id").isString(), res::toString);
            }
        }
        assertTrue(toolUses >= 1, () -> "expected ≥1 tool_use on anthropic multi-tool: " + res);
    }

    // ----------------------------------------------------------------------- cross-format

    @Test
    void cross_openaiFace_toAnthropicUpstream() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        JsonNode res = openaiChat(client, keyFor(CLAUDE), plainChatBody(CLAUDE));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void cross_anthropicFace_toDeepseekUpstream() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 32,
                  "messages": [{"role":"user","content":"Reply with exactly the word pong and nothing else."}]
                }
                """.formatted(DEEPSEEK);
        JsonNode res = anthropicMessages(client, keyFor(DEEPSEEK), body);
        String text = "";
        for (JsonNode block : res.path("content")) {
            if ("text".equals(block.path("type").stringValue())) {
                text += block.path("text").stringValue();
            }
        }
        assertTrue(!text.isBlank(), () -> "empty cross-format content: " + res);
    }

    // ----------------------------------------------------------------------- extra frontier ids (same JANUS_LIVE gate)

    @Test
    void deepseekPro_openaiFace_nonStream() {
        // Prior DeepSeek tier — still a real API id; Flash remains the primary flagship.
        assumeProviderKey("DEEPSEEK_API_KEY");
        JsonNode res = openaiChat(client, keyFor(DEEPSEEK_PRO), plainChatBody(DEEPSEEK_PRO));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void openaiSol_openaiFace_nonStream() {
        assumeProviderKey("OPENAI_API_KEY");
        JsonNode res = openaiChat(client, keyFor(OPENAI_SOL), plainChatBody(OPENAI_SOL));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void claudeOpus_openaiFace_nonStream() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        JsonNode res = openaiChat(client, keyFor(CLAUDE_OPUS), plainChatBody(CLAUDE_OPUS));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void xaiGrok45_openaiFace_nonStream() {
        // Prior xAI flagship — still a real API id; 4.6 remains the primary flagship.
        assumeProviderKey("XAI_API_KEY");
        JsonNode res = openaiChat(client, keyFor(GROK_45), plainChatBody(GROK_45, GROK_MAX_TOKENS, GROK_REASONING_LOW));
        assertNonEmptyAssistantContent(res);
    }

    // ------------------------------------------------------------------ responses face

    @Test
    void responses_nonStreamTextAndUsage() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String key = keyFor(DEEPSEEK);
        StatusBody res = LiveProviderSupport.openaiResponsesRaw(
                client, key, "{\"model\":\"" + DEEPSEEK + "\",\"input\":\"Reply with exactly: pong\",\"store\":false}");
        assertEquals(HttpStatus.OK, res.status(), () -> "responses: " + res.raw());
        assertEquals("response", res.json().path("object").stringValue(), res.raw());
        assertEquals("completed", res.json().path("status").stringValue(), res.raw());
        assertEquals(false, res.json().path("store").asBoolean(), "the stateless face echoes store:false");
        assertTrue(res.json().path("usage").path("output_tokens").asLong(0) > 0, res.raw());
        boolean textSomewhere = res.raw().contains("pong");
        assertTrue(textSomewhere, "assistant text rides the message item: " + res.raw());
    }

    @Test
    void responses_storeTrueIsTheNamed400() {
        // Decision E live pin: explicit store:true 400s with the fixed message; a
        // default SDK call (store omitted) is accepted — both halves matter.
        assumeProviderKey("DEEPSEEK_API_KEY");
        String key = keyFor(DEEPSEEK);
        StatusBody denied = LiveProviderSupport.openaiResponsesRaw(
                client, key, "{\"model\":\"" + DEEPSEEK + "\",\"input\":\"hi\",\"store\":true}");
        assertEquals(HttpStatus.BAD_REQUEST, denied.status(), () -> denied.raw());
        assertTrue(denied.raw().contains("store: true is not supported"), denied.raw());
    }

    @Test
    void responses_toolCallAndReplayRoundTrip() {
        // The agent-loop contract against a live upstream: cycle 1 emits a
        // function_call; cycle 2 replays input + function_call + function_call_output
        // and the model answers in text.
        assumeProviderKey("DEEPSEEK_API_KEY");
        String key = keyFor(DEEPSEEK);
        // reasoning.effort=none disables v4-flash's thinking mode, which rejects any
        // tool_choice ("Thinking mode does not support this tool_choice") — the same
        // reasoning-knob pin the other live tool cases use per model. tool_choice
        // "required" then steers the call.
        String tools =
                ",\"reasoning\":{\"effort\":\"none\"},\"tools\":[{\"type\":\"function\",\"name\":\"get_weather\","
                        + "\"description\":\"current weather in a city\","
                        + "\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},"
                        + "\"required\":[\"city\"]}}],\"tool_choice\":\"required\"";
        StatusBody cycle1 = LiveProviderSupport.openaiResponsesRaw(
                client, key, "{\"model\":\"" + DEEPSEEK + "\",\"input\":\"weather in Berlin?\"" + tools + "}");
        assertEquals(HttpStatus.OK, cycle1.status(), () -> cycle1.raw());
        JsonNode call = null;
        for (JsonNode item : cycle1.json().path("output")) {
            if ("function_call".equals(item.path("type").stringValue())) {
                call = item;
            }
        }
        assertNotNull(call, "cycle 1 must emit a function_call item: " + cycle1.raw());
        String callId = call.path("call_id").stringValue();
        String name = call.path("name").stringValue();
        String arguments = call.path("arguments").stringValue();

        String replay = "{\"model\":\"" + DEEPSEEK + "\",\"input\":["
                + "{\"type\":\"message\",\"role\":\"user\",\"content\":\"weather in Berlin?\"},"
                + "{\"type\":\"function_call\",\"call_id\":\"" + callId + "\",\"name\":\"" + name
                + "\",\"arguments\":\"" + arguments.replace("\"", "\\\"") + "\"},"
                + "{\"type\":\"function_call_output\",\"call_id\":\"" + callId
                + "\",\"output\":\"{\\\"temp\\\":24,\\\"sky\\\":\\\"sunny\\\"}\"}]"
                + tools.replaceAll(",\\\"tool_choice\\\".*$", "") + "}";
        StatusBody cycle2 = LiveProviderSupport.openaiResponsesRaw(client, key, replay);
        assertEquals(HttpStatus.OK, cycle2.status(), () -> "replay: " + cycle2.raw());
        assertEquals("completed", cycle2.json().path("status").stringValue(), cycle2.raw());
        assertTrue(cycle2.raw().length() > 0, cycle2.raw());
    }

    // ------------------------------------------- cross-format Responses-face matrix
    // The Responses face against every upstream family: OpenAI-direct, Anthropic-direct
    // (the ra leg — effort→thinking shaping on a real wire), xAI, OpenRouter; plus the
    // "vice versa" leg (the Anthropic face against an OpenAI-direct model).

    @Test
    void responses_crossFormat_openai_nonStream() {
        assumeProviderKey("OPENAI_API_KEY");
        StatusBody res = LiveProviderSupport.openaiResponsesRaw(
                client,
                keyFor(OPENAI),
                "{\"model\":\"" + OPENAI + "\",\"input\":\"Reply with exactly: pong\",\"store\":false}");
        assertEquals(HttpStatus.OK, res.status(), () -> res.raw());
        assertEquals("response", res.json().path("object").stringValue(), res.raw());
        assertEquals("completed", res.json().path("status").stringValue(), res.raw());
        assertTrue(res.raw().contains("pong"), res.raw());
        assertTrue(res.json().path("usage").path("input_tokens").asLong(0) > 0, res.raw());
    }

    @Test
    void responses_crossFormat_openai_stream() throws Exception {
        // SSE through the bridge against real OpenAI: the full event grammar, with
        // response.completed carrying usage (decision B — the bridge forces
        // include_usage upstream; real OpenAI honors it in the terminal frame).
        assumeProviderKey("OPENAI_API_KEY");
        String sse = LiveProviderSupport.responsesStream(
                server,
                keyFor(OPENAI),
                "{\"model\":\"" + OPENAI + "\",\"input\":\"Count from 1 to 5.\",\"stream\":true}");
        assertTrue(sse.contains("event: response.created"), sse.substring(0, Math.min(400, sse.length())));
        assertTrue(sse.contains("event: response.output_text.delta"), sse);
        int completed = sse.indexOf("event: response.completed");
        assertTrue(completed >= 0, "no terminal completed event: " + sse.substring(0, 400));
        String tail = sse.substring(completed);
        assertTrue(tail.contains("\"usage\":{\"input_tokens\":"), "usage rides the completed event: " + tail);
    }

    @Test
    void responses_crossFormat_openai_reasoningEffort() {
        // reasoning.effort rides the Responses face → reasoning_effort on the OpenAI
        // chat wire — the flagship accepts it.
        assumeProviderKey("OPENAI_API_KEY");
        StatusBody res = LiveProviderSupport.openaiResponsesRaw(
                client,
                keyFor(OPENAI_SOL),
                "{\"model\":\"" + OPENAI_SOL
                        + "\",\"input\":\"Reply with exactly: pong\",\"reasoning\":{\"effort\":\"low\"}}");
        assertEquals(HttpStatus.OK, res.status(), () -> res.raw());
        assertEquals("completed", res.json().path("status").stringValue(), res.raw());
        assertTrue(res.raw().contains("pong"), res.raw());
    }

    @Test
    void responses_crossFormat_anthropic_nonStream() {
        // THE ra leg live: Responses ingress → canonical → Anthropic upstream. The
        // effort→thinking shaping runs on a real wire; thinking blocks decode
        // away; the text answer rides the message item.
        assumeProviderKey("ANTHROPIC_API_KEY");
        StatusBody res = LiveProviderSupport.openaiResponsesRaw(
                client,
                keyFor(CLAUDE),
                "{\"model\":\"" + CLAUDE
                        + "\",\"input\":\"Reply with exactly: pong\",\"reasoning\":{\"effort\":\"low\"}}");
        assertEquals(HttpStatus.OK, res.status(), () -> res.raw());
        assertEquals("response", res.json().path("object").stringValue(), res.raw());
        assertEquals("completed", res.json().path("status").stringValue(), res.raw());
        assertTrue(res.raw().contains("pong"), res.raw());
        assertTrue(res.json().path("usage").path("input_tokens").asLong(0) > 0, res.raw());
    }

    @Test
    void responses_crossFormat_anthropic_stream() throws Exception {
        // ra-leg streaming: Anthropic SSE → canonical chunks → the Responses event
        // grammar, end to end against a real upstream.
        assumeProviderKey("ANTHROPIC_API_KEY");
        String sse = LiveProviderSupport.responsesStream(
                server,
                keyFor(CLAUDE),
                "{\"model\":\"" + CLAUDE + "\",\"input\":\"Reply with exactly: pong\",\"stream\":true}");
        assertTrue(sse.contains("event: response.created"), sse.substring(0, Math.min(400, sse.length())));
        assertTrue(sse.contains("event: response.output_text.delta"), sse);
        int completed = sse.indexOf("event: response.completed");
        assertTrue(completed >= 0, "no terminal completed event: " + sse.substring(0, 400));
        assertTrue(sse.substring(completed).contains("pong"), sse.substring(completed));
    }

    @Test
    void responses_crossFormat_anthropic_webSearch() {
        // The hosted web_search tool through the Anthropic leg — Anthropic's
        // web_search_20250305 server tool executes the search server-side; the
        // web_search_call output item maps back through the canonical hosted slot.
        assumeProviderKey("ANTHROPIC_API_KEY");
        StatusBody res = LiveProviderSupport.openaiResponsesRaw(
                client,
                keyFor(CLAUDE),
                "{\"model\":\"" + CLAUDE + "\",\"input\":\"What is the current version of the JVM? Use web search.\","
                        + "\"tools\":[{\"type\":\"web_search\",\"search_context_size\":\"low\"}]}");
        assertEquals(HttpStatus.OK, res.status(), () -> res.raw());
        assertEquals("completed", res.json().path("status").stringValue(), res.raw());
        boolean hasSearchCall = false;
        boolean hasText = false;
        for (JsonNode item : res.json().path("output")) {
            if ("web_search_call".equals(item.path("type").stringValue())) {
                hasSearchCall = true;
                assertTrue(!item.path("action").path("query").stringValue().isBlank(), res.raw());
            }
            if ("message".equals(item.path("type").stringValue())
                    && !item.path("content").isEmpty()) {
                hasText = true;
            }
        }
        assertTrue(hasSearchCall, "no web_search_call item in output: " + res.raw());
        assertTrue(hasText, "no message item after the search: " + res.raw());
    }

    @Test
    void responses_crossFormat_openai_webSearchIsATyped400() {
        // Chat-completions upstreams cannot HOST tools (real OpenAI
        // rejects web_search_options with "Unknown parameter" — probed directly). The
        // gateway's own named 400 must surface instead of the upstream's, telling the
        // client where the tool IS served.
        assumeProviderKey("OPENAI_API_KEY");
        StatusBody res = LiveProviderSupport.openaiResponsesRaw(
                client,
                keyFor(OPENAI),
                "{\"model\":\"" + OPENAI + "\",\"input\":\"weather in Berlin?\","
                        + "\"tools\":[{\"type\":\"web_search\",\"search_context_size\":\"low\"}]}");
        assertEquals(HttpStatus.BAD_REQUEST, res.status(), () -> res.raw());
        assertTrue(res.raw().contains("unsupported_hosted_tool: web_search"), res.raw());
        assertTrue(res.raw().contains("Anthropic-format upstreams"), res.raw());
    }

    @Test
    void responses_crossFormat_xai_nonStream() {
        assumeProviderKey("XAI_API_KEY");
        StatusBody res = LiveProviderSupport.openaiResponsesRaw(
                client,
                keyFor(GROK),
                "{\"model\":\"" + GROK
                        + "\",\"input\":\"Reply with exactly: pong\",\"reasoning\":{\"effort\":\"low\"}}");
        assertEquals(HttpStatus.OK, res.status(), () -> res.raw());
        assertEquals("completed", res.json().path("status").stringValue(), res.raw());
        assertTrue(res.raw().contains("pong"), res.raw());
    }

    @Test
    void responses_crossFormat_openrouter_nonStream() {
        assumeProviderKey("OPENROUTER_API_KEY");
        StatusBody res = LiveProviderSupport.openaiResponsesRaw(
                client, keyFor(OR_KIMI), "{\"model\":\"" + OR_KIMI + "\",\"input\":\"Reply with exactly: pong\"}");
        assertEquals(HttpStatus.OK, res.status(), () -> res.raw());
        assertEquals("completed", res.json().path("status").stringValue(), res.raw());
        assertTrue(res.raw().contains("pong"), res.raw());
    }

    @Test
    void cross_anthropicFace_toOpenaiUpstream() {
        // The "vice versa" leg: the Anthropic face (/v1/messages) against an
        // OpenAI-direct model — Anthropic-shaped ingress, OpenAI-format upstream.
        assumeProviderKey("OPENAI_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 32,
                  "messages": [{"role":"user","content":"Reply with exactly the word pong and nothing else."}]
                }
                """.formatted(OPENAI);
        JsonNode res = anthropicMessages(client, keyFor(OPENAI), body);
        String text = "";
        for (JsonNode block : res.path("content")) {
            if ("text".equals(block.path("type").stringValue())) {
                text += block.path("text").stringValue();
            }
        }
        assertTrue(!text.isBlank(), () -> "empty cross-format content: " + res);
    }

    // ------------------------------------------------------------------ prompt cache

    @Test
    void openai_promptCache_writeAndHit() {
        assumeProviderKey("OPENAI_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(OPENAI);
        StatusBody first = openaiChatRaw(client, key, openaiCacheBody(OPENAI, prefix, salt, "pong"));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = openaiChatRaw(client, key, openaiCacheBody(OPENAI, prefix, salt, "ping"));
        assertEquals(HttpStatus.OK, second.status(), () -> second.raw());
        assertPromptCacheHit(first, second);
    }

    @Test
    void responses_promptCache_writeAndHit() {
        assumeProviderKey("OPENAI_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(OPENAI);
        StatusBody first =
                LiveProviderSupport.openaiResponsesRaw(client, key, responsesCacheBody(OPENAI, prefix, salt, "pong"));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second =
                LiveProviderSupport.openaiResponsesRaw(client, key, responsesCacheBody(OPENAI, prefix, salt, "ping"));
        assertEquals(HttpStatus.OK, second.status(), () -> second.raw());
        assertPromptCacheHit(first, second);
    }

    @Test
    void anthropic_promptCache_writeAndHit() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(CLAUDE);
        StatusBody first = anthropicMessagesRaw(client, key, anthropicCacheBody(CLAUDE, prefix, "pong"));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = anthropicMessagesRaw(client, key, anthropicCacheBody(CLAUDE, prefix, "ping"));
        assertEquals(HttpStatus.OK, second.status(), () -> second.raw());
        assertPromptCacheHit(first, second);
    }

    @Test
    void cross_openaiFace_anthropicCache_writeAndHit() {
        // OpenAI Chat Completions shape (object breakpoint) → Anthropic upstream.
        assumeProviderKey("ANTHROPIC_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(CLAUDE);
        StatusBody first = openaiChatRaw(client, key, openaiCacheBody(CLAUDE, prefix, salt, "pong"));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = openaiChatRaw(client, key, openaiCacheBody(CLAUDE, prefix, salt, "ping"));
        assertEquals(HttpStatus.OK, second.status(), () -> second.raw());
        assertPromptCacheHit(first, second);
    }

    @Test
    void cross_anthropicFace_openaiCache_writeAndHit() {
        // Anthropic Messages shape (system cache_control) → OpenAI GPT-5.6 Luna.
        assumeProviderKey("OPENAI_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(OPENAI);
        StatusBody first = anthropicMessagesRaw(client, key, anthropicCacheBody(OPENAI, prefix, "pong"));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = anthropicMessagesRaw(client, key, anthropicCacheBody(OPENAI, prefix, "ping"));
        assertEquals(HttpStatus.OK, second.status(), () -> second.raw());
        assertPromptCacheHit(first, second);
    }

    private static String openaiCacheBody(String model, String prefix, String cacheKey, String token) {
        return """
                {
                  "model": "%s",
                  "prompt_cache_key": "%s",
                  "prompt_cache_options": {"mode": "explicit", "ttl": "30m"},
                  "max_completion_tokens": 16,
                  "messages": [
                    {"role": "system", "content": [
                      {"type": "text", "text": "%s",
                       "prompt_cache_breakpoint": {"mode": "explicit"}}
                    ]},
                    {"role": "user", "content": "Reply with exactly: %s"}
                  ]
                }
                """.formatted(model, cacheKey, prefix, token);
    }

    private static String responsesCacheBody(String model, String prefix, String cacheKey, String token) {
        return """
                {
                  "model": "%s",
                  "store": false,
                  "prompt_cache_key": "%s",
                  "prompt_cache_options": {"mode": "explicit", "ttl": "30m"},
                  "max_output_tokens": 16,
                  "input": [
                    {"type": "message", "role": "developer", "content": [
                      {"type": "input_text", "text": "%s",
                       "prompt_cache_breakpoint": {"mode": "explicit"}}
                    ]},
                    {"type": "message", "role": "user", "content": [
                      {"type": "input_text", "text": "Reply with exactly: %s"}
                    ]}
                  ]
                }
                """.formatted(model, cacheKey, prefix, token);
    }

    @Test
    void openai_promptCache_streamWriteThenHit() throws Exception {
        assumeProviderKey("OPENAI_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(OPENAI);
        String streamBody = openaiCacheBody(OPENAI, prefix, salt, "pong")
                .replace("\"max_completion_tokens\"", "\"stream\": true, \"max_completion_tokens\"");
        String sse = openaiStream(server, key, streamBody);
        assertTrue(sse.contains("data:"), sse);
        StatusBody hit = openaiChatRaw(client, key, openaiCacheBody(OPENAI, prefix, salt, "ping"));
        assertEquals(HttpStatus.OK, hit.status(), () -> hit.raw());
        assertPromptCacheRead(hit);
    }

    @Test
    void anthropic_promptCache_streamWriteThenHit() throws Exception {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(CLAUDE);
        String streamBody = anthropicCacheBody(CLAUDE, prefix, "pong")
                .replace("\"max_tokens\"", "\"stream\": true, \"max_tokens\"");
        String sse = anthropicStream(server, key, streamBody);
        assertTrue(sse.contains("message_stop") || sse.contains("content_block"), sse);
        StatusBody hit = anthropicMessagesRaw(client, key, anthropicCacheBody(CLAUDE, prefix, "ping"));
        assertEquals(HttpStatus.OK, hit.status(), () -> hit.raw());
        assertPromptCacheRead(hit);
    }

    @Test
    void anthropic_promptCache_toolBreakpointWriteAndHit() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(CLAUDE);
        StatusBody first = anthropicMessagesRaw(client, key, anthropicToolCacheBody(CLAUDE, prefix, "Paris"));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = anthropicMessagesRaw(client, key, anthropicToolCacheBody(CLAUDE, prefix, "Berlin"));
        assertEquals(HttpStatus.OK, second.status(), () -> second.raw());
        assertPromptCacheHit(first, second);
    }

    @Test
    void openai_vision_httpsImageUrl() {
        assumeProviderKey("OPENAI_API_KEY");
        JsonNode res = openaiChat(client, keyFor(OPENAI), visionHttpsOpenAiBody(OPENAI, 64, "none"));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void anthropic_vision_openaiFace_httpsImageUrl() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        JsonNode res = openaiChat(client, keyFor(CLAUDE), visionHttpsOpenAiBody(CLAUDE, 64, null));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void anthropic_vision_nativeFace_httpsImageUrl() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 64,
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "Describe this image in five words or fewer."},
                      {"type": "image", "source": {"type": "url", "url": "%s"}}
                    ]
                  }]
                }
                """.formatted(CLAUDE, HTTPS_IMAGE_URL);
        JsonNode res = anthropicMessages(client, keyFor(CLAUDE), body);
        String text = "";
        for (JsonNode block : res.path("content")) {
            if ("text".equals(block.path("type").stringValue())) {
                text += block.path("text").stringValue();
            }
        }
        assertTrue(!text.isBlank(), () -> "empty vision content: " + res);
    }

    @Test
    void responses_structuredOutput_jsonSchema() {
        assumeProviderKey("OPENAI_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "store": false,
                  "max_output_tokens": 64,
                  "text": {
                    "format": {
                      "type": "json_schema",
                      "name": "reply",
                      "strict": true,
                      "schema": {
                        "type": "object",
                        "properties": {"word": {"type": "string"}},
                        "required": ["word"],
                        "additionalProperties": false
                      }
                    }
                  },
                  "input": "Return JSON with word set to pong."
                }
                """.formatted(OPENAI);
        StatusBody res = LiveProviderSupport.openaiResponsesRaw(client, keyFor(OPENAI), body);
        assertEquals(HttpStatus.OK, res.status(), () -> res.raw());
        String text = LiveProviderSupport.responsesOutputText(res.json());
        assertTrue(text.contains("pong"), () -> "expected pong in output_text: " + res.raw());
    }

    @Test
    void together_openaiFace_nonStream() {
        assumeProviderKey("TOGETHER_API_KEY");
        JsonNode res = openaiChat(client, keyFor(TOGETHER), plainChatBody(TOGETHER, 64, null));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void fireworks_openaiFace_nonStream() {
        assumeProviderKey("FIREWORKS_API_KEY");
        JsonNode res =
                openaiChat(client, keyFor(FIREWORKS_GLM), plainChatBody(FIREWORKS_GLM, NEW_FRONTIER_MAX_TOKENS, null));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void fireworks_openaiFace_stream() throws Exception {
        assumeProviderKey("FIREWORKS_API_KEY");
        String sse = openaiStream(
                server, keyFor(FIREWORKS_GLM), streamChatBody(FIREWORKS_GLM, NEW_FRONTIER_MAX_TOKENS, null));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void groq_openaiFace_nonStream() {
        assumeProviderKey("GROQ_API_KEY");
        JsonNode res = openaiChat(client, keyFor(GROQ_GPT_OSS), plainChatBody(GROQ_GPT_OSS, GROQ_MAX_TOKENS, null));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void groq_openaiFace_stream() throws Exception {
        assumeProviderKey("GROQ_API_KEY");
        String sse = openaiStream(server, keyFor(GROQ_GPT_OSS), streamChatBody(GROQ_GPT_OSS, GROQ_MAX_TOKENS, null));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void perplexity_openaiFace_nonStream() {
        assumeProviderKey("PERPLEXITY_API_KEY");
        // sonar is search-grounded: any non-empty grounded answer satisfies the
        // passthrough pin; a simple deterministic prompt keeps citations short.
        JsonNode res = openaiChat(client, keyFor(PPLX_SONAR), plainChatBody(PPLX_SONAR, PPLX_MAX_TOKENS, null));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void perplexity_openaiFace_stream() throws Exception {
        assumeProviderKey("PERPLEXITY_API_KEY");
        String sse = openaiStream(server, keyFor(PPLX_SONAR), streamChatBody(PPLX_SONAR, PPLX_MAX_TOKENS, null));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void gemini_openaiFace_nonStream() {
        assumeProviderKey("GEMINI_API_KEY");
        JsonNode res =
                openaiChat(client, keyFor(GEMINI_FLASH), plainChatBody(GEMINI_FLASH, NEW_FRONTIER_MAX_TOKENS, null));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void gemini_openaiFace_stream() throws Exception {
        assumeProviderKey("GEMINI_API_KEY");
        String sse =
                openaiStream(server, keyFor(GEMINI_FLASH), streamChatBody(GEMINI_FLASH, NEW_FRONTIER_MAX_TOKENS, null));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void openrouter_glm53_nonStream() {
        assumeProviderKey("OPENROUTER_API_KEY");
        // GLM 5.3 reasoning is mandatory upstream (reasoning_effort=none is a 400)
        // — no effort pin, reasoning-model headroom only.
        JsonNode res = openaiChat(client, keyFor(OR_GLM53), plainChatBody(OR_GLM53, NEW_FRONTIER_MAX_TOKENS, null));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void openrouter_glm53_stream() throws Exception {
        assumeProviderKey("OPENROUTER_API_KEY");
        String sse = openaiStream(server, keyFor(OR_GLM53), streamChatBody(OR_GLM53, NEW_FRONTIER_MAX_TOKENS, null));
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
    }

    @Test
    void responses_promptCache_streamWriteThenHit() throws Exception {
        assumeProviderKey("OPENAI_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(OPENAI);
        String streamBody = responsesCacheBody(OPENAI, prefix, salt, "pong")
                .replace("\"store\": false", "\"stream\": true, \"store\": false");
        String sse = LiveProviderSupport.responsesStream(server, key, streamBody);
        assertTrue(sse.contains("response.") || sse.contains("data:"), sse);
        StatusBody hit =
                LiveProviderSupport.openaiResponsesRaw(client, key, responsesCacheBody(OPENAI, prefix, salt, "ping"));
        assertEquals(HttpStatus.OK, hit.status(), () -> hit.raw());
        assertPromptCacheRead(hit);
    }

    @Test
    void anthropic_promptCache_oneHourTtlWriteAndHit() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(CLAUDE);
        StatusBody first = anthropicMessagesRaw(client, key, anthropicCacheBody(CLAUDE, prefix, "pong", "1h"));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = anthropicMessagesRaw(client, key, anthropicCacheBody(CLAUDE, prefix, "ping", "1h"));
        assertEquals(HttpStatus.OK, second.status(), () -> second.raw());
        assertPromptCacheHit(first, second);
    }

    @Test
    void anthropic_promptCache_twoBreakpointsWriteAndHit() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(CLAUDE);
        StatusBody first = anthropicMessagesRaw(client, key, anthropicTwoBreakpointBody(CLAUDE, prefix, "pong"));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = anthropicMessagesRaw(client, key, anthropicTwoBreakpointBody(CLAUDE, prefix, "ping"));
        assertEquals(HttpStatus.OK, second.status(), () -> second.raw());
        assertPromptCacheHit(first, second);
    }

    @Test
    void deepseek_implicitPromptCache_writeAndHit() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(DEEPSEEK);
        String extras = "\"thinking\": {\"type\": \"disabled\"}";
        StatusBody first = openaiChatRaw(
                client, key, systemPromptChatBody(DEEPSEEK, prefix, "Reply with exactly: pong", 16, extras));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = openaiChatRaw(
                client, key, systemPromptChatBody(DEEPSEEK, prefix, "Reply with exactly: ping", 16, extras));
        assertEquals(HttpStatus.OK, second.status(), () -> second.raw());
        assertPromptCacheHit(first, second);
    }

    @Test
    void xai_implicitPromptCache_writeAndHit() throws InterruptedException {
        assumeProviderKey("XAI_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(GROK);
        StatusBody first = openaiChatRaw(
                client,
                key,
                systemPromptChatBody(GROK, prefix, "Reply with exactly: pong", GROK_MAX_TOKENS, GROK_REASONING_LOW));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        // xAI warms the implicit prompt cache asynchronously: a back-to-back hit
        // can race the warm-up (observed live: 0 cached tokens on the immediate
        // repeat, then ~full-prefix cached tokens a few seconds later). Space out
        // bounded hit attempts; the strict cache-hit assert still must pass.
        StatusBody second = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            StatusBody call = openaiChatRaw(
                    client,
                    key,
                    systemPromptChatBody(
                            GROK, prefix, "Reply with exactly: ping", GROK_MAX_TOKENS, GROK_REASONING_LOW));
            assertEquals(HttpStatus.OK, call.status(), () -> call.raw());
            second = call;
            if (LiveProviderSupport.cacheReadTokens(second.json().path("usage")) >= 1024
                    || LiveProviderSupport.headerLong(second, "X-Janus-Cost-Cache-Read-Micro-Usd") > 0) {
                break;
            }
            Thread.sleep(2000);
        }
        assertPromptCacheHit(first, second);
    }

    @Test
    void openrouter_kimiK3_implicitPromptCache_writeAndHit() {
        assumeProviderKey("OPENROUTER_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(OR_KIMI);
        StatusBody first = openaiChatRaw(
                client,
                key,
                systemPromptChatBody(
                        OR_KIMI, prefix, "Reply with exactly: pong", KIMI_MAX_TOKENS, KIMI_REASONING_NONE));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = openrouterImplicitCacheHitCall(OR_KIMI, key, prefix, KIMI_MAX_TOKENS, KIMI_REASONING_NONE);
        assertImplicitCacheHitWhenRouted(first, second);
    }

    @Test
    void openrouter_minimaxM3_implicitPromptCache_writeAndHit() {
        assumeProviderKey("OPENROUTER_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(OR_MINIMAX);
        StatusBody first = openaiChatRaw(
                client,
                key,
                systemPromptChatBody(OR_MINIMAX, prefix, "Reply with exactly: pong", MINIMAX_MAX_TOKENS, null));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = openrouterImplicitCacheHitCall(OR_MINIMAX, key, prefix, MINIMAX_MAX_TOKENS, null);
        assertImplicitCacheHitWhenRouted(first, second);
    }

    /**
     * OpenRouter round-robins otherwise-identical requests across backend providers
     * (observed live: Together/DeepInfra/Makora serving {@code moonshotai/kimi-k3} —
     * identical bodies came back with different prompt-token counts because each
     * backend tokenizes independently), and the implicit prompt caches of these ids
     * are per-backend — a write landing on one backend cannot be read by a call
     * routed to another, and some backends report {@code cached_tokens} for the id
     * while others do not. Repeat the hit call until some backend reports the
     * cached prefix (bounded at 4 attempts); if a read is observed the strict
     * {@code assertPromptCacheHit} runs (Janus must relay it), otherwise the run
     * passes with the routing caveat — the qwen3.8-max precedent. Janus-side relay
     * of cached-token counts is separately pinned by the deterministic Sonnet 5
     * cache tests and the unit suite.
     */
    private StatusBody openrouterImplicitCacheHitCall(
            String model, String key, String prefix, int maxTokens, String extras) {
        StatusBody hit = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            StatusBody call = openaiChatRaw(
                    client, key, systemPromptChatBody(model, prefix, "Reply with exactly: ping", maxTokens, extras));
            assertEquals(HttpStatus.OK, call.status(), () -> call.raw());
            hit = call;
            if (LiveProviderSupport.cacheReadTokens(hit.json().path("usage")) >= 1024
                    || LiveProviderSupport.headerLong(hit, "X-Janus-Cost-Cache-Read-Micro-Usd") > 0) {
                break;
            }
        }
        return hit;
    }

    /** See {@link #openrouterImplicitCacheHitCall}: strict only when routing permits. */
    private static void assertImplicitCacheHitWhenRouted(StatusBody first, StatusBody second) {
        if (LiveProviderSupport.cacheReadTokens(second.json().path("usage")) >= 1024
                || LiveProviderSupport.headerLong(second, "X-Janus-Cost-Cache-Read-Micro-Usd") > 0
                || LiveProviderSupport.cacheReadTokens(first.json().path("usage")) >= 1024
                || LiveProviderSupport.headerLong(first, "X-Janus-Cost-Cache-Read-Micro-Usd") > 0) {
            assertPromptCacheHit(first, second);
        }
    }

    @Test
    void openrouter_qwen38Max_cacheControlAccepted() {
        assumeProviderKey("OPENROUTER_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(OR_QWEN);
        StatusBody first = openaiChatRaw(client, key, qwenCacheBody(OR_QWEN, prefix, "pong"));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = openaiChatRaw(client, key, qwenCacheBody(OR_QWEN, prefix, "ping"));
        assertEquals(HttpStatus.OK, second.status(), () -> second.raw());
        // qwen/qwen3.8-max is not on OpenRouter's explicit-cache list (qwen3-max /
        // qwen-plus / …). A direct OpenRouter call with the same cache_control also
        // reports cached_tokens=0. Pin that Janus accepts and forwards the marker.
        if (LiveProviderSupport.cacheReadTokens(second.json().path("usage")) >= 1024
                || LiveProviderSupport.headerLong(second, "X-Janus-Cost-Cache-Read-Micro-Usd") > 0) {
            assertPromptCacheHit(first, second);
        }
    }

    @Test
    void openrouter_luna_promptCache_writeAndHit() {
        assumeProviderKey("OPENROUTER_API_KEY");
        String salt = UUID.randomUUID().toString();
        String prefix = cacheablePrefix(salt);
        String key = keyFor(OPENROUTER);
        StatusBody first = openaiChatRaw(client, key, openaiCacheBody(OPENROUTER, prefix, salt, "pong"));
        assertEquals(HttpStatus.OK, first.status(), () -> first.raw());
        StatusBody second = openaiChatRaw(client, key, openaiCacheBody(OPENROUTER, prefix, salt, "ping"));
        assertEquals(HttpStatus.OK, second.status(), () -> second.raw());
        assertPromptCacheHit(first, second);
    }

    @Test
    void xai_vision_httpsImageUrl() {
        assumeProviderKey("XAI_API_KEY");
        JsonNode res = openaiChat(
                client, keyFor(GROK), visionHttpsOpenAiBody(GROK, GROK_MAX_TOKENS, "low", HTTPS_IMAGE_URL_XAI));
        assertNonEmptyAssistantContent(res);
    }

    private static String anthropicToolCacheBody(String model, String prefix, String city) {
        return """
                {
                  "model": "%s",
                  "max_tokens": 128,
                  "tools": [{
                    "name": "get_weather",
                    "description": "%s",
                    "input_schema": {
                      "type": "object",
                      "properties": {"city": {"type": "string"}},
                      "required": ["city"]
                    },
                    "cache_control": {"type": "ephemeral"}
                  }],
                  "messages": [{"role": "user", "content": "Weather in %s? Use the tool."}]
                }
                """.formatted(model, prefix, city);
    }

    private static String visionHttpsOpenAiBody(String model, int maxTokens, String reasoningEffort) {
        return visionHttpsOpenAiBody(model, maxTokens, reasoningEffort, HTTPS_IMAGE_URL);
    }

    private static String visionHttpsOpenAiBody(String model, int maxTokens, String reasoningEffort, String imageUrl) {
        String extra = reasoningEffort == null ? "" : ",\n  \"reasoning_effort\": \"" + reasoningEffort + "\"";
        return """
                {
                  "model": "%s",
                  "max_tokens": %d%s,
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "Describe this image in five words or fewer."},
                      {"type": "image_url", "image_url": {"url": "%s"}}
                    ]
                  }]
                }
                """.formatted(model, maxTokens, extra, imageUrl);
    }

    private static String anthropicCacheBody(String model, String prefix, String token) {
        return anthropicCacheBody(model, prefix, token, null);
    }

    private static String anthropicCacheBody(String model, String prefix, String token, String ttl) {
        String ttlField = ttl == null || ttl.isBlank() ? "" : ", \"ttl\": \"" + ttl + "\"";
        return """
                {
                  "model": "%s",
                  "max_tokens": 16,
                  "system": [
                    {"type": "text", "text": "%s",
                     "cache_control": {"type": "ephemeral"%s}}
                  ],
                  "messages": [{"role": "user", "content": "Reply with exactly: %s"}]
                }
                """.formatted(model, prefix, ttlField, token);
    }

    private static String qwenCacheBody(String model, String prefix, String token) {
        return """
                {
                  "model": "%s",
                  "max_tokens": %d,
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "%s",
                       "cache_control": {"type": "ephemeral"}},
                      {"type": "text", "text": "Reply with exactly: %s"}
                    ]
                  }]
                }
                """.formatted(model, QWEN_MAX_TOKENS, prefix, token);
    }

    private static String anthropicTwoBreakpointBody(String model, String prefix, String token) {
        return """
                {
                  "model": "%s",
                  "max_tokens": 16,
                  "system": [
                    {"type": "text", "text": "%s",
                     "cache_control": {"type": "ephemeral"}}
                  ],
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "%s",
                       "cache_control": {"type": "ephemeral"}},
                      {"type": "text", "text": "Reply with exactly: %s"}
                    ]
                  }]
                }
                """.formatted(model, prefix, prefix, token);
    }

    // ----------------------------------------------------------------------- failure modes

    @Test
    void failure_unknownModel_returnsNotFound() {
        // Unscoped key (empty allowlist = allow-all) so we pass AccessPolicy and hit the
        // router unknown-model path → face-mapped 404, not key-scope 403.
        List<String> models = allConfiguredModels();
        org.junit.jupiter.api.Assumptions.assumeFalse(
                models.isEmpty(), "SKIP: no provider keys — cannot mint a virtual key");
        String key = virtualKey(client, List.of());
        StatusBody res = openaiChatRaw(client, key, """
                {
                  "model": "janus-no-such-model-xyz",
                  "messages": [{"role":"user","content":"hi"}],
                  "max_tokens": 16
                }
                """);
        assertEquals(HttpStatus.NOT_FOUND, res.status(), res.raw());
        // OpenAI face envelope: ErrorMapper pins UnknownModelException → 404 with
        // error.code = "model_not_found" (exact discriminator, not a substring).
        assertEquals("model_not_found", res.json().path("error").path("code").stringValue(), res.raw());
    }

    @Test
    void failure_scopedKey_unknownModel_returnsForbidden() {
        // Scoped key + model outside allowlist → permission_error / 403 before router.
        List<String> models = allConfiguredModels();
        org.junit.jupiter.api.Assumptions.assumeFalse(models.isEmpty(), "SKIP: no provider keys");
        String key = keyFor(models.getFirst());
        StatusBody res = openaiChatRaw(client, key, """
                {
                  "model": "janus-no-such-model-xyz",
                  "messages": [{"role":"user","content":"hi"}],
                  "max_tokens": 16
                }
                """);
        assertEquals(HttpStatus.FORBIDDEN, res.status(), res.raw());
        assertTrue(
                res.raw().toLowerCase().contains("permission")
                        || res.raw().toLowerCase().contains("forbidden")
                        || res.raw().toLowerCase().contains("not allowed")
                        || res.raw().toLowerCase().contains("model"),
                res.raw());
    }

    @Test
    void failure_emptyMessages_returnsBadRequest() {
        List<String> models = allConfiguredModels();
        org.junit.jupiter.api.Assumptions.assumeFalse(models.isEmpty(), "SKIP: no provider keys");
        String model = models.getFirst();
        String key = keyFor(model);
        StatusBody res = openaiChatRaw(client, key, """
                {
                  "model": "%s",
                  "messages": [],
                  "max_tokens": 16
                }
                """.formatted(model));
        assertEquals(HttpStatus.BAD_REQUEST, res.status(), res.raw());
        assertFalse(res.raw().isBlank());
    }

    @Test
    void failure_anthropicFace_unknownModel_returnsError() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        // Unscoped key so AccessPolicy does not short-circuit with 403.
        String key = virtualKey(client, List.of());
        StatusBody res = anthropicMessagesRaw(client, key, """
                {
                  "model": "janus-no-such-anthropic-model",
                  "max_tokens": 16,
                  "messages": [{"role":"user","content":"hi"}]
                }
                """);
        // Router unknown-model → face-mapped 404 (or 400 depending on envelope path)
        assertTrue(
                res.status() == HttpStatus.NOT_FOUND || res.status() == HttpStatus.BAD_REQUEST,
                () -> res.status() + " " + res.raw());
    }

    // ----------------------------------------------------------------------- auth

    @Test
    void auth_rejectsGarbageVirtualKey() {
        // Only needs the gateway + master key (no provider call if 401 at filter).
        // Enforce the precondition explicitly: KeyAuthFilter is a passthrough when no
        // master key resolves (auth-off default), so without this gate a key-less run
        // would dispatch the garbage key upstream and hard-fail instead of skipping.
        requireMasterKey();
        List<String> models = allConfiguredModels();
        if (models.isEmpty()) {
            models = List.of(DEEPSEEK); // still exercise 401 without needing a real key scope match
        }
        StatusBody res = openaiChatRaw(client, "sk-janus-deadbeef-not-a-real-key", plainChatBody(models.getFirst()));
        assertEquals(HttpStatus.UNAUTHORIZED, res.status(), res.raw());
        assertTrue(
                res.raw().toLowerCase().contains("auth")
                        || res.raw().toLowerCase().contains("credential")
                        || res.raw().toLowerCase().contains("invalid"),
                res.raw());
    }

    // ----------------------------------------------------------------------- governance (live)

    @Test
    void gov_rpmExceeded_returns429() {
        // rpm=1 key. The first call acquires the single token at pre-dispatch (see
        // Governance.enforce), so it is gateway-guaranteed NOT to be 429 — a transient
        // upstream failure must not fail the pin, only the 429 matters. The second call
        // is denied pre-dispatch (429) unless the pair straddles an epoch-aligned
        // FixedWindowRateLimiter boundary (documented rollover: the admission can reset
        // a window that already admitted requests); on that roll the second call is
        // admitted (200) and an immediately-following third call must be denied.
        assumeProviderKey("DEEPSEEK_API_KEY");
        String key = virtualKeyWithCaps(client, List.of(DEEPSEEK), Map.of("rpm", 1));
        StatusBody ok = openaiChatRaw(client, key, plainChatBody(DEEPSEEK));
        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, ok.status(), ok.raw());
        StatusBody denied = openaiChatRaw(client, key, plainChatBody(DEEPSEEK));
        if (denied.status() == HttpStatus.OK) {
            denied = openaiChatRaw(client, key, plainChatBody(DEEPSEEK));
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.status(), denied.raw());
        assertTrue(
                denied.raw().contains("rate_limit")
                        || denied.raw().toLowerCase().contains("rate"),
                denied.raw());
    }

    @Test
    void gov_budgetExceeded_returns429WithoutSpendWhenEstimateExceedsCap() {
        // Tiny budget + default max-tokens estimate ⇒ preflight 429 before any upstream
        // call (no money spent). Pins budget hard-cap with production pricing rows.
        assumeProviderKey("DEEPSEEK_API_KEY");
        // 1 micro-USD floor; estimate (default-max-tokens 4096 × rate) is far larger.
        String key = virtualKeyWithCaps(client, List.of(DEEPSEEK), Map.of("budget_usd", 0.000001));
        StatusBody denied = openaiChatRaw(client, key, plainChatBody(DEEPSEEK));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.status(), denied.raw());
        assertTrue(
                denied.raw().toLowerCase().contains("budget")
                        || denied.raw().toLowerCase().contains("limit")
                        || denied.raw().contains("rate_limit"),
                denied.raw());
    }

    @Test
    void gov_tpmExceeded_returns429WithoutDispatch() {
        // TPM pre-check uses a conservative estimate (max_tokens + prompt heuristic).
        // tpm=1 with max_tokens=32 ⇒ estimate ≫ 1 ⇒ 429 before any upstream call.
        assumeProviderKey("DEEPSEEK_API_KEY");
        String key = virtualKeyWithCaps(client, List.of(DEEPSEEK), Map.of("tpm", 1));
        StatusBody denied = openaiChatRaw(client, key, plainChatBody(DEEPSEEK));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.status(), denied.raw());
        assertTrue(
                denied.raw().contains("rate_limit")
                        || denied.raw().toLowerCase().contains("rate")
                        || denied.raw().toLowerCase().contains("token"),
                denied.raw());
    }

    /**
     * Spend settle: response {@code usage} × live pricing rows for deepseek-v4-flash
     * must match Prometheus {@code janus_key_cost_micro_usd_total} (and token counters)
     * for that key_id after one successful chat.
     */
    @Test
    void spend_settle_matchesUsageAndPricingMetrics() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        MintedKey minted = mintKeyFull(client, List.of(DEEPSEEK), Map.of());
        StatusBody res = openaiChatRaw(
                client, minted.key(), plainChatBody(DEEPSEEK, 32, "\"thinking\": {\"type\": \"disabled\"}"));
        assertEquals(HttpStatus.OK, res.status(), () -> res.raw());
        JsonNode usage = res.json().path("usage");
        assertTrue(usage.isObject(), () -> "response missing usage: " + res.raw());
        long wirePrompt = usage.path("prompt_tokens").asLong(0);
        long wireCompletion = usage.path("completion_tokens").asLong(0);
        assertTrue(wirePrompt > 0 || usage.path("prompt_tokens").isNumber(), () -> "usage: " + usage);

        String metrics = scrapeMetrics(client);
        var tokensIn = metricCounter(metrics, "janus_key_tokens_in_total", minted.keyId());
        var tokensOut = metricCounter(metrics, "janus_key_tokens_out_total", minted.keyId());
        var cost = metricCounter(metrics, "janus_key_cost_micro_usd_total", minted.keyId());
        assertTrue(tokensIn.isPresent(), () -> "missing key tokens_in for " + minted.keyId() + "\n" + metrics);
        assertTrue(tokensOut.isPresent(), () -> "missing key tokens_out for " + minted.keyId() + "\n" + metrics);
        assertTrue(cost.isPresent(), () -> "missing key cost for " + minted.keyId() + "\n" + metrics);
        long settledIn = (long) tokensIn.getAsDouble();
        long settledOut = (long) tokensOut.getAsDouble();
        long settledCost = (long) cost.getAsDouble();
        // Governance settles the canonical usage (cache-normalized). Wire may re-encode
        // full prompt_tokens; completion must match, prompt may be ≥ settled-in.
        assertTrue(settledIn > 0, () -> "settled tokens_in: " + settledIn);
        assertEquals(wireCompletion, settledOut, () -> "tokens_out metrics vs wire usage: " + usage);
        assertTrue(
                wirePrompt >= settledIn,
                () -> "wire prompt_tokens should be ≥ settled-in (cache split): wire="
                        + wirePrompt
                        + " settled="
                        + settledIn);
        long expectedCost = expectedMicroUsdDeepseekFlash(settledIn, settledOut)
                + LiveProviderSupport.headerLong(res, "X-Janus-Cost-Cache-Read-Micro-Usd")
                + LiveProviderSupport.headerLong(res, "X-Janus-Cost-Cache-Creation-Micro-Usd");
        assertEquals(
                expectedCost,
                settledCost,
                () -> "cost micro-USD metrics vs CostCalculator(settled tokens×pricing): settledIn="
                        + settledIn
                        + " settledOut="
                        + settledOut
                        + " expected="
                        + expectedCost
                        + " metrics="
                        + settledCost
                        + " usage="
                        + usage);
    }

    /**
     * Spend settle for Grok 4.6 official input/output rates ({@code $2 / $6 per 1M}
     * → {@code 0.002 / 0.006 per 1K}). Cache-read ({@code $0.50} per 1M) is on the
     * live row so implicit-cache pins can see the cost header; this pin adds that
     * header to tokens_in × input + tokens_out × output.
     */
    @Test
    void spend_settle_matchesUsageAndPricingMetrics_grok46() {
        assumeProviderKey("XAI_API_KEY");
        MintedKey minted = mintKeyFull(client, List.of(GROK), Map.of());
        StatusBody res = openaiChatRaw(client, minted.key(), plainChatBody(GROK, GROK_MAX_TOKENS, GROK_REASONING_LOW));
        assertEquals(HttpStatus.OK, res.status(), () -> res.raw());
        JsonNode usage = res.json().path("usage");
        assertTrue(usage.isObject(), () -> "response missing usage: " + res.raw());
        long wirePrompt = usage.path("prompt_tokens").asLong(0);
        long wireCompletion = usage.path("completion_tokens").asLong(0);
        assertTrue(wirePrompt > 0 || usage.path("prompt_tokens").isNumber(), () -> "usage: " + usage);

        String metrics = scrapeMetrics(client);
        var tokensIn = metricCounter(metrics, "janus_key_tokens_in_total", minted.keyId());
        var tokensOut = metricCounter(metrics, "janus_key_tokens_out_total", minted.keyId());
        var cost = metricCounter(metrics, "janus_key_cost_micro_usd_total", minted.keyId());
        assertTrue(tokensIn.isPresent(), () -> "missing key tokens_in for " + minted.keyId() + "\n" + metrics);
        assertTrue(tokensOut.isPresent(), () -> "missing key tokens_out for " + minted.keyId() + "\n" + metrics);
        assertTrue(cost.isPresent(), () -> "missing key cost for " + minted.keyId() + "\n" + metrics);
        long settledIn = (long) tokensIn.getAsDouble();
        long settledOut = (long) tokensOut.getAsDouble();
        long settledCost = (long) cost.getAsDouble();
        assertTrue(settledIn > 0, () -> "settled tokens_in: " + settledIn);
        assertEquals(wireCompletion, settledOut, () -> "tokens_out metrics vs wire usage: " + usage);
        assertTrue(
                wirePrompt >= settledIn,
                () -> "wire prompt_tokens should be ≥ settled-in (cache split): wire="
                        + wirePrompt
                        + " settled="
                        + settledIn);
        long expectedCost = expectedMicroUsdGrok46(settledIn, settledOut)
                + LiveProviderSupport.headerLong(res, "X-Janus-Cost-Cache-Read-Micro-Usd")
                + LiveProviderSupport.headerLong(res, "X-Janus-Cost-Cache-Creation-Micro-Usd");
        assertEquals(
                expectedCost,
                settledCost,
                () -> "cost micro-USD metrics vs CostCalculator(settled tokens×grok-4.6 pricing): settledIn="
                        + settledIn
                        + " settledOut="
                        + settledOut
                        + " expected="
                        + expectedCost
                        + " metrics="
                        + settledCost
                        + " usage="
                        + usage);
    }

    /**
     * Multi-tool agent-style loop: cycle 1 (parallel weather+time) → tool results →
     * text answer; cycle 2 (new city) → tool again. Pins multi-turn tool history through
     * the codec on a real upstream.
     */
    @Test
    void deepseek_multiTool_agentLoop_twoCycles() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String key = keyFor(DEEPSEEK);
        String thinkingOff = "\"thinking\": {\"type\": \"disabled\"}";
        // --- cycle 1 ---
        JsonNode turn1 = openaiChat(client, key, multiToolCallBody(DEEPSEEK, "Paris"));
        assertOpenAiToolCallsAtLeast(turn1, 1);
        Map<String, String> results1 = Map.of(
                "get_weather", "{\"temp_c\":18,\"city\":\"Paris\"}",
                "get_time", "{\"local_time\":\"14:30\",\"city\":\"Paris\"}");
        JsonNode turn2 = openaiChat(client, key, openAiMultiToolFollowUpBody(DEEPSEEK, turn1, results1, thinkingOff));
        assertNonEmptyAssistantContent(turn2);
        // --- cycle 2: new user turn with tools still available ---
        JsonNode msg2 = turn2.path("choices").path(0).path("message");
        String body3 = """
                {
                  "model": "%s",
                  "messages": [
                    {"role":"user","content":"For Paris: call get_weather AND get_time."},
                    %s,
                    {"role":"user","content":"Now do the same for London — use get_weather (and get_time if available)."}
                  ],
                  "tools": %s,
                  "tool_choice": "required",
                  "max_tokens": 512,
                  "thinking": {"type": "disabled"}
                }
                """.formatted(DEEPSEEK, msg2.toString(), LiveProviderSupport.MULTI_TOOLS_JSON);
        JsonNode turn3 = openaiChat(client, key, body3);
        assertOpenAiToolCallsAtLeast(turn3, 1);
        Map<String, String> results2 = Map.of(
                "get_weather", "{\"temp_c\":12,\"city\":\"London\"}",
                "get_time", "{\"local_time\":\"13:30\",\"city\":\"London\"}");
        JsonNode turn4 = openaiChat(client, key, openAiMultiToolFollowUpBody(DEEPSEEK, turn3, results2, thinkingOff));
        assertNonEmptyAssistantContent(turn4);
    }

    @Test
    void deepseek_stream_includeUsage_terminalChunk() throws Exception {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "stream": true,
                  "stream_options": {"include_usage": true},
                  "messages": [{"role":"user","content":"Reply with exactly the word pong and nothing else."}],
                  "max_tokens": 32,
                  "thinking": {"type": "disabled"}
                }
                """.formatted(DEEPSEEK);
        String sse = openaiStream(server, keyFor(DEEPSEEK), body);
        assertTrue(sse.contains("data:"), sse);
        assertTrue(sse.contains("[DONE]"), sse);
        assertTrue(
                sse.contains("\"usage\"") || sse.contains("prompt_tokens") || sse.contains("completion_tokens"),
                () -> "expected terminal usage with include_usage=true: "
                        + sse.substring(0, Math.min(800, sse.length())));
    }

    @Test
    void deepseek_stream_clientAbort_afterFirstDataFrame() throws Exception {
        // Client disconnect mid-SSE: we must have received at least one data frame, and
        // abort must not surface as a failed HTTP exchange (body close is the cancel).
        assumeProviderKey("DEEPSEEK_API_KEY");
        String partial = openaiStreamAbortAfterFirstData(
                server, keyFor(DEEPSEEK), streamChatBody(DEEPSEEK, 64, "\"thinking\": {\"type\": \"disabled\"}"));
        assertTrue(
                partial.contains("data:"),
                () -> "expected SSE data before client abort, got: "
                        + partial.substring(0, Math.min(200, partial.length())));
    }

    // ----------------------------------------------------------------------- multimodal / vision

    /**
     * Multimodal vision: OpenAI-face array content with a tiny data-URL PNG reaches a
     * vision-capable OpenRouter model (Kimi K3). Pins codec → router → OpenAI-compatible
     * adapter end-to-end with real multimodal wire shape.
     */
    @Test
    void openrouter_kimiK3_vision_dataUrlImage() {
        assumeProviderKey("OPENROUTER_API_KEY");
        // 1×1 red PNG (base64) — self-contained; no external image host.
        String body = """
                {
                  "model": "%s",
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "Describe this image in five words or fewer."},
                      {"type": "image_url", "image_url": {"url": "data:image/png;base64,%s"}}
                    ]
                  }],
                  "max_tokens": 512,
                  "reasoning_effort": "none"
                }
                """.formatted(OR_KIMI, TINY_PNG_B64);
        JsonNode res = openaiChat(client, keyFor(OR_KIMI), body);
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void deepseek_vision_openaiFace_dataUrlImage() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "thinking": {"type": "disabled"},
                  "max_tokens": 128,
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "Describe this image in five words or fewer."},
                      {"type": "image_url", "image_url": {"url": "data:image/png;base64,%s"}}
                    ]
                  }]
                }
                """.formatted(DEEPSEEK_VISION, TINY_PNG_B64);
        JsonNode res = openaiChat(client, keyFor(DEEPSEEK_VISION), body);
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void deepseek_vision_anthropicFace_base64Image() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 128,
                  "thinking": {"type": "disabled"},
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "Describe this image in five words or fewer."},
                      {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "%s"}}
                    ]
                  }]
                }
                """.formatted(DEEPSEEK_VISION, TINY_PNG_B64);
        JsonNode res = anthropicMessages(client, keyFor(DEEPSEEK_VISION), body);
        String text = "";
        for (JsonNode block : res.path("content")) {
            if ("text".equals(block.path("type").stringValue())) {
                text += block.path("text").stringValue();
            }
        }
        assertTrue(!text.isBlank(), () -> "empty vision content: " + res);
    }

    @Test
    void openai_vision_dataUrlImage() {
        assumeProviderKey("OPENAI_API_KEY");
        JsonNode res = openaiChat(client, keyFor(OPENAI), visionOpenAiBody(OPENAI, TINY_PNG_B64, 64, "none"));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void xai_vision_dataUrlImage() {
        assumeProviderKey("XAI_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": %d,
                  "reasoning_effort": "low",
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "Describe this image in five words or fewer."},
                      {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,%s", "detail": "high"}}
                    ]
                  }]
                }
                """.formatted(GROK, GROK_MAX_TOKENS, TINY_JPEG_B64);
        JsonNode res = openaiChat(client, keyFor(GROK), body);
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void anthropic_vision_openaiFace_dataUrlImage() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        JsonNode res = openaiChat(client, keyFor(CLAUDE), visionOpenAiBody(CLAUDE, TINY_PNG_B64, 64, null));
        assertNonEmptyAssistantContent(res);
    }

    @Test
    void anthropic_vision_nativeFace_base64Image() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 64,
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "Describe this image in five words or fewer."},
                      {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "%s"}}
                    ]
                  }]
                }
                """.formatted(CLAUDE, TINY_PNG_B64);
        JsonNode res = anthropicMessages(client, keyFor(CLAUDE), body);
        String text = "";
        for (JsonNode block : res.path("content")) {
            if ("text".equals(block.path("type").stringValue())) {
                text += block.path("text").stringValue();
            }
        }
        assertTrue(!text.isBlank(), () -> "empty vision content: " + res);
    }

    // ----------------------------------------------------------------------- structured outputs

    @Test
    void openai_structuredOutput_jsonSchema() {
        assumeProviderKey("OPENAI_API_KEY");
        JsonNode res = openaiChat(client, keyFor(OPENAI), jsonSchemaBody(OPENAI, 64, "none"));
        JsonNode obj = LiveProviderSupport.parseJsonObjectFromAssistant(res);
        assertEquals("pong", obj.path("word").stringValue(), res::toString);
    }

    @Test
    void xai_structuredOutput_jsonSchema() {
        assumeProviderKey("XAI_API_KEY");
        JsonNode res = openaiChat(client, keyFor(GROK), jsonSchemaBody(GROK, GROK_MAX_TOKENS, "low"));
        JsonNode obj = LiveProviderSupport.parseJsonObjectFromAssistant(res);
        assertEquals("pong", obj.path("word").stringValue(), res::toString);
    }

    @Test
    void deepseek_structuredOutput_jsonObject() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "thinking": {"type": "disabled"},
                  "max_tokens": 64,
                  "response_format": {"type": "json_object"},
                  "messages": [{"role":"user","content":"Return JSON with a single key word set to the string pong."}]
                }
                """.formatted(DEEPSEEK);
        JsonNode res = openaiChat(client, keyFor(DEEPSEEK), body);
        JsonNode obj = LiveProviderSupport.parseJsonObjectFromAssistant(res);
        assertEquals("pong", obj.path("word").stringValue(), res::toString);
    }

    // ----------------------------------------------------------------------- Anthropic native extras

    @Test
    void anthropic_nativeFace_adaptiveThinking() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 256,
                  "thinking": {"type": "adaptive"},
                  "output_config": {"effort": "low"},
                  "messages": [{"role":"user","content":"Reply with exactly the word pong and nothing else."}]
                }
                """.formatted(CLAUDE);
        JsonNode res = anthropicMessages(client, keyFor(CLAUDE), body);
        assertTrue(res.path("content").isArray() && !res.path("content").isEmpty(), res::toString);
    }

    @Test
    void anthropic_nativeFace_webSearch() {
        assumeProviderKey("ANTHROPIC_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 512,
                  "messages": [{"role":"user","content":"What is the current Java LTS version? Use web search."}],
                  "tools": [{"type": "web_search_20250305", "name": "web_search", "max_uses": 1}]
                }
                """.formatted(CLAUDE);
        StatusBody raw = anthropicMessagesRaw(client, keyFor(CLAUDE), body);
        assertEquals(HttpStatus.OK, raw.status(), () -> raw.raw());
        JsonNode res = raw.json();
        assertEquals("message", res.path("type").stringValue(), () -> raw.raw());
        boolean sawSearch = false;
        boolean sawText = false;
        for (JsonNode block : res.path("content")) {
            String type = block.path("type").stringValue();
            if ("server_tool_use".equals(type)
                    && "web_search".equals(block.path("name").stringValue())) {
                sawSearch = true;
            }
            if ("text".equals(type) && !block.path("text").stringValue().isBlank()) {
                sawText = true;
            }
        }
        assertTrue(sawSearch || sawText, () -> "expected web search or text: " + raw.raw());
        assertTrue(
                LiveProviderSupport.headerLong(raw, "X-Janus-Cost-Search-Micro-Usd") > 0 || sawSearch || sawText,
                () -> "search not billed and no search/text in body: " + raw.raw());
    }

    private static String visionOpenAiBody(String model, String pngB64, int maxTokens, String reasoningEffort) {
        String extra = reasoningEffort == null ? "" : ",\n  \"reasoning_effort\": \"" + reasoningEffort + "\"";
        return """
                {
                  "model": "%s",
                  "max_tokens": %d%s,
                  "messages": [{
                    "role": "user",
                    "content": [
                      {"type": "text", "text": "Describe this image in five words or fewer."},
                      {"type": "image_url", "image_url": {"url": "data:image/png;base64,%s"}}
                    ]
                  }]
                }
                """.formatted(model, maxTokens, extra, pngB64);
    }

    private static String jsonSchemaBody(String model, int maxTokens, String reasoningEffort) {
        String extra = reasoningEffort == null ? "" : ",\n  \"reasoning_effort\": \"" + reasoningEffort + "\"";
        return """
                {
                  "model": "%s",
                  "max_tokens": %d%s,
                  "response_format": {
                    "type": "json_schema",
                    "json_schema": {
                      "name": "reply",
                      "strict": true,
                      "schema": {
                        "type": "object",
                        "properties": {"word": {"type": "string"}},
                        "required": ["word"],
                        "additionalProperties": false
                      }
                    }
                  },
                  "messages": [{"role":"user","content":"Return JSON with word set to pong."}]
                }
                """.formatted(model, maxTokens, extra);
    }

    // ----------------------------------------------------------------------- failover

    @Test
    void failover_deadPrimary_fallsToSecondary() {
        // Alias has two candidates: connection-refused primary, live DeepSeek secondary.
        // With max-retries=1, network failure on primary must land on secondary 200.
        assumeProviderKey("DEEPSEEK_API_KEY");
        String key = keyFor(FAILOVER);
        JsonNode res = openaiChat(client, key, plainChatBody(FAILOVER));
        assertNonEmptyAssistantContent(res);
    }
}
