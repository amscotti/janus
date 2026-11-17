package io.amscotti.janus.gateway;

import io.amscotti.janus.gateway.dto.OpenAiErrorEnvelope;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The decision face accepts identity, gzip, and zstd request encodings (the
 * TypeSafe SDKs default to zstd). gzip is normally decompressed by Netty before
 * the controller; zstd is decompressed by the controller under the normal
 * 32 MiB ceiling. Anything else is rejected before the body is read, with the
 * OpenAI envelope.
 *
 * <p>Runs explicitly in {@link ServerFilterPhase#SECURITY} right <em>after</em>
 * {@link KeyAuthFilter} so unauthenticated requests fail 401 before encoding/media-type
 * inspection, matches normalized {@code /v1/systemone} paths on {@code POST}, and
 * records rejected requests to {@link MetricsRecorder}.
 */
@Filter("/**")
final class DecisionEncodingFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionEncodingFilter.class);
    private static final Set<String> SUPPORTED_ENCODINGS = Set.of("identity", "gzip", "zstd");
    private static final String UNSUPPORTED_MESSAGE =
            "Content-Encoding or Content-Type is not supported on /v1/systemone; supported encodings: identity, gzip,"
                    + " zstd; supported types: application/json, multipart/form-data";

    private final MetricsRecorder metricsRecorder;

    @Inject
    DecisionEncodingFilter(MetricsRecorder metricsRecorder) {
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.after();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        if (request.getMethod() != HttpMethod.POST) {
            return chain.proceed(request);
        }
        String normalizedPath = KeyAuthFilter.normalizePath(request.getUri().getPath());
        if (Face.of(normalizedPath).orElse(null) != Face.SYSTEMONE) {
            return chain.proceed(request);
        }
        long startNanos = System.nanoTime();
        if (parseSupportedEncoding(request.getHeaders()).isEmpty()) {
            return rejectAndRecord(request, startNanos);
        }
        String contentType = request.getHeaders().get("Content-Type");
        if (contentType != null && !contentType.isBlank()) {
            String essence = mediaTypeEssence(contentType);
            if (!"application/json".equals(essence) && !"multipart/form-data".equals(essence)) {
                return rejectAndRecord(request, startNanos);
            }
        }
        return chain.proceed(request);
    }

    /**
     * Resolve a single supported {@code Content-Encoding} across all header lines and
     * comma-separated tokens. Returns {@code "identity"} when absent/blank, or empty if
     * unsupported or stacked/ambiguous.
     */
    static Optional<String> parseSupportedEncoding(HttpHeaders headers) {
        List<String> values = headers.getAll("Content-Encoding");
        if (values == null || values.isEmpty()) {
            return Optional.of("identity");
        }
        List<String> tokens = new ArrayList<>();
        for (String header : values) {
            if (header == null || header.isBlank()) {
                continue;
            }
            for (String part : header.split(",", -1)) {
                String trimmed = part.trim().toLowerCase(Locale.ROOT);
                if (trimmed.isEmpty()) {
                    return Optional.empty();
                }
                tokens.add(trimmed);
            }
        }
        if (tokens.isEmpty()) {
            return Optional.of("identity");
        }
        if (tokens.size() > 1) {
            return Optional.empty();
        }
        String single = tokens.get(0);
        return SUPPORTED_ENCODINGS.contains(single) ? Optional.of(single) : Optional.empty();
    }

    /** Extract the exact lowercase media type essence before any {@code ;} parameter. */
    static String mediaTypeEssence(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String essence = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return essence.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isMultipartContentType(String contentType) {
        return "multipart/form-data".equals(mediaTypeEssence(contentType));
    }

    private Publisher<MutableHttpResponse<?>> rejectAndRecord(HttpRequest<?> request, long startNanos) {
        try {
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            metricsRecorder.recordRequest(
                    Face.SYSTEMONE.label(),
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE.getCode(),
                    durationMillis,
                    ModelFaceControllerSupport.keyIdOf(request));
        } catch (RuntimeException e) {
            LOG.warn("metrics recording dropped on 415 (recorder failure): {}", e.toString());
        }
        String body = GatewayJson.errorBody(
                new OpenAiErrorEnvelope(UNSUPPORTED_MESSAGE, ErrorMapper.TYPE_INVALID_REQUEST, null, null));
        MutableHttpResponse<String> rejected = io.micronaut.http.HttpResponse.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        return Publishers.just(rejected);
    }
}
