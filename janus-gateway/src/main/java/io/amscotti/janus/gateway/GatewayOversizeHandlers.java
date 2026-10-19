package io.amscotti.janus.gateway;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.exceptions.BufferLengthExceededException;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.http.server.exceptions.BufferLengthExceededHandler;
import io.micronaut.http.server.exceptions.ContentLengthExceededHandler;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

/**
 * Oversize-request handlers. Micronaut ships dedicated
 * {@code ContentLengthExceededHandler}/{@code BufferLengthExceededHandler}
 * beans that are <em>more specific</em> than the gateway's
 * {@code ExceptionHandler<Throwable, …>} ({@link GatewayExceptionHandler}),
 * so a body rejected for size during Netty body binding got the framework's
 * default problem body instead of the per-face error envelope. These
 * {@code @Replaces} handlers delegate to the gateway handler so a 413
 * carries the same Anthropic/OpenAI envelope split as every other error.
 */
final class GatewayOversizeHandlers {

    private GatewayOversizeHandlers() {}

    /** The one gateway error rendering both handlers delegate to. */
    static HttpResponse<String> render(HttpRequest<?> request, Throwable throwable) {
        return new GatewayExceptionHandler().handle(request, throwable);
    }
}

/** 413 for a Content-Length over {@code micronaut.server.max-request-size}. */
@Singleton
@Replaces(ContentLengthExceededHandler.class)
@Produces(MediaType.APPLICATION_JSON)
class GatewayContentLengthExceededHandler
        implements ExceptionHandler<ContentLengthExceededException, HttpResponse<String>> {

    @Override
    @SuppressWarnings("rawtypes")
    public HttpResponse<String> handle(HttpRequest request, ContentLengthExceededException exception) {
        return GatewayOversizeHandlers.render(request, exception);
    }
}

/** 413 for a streamed body over {@code micronaut.server.max-request-buffer-size}. */
@Singleton
@Replaces(BufferLengthExceededHandler.class)
@Produces(MediaType.APPLICATION_JSON)
class GatewayBufferLengthExceededHandler
        implements ExceptionHandler<BufferLengthExceededException, HttpResponse<String>> {

    @Override
    @SuppressWarnings("rawtypes")
    public HttpResponse<String> handle(HttpRequest request, BufferLengthExceededException exception) {
        return GatewayOversizeHandlers.render(request, exception);
    }
}
