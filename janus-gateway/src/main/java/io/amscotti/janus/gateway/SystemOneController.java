package io.amscotti.janus.gateway;

import io.airlift.compress.zstd.ZstdInputStream;
import io.amscotti.janus.core.codec.DecisionCodecException;
import io.amscotti.janus.core.codec.SystemOneCodec;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import io.amscotti.janus.router.DecisionRouter;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

/**
 * System One face: {@code POST /v1/systemone} — the one client dialect for
 * decision models (docs/system-one.md). An app written against the Jev API
 * repoints its base URL here and selects the backend by {@code model} id.
 *
 * <p>The ladder shares preflight/catch/budget-header/metrics helpers via
 * {@link ModelFaceControllerSupport}: ingress read/decompression/multipart decode →
 * canonical decode → per-key scope check → governance pre-dispatch → decision-router
 * dispatch → encode-before-finalize → settle + spend headers, with catch-path
 * release/meter/ledger backstop (exactly one Tier-1 call record per request).
 */
@Controller("/v1")
class SystemOneController extends ModelFaceControllerSupport {

    /** Compressed request bodies are decompressed under the same 32 MiB ceiling. */
    static final int MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024;

    private static final int READ_CHUNK_SIZE = 64 * 1024;

    private final DecisionRouter decisionRouter;
    private final SystemOneCodec codec = SystemOneCodec.create();
    private final ErrorMapper errorMapper = new ErrorMapper();

    SystemOneController(DecisionRouter decisionRouter, Governance governance, MetricsRecorder metricsRecorder) {
        super(governance, metricsRecorder);
        this.decisionRouter = Objects.requireNonNull(decisionRouter, "decisionRouter");
    }

