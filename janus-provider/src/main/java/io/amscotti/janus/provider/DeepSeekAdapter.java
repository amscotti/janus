package io.amscotti.janus.provider;

import java.time.Duration;

/**
 * DeepSeek chat-completions provider — the OpenAI-format
 * passthrough, re-implemented as a thin named subclass of {@link
 * OpenAiCompatibleAdapter} so every call site ({@code RouterFactory} case, tests,
 * services file) keeps working unchanged. Public API and behavior are identical to;
 * the implementation (endpoint, wire decisions, error mapping, streaming) lives in the
 * superclass and is shared with the other OpenAI-format upstreams (OpenRouter, xAI,
 * Ollama).
 *
 * <p>Thread-safe: the superclass builds one {@code HttpClient} and one codec at
 * construction; a single instance serves concurrent requests.
 */
public final class DeepSeekAdapter extends OpenAiCompatibleAdapter {

    public static final String NAME = "deepseek";
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    /** Composition-root form: resolved base URL + API key (/). */
    public DeepSeekAdapter(String baseUrl, String apiKey) {
        super(NAME, baseUrl, apiKey);
    }

    /**
     * Timeout-aware composition-root form: explicit connect, header-arrival and
     * body-read deadlines — the {@code [janus.timeouts]} threading leg the {@code
     * RouterFactory} calls (a present-but-default section reproduces the three
     * constants exactly). Public since made the deadlines operator-tunable;
     * positivity is validated by the superclass form it delegates to.
     */
    public DeepSeekAdapter(
            String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout, Duration bodyReadTimeout) {
        super(NAME, baseUrl, apiKey, connectTimeout, requestTimeout, bodyReadTimeout);
    }

    /** ServiceLoader discovery form — inert by shape: blank base, no credentials (the
     * blank base makes the superclass {@code endpoint} guard fail fast if the instance
     * is ever misused for a call). Must not be used for calls. */
    public DeepSeekAdapter() {
        super(NAME);
    }

    /** Test/internal form with explicit timeouts ( contract, package-private). */
    DeepSeekAdapter(String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout) {
        super(NAME, baseUrl, apiKey, connectTimeout, requestTimeout);
    }
}
