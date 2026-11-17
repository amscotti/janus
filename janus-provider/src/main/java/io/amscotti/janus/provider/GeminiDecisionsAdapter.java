package io.amscotti.janus.provider;

import io.amscotti.janus.core.codec.GeminiDecisionsCodec;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import java.net.URI;
import java.time.Duration;

/**
 * Google Gemini Decision 1 upstream — translates canonical decisions to
 * {@code POST {baseUrl}/v1beta/decisions} through {@link GeminiDecisionsCodec}.
 * Fixed family name ({@value #NAME}); authenticates with {@code x-goog-api-key}
 * and dispatches verbatim when {@code baseUrl} already ends in {@code /decisions}.
 */
public final class GeminiDecisionsAdapter extends AbstractDecisionAdapter {

    /** ServiceLoader / router name. */
    public static final String NAME = "gemini-decisions";

    /** Google AI Studio production base URL (the adapter appends {@code /v1beta/decisions}). */
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    /** The Beta decisions path appended to a normalized base. */
    public static final String DECISIONS_PATH = "/v1beta/decisions";

    private static final String FULL_ENDPOINT_SUFFIX = "/decisions";

    private final GeminiDecisionsCodec codec = GeminiDecisionsCodec.create();

    /** ServiceLoader discovery form — inert (blank base/secret); must not be used for calls. */
    public GeminiDecisionsAdapter() {
        super(NAME, ProviderAuth.TYPE_X_GOOG_API_KEY);
    }

    /** Composition-root form: resolved base URL + API key. */
    public GeminiDecisionsAdapter(String baseUrl, String apiKey) {
        super(NAME, baseUrl, ProviderAuth.TYPE_X_GOOG_API_KEY, apiKey);
    }

    /** Timeout-aware composition-root form (the {@code [janus.timeouts]} threading leg). */
    public GeminiDecisionsAdapter(
            String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout, Duration bodyReadTimeout) {
        super(NAME, baseUrl, ProviderAuth.TYPE_X_GOOG_API_KEY, apiKey, connectTimeout, requestTimeout, bodyReadTimeout);
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
