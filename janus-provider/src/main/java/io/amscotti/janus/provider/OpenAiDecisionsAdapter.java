package io.amscotti.janus.provider;

import io.amscotti.janus.core.codec.OpenAiDecisionsCodec;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import java.net.URI;
import java.time.Duration;

/**
 * OpenAI Decisions upstream — the translation-dialect decision adapter
 * ({@code POST {baseUrl}/v1/decisions} through {@link OpenAiDecisionsCodec}).
 * Fixed name (one OpenAI decisions endpoint family); the operator chooses
 * the base URL, so an OpenAI-compatible decisions host works by config.
 * Bearer auth; a base already ending in {@code /decisions} is dispatched
 * verbatim.
 */
public final class OpenAiDecisionsAdapter extends AbstractDecisionAdapter {

    /** ServiceLoader / router name. */
    public static final String NAME = "openai-decisions";

    /** OpenAI's production base URL (the adapter appends {@code /v1/decisions}). */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";

    /** The versioned decisions path appended to a normalized base. */
    public static final String DECISIONS_PATH = "/v1/decisions";

    private static final String FULL_ENDPOINT_SUFFIX = "/decisions";

    private final OpenAiDecisionsCodec codec = OpenAiDecisionsCodec.create();

    /** ServiceLoader discovery form — inert (blank base/secret); must not be used for calls. */
    public OpenAiDecisionsAdapter() {
        super(NAME);
    }

    /** Composition-root form: resolved base URL + API key. */
    public OpenAiDecisionsAdapter(String baseUrl, String apiKey) {
        super(NAME, baseUrl, apiKey);
    }

    /** Timeout-aware composition-root form (the {@code [janus.timeouts]} threading leg). */
    public OpenAiDecisionsAdapter(
            String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout, Duration bodyReadTimeout) {
        super(NAME, baseUrl, apiKey, connectTimeout, requestTimeout, bodyReadTimeout);
    }

    @Override
    protected URI endpoint(String baseUrl) {
        boolean verbatim = baseUrl.length() >= FULL_ENDPOINT_SUFFIX.length()
                && baseUrl.regionMatches(
                        true,
                        baseUrl.length() - FULL_ENDPOINT_SUFFIX.length(),
                        FULL_ENDPOINT_SUFFIX,
                        0,
                        FULL_ENDPOINT_SUFFIX.length());
        return URI.create(verbatim ? baseUrl : baseUrl + DECISIONS_PATH);
    }

    @Override
    protected String encode(DecisionRequest request) {
        return codec.encodeRequest(request);
    }

    @Override
    protected DecisionResponse decode(String body) {
        return codec.decodeResponse(body);
    }
}