    /**
     * The face is one path with two body envelopes: JSON (optionally zstd/gzip
     * compressed) and TypeSafe's multipart {@code request} + image parts. The
     * body is read as an {@link InputStream} and decoded/decompressed inside
     * {@link #handle} so ingress failures still run the catch-path metrics and
     * call-ledger recording and the stream is always closed.
     */
    @Post(
            value = "/systemone",
            consumes = {MediaType.APPLICATION_JSON, MediaType.MULTIPART_FORM_DATA},
            produces = MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    HttpResponse<String> systemOne(@Body @Nullable InputStream rawBody, HttpRequest<?> httpRequest) {
        return handle(rawBody, httpRequest);
    }

    private HttpResponse<String> handle(@Nullable InputStream rawBody, HttpRequest<?> httpRequest) {
        long startNanos = System.nanoTime();
        String keyId = keyIdOf(httpRequest);
        DecisionRequest request = null;
        Governance.Preflight preflight = Governance.Preflight.NONE;
        AtomicReference<String> dispatchedProvider = new AtomicReference<>();
        try {
            String rawJson = decodeIngressBody(rawBody, httpRequest);
            request = codec.decodeRequest(rawJson);
            request = withDangerZoneHeaders(request, httpRequest);
            KeyAuthFilter.checkScope(httpRequest, request.model());
            // Alias existence wins over pricing (require-priced must not turn the
            // documented unknown-alias 404 into a 400), but scope stays first so a
            // scoped key cannot use the status as an alias-existence oracle.
            if (!decisionRouter.knows(request.model())) {
                throw new io.amscotti.janus.router.UnknownModelException(request.model());
            }
            preflight = governance().enforceDecision(httpRequest, request);
            DecisionResponse response;
            try {
                response = decisionRouter.decide(request, backend -> dispatchedProvider.set(backend.name()));
            } catch (RuntimeException | Error e) {
                governance().release(preflight);
                throw e;
            }
            String body;
            try {
                // Encode BEFORE finalize — an encode failure must skip every
                // ledger side effect (the chat faces' exactly-once pin).
                body = codec.encodeResponse(response);
            } catch (RuntimeException | Error e) {
                governance().release(preflight);
                throw e;
            }
            Governance.Finalized finalized = governance()
                    .finalizeDecision(
                            request, response, preflight, elapsedMillis(startNanos), dispatchedProvider.get());
            return okWithBudgetHeaders(body, finalized, startNanos, keyId);
        } catch (RuntimeException | Error e) {
            governance().release(preflight);
            recordError(startNanos, keyId, e);
            governance()
                    .recordDecisionFailure(
                            httpRequest,
                            preflight,
                            request,
                            statusOf(e),
                            elapsedMillis(startNanos),
                            dispatchedProvider.get());
            throw e;
        }
    }

    @Override
    protected String face() {
        return Face.SYSTEMONE.label();
    }

    @Override
    protected int statusOf(Throwable throwable) {
        return errorMapper.map(throwable).status().getCode();
    }

    private String decodeIngressBody(@Nullable InputStream rawBody, HttpRequest<?> httpRequest) {
        byte[] decoded = readDecodedBytes(rawBody, httpRequest);
        String contentType = httpRequest.getHeaders().get("Content-Type");
        if (DecisionEncodingFilter.isMultipartContentType(contentType)) {
            return multipartToJson(DecisionMultipartParser.parse(decoded, contentType));
        }
        return decoded == null ? null : strictUtf8(decoded);
    }

    /** Decode the wire stream: identity, gzip (possibly already decompressed by Netty), or zstd. */
    private static byte[] readDecodedBytes(@Nullable InputStream rawBody, HttpRequest<?> httpRequest) {
        if (rawBody == null) {
            return null;
        }
        String encoding = DecisionEncodingFilter.parseSupportedEncoding(httpRequest.getHeaders())
                .orElseThrow(() -> {
                    String rawHeader =
                            String.join(", ", httpRequest.getHeaders().getAll("Content-Encoding"));
                    return new HttpStatusException(
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "Content-Encoding \"" + ErrorMapper.sanitizeClientText(rawHeader)
                                    + "\" is not supported on /v1/systemone; supported: identity, gzip, zstd");
                });
        try (InputStream stream = rawBody) {
            PushbackInputStream pb = new PushbackInputStream(stream, 2);
            byte[] magic = new byte[2];
            int magicLen = readUpTo(pb, magic);
            if (magicLen == 0) {
                return null;
            }
            pb.unread(magic, 0, magicLen);
            return switch (encoding) {
                case "identity" -> readBoundedIdentity(pb);
                case "zstd" -> unzstd(pb);
                case "gzip" -> (magicLen == 2 && isGzipMagic(magic)) ? gunzip(pb) : readBoundedIdentity(pb);
                default -> throw new IllegalStateException("unexpected encoding: " + encoding);
            };
        } catch (HttpStatusException | ContentLengthExceededException | DecisionCodecException e) {
            throw e;
        } catch (IOException e) {
            throw new DecisionCodecException(
                    DecisionCodecException.TYPE_INVALID_REQUEST, "failed to read the request body");
        }
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
                        "multipart part \"" + ErrorMapper.sanitizeClientText(part.name())
                                + "\" must be a file part (only `request` is text)");
            }
            if (files.put(part.name(), part.data()) != null) {
                throw new DecisionCodecException(
                        DecisionCodecException.TYPE_INVALID_REQUEST,
                        "duplicate multipart file part \"" + ErrorMapper.sanitizeClientText(part.name()) + "\"");
            }
        }
        if (requestJson == null) {
            throw new DecisionCodecException(
                    DecisionCodecException.TYPE_INVALID_REQUEST, "multipart body must carry the JSON `request` part");
        }
        return codec.inlineMultipartAssets(requestJson, files);
    }

    private static byte[] readBoundedIdentity(InputStream raw) throws IOException {
        return readBounded(raw);
    }

    private static byte[] unzstd(InputStream raw) {
        try (InputStream in = new ZstdInputStream(raw)) {
            return readBounded(in);
        } catch (HttpStatusException | ContentLengthExceededException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DecisionCodecException(DecisionCodecException.TYPE_INVALID_REQUEST, "invalid zstd request body");
        }
    }

    private static byte[] gunzip(InputStream raw) {
        try (InputStream in = new GZIPInputStream(raw)) {
            return readBounded(in);
        } catch (HttpStatusException | ContentLengthExceededException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DecisionCodecException(DecisionCodecException.TYPE_INVALID_REQUEST, "invalid gzip request body");
        }
    }

    /**
     * Bounded streaming accumulator using fixed 64 KiB chunks to avoid {@link java.io.ByteArrayOutputStream}
     * buffer doubling (32 MiB -> 64 MiB + copy) alongside a separate {@code readAllBytes()} wire buffer
     * under native-image's 256 MiB heap ceiling.
     */
    private static byte[] readBounded(InputStream in) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        int total = 0;
        byte[] current = new byte[READ_CHUNK_SIZE];
        int offset = 0;
        while (true) {
            int read = in.read(current, offset, READ_CHUNK_SIZE - offset);
            if (read == -1) {
                if (offset > 0) {
                    chunks.add(offset == current.length ? current : Arrays.copyOf(current, offset));
                }
                break;
            }
            if (read > MAX_DECOMPRESSED_BYTES - total) {
                throw new ContentLengthExceededException("decompressed request body exceeds 32 MiB");
            }
            total += read;
            offset += read;
            if (offset == READ_CHUNK_SIZE) {
                chunks.add(current);
                current = new byte[READ_CHUNK_SIZE];
                offset = 0;
            }
        }
        if (total == 0) {
            return new byte[0];
        }
        if (chunks.size() == 1) {
            return chunks.get(0);
        }
        byte[] result = new byte[total];
        int destPos = 0;
        for (int i = 0; i < chunks.size(); i++) {
            byte[] chunk = chunks.get(i);
            chunks.set(i, null);
            System.arraycopy(chunk, 0, result, destPos, chunk.length);
            destPos += chunk.length;
        }
        return result;
    }

    private static int readUpTo(InputStream in, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return off;
    }

    private static boolean isGzipMagic(byte[] header) {
        return (header[0] & 0xFF) == 0x1F && (header[1] & 0xFF) == 0x8B;
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
        Map<String, Object> meta = new java.util.HashMap<>();
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
}
