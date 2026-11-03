package io.amscotti.janus.provider;

import io.amscotti.janus.core.codec.SystemOneCodec;
import io.amscotti.janus.core.model.DecisionQuestion;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * System One decision upstream — the generic Jev-dialect adapter: one class
 * serves TypeSafe, OpenRouter's System One surface, Ollama (local and
 * cloud), Cloudflare Clef, and any other {@code /v1/systemone} server; the
 * operator's provider name is a constructor parameter (the
 * {@link OpenAiCompatibleAdapter} pattern).
 *
 * <p><b>Endpoint.</b> {@code POST {baseUrl}/v1/systemone} on the normalized
 * base, with two verbatim forms: a base already ending in {@code /systemone}
 * is the exact endpoint, and a Cloudflare Workers AI run URL (a base
 * containing {@code /ai/run/} — the model id rides the path there) is
 * dispatched as given. Bearer auth; a blank secret omits the header (local
 * upstreams need none).
 */
public final class SystemOneAdapter extends AbstractDecisionAdapter {

    /** ServiceLoader discovery name — the generic System One family key. */
    public static final String NAME = "systemone";

    /** The versioned System One path appended to a normalized base. */
    public static final String SYSTEMONE_PATH = "/v1/systemone";

    private static final String FULL_ENDPOINT_SUFFIX = "/systemone";
    static final String CLOUDFLARE_RUN_MARKER = "/ai/run/";

    private final SystemOneCodec codec = SystemOneCodec.create();

    /** ServiceLoader discovery form — inert (blank base/secret); must not be used for calls. */
    public SystemOneAdapter() {
        super(NAME);
    }

    /** Composition-root form: provider name + resolved base URL + API key. */
    public SystemOneAdapter(String name, String baseUrl, String apiKey) {
        super(name, baseUrl, apiKey);
    }

    /** Timeout-aware composition-root form (the {@code [janus.timeouts]} threading leg). */
    public SystemOneAdapter(
            String name,
            String baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration bodyReadTimeout) {
        super(name, baseUrl, apiKey, connectTimeout, requestTimeout, bodyReadTimeout);
    }

    @Override
    protected URI endpoint(String baseUrl) {
        boolean verbatim = baseUrl.length() >= FULL_ENDPOINT_SUFFIX.length()
                        && baseUrl.regionMatches(
                                true,
                                baseUrl.length() - FULL_ENDPOINT_SUFFIX.length(),
                                FULL_ENDPOINT_SUFFIX,
                                0,
                                FULL_ENDPOINT_SUFFIX.length())
                || baseUrl.contains(CLOUDFLARE_RUN_MARKER);
        return URI.create(verbatim ? baseUrl : baseUrl + SYSTEMONE_PATH);
    }

    @Override
    protected String encode(DecisionRequest request) {
        return codec.encodeRequest(request);
    }

    @Override
    protected void addHeaders(HttpRequest.Builder builder, DecisionRequest request) {
        // TypeSafe's danger-zone beta gates are request headers, not body fields.
        // The client may send them through Janus (controller → meta), or a body
        // using TypeSafe's assets/Box shape may omit them: derive the images gate
        // so a drop-in SDK request never reaches TypeSafe disabled.
        boolean imageEvidence = Boolean.TRUE.equals(request.meta().get(DecisionRequest.META_DANGER_ZONE_IMAGES))
                || request.extras().containsKey("assets")
                || request.questions().values().stream().anyMatch(q -> q instanceof DecisionQuestion.BoxQuestion);
        if (imageEvidence) {
            builder.header("TypeSafe-Danger-Zone-Images", "true");
        }
        if (Boolean.TRUE.equals(request.meta().get(DecisionRequest.META_DANGER_ZONE_LONG_CONTEXT))) {
            builder.header("TypeSafe-Danger-Zone-Long-Context", "true");
        }
    }

    @Override
    protected DecisionResponse decode(String body) {
        String payload = baseUrl().contains(CLOUDFLARE_RUN_MARKER) ? codec.unwrapCloudflareEnvelope(body) : body;
        return codec.decodeResponse(payload);
    }
}
