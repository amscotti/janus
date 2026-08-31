package io.amscotti.janus.gateway;

import io.amscotti.janus.gateway.dto.ModelEntry;
import io.amscotti.janus.gateway.dto.ModelsResponse;
import io.amscotti.janus.router.Router;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI face: {@code GET /v1/models}. Lists the router's configured model aliases
 * in config (insertion) order, with {@code owned_by} = the resolved backend's
 * {@code name}; {@code created} is the boot-time epoch second captured once, so the
 * listing is deterministic per process. bound the {@code model-list} entries into the
 * router; the listing itself stays alias/backend-name only (richer per-model metadata is
 * future work). The DTOs ({@link ModelsResponse}, {@link ModelEntry}) live in the
 * {@code gateway.dto} package — the drift guard requires every class there to have a
 * {@code reflect-config.json} entry, annotation-independent.
 *
 * <p><b>This OpenAI-face route is Tier-1 metered.</b> {@code GET /v1/models}
 * successes land in {@code janus_requests_total{face="openai",status="2xx"}} (the
 * {@code openai} series was previously incomplete — only the chat route recorded) and a
 * throwing {@code router.models/route} lands in the 4xx/5xx bucket via the same
 * catch-mirrors-the-handler pattern as the chat controllers. The route is <b>auth-off
 * surface</b> (the {@link KeyAuthFilter} exempts unlisted paths), so there is never a
 * governing key: {@code keyId} is always {@code null} (unlabeled totals only, never a
 * per-key series).
 */
@Controller("/v1")
class ModelsController {

    private static final Logger LOG = LoggerFactory.getLogger(ModelsController.class);

    /** The {@code face} label value for this controller ( series). */
    private static final String FACE = "openai";

    private final Router router;
    private final MetricsRecorder metricsRecorder;
    private final ErrorMapper errorMapper;
    private final long created;

    ModelsController(Router router, MetricsRecorder metricsRecorder) {
        this.router = Objects.requireNonNull(router, "router");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.errorMapper = new ErrorMapper();
        this.created = Instant.now().getEpochSecond();
    }

    @Get("/models")
    HttpResponse<ModelsResponse> models() {
        long startNanos = System.nanoTime();
        try {
            List<ModelEntry> data = new ArrayList<>();
            for (String alias : router.models()) {
                data.add(new ModelEntry(
                        alias, "model", created, router.route(alias).name()));
            }
            // Record AFTER the listing — a throw above lands in the catch's error
            // bucket once (no double 2xx+error), matching the chat controllers.
            metricsRecorder.recordRequest(FACE, HttpStatus.OK.getCode(), elapsedMillis(startNanos), null);
            return HttpResponse.ok(new ModelsResponse("list", data));
        } catch (RuntimeException e) {
            recordFailure(e, startNanos);
            throw e;
        }
    }

    /**
     * OpenAI face: {@code GET /v1/models/{id}} (retrieve model). Returns a SINGLE
     * model object — the same members as a list entry — for a configured alias, or
     * real-OpenAI's {@code 404 invalid_request_error / model_not_found} for an unknown
     * id: {@code Router.route} throws {@link io.amscotti.janus.router.UnknownModelException},
     * which the global handler maps to exactly that envelope (the same row chat uses,
     * so an SDK probing capabilities reads one error shape everywhere). Auth-off
     * surface and Tier-1 metering match the list route ({@code keyId} always null).
     */
    @Get("/models/{id}")
    HttpResponse<ModelEntry> model(String id) {
        long startNanos = System.nanoTime();
        try {
            ModelEntry entry =
                    new ModelEntry(id, "model", created, router.route(id).name());
            metricsRecorder.recordRequest(FACE, HttpStatus.OK.getCode(), elapsedMillis(startNanos), null);
            return HttpResponse.ok(entry);
        } catch (RuntimeException e) {
            recordFailure(e, startNanos);
            throw e;
        }
    }

    /**
     * Record the coarse status bucket the exception handler will produce (the
     * catch-mirrors-the-handler pattern) and rethrow unchanged — recording never
     * alters the request path. Best-effort like the chat controllers' guard.
     */
    private void recordFailure(RuntimeException e, long startNanos) {
        try {
            metricsRecorder.recordRequest(FACE, statusOf(e), elapsedMillis(startNanos), null);
        } catch (RuntimeException recordingFailure) {
            LOG.warn("metrics recording dropped (recorder failure): {}", recordingFailure.toString());
        }
    }

    /** The HTTP status the {@link GatewayExceptionHandler} would map this throwable to
     * (one shared mapper instance, no per-error allocation). */
    private int statusOf(Throwable throwable) {
        return errorMapper.map(throwable).status().getCode();
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
