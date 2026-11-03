package io.amscotti.janus.gateway;

import io.airlift.compress.zstd.ZstdInputStream;
import io.amscotti.janus.core.codec.DecisionCodecException;
import io.amscotti.janus.core.codec.SystemOneCodec;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import io.amscotti.janus.router.DecisionRouter;
import io.amscotti.janus.store.KeyRecord;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * System One face: {@code POST /v1/systemone} — the one client dialect for
 * decision models (docs/system-one.md). An app written against the Jev API
 * repoints its base URL here and selects the backend by {@code model} id.
 *
 * <p>The ladder mirrors the chat faces' non-streaming branch, minus
 * streaming (decisions never stream): decode → per-key scope check →
 * governance pre-dispatch → decision-router dispatch → encode-before-finalize
 * → settle + spend headers, with the same catch-path release/meter/ledger
 * backstop (exactly one Tier-1 call record per request). Errors use the
 * OpenAI envelope via {@link ErrorMapper} (the handler selects it for every
 * non-Anthropic face).
 */
@Controller("/v1")
class SystemOneController {

    private static final Logger LOG = LoggerFactory.getLogger(SystemOneController.class);

    /** Compressed request bodies are decompressed under the same 32 MiB ceiling. */
    private static final int MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024;

    private final DecisionRouter decisionRouter;
    private final Governance governance;
    private final MetricsRecorder metricsRecorder;
    private final SystemOneCodec codec = SystemOneCodec.create();
    private final ErrorMapper errorMapper = new ErrorMapper();

    SystemOneController(DecisionRouter decisionRouter, Governance governance, MetricsRecorder metricsRecorder) {
        this.decisionRouter = Objects.requireNonNull(decisionRouter, "decisionRouter");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
    }

