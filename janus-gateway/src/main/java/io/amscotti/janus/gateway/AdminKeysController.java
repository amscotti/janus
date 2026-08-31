package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.OpenAiCodecException;
import io.amscotti.janus.gateway.dto.KeyDeleteRequest;
import io.amscotti.janus.gateway.dto.KeyDeleteResponse;
import io.amscotti.janus.gateway.dto.KeyGenerateRequest;
import io.amscotti.janus.gateway.dto.KeyGenerateResponse;
import io.amscotti.janus.gateway.dto.KeyListItem;
import io.amscotti.janus.gateway.dto.KeyListResponse;
import io.amscotti.janus.store.KeyGenerator;
import io.amscotti.janus.store.KeyHash;
import io.amscotti.janus.store.KeyRecord;
import io.amscotti.janus.store.KeyRecordView;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

/**
 * Admin keys API (, master-key-authed by {@link KeyAuthFilter} — this controller
 * trusts the filter; with auth off the whole middleware is a passthrough, documented
 * in {@code config.toml}):
 *
 * <ul>
 * <li>{@code POST /key/generate} — {@code {"models": [...], "name":..., "budget_usd":
 *..., "budget_duration":..., "rpm":..., "tpm":..., "duration":...}} → creates a
 * virtual key; the response carries the
 * full {@code sk-janus-…} key <b>exactly once</b> (the store holds only the salted
 * hash; list/delete never echo it). {@code budget_usd}/{@code rpm}/{@code tpm}
 * are optional and wire into {@code KeyStore.KeyCreateRequest} — absent fields ⇒
 * null caps (a null cap means "no cap", not zero; enforcement is the {@code
 * Governance}, which 429s over-limit/over-budget keys before dispatch).
 * {@code budget_duration} (seconds, ≤ 10 years) makes the budget a reset window —
 * the cap refills each aligned window; absent = lifetime budget. {@code duration}
 * (seconds) sets {@code expires_at} from the store clock (absent ⇒
 * never expires) — LiteLLM {@code /key/generate} parity.
 * <li>{@code POST /key/delete} — {@code {"key_id":...}} or {@code {"key":...}}
 * (full key string, resolved via its prefix) → revokes; {@code deleted: true}
 * when the id exists (idempotent), {@code false} for an unknown id. A
 * successful revoke also drops the key's per-key {@code janus_key_*} metrics
 * series ({@link MetricsRecorder#forgetKey}) and the notifier's dedup-window
 * entry ({@link Notifier#forgetKey}) so neither outlives the key.
 * <li>{@code GET /key/list} — redacted records (no salt/hash/secret — see
 * {@link KeyRecordView}); carries {@code budget_usd}/{@code budget_duration}/
 * {@code rpm}/{@code tpm} unchanged from
 * </ul>
 *
 * <p><b>Wire shape.</b> Bodies are parsed and responses serialized with the existing
 * {@link GatewayJson} mapper — records with explicit {@code @JsonProperty}s, no
 * polymorphic types. Malformed bodies / missing delete identifiers throw the codec's
 * {@link OpenAiCodecException} {@code TYPE_INVALID_REQUEST} → 400 OpenAI envelope via
 * {@link GatewayExceptionHandler} (the admin API is OpenAI-styled). All responses are
 * explicit {@code String} bodies (same pattern as the chat controllers and the
 * exception handler — no Micronaut DTO binding, no second JSON path).
 */
