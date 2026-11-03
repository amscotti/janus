package io.amscotti.janus.gateway;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import java.util.Locale;
import java.util.Set;
import org.reactivestreams.Publisher;

/**
 * The decision face accepts identity, gzip, and zstd request encodings (the
 * TypeSafe SDKs default to zstd). gzip is normally decompressed by Netty before
 * the controller; zstd is decompressed by the controller under the normal
 * 32 MiB ceiling. Anything else is rejected before the body is read, with the
 * OpenAI envelope.
 */
@Filter("/v1/systemone")
final class DecisionEncodingFilter implements HttpServerFilter {

    private static final Set<String> SUPPORTED = Set.of("identity", "gzip", "zstd");

    private static final String BODY =
            "{\"error\":{\"message\":\"Content-Encoding or Content-Type is not supported on /v1/systemone;"
                    + " supported encodings: identity, gzip, zstd; supported types: application/json,"
                    + " multipart/form-data\",\"type\":\"invalid_request_error\","
                    + "\"param\":null,\"code\":null}}";

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String encoding = request.getHeaders().get("Content-Encoding");
        if (encoding != null && !encoding.isBlank()) {
            String normalized = encoding.trim().toLowerCase(Locale.ROOT);
            if (!SUPPORTED.contains(normalized)) {
                return reject();
            }
        }
        String contentType = request.getHeaders().get("Content-Type");
        if (contentType != null && !contentType.isBlank()) {
            String normalized = contentType.trim().toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("application/json") && !normalized.startsWith("multipart/form-data")) {
                return reject();
            }
        }
        return chain.proceed(request);
    }

    private static Publisher<MutableHttpResponse<?>> reject() {
        MutableHttpResponse<String> rejected = io.micronaut.http.HttpResponse.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BODY);
        return Publishers.just(rejected);
    }
}
