package io.amscotti.janus.gateway.live;

import static io.amscotti.janus.gateway.live.LiveProviderSupport.LIVE_OPT_IN;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.anthropicMessages;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.anthropicStream;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.assertNonEmptyAssistantContent;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.assumeProviderKey;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.openaiChat;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.plainChatBody;
import static io.amscotti.janus.gateway.live.LiveProviderSupport.virtualKey;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

/**
 * DeepSeek's Anthropic-format endpoint ({@code https://api.deepseek.com/anthropic})
 * through Janus's Anthropic adapter. Isolated from {@link LiveProviderIT} so the
 * alias {@code deepseek-v4-flash} is sent upstream unchanged (no remap) and is
 * not pooled with the OpenAI-compatible DeepSeek row.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = LIVE_OPT_IN, matches = "1")
@MicronautTest
@Property(name = "janus.test.production-factories", value = "true")
@Property(name = "micronaut.http.client.read-timeout", value = "120s")
@Property(name = "janus.keys.master-key-env", value = "JANUS_MASTER_KEY")
@Property(name = "janus.providers.deepseek-anthropic.wire-format", value = "anthropic")
@Property(name = "janus.providers.deepseek-anthropic.base-url", value = "https://api.deepseek.com/anthropic")
@Property(name = "janus.providers.deepseek-anthropic.api-key-env", value = "DEEPSEEK_API_KEY")
@Property(name = "janus.model-list[0].name", value = "deepseek-v4-flash")
@Property(name = "janus.model-list[0].provider", value = "deepseek-anthropic")
@Property(name = "janus.model-list[0].api-key-env", value = "DEEPSEEK_API_KEY")
@Property(name = "janus.model-list[0].base-url", value = "https://api.deepseek.com/anthropic")
@Property(name = "janus.pricing.models[0].name", value = "deepseek-v4-flash")
@Property(name = "janus.pricing.models[0].input-per-1k", value = "0.00044")
@Property(name = "janus.pricing.models[0].output-per-1k", value = "0.00132")
@Property(name = "janus.pricing.models[0].default-max-tokens", value = "4096")
class DeepSeekAnthropicLiveIT {

    private static final String FLASH = "deepseek-v4-flash";
    private static final String THINKING_OFF = "\"thinking\": {\"type\": \"disabled\"}";
    /**
     * DeepSeek V4 Flash burns completion tokens on reasoning even when thinking is
     * requested off (observed live: a thinking-disabled 32-token call finished
     * {@code length} with empty content — the whole budget went to reasoning).
     * Match the suite's reasoning-model headroom.
     */
    private static final int MAX_TOKENS = 512;

    @Inject
    EmbeddedServer server;

    @Inject
    @Client("/")
    HttpClient client;

    private String key() {
        return virtualKey(client, List.of(FLASH));
    }

    @Test
    void anthropicFace_nonStream() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": %d,
                  "thinking": {"type": "disabled"},
                  "messages": [{"role":"user","content":"Reply with exactly the word pong and nothing else."}]
                }
                """.formatted(FLASH, MAX_TOKENS);
        JsonNode res = anthropicMessages(client, key(), body);
        String text = "";
        for (JsonNode block : res.path("content")) {
            if ("text".equals(block.path("type").stringValue())) {
                text += block.path("text").stringValue();
            }
        }
        assertTrue(!text.isBlank(), () -> "empty Anthropic-format DeepSeek content: " + res);
    }

    @Test
    void anthropicFace_stream() throws Exception {
        assumeProviderKey("DEEPSEEK_API_KEY");
        String body = """
                {
                  "model": "%s",
                  "max_tokens": %d,
                  "stream": true,
                  "thinking": {"type": "disabled"},
                  "messages": [{"role":"user","content":"Reply with exactly the word pong and nothing else."}]
                }
                """.formatted(FLASH, MAX_TOKENS);
        String sse = anthropicStream(server, key(), body);
        assertTrue(sse.contains("message_stop") || sse.contains("data:"), sse);
    }

    @Test
    void openaiFace_crossFormatToAnthropicAdapter() {
        assumeProviderKey("DEEPSEEK_API_KEY");
        JsonNode res = openaiChat(client, key(), plainChatBody(FLASH, MAX_TOKENS, THINKING_OFF));
        assertNonEmptyAssistantContent(res);
    }
}