    /**
     * The face is one path with two body envelopes: JSON (optionally zstd/gzip
     * compressed) and TypeSafe's multipart {@code request} + image parts. The
     * body is read raw and decoded/parsed in the gateway; Micronaut's multipart
     * binder is not used because it would see the SDK's zstd-compressed
     * multipart bytes before they can be decompressed.
     */
    @Post(
            value = "/systemone",
            consumes = {MediaType.APPLICATION_JSON, MediaType.MULTIPART_FORM_DATA},
            produces = MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    HttpResponse<String> systemOne(@Body @Nullable InputStream rawBody, HttpRequest<?> httpRequest) {
        byte[] decoded = decodedBytes(readBody(rawBody), httpRequest);
        String contentType = httpRequest.getHeaders().get("Content-Type");
        String json;
        if (isMultipart(contentType)) {
            json = multipartToJson(DecisionMultipartParser.parse(decoded, contentType));
        } else {
            json = decoded == null ? null : strictUtf8(decoded);
        }
        return handle(json, httpRequest);
    }

    private HttpResponse<String> handle(String rawBody, HttpRequest<?> httpRequest) {
        long startNanos = System.nanoTime();
        String keyId = keyIdOf(httpRequest);
        DecisionRequest request = null;
        Governance.Preflight preflight = Governance.Preflight.NONE;
        AtomicReference<String> dispatchedProvider = new AtomicReference<>();
        try {
            request = codec.decodeRequest(rawBody);
            request = withDangerZoneHeaders(request, httpRequest);
            KeyAuthFilter.checkScope(httpRequest, request.model());
            // Alias existence wins over pricing (require-priced must not turn the
            // documented unknown-alias 404 into a 400), but scope stays first so a
            // scoped key cannot use the status as an alias-existence oracle.
            if (!decisionRouter.knows(request.model())) {
                throw new io.amscotti.janus.router.UnknownModelException(request.model());
            }
            preflight = governance.enforceDecision(httpRequest, request);
            DecisionResponse response;
            try {
                response = decisionRouter.decide(request, backend -> dispatchedProvider.set(backend.name()));
            } catch (RuntimeException | Error e) {
                governance.release(preflight);
                throw e;
            }
            String body;
            try {
                // Encode BEFORE finalize — an encode failure must skip every
                // ledger side effect (the chat faces' exactly-once pin).
                body = codec.encodeResponse(response);
            } catch (RuntimeException | Error e) {
                governance.release(preflight);
                throw e;
            }
            Governance.Finalized finalized = governance.finalizeDecision(
                    request, response, preflight, elapsedMillis(startNanos), dispatchedProvider.get());
            // Best-effort like recordError: a throwing recorder must not turn a fully
            // accounted 200 into a 500 + a second ERROR ledger row (OK+ERROR for one
            // request breaks the exactly-once pin).
            try {
                metricsRecorder.recordRequest(
                        Face.SYSTEMONE.label(), HttpStatus.OK.getCode(), elapsedMillis(startNanos), keyId);
            } catch (RuntimeException e) {
                System.getLogger("io.amscotti.janus.gateway.SystemOneController")
                        .log(
                                System.Logger.Level.WARNING,
                                "metrics recording dropped (recorder failure): {0}",
                                e.toString());
            }
            MutableHttpResponse<String> http = HttpResponse.ok(body).contentType(MediaType.APPLICATION_JSON);
            return withBudgetHeaders(http, finalized);
        } catch (RuntimeException | Error e) {
            governance.release(preflight);
            recordError(startNanos, keyId, e);
            governance.recordDecisionFailure(
                    httpRequest, preflight, request, statusOf(e), elapsedMillis(startNanos), dispatchedProvider.get());
            throw e;
        }
    }

    private static byte[] readBody(InputStream rawBody) {
        if (rawBody == null) {
            return null;
        }
        try {
            return rawBody.readAllBytes();
        } catch (IOException e) {
            throw new DecisionCodecException(
                    DecisionCodecException.TYPE_INVALID_REQUEST, "failed to read the request body");
        }
    }

    /** Decode the wire body: identity, gzip (possibly already decompressed by Netty), or zstd. */
    private static byte[] decodedBytes(byte[] rawBody, HttpRequest<?> httpRequest) {
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        String encoding = httpRequest.getHeaders().get("Content-Encoding");
        if (encoding == null || encoding.isBlank() || "identity".equalsIgnoreCase(encoding.trim())) {
            return rawBody;
        }
        return switch (encoding.trim().toLowerCase(Locale.ROOT)) {
            case "zstd" -> unzstd(rawBody);
            case "gzip" -> isGzip(rawBody) ? gunzip(rawBody) : rawBody;
            default ->
                throw new HttpStatusException(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Content-Encoding \"" + encoding + "\" is not supported on /v1/systemone;"
                                + " supported: identity, gzip, zstd");
        };
    }

    private static boolean isMultipart(String contentType) {
        return contentType != null
                && contentType.trim().toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
    }

    private String multipartToJson(List<DecisionMultipartParser.Part> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new DecisionCodecException(
                    DecisionCodecException.TYPE_INVALID_REQUEST, "multipart body has no parts");
        }
        String requestJson = null;
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (DecisionMultipartParser.Part part : parts) {
            if ("request".equals(part.name())) {
                if (requestJson != null) {
                    throw new DecisionCodecException(
                            DecisionCodecException.TYPE_INVALID_REQUEST,
                            "multipart body must carry exactly one `request` part");
                }
                requestJson = strictUtf8(part.data());
                continue;
            }
            if (part.filename() == null) {
                throw new DecisionCodecException(
                        DecisionCodecException.TYPE_INVALID_REQUEST,
                        "multipart part \"" + part.name() + "\" must be a file part (only `request` is text)");
            }
            if (files.put(part.name(), part.data()) != null) {
                throw new DecisionCodecException(
                        DecisionCodecException.TYPE_INVALID_REQUEST,
                        "duplicate multipart file part \"" + part.name() + "\"");
            }
        }
        if (requestJson == null) {
            throw new DecisionCodecException(
                    DecisionCodecException.TYPE_INVALID_REQUEST, "multipart body must carry the JSON `request` part");
        }
        return codec.inlineMultipartAssets(requestJson, files);
    }