@Controller("/key")
class AdminKeysController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminKeysController.class);

    private final KeyStore keyStore;
    private final MetricsRecorder metricsRecorder;
    private final Clock clock;

    /** The single revocation seam — every key-revocation path funnels its
     * per-key state prune through this (metrics series + notifier dedup window). */
    private final KeyRevocationHooks revocationHooks;

    /** Max length of the {@code name} label (echoed verbatim into {@code GET /key/list}). */
    private static final int MAX_NAME_LENGTH = 256;

    /** The {@code face} label value for the admin surface ( series). */
    private static final String FACE_ADMIN = "admin";

    /** Shared error mapper (one instance, no per-error allocation). */
    private static final ErrorMapper ERROR_MAPPER = new ErrorMapper();

    @Inject
    AdminKeysController(KeyStore keyStore, MetricsRecorder metricsRecorder, Notifier notifier, Clock clock) {
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.revocationHooks = new KeyRevocationHooks(metricsRecorder, Objects.requireNonNull(notifier, "notifier"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Post(value = "/generate", consumes = MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    HttpResponse<String> generate(@Body @Nullable String rawBody) {
        long startNanos = System.nanoTime();
        try {
            KeyGenerateRequest request = parse(rawBody, KeyGenerateRequest.class);
            requireJsonObject(request);
            validateCaps(request);
            KeyStore.CreatedKey created = keyStore.create(new KeyStore.KeyCreateRequest(
                    request.name(),
                    request.models(),
                    expiresAt(request),
                    request.budgetUsd(),
                    request.budgetDuration(),
                    request.rpm(),
                    request.tpm()));
            KeyGenerateResponse response = new KeyGenerateResponse(
                    created.fullKey(),
                    created.record().id(),
                    created.record().owner(),
                    created.record().models(),
                    created.record().budgetDuration(),
                    created.record().createdAt(),
                    created.record().expiresAt());
            // Admin success traffic was previously invisible (only the catch path and the
            // filter's rejections were recorded) — record the 2xx so the admin series is
            // complete (a dashboard summing by face no longer sees admin grow only on errors).
            recordAdminSuccess(startNanos);
            return HttpResponse.ok(GatewayJson.write(response)).contentType(MediaType.APPLICATION_JSON);
        } catch (RuntimeException e) {
            // Controller-level admin failures (bad JSON, rejected caps, a
            // throwing store) never reached a MetricsRecorder — the filter only meters
            // its own rejections. Record the coarse bucket the exception handler will
            // produce and rethrow unchanged (mirrors the chat controllers). The
            // recorder call is best-effort (a throwing recorder must not mask the
            // client's true envelope).
            recordError(startNanos, e);
            throw e;
        }
    }

    @Post(value = "/delete", consumes = MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    HttpResponse<String> delete(@Body @Nullable String rawBody) {
        long startNanos = System.nanoTime();
        try {
            KeyDeleteRequest request = parse(rawBody, KeyDeleteRequest.class);
            requireJsonObject(request);
            String keyId = request.keyId();
            if (keyId == null || keyId.isBlank()) {
                if (request.key() == null || request.key().isBlank()) {
                    throw new OpenAiCodecException(
                            OpenAiCodecException.TYPE_INVALID_REQUEST, "/key/delete requires \"key_id\" or \"key\"");
                }
                Optional<KeyGenerator.Parsed> parsed = KeyGenerator.parse(request.key());
                if (parsed.isEmpty()) {
                    throw new OpenAiCodecException(OpenAiCodecException.TYPE_INVALID_REQUEST, "invalid key string");
                }
                Optional<KeyRecord> record = keyStore.findByPrefix(parsed.get().prefix());
                if (record.isEmpty()) {
                    recordAdminSuccess(startNanos);
                    return deleted(null, false);
                }
                // The key form must verify the presented secret against the stored
                // hash. The prefix alone is public (it rides GET /key/list), so a prefix
                // match is not proof of possession — a wrong/typo'd secret must not
                // silently revoke the key (and returns the same deleted:false shape as an
                // unknown prefix, so it leaks nothing beyond the public prefix index).
                if (!KeyHash.verify(
                        record.get().salt(),
                        record.get().secretHash(),
                        parsed.get().secret())) {
                    recordAdminSuccess(startNanos);
                    return deleted(null, false);
                }
                keyId = record.get().id();
            }
            // Record the 2xx only AFTER the revoke succeeds — the old form
            // recorded first, so a throwing revokeAndForget double-counted the request
            // (an admin 2xx AND the catch path's 5xx for one delete). keyStore.revoke
            // itself may legitimately fail — that is the only reason the 2xx is skipped.
            boolean revoked = revokeAndForget(keyId);
            recordAdminSuccess(startNanos);
            return deleted(keyId, revoked);
        } catch (RuntimeException e) {
            recordError(startNanos, e);
            throw e;
        }
    }

    /**
     * Revoke the key and, on success, drop its per-key state so it does not accumulate
     * over the process lifetime: the per-key metrics series ({@code MetricsRecorder.
     * forgetKey}) and the notifier's dedup window entry ({@link Notifier#forgetKey} —
     * a key that is deleted and re-created fires {@code :budget_exceeded} again in the
     * same window instead of being silently suppressed). Idempotent: a {@code false}
     * return (unknown / already-revoked id) leaves metrics and notifier state untouched.
     *
     * <p>The two {@code forgetKey} calls are wrapped in the single
     * {@link KeyRevocationHooks} seam — the documented funnel <b>every</b> future
     * revocation path must use (a direct {@code KeyStore.revoke}, a lifecycle job),
     * so per-key state can never grow unboundedly because a path forgot one hook.
     *
     * <p>The {@code forgetKey} hooks are best-effort by contract (a deleting
     * adapter must never turn a successful revoke into a 500 after the key is gone) —
     * a throwing recorder/notifier is logged and dropped; only {@code keyStore.revoke}
     * can legitimately fail and skip the caller's 2xx.
     */
    private boolean revokeAndForget(String keyId) {
        boolean revoked = keyStore.revoke(keyId);
        if (revoked) {
            revocationHooks.forget(keyId);
        }
        return revoked;
    }

    @Get("/list")
    @ExecuteOn(TaskExecutors.BLOCKING)
    HttpResponse<String> list() {
        long startNanos = System.nanoTime();
        try {
            // One snapshot — a concurrent create/revoke between two list calls
            // would yield an inconsistent view (and the old form did two full-store
            // traversals per request).
            List<KeyRecordView> views = keyStore.list();
            List<KeyListItem> items = new ArrayList<>(views.size());
            for (KeyRecordView view : views) {
                items.add(new KeyListItem(
                        view.id(),
                        view.prefix(),
                        view.owner(),
                        view.models(),
                        view.status(),
                        view.createdAt(),
                        view.expiresAt(),
                        view.lastUsedAt(),
                        view.budgetUsd(),
                        view.budgetDuration(),
                        view.rpm(),
                        view.tpm()));
            }
            recordAdminSuccess(startNanos);
            return HttpResponse.ok(GatewayJson.write(new KeyListResponse(items)))
                    .contentType(MediaType.APPLICATION_JSON);
        } catch (RuntimeException e) {
            recordError(startNanos, e);
            throw e;
        }
    }

    /**
     * A successful admin operation is recorded in the admin 2xx series —
     * <b>best-effort</b>, like the catch path: the recording happens after the
     * operation already committed (the key is persisted / revoked), so a throwing
     * recorder must not retroactively fail the response — for {@code generate} that
     * would swallow the one response that ever carries the full {@code sk-janus-…}
     * secret, and for {@code delete} it would report a revoke that succeeded as a 500
     * (the same "recording never alters the request path" invariant the hooks and the
     * catch path already carry).
     */
    private void recordAdminSuccess(long startNanos) {
        try {
            metricsRecorder.recordRequest(FACE_ADMIN, HttpStatus.OK.getCode(), elapsedMillis(startNanos), null);
        } catch (RuntimeException e) {
            LOG.warn("metrics recording dropped (recorder failure): {}", e.toString());
        }
    }

    /**
     * The catch-path {@code recordRequest} is best-effort (the
     * {@code writeCallRecord}/forget-hook guard pattern) — a throwing recorder must
     * never replace the client's true envelope with a 500 or mask the admin failure.
     */
    private void recordError(long startNanos, Throwable throwable) {
        try {
            metricsRecorder.recordRequest(FACE_ADMIN, statusOf(throwable), elapsedMillis(startNanos), null);
        } catch (RuntimeException e) {
            LOG.warn("metrics recording dropped (recorder failure): {}", e.toString());
        }
    }

    private static HttpResponse<String> deleted(@Nullable String keyId, boolean deleted) {
        return HttpResponse.ok(GatewayJson.write(new KeyDeleteResponse(keyId, deleted)))
                .contentType(MediaType.APPLICATION_JSON);
    }

    /**
     * The largest finite {@code budget_usd} whose micro-USD conversion fits a {@code long}
     * ({@code Long.MAX_VALUE / 1_000_000} USD). Anything larger would saturate in
     * {@code Governance.toMicroUsd} to a de-facto unbounded cap — rejected here so the
     * operator's cap is never silently unbound.
     */
    private static final double MAX_BUDGET_USD = Long.MAX_VALUE / 1_000_000.0;

    /**
     * A non-positive {@code rpm}/{@code tpm}/{@code budget_usd} is rejected
     * at creation (400, the admin API's invalid-request path): caps are optional (null
     * = unenforced), but a stored ≤ 0 cap would make {@link Governance}'s limiter call
     * throw {@code requirePositive} on <b>every</b> request for that key — a 500 on the
     * hot path with no upstream call, never a 429. "Deny all" is not expressible via 0
     * (a documented invariant: null means "no cap", not zero).
     *
     * <p>{@code budget_usd} must also be <em>finite</em> — Jackson parses a
     * JSON {@code 1e999} (double overflow) into {@code POSITIVE_INFINITY} and a {@code
     * NaN} literal into {@code NaN}; both pass {@code <= 0} and would be stored, where
     * {@code Governance.toMicroUsd} would silently turn the operator's cap into "no
     * cap" and {@code GET /key/list} would emit non-JSON {@code Infinity}/{@code NaN}
     * literals. Blank {@code models} entries and a blank/over-long {@code name} are
     * likewise rejected: a stored {@code ""} scope entry authenticates but denies every
     * real model call (403 on everything — a confusing footgun), and {@code name} is
     * echoed verbatim into {@code GET /key/list}.
     *
     * <p>A finite-but-impossibly-large {@code budget_usd} (≥ {@value #MAX_BUDGET_USD},
     * e.g. {@code 1e13}) would saturate {@code Governance.toMicroUsd} to
     * {@code Long.MAX_VALUE} — a silently unbounded cap — so it is rejected here too.
     */
    private static void validateCaps(KeyGenerateRequest request) {
        if (request.rpm() != null && request.rpm() <= 0) {
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_INVALID_REQUEST, "\"rpm\" must be positive (got " + request.rpm() + ")");
        }
        if (request.tpm() != null && request.tpm() <= 0) {
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_INVALID_REQUEST, "\"tpm\" must be positive (got " + request.tpm() + ")");
        }
        if (request.duration() != null && request.duration() <= 0) {
            // A non-positive duration is meaningless — null (absent) is the "never
            // expires" spelling, mirroring the null-means-no-cap invariant for caps.
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_INVALID_REQUEST,
                    "\"duration\" must be positive (got " + request.duration() + ")");
        }
        if (request.budgetUsd() != null && (!Double.isFinite(request.budgetUsd()) || request.budgetUsd() <= 0)) {
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_INVALID_REQUEST,
                    "\"budget_usd\" must be a positive finite number (got " + request.budgetUsd() + ")");
        }
        if (request.budgetUsd() != null && microUsdSaturates(request.budgetUsd())) {
            // A budget whose micro-USD conversion saturates in Governance.toMicroUsd
            // silently becomes unbounded — reject the footgun at creation (Double.MAX_VALUE
            // and 1e13 are both finite, so the <= 0 / finite checks above do not catch them).
            // The rejection uses the same predicate as toMicroUsd
            // (usd * 1e6 + 0.5 >= Long.MAX_VALUE), so the ~5e-7 USD band just below
            // MAX_BUDGET_USD where the old `> MAX_BUDGET_USD` test passed but the value
            // still saturates is rejected too — a stored cap can never be silently unbound.
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_INVALID_REQUEST,
                    "\"budget_usd\" is too large to represent in micro-USD (must be < " + MAX_BUDGET_USD + ")");
        }
        if (request.budgetDuration() != null
                && (request.budgetDuration() <= 0 || request.budgetDuration() > KeyStore.MAX_BUDGET_DURATION_SECONDS)) {
            // Positive integer seconds up to 10 years: above the bound the derived
            // window epoch floorDiv(nowSec, dur) * dur hits 0 — the LIFETIME window row —
            // so an absurd duration would silently alias the key's windowed spend onto
            // the lifetime row. The bound (and its constant) lives on
            // KeyStore.validateCaps — the store seam this mirrors.
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_INVALID_REQUEST,
                    "\"budget_duration\" must be a positive number of seconds up to "
                            + KeyStore.MAX_BUDGET_DURATION_SECONDS
                            + " (10 years; null = lifetime budget), got " + request.budgetDuration());
        }
        if (request.budgetUsd() != null && microUsdRoundsToZero(request.budgetUsd())) {
            // A tiny-but-positive budget whose micro-USD conversion rounds to 0
            // (usd * 1e6 + 0.5 < 1) collides with the "0 = no cap" sentinel in
            // Governance.toMicroUsd — the operator believes a cap exists but the key is
            // silently unbounded. Reject the collision at creation with the exact
            // toMicroUsd predicate (a budget must represent at least 1 micro-USD).
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_INVALID_REQUEST,
                    "\"budget_usd\" is too small — it rounds to 0 micro-USD (must be >= 0.0000005 USD)");
        }
        validateScope(request.models());
        validateOwner(request.name());
    }

    /**
     * Exactly the saturation predicate of {@code Governance.toMicroUsd} — a budget
     * for which {@code usd * 1e6 + 0.5 >= Long.MAX_VALUE} clamps to a de-facto unbounded
     * cap there, so the API must reject it with the same test (not the coarser
     * {@code usd > MAX_BUDGET_USD}, which leaves a rounding sliver that still saturates).
     */
    private static boolean microUsdSaturates(double usd) {
        return usd * 1_000_000.0 + 0.5 >= Long.MAX_VALUE;
    }

    /**
     * Exactly {@code Governance.toMicroUsd(budget) == 0} for a positive finite
     * budget — {@code usd * 1e6 + 0.5 < 1} floors to 0 micro-USD, which the ledger
     * treats as the explicit "no cap" sentinel. Rejecting it at creation keeps a
     * stored positive budget semantically meaningful (never a silently-unbound cap).
     */
    private static boolean microUsdRoundsToZero(double usd) {
        return usd * 1_000_000.0 + 0.5 < 1.0;
    }

    /**
     * The {@code expires_at} a {@code duration} (seconds) implies — computed against
     * the shared {@link Clock} bean (the same one the store enforces expiry with, so the
     * admin API's {@code expires_at} and the store's check come from one clock;
     * discipline). Absent {@code duration} ⇒ never expires.
     */
    private Long expiresAt(KeyGenerateRequest request) {
        Long duration = request.duration();
        if (duration == null) {
            return null;
        }
        long now = clock.millis();
        if (duration > (Long.MAX_VALUE - now) / 1000) {
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_INVALID_REQUEST, "\"duration\" is too large (overflow in expires_at)");
        }
        return now + duration * 1000;
    }

    private static void validateScope(List<String> models) {
        if (models == null) {
            return;
        }
        for (String model : models) {
            if (model == null || model.isBlank()) {
                throw new OpenAiCodecException(
                        OpenAiCodecException.TYPE_INVALID_REQUEST, "\"models\" entries must be non-blank model names");
            }
        }
    }

    private static void validateOwner(String name) {
        if (name == null) {
            return;
        }
        if (name.isBlank()) {
            throw new OpenAiCodecException(OpenAiCodecException.TYPE_INVALID_REQUEST, "\"name\" must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_INVALID_REQUEST,
                    "\"name\" must be at most " + MAX_NAME_LENGTH + " chars (got " + name.length() + ")");
        }
    }

    private static <T> T parse(String rawBody, Class<T> type) {
        if (rawBody == null || rawBody.isBlank()) {
            // An empty/missing body must be a client 400 (the mapper's
            // null-content handling throws an untyped IllegalArgumentException → 500).
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_INVALID_REQUEST, "request body must be a JSON object");
        }
        try {
            return GatewayJson.mapper().readValue(rawBody, type);
        } catch (JacksonException e) {
            throw new OpenAiCodecException(OpenAiCodecException.TYPE_INVALID_REQUEST, "invalid JSON body");
        }
    }

    /**
     * A JSON literal {@code null} body binds the DTO to {@code null} —
     * dereferencing it below would NPE (a 500) for purely client-malformed input.
     */
    private static void requireJsonObject(Object request) {
        if (request == null) {
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_INVALID_REQUEST, "request body must be a JSON object");
        }
    }

    /**
     * The HTTP status the {@link GatewayExceptionHandler} would map this throwable to
     * (the admin API is OpenAI-styled, so the shared OpenAI {@link ErrorMapper} is the
     * classification vocabulary) — the coarse {@code status} label the request carries.
     */
    private static int statusOf(Throwable throwable) {
        return ERROR_MAPPER.map(throwable).status().getCode();
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