    private static byte[] unzstd(byte[] raw) {
        try (InputStream in = new ZstdInputStream(new ByteArrayInputStream(raw))) {
            return readBounded(in);
        } catch (HttpStatusException | ContentLengthExceededException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DecisionCodecException(DecisionCodecException.TYPE_INVALID_REQUEST, "invalid zstd request body");
        }
    }

    private static byte[] gunzip(byte[] raw) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return readBounded(in);
        } catch (HttpStatusException | ContentLengthExceededException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DecisionCodecException(DecisionCodecException.TYPE_INVALID_REQUEST, "invalid gzip request body");
        }
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_DECOMPRESSED_BYTES) {
                throw new ContentLengthExceededException("decompressed request body exceeds 32 MiB");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean isGzip(byte[] raw) {
        return raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new DecisionCodecException(
                    DecisionCodecException.TYPE_INVALID_REQUEST, "request body is not valid UTF-8");
        }
    }

    private int statusOf(Throwable throwable) {
        return errorMapper.map(throwable).status().getCode();
    }

    /**
     * Forward TypeSafe's danger-zone beta gates (images, long context) through the
     * canonical request's gateway-internal meta. The systemone adapter adds the
     * headers upstream; the images gate is also derived from assets/Box bodies.
     */
    private static DecisionRequest withDangerZoneHeaders(DecisionRequest request, HttpRequest<?> httpRequest) {
        boolean images = headerTrue(httpRequest, "TypeSafe-Danger-Zone-Images");
        boolean longContext = headerTrue(httpRequest, "TypeSafe-Danger-Zone-Long-Context");
        if (!images && !longContext) {
            return request;
        }
        java.util.Map<String, Object> meta = new java.util.HashMap<>();
        if (images) {
            meta.put(DecisionRequest.META_DANGER_ZONE_IMAGES, true);
        }
        if (longContext) {
            meta.put(DecisionRequest.META_DANGER_ZONE_LONG_CONTEXT, true);
        }
        return request.withMeta(meta);
    }

    private static boolean headerTrue(HttpRequest<?> request, String name) {
        String value = request.getHeaders().get(name);
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    private void recordError(long startNanos, String keyId, Throwable throwable) {
        try {
            metricsRecorder.recordRequest(
                    Face.SYSTEMONE.label(), statusOf(throwable), elapsedMillis(startNanos), keyId);
        } catch (RuntimeException e) {
            LOG.warn("metrics recording dropped (recorder failure): {}", e.toString());
        }
    }

    private static String keyIdOf(HttpRequest<?> httpRequest) {
        return httpRequest
                .getAttribute(KeyAuthFilter.KEY_ATTRIBUTE, KeyRecord.class)
                .map(KeyRecord::id)
                .orElse(null);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** Spend headers on a successful response (the chat faces' header contract). */
    private static MutableHttpResponse<String> withBudgetHeaders(
            MutableHttpResponse<String> response, Governance.Finalized finalized) {
        MutableHttpResponse<String> out = response;
        if (finalized != Governance.Finalized.NONE && finalized.cost() != null) {
            var cost = finalized.cost();
            out = out.header(Governance.HEADER_COST_INPUT, String.valueOf(cost.inputMicroUsd()))
                    .header(Governance.HEADER_COST_OUTPUT, String.valueOf(cost.outputMicroUsd()))
                    .header(Governance.HEADER_COST_CACHE_READ, String.valueOf(cost.cacheReadMicroUsd()))
                    .header(Governance.HEADER_COST_CACHE_CREATION, String.valueOf(cost.cacheCreationMicroUsd()))
                    .header(Governance.HEADER_COST_SEARCH, String.valueOf(cost.searchMicroUsd()))
                    .header(Governance.HEADER_COST_TOTAL, String.valueOf(cost.totalMicroUsd()));
        }
        if (!finalized.softExceeded()) {
            return out;
        }
        out = out.header(Governance.HEADER_BUDGET_WARNING, Governance.WARNING_SOFT);
        if (finalized.usedMicroUsd() != null) {
            out = out.header(Governance.HEADER_BUDGET_USED, String.valueOf(finalized.usedMicroUsd()));
        }
        return out;
    }
}
