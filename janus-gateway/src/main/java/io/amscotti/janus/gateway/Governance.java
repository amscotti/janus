package io.amscotti.janus.gateway;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.DeveloperMessage;
import io.amscotti.janus.core.model.Message;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.SystemMessage;
import io.amscotti.janus.core.model.ToolMessage;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.core.model.UserMessage;
import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.CallStatus;
import io.amscotti.janus.store.CallStore;
import io.amscotti.janus.store.CostBreakdown;
import io.amscotti.janus.store.CostCalculator;
import io.amscotti.janus.store.FixedWindowRateLimiter;
import io.amscotti.janus.store.KeyRecord;
import io.amscotti.janus.store.PriceTable;
import io.amscotti.janus.store.PricingRate;
import io.amscotti.janus.store.RateLimiter;
import io.amscotti.janus.store.RateLimiter.RateLimitResult;
import io.amscotti.janus.store.SpendLedger;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * governance collaborator injected into both controllers ({@link
 * ChatCompletionsController}, {@link MessagesController}): pre-dispatch enforcement
 * (RPM 429, TPM pre-check 429, budget hard-cap 429 + reserve) and post-dispatch
 * accounting (settle actual cost, TPM accumulate, soft-cap warning header + notifier),
 * keyed by the {@link KeyRecord} the {@link KeyAuthFilter} attached
 * ({@link KeyAuthFilter#KEY_ATTRIBUTE}).
 *
 * <p><b>Key-scoped only — auth-off preserves keyless behavior byte-identically.</b>
 * No key attached ⇒ no limits, no budget, no accounting (the filter is a passthrough
 * under auth-off, so keyless configs are untouched); a key's {@code rpm}/{@code tpm}/
 * {@code budgetUsd} null ⇒ that dimension is unenforced — <b>null means "no cap", not
 * "zero"</b>. {@link #noop} is the limits-off instance the / suites inject; the
 * production factory's defaults reproduce the same behavior when {@code [janus.limits]}
 * is absent.
 *
 * <p><b>The request lifecycle (pipeline steps 5/6/9).</b>
 *
 * <ul>
 * <li><b>Pre-dispatch</b> ({@link #enforce}, after the scope check): RPM
 * {@code tryAcquire} (denied ⇒ 429 {@code rate_limit_error} + {@code Retry-After},
 * never dispatched upstream); TPM {@code wouldExceed} with the conservative
 * estimate ({@code max_tokens} ?? row {@code default-max-tokens} ?? 4096 — the
 * reserve-factor convention — <b>plus the prompt estimate</b>, so the
 * gate prices both sides exactly like the budget gate; denied ⇒ 429 before it
 * would cross, documented conservative semantics); budget {@code SpendLedger.reserve} — atomic
 * increment-then-check, hard-cap crossing ⇒ rollback + 429 (no {@code Retry-After}
 * — a budget does not refill on a timer), soft-cap crossing ⇒ proceed but flag
 * soft. The estimate prices BOTH sides: the output reserve (the
 * {@code max_tokens} ?? row {@code default-max-tokens} ?? 4096 × the output rate)
 * plus a prompt estimate from the request's message content (sum of UTF-16 chars
 * / 4 × the input + cache-read rates) — so a prompt-heavy request cannot drive
 * the hard cap arbitrarily past cap unguarded; settle corrects to actual.
 * <li><b>Post-dispatch</b> ({@link #finalize}): cost =
 * {@code CostCalculator.costMicroUsd(usage, rateFor(model))} — exact integer
 * micro-USD; {@code settle} corrects the reservation (pending −= estimate,
 * settled += actual); {@code recordSpend} appends the usage record;
 * {@code RateLimiter.accumulate} records real TPM tokens; a soft flag attaches
 * the {@code X-Janus-Budget-Warning: soft} header (non-streaming only — SSE
 * headers are already sent) and fires the {@code :budget_exceeded} notifier
 * event (shape: {@code key_id}, {@code tier: :soft}, committed, cap).
 * <li><b>Abort paths</b>: an exception after {@code enforce} (upstream error, codec
 * failure) ⇒ {@link #release} — the reservation never leaks; mid-stream aborts
 * release through the wrap's close hook.
 * </ul>
 *
 * <p><b>Metrics hook.</b> {@link #finalize} and the stream settle paths call
 * {@link MetricsRecorder#recordUsage} — the one place {@link Usage} + exact micro-USD
 * cost + the governing key all exist — so both faces and both streaming modes record
 * from the same seam: keyed requests label the {@code janus_key_*} series with the
 * opaque non-secret {@code KeyRecord.id}, auth-off (no key) records the unlabeled
 * totals only, streams record from the terminal usage-bearing chunk (or a zero entry
 * on clean exhaustion without usage — the documented limitation), and aborts
 * record nothing. {@link MetricsRecorder#noop} (the 6-arg constructor's recorder)
 * keeps keyless behavior byte-identical. The call ledger follows the same
 * abort rule — a client-aborted stream (no terminal usage chunk) records no
 * {@link CallRecord} either (the decision: record-nothing, matching the
 * metrics side and the reference recorder; the closed {@link CallStatus} set is
 * unchanged — no canceled variant).
 *
 * <p><b>Streaming — settle at the terminal chunk.</b>
 * {@link #wrapStream} wraps the router's stream: when the terminal chunk carries
 * aggregate {@code usage} (OpenAI leg with client-requested
 * {@code stream_options.include_usage}; the Anthropic encoder aggregates usage into
 * its final chunk), the wrap settles + accumulates + records from it; a stream that
 * exhausts without a usage chunk settles a $0 / tokens-unknown entry (Janus does
 * <b>not</b> force {@code include_usage} — that would change the outbound wire bytes,
 * D1 byte-golden discipline); an abort/cancel (close before exhaustion) releases
 * the reservation; a hard-cap crossing mid-stream affects only the <em>next</em>
 * request; soft-exceed for streams is <b>notifier-only</b> (no header possible
 * post-SSE-start). In-flight streams are never aborted by governance.
 *
 * <p><b>Call-ledger writer.</b> {@link #finalize}, the stream settle paths and
 * {@link #recordFailure} build one Tier-1 {@link CallRecord} per request (status
 * mapped from the existing error kinds; auth-off ⇒ null keyId; provider = the
 * <b>actually-dispatched backend</b> threaded from the controller's dispatch seam
 * — the router's dispatch observer, NOT a re-resolve through {@code Router.route}
 * (which is deliberately the config-first candidate, so it misattributes under
 * balancing/retry failover); null when no backend was dispatched; duration
 * measured wall-clock) and append it to the wired {@link CallStore}. Exactly-once:
 * a stream settled at the terminal usage chunk is never recorded twice — the
 * {@code settledOrReleased} CAS gates both the settle and the mid-stream failure
 * write (the pin).
 *
 * <p><b>Stream settle timing (documented acceptance).</b> The stream settle
 * happens at <em>pull</em> time — {@link #wrapStream}'s {@code tryAdvance} settles +
 * writes the OK {@link CallRecord} the moment the terminal usage chunk is pulled by
 * the publisher ({@code SseChunkPublisher} pulls the chunk, then checks
 * cancel/stall, then emits). A client cancel landing in the microseconds between
 * pull and emit therefore still records a settled OK row + usage for a stream the
 * client aborted mid-flight — slightly off from the documented "a client-aborted
 * stream (no terminal usage chunk) records no CallRecord". The window is
 * microseconds and the accounting is conservative (the tokens were genuinely
 * consumed), so deferring the settle to delivery (significant plumbing for no
 * real-world benefit) is not worth it; the behavior is accepted and pinned by the
 * tests' pre-terminal-abort case.
 *
 * <p>Thread-safe: all components are concurrent maps with atomic computes; {@code
 * noop} is a stateless singleton.
 */
final class Governance {

    private static final Logger LOG = LoggerFactory.getLogger(Governance.class);

    /** Response header on non-streaming successes once spend crosses the soft fraction. */
    static final String HEADER_BUDGET_WARNING = "X-Janus-Budget-Warning";

    /** Response header with the settled micro-USD when the soft warning fires. */
    static final String HEADER_BUDGET_USED = "X-Janus-Budget-Used-Micro-Usd";

    static final String HEADER_COST_INPUT = "X-Janus-Cost-Input-Micro-Usd";
    static final String HEADER_COST_OUTPUT = "X-Janus-Cost-Output-Micro-Usd";
    static final String HEADER_COST_CACHE_READ = "X-Janus-Cost-Cache-Read-Micro-Usd";
    static final String HEADER_COST_CACHE_CREATION = "X-Janus-Cost-Cache-Creation-Micro-Usd";
    static final String HEADER_COST_SEARCH = "X-Janus-Cost-Search-Micro-Usd";
    static final String HEADER_COST_TOTAL = "X-Janus-Cost-Total-Micro-Usd";

    static final String WARNING_SOFT = "soft";
    static final String WARNING_HARD = "hard";

    private static final String TIER_SOFT = "soft";
    private static final String TIER_HARD = "hard";

    /**
     * The estimate fallback when neither the request nor the pricing row has max tokens.
     * Reference default (4096) — aligns the
     * Java-side value: the reserve estimate only gates admission (settle corrects to
     * actual), and the reference default is the more conservative of the two.
     */
    static final int DEFAULT_MAX_TOKENS = 4096;

    private final RateLimiter rateLimiter;
    private final PriceTable priceTable;
    private final SpendLedger spendLedger;
    private final Notifier notifier;
    private final MetricsRecorder metricsRecorder;
    private final double softCapFraction;
    private final Clock clock;
    private final CallStore callStore;
    private final boolean noop;

    /**
     * The pre- constructor: delegates to the 7-arg form with
     * {@link MetricsRecorder#noop} so the governance suites compile and behave
     * <b>unchanged</b> (no metrics side effects unless a recorder is wired).
     */
    Governance(
            RateLimiter rateLimiter,
            PriceTable priceTable,
            SpendLedger spendLedger,
            Notifier notifier,
            double softCapFraction,
            Clock clock) {
        this(rateLimiter, priceTable, spendLedger, notifier, softCapFraction, clock, MetricsRecorder.noop());
    }

    /** the full form with the Tier-1 recorder (see the class javadoc). */
    Governance(
            RateLimiter rateLimiter,
            PriceTable priceTable,
            SpendLedger spendLedger,
            Notifier notifier,
            double softCapFraction,
            Clock clock,
            MetricsRecorder metricsRecorder) {
        this(rateLimiter, priceTable, spendLedger, notifier, softCapFraction, clock, metricsRecorder, null);
    }

    /**
     * the full form with the {@link CallStore} call-ledger writer (see the
     * class javadoc). {@code callStore} is nullable — some test constructions wire
     * no call ledger; the production factory always wires one, activating the writer.
     * The record's {@code provider} is threaded per call from the dispatch seam
     * ({@link #finalize}/{@link #wrapStream}/{@link #recordFailure}) — the
     * actually-dispatched backend, not a constructor-time resolver: a shared
     * model-alias resolver can only ever answer the config-first candidate, which is
     * wrong exactly when the load balancer picked another backend or a retry failed
     * over.
     */
    Governance(
            RateLimiter rateLimiter,
            PriceTable priceTable,
            SpendLedger spendLedger,
            Notifier notifier,
            double softCapFraction,
            Clock clock,
            MetricsRecorder metricsRecorder,
            CallStore callStore) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.priceTable = Objects.requireNonNull(priceTable, "priceTable");
        this.spendLedger = Objects.requireNonNull(spendLedger, "spendLedger");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        if (softCapFraction < 0 || softCapFraction > 1) {
            throw new IllegalArgumentException("softCapFraction must be in [0, 1] (got " + softCapFraction + ")");
        }
        this.softCapFraction = softCapFraction;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.callStore = callStore; // nullable: some test constructions wire no call ledger
        this.noop = false;
    }

    /** Private: the {@link #noop()} singleton has no components. */
    private Governance() {
        this.rateLimiter = null;
        this.priceTable = null;
        this.spendLedger = null;
        this.notifier = null;
        this.metricsRecorder = MetricsRecorder.noop();
        this.softCapFraction = 0.8;
        this.clock = null;
        this.callStore = null;
        this.noop = true;
    }

    /**
     * The limits-off instance (/ suites inject it; the production no-config path
     * is behaviorally equivalent — no key attached ⇒ nothing happens).
     */
    static Governance noop() {
        return NOOP;
    }

    private static final Governance NOOP = new Governance();

    /**
     * The pre-dispatch outcome the controller threads into {@link #finalize} /
     * {@link #wrapStream} / {@link #release}: the governing key, the resolved price
     * row, the estimate used for the TPM pre-check and the budget reservation, whether
     * a reservation was taken, and whether the soft tier was crossed.
     */
    record Preflight(
            KeyRecord key,
            PricingRate rate,
            int estimateTokens,
            long estimateMicroUsd,
            boolean reserved,
            boolean soft,
            long reservationWindowStart) {

        /** The no-key / noop outcome: nothing reserved, nothing to settle. */
        static final Preflight NONE = new Preflight(null, null, 0, 0, false, false, 0);
    }

    /** The post-dispatch outcome: soft warning + settled micro-USD for the response headers. */
    record Finalized(boolean softExceeded, Long usedMicroUsd, CostBreakdown cost) {

        static final Finalized NONE = new Finalized(false, null, CostBreakdown.ZERO);
    }

    /**
     * Pre-dispatch gate : RPM consume-on-allow → 429 with
     * {@code Retry-After} on denial; TPM non-consuming pre-check with the conservative
     * estimate → 429 on denial; budget {@code reserve} — hard cap → 429 (no
     * {@code Retry-After}), soft flag returned for the post-dispatch warning. Throws
     * {@link RateLimitExceededException} on any denial — the request is <b>not</b>
     * dispatched upstream. No key attached ⇒ {@link Preflight#NONE} (keyless
     * behavior).
     */
    Preflight enforce(HttpRequest<?> httpRequest, ChatRequest request) {
        if (noop) {
            return Preflight.NONE;
        }
        KeyRecord key = keyFrom(httpRequest);
        if (key == null) {
            return Preflight.NONE;
        }
        if (key.rpm() != null) {
            RateLimitResult result = rateLimiter.tryAcquire(key.id(), key.rpm(), 1);
            if (result instanceof RateLimitResult.Denied denied) {
                throw new RateLimitExceededException(
                        RateLimitExceededException.Reason.RATE_LIMIT_EXCEEDED, denied.retryAfterSeconds(), key.id());
            }
        }
        long promptEstimate = estimatePromptTokens(request);
        PricingRate rate = priceTable.rateFor(request.model()).forPromptTokens(promptEstimate);
        int estimateTokens = estimateTokens(request, rate);
        if (key.tpm() != null && rateLimiter.wouldExceed(key.id(), key.tpm(), estimateTokens + promptEstimate)) {
            // Conservative pre-check (the estimate prices BOTH sides now, like
            // the budget gate): output reserve = max_tokens ?? row default ?? 4096, plus
            // the prompt estimate from the request's content — the accumulator counts
            // prompt + completion, so a prompt-heavy request cannot cross the cap
            // undetected until the next request. Denied ⇒ 429 before it would cross
            // (the documented semantics: the cap "trips on the request after the one
            // that crossed" for real tokens). Retry-After = seconds until the aligned
            // window resets — exact for the fixed-window variant; for the
            // sliding/token-bucket variant this is a conservative window-aligned value
            // (the bucket may refill sooner, deficit ÷ rate — the deficit is not
            // surfaced by wouldExceed, documented risk: never under-promises).
            throw new RateLimitExceededException(
                    RateLimitExceededException.Reason.RATE_LIMIT_EXCEEDED,
                    secondsUntilWindowReset(FixedWindowRateLimiter.WINDOW_SECONDS),
                    key.id());
        }
        if (key.budgetUsd() != null) {
            // The admission estimate prices BOTH sides — the output
            // reserve plus the prompt estimate from the request's message content (the
            // old estimate priced prompt at 0 "unknown pre-dispatch", so a single
            // prompt-heavy request could drive the hard cap arbitrarily past cap before
            // the next-request gate caught it). Settle corrects to actual either way.
            long estimateMicro = CostCalculator.estimateMicroUsd(estimateTokens, rate)
                    + CostCalculator.estimatePromptMicroUsd(promptEstimate, rate);
            long capMicro = toMicroUsd(key.budgetUsd());
            // The budget reset window: null/≤ 0 budgetDuration = the lifetime window
            // (windowSeconds 0 — the pre-window semantics); a positive duration derives
            // the aligned window epoch inside the ledger and refills the cap each
            // rollover. The clamp mirrors toMicroUsd's defense-in-depth for
            // directly-seeded records.
            long windowSeconds = key.budgetDuration() != null && key.budgetDuration() > 0 ? key.budgetDuration() : 0;
            SpendLedger.ReserveResult reserve =
                    spendLedger.reserve(key.id(), estimateMicro, capMicro, softCapFraction, windowSeconds);
            if (reserve instanceof SpendLedger.ReserveResult.Denied) {
                // Hard cap: the reservation was rolled back inside the atomic reserve —
                // 429. A lifetime budget carries no Retry-After (it does not refill on a
                // timer); a windowed budget DOES — the seconds until its window resets.
                Long retryAfter = windowSeconds > 0 ? secondsUntilWindowReset(windowSeconds) : null;
                throw new RateLimitExceededException(
                        RateLimitExceededException.Reason.BUDGET_EXCEEDED_HARD, retryAfter, key.id());
            }
            if (reserve instanceof SpendLedger.ReserveResult.Allowed allowed) {
                return new Preflight(
                        key,
                        rate,
                        estimateTokens,
                        estimateMicro,
                        true,
                        allowed.soft(),
                        allowed.windowStartEpochSeconds());
            }
        }
        return new Preflight(key, rate, estimateTokens, 0, false, false, 0);
    }

    /**
     * Post-dispatch accounting : settle the reservation to the exact
     * micro-USD cost, record the spend entry, accumulate real TPM tokens, and
     * fire the soft-cap notifier + return the header payload when the soft tier was
     * crossed at reserve time. No-op for {@link Preflight#NONE}. {@code
     * durationMillis} is the wall-clock request duration the controller measured
     * — carried onto the {@link CallRecord}.
     *
     * <p><b>Doc note — {@code spendByKey} is budgeted-keys-only.</b> {@code settle}
     * runs only when {@code preflight.reserved} (a budgeted key), so an unbudgeted
     * key's settled views stay 0 — the value feeds only the soft-warning path, which
     * needs a budget. Unbudgeted keys still fill the {@code recordSpend} recent ring,
     * so a future "total spend by key" report must not read the ledger totals for them
     * (pinned by {@code GovernanceControllerTest}). <b>The two-method split:</b> the
     * budget views are {@code spendByKey(keyId, windowSeconds)} (the current reset
     * window — what the header/notifier report and what a windowed cap checks) and
     * {@code totalSpendByKey(keyId)} (all-time, never trimmed — the lifetime cap's
     * number and the future report's windowed-key total); read the one that matches
     * the question, never one for the other.
     *
     * <p>{@code provider} is the actually-dispatched backend threaded from the
     * controller's dispatch seam (the router's dispatch observer — the LB pick / retry
     * failover target), NOT re-resolved from the model alias: {@code Router.route} is
     * deliberately the config-first candidate in balanced/resilient mode, so a
     * model-alias resolver would attribute every call to the wrong provider exactly in
     * the multi-provider-alias configs the cost-based balancer exists for.
     */
    Finalized finalize(
            HttpRequest<?> httpRequest,
            ChatRequest request,
            ChatResponse response,
            Preflight preflight,
            long durationMillis,
            String provider) {
        if (noop) {
            return Finalized.NONE;
        }
        Usage usage = response.usage();
        // Re-resolve from actual prompt tokens so a request that crossed the
        // long-context threshold at settle is billed at the long-tier rates, even
        // if the pre-dispatch estimate was still under the floor.
        PricingRate rate = rateForUsage(request, usage);
        if (preflight == Preflight.NONE) {
            // Auth-off: no key accounting, but the unlabeled Tier-1 totals still
            // record (keyId null = auth-off; per-key series are never created). The
            // usage metric records BEFORE the OK row — a throwing recorder must not
            // leave an OK CallRecord behind and then propagate into the controller's
            // catch (which would write a second ERROR row: OK+ERROR for one request).
            long actualMicro = CostCalculator.costMicroUsd(usage, rate, searchCount(response));
            metricsRecorder.recordUsage(null, usage, actualMicro);
            // writer: one Tier-1 CallRecord per request, exactly once — status OK on
            // the settled-success path (the error statuses land via recordFailure /
            // recordStreamFailure — finalize and those paths are mutually exclusive).
            writeCallRecord(null, request, usage, actualMicro, false, CallStatus.OK, durationMillis, provider);
            return new Finalized(false, null, CostCalculator.breakdown(usage, rate, searchCount(response)));
        }
        KeyRecord key = preflight.key();
        try {
            // The whole accounting block is failure-contained. A throwing
            // ledger settle/recordSpend (Postgres SQL failure) or a CostCalculator
            // rejection of a malformed negative-usage canonical must (a) release the
            // reservation — the controller catch's release is the backstop, this is the
            // primary — and (b) leave the OK CallRecord unwritten, so the controller
            // catch writes exactly one (ERROR) row: the exactly-once pin, never OK+ERROR
            // for one request. The OK write is deliberately the LAST side effect — the
            // soft notify therefore runs BEFORE it (as it does on the stream settle
            // path): notifySoft is fully guarded, ledger read included, so nothing
            // after a successful settle can turn a fully-accounted 200 into a 500.
            long actualMicro = CostCalculator.costMicroUsd(usage, rate, searchCount(response));
            if (preflight.reserved()) {
                spendLedger.settle(
                        key.id(), preflight.estimateMicroUsd(), actualMicro, preflight.reservationWindowStart());
            }
            spendLedger.recordSpend(key.id(), actualMicro);
            if (key.tpm() != null) {
                long actualTokens = usage == null ? 0 : usage.promptTokens() + usage.completionTokens();
                accumulateTpm(key, actualTokens);
            }
            metricsRecorder.recordUsage(key.id(), usage, actualMicro);
            CostBreakdown split = CostCalculator.breakdown(usage, rate, searchCount(response));
            long used = preflight.soft() ? notifySoft(key) : 0L;
            writeCallRecord(key, request, usage, actualMicro, false, CallStatus.OK, durationMillis, provider);
            if (preflight.soft()) {
                return new Finalized(true, used, split);
            }
            return new Finalized(false, null, split);
        } catch (RuntimeException e) {
            // The reservation must never leak from the settle path (a leaked pending
            // balance 429s the key "budget exceeded" forever). release is a no-op for
            // non-reserved preflights and harmless after a partial settle (pending is
            // clamped at 0); the throw propagates so the client gets the true 5xx.
            release(preflight);
            throw e;
        }
    }

    /**
     * Roll an aborted reservation back (upstream error / codec failure after
     * {@link #enforce}): the controller's catch path calls this so a reservation never
     * leaks. No-op for {@link Preflight#NONE} and non-reserved preflights.
     *
     * <p><b>Log-and-drop.</b> The ledger call is guarded: every call site sits in a
     * catch block or a stream close hook, so a rethrow would (a) replace the caller's
     * true exception — the controller's catch rethrows the original envelope, and the
     * stream paths rethrow the upstream error — and (b) on the close-hook path skip
     * the {@code upstream.close} that follows it (a connection leak). A transient
     * ledger failure is therefore logged and dropped, the same best-effort posture as
     * {@link #accumulateTpm}, {@link #notifySoft} and the call-ledger write. (The
     * reservation itself stays pending in that degraded case — the ledger is down;
     * there is nothing better to do, and the alternative is worse.)
     */
    void release(Preflight preflight) {
        if (noop || preflight == Preflight.NONE || !preflight.reserved()) {
            return;
        }
        try {
            spendLedger.release(preflight.key().id(), preflight.estimateMicroUsd(), preflight.reservationWindowStart());
        } catch (RuntimeException e) {
            LOG.warn("reservation release dropped (ledger failure): {}", e.toString());
        }
    }

    /**
     * Wrap the router's stream for governance accounting (streaming-safe,
     * discipline — see the class javadoc): settle + accumulate + record from the
     * terminal usage-bearing chunk, settle a $0 entry on clean exhaustion without
     * usage, release the reservation on abort/cancel/exception (the close hook), and
     * notify soft (no header possible post-SSE-start). Wraps even for
     * {@link Preflight#NONE} — auth-off streams record the unlabeled token/cost
     * totals only (always-on Tier-1); the chunks pass through unchanged
     * (byte-identical keyless wire behavior, verified by the suites). The
     * wrap's close hook also propagates the close to the upstream stream —
     * the close-releases-the-connection contract and the SSE stall watchdog's
     * unblock depend on the router/adapter stream being released.
     *
     * <p>{@code provider} is the actually-dispatched backend the controller captured
     * from the router's dispatch observer at {@code stream(...)} time (fired before the
     * stream is returned, so it is stable by the time the wrap is built); it rides onto
     * the OK/ERROR {@link CallRecord}s this wrap writes — see {@link #finalize}.
     */
    Stream<StreamChunk> wrapStream(
            Preflight preflight, ChatRequest request, Stream<StreamChunk> upstream, String provider) {
        if (noop) {
            return upstream;
        }
        KeyRecord key = preflight.key();
        Spliterator<StreamChunk> delegate = upstream.spliterator();
        long startNanos = System.nanoTime();
        AtomicBoolean settledOrReleased = new AtomicBoolean();
        AtomicBoolean exhausted = new AtomicBoolean();
        // Last usage-bearing chunk seen (settling happens at exhaustion, not at the
        // FIRST usage chunk): several OpenAI-compatible upstreams attach a running
        // usage object to every chunk — settling at the first one would under-bill
        // and write a partial CallRecord. Matching the router's wrap semantics, the
        // LAST usage chunk wins.
        AtomicReference<StreamChunk> lastUsageChunk = new AtomicReference<>();
        Spliterator<StreamChunk> wrapped = new Spliterator<>() {
            @Override
            public boolean tryAdvance(Consumer<? super StreamChunk> action) {
                StreamChunk[] box = new StreamChunk[1];
                boolean advanced;
                try {
                    advanced = delegate.tryAdvance(chunk -> box[0] = chunk);
                } catch (Throwable t) {
                    // Mid-stream failure (upstream died): exactly one CallRecord per
                    // request — if the terminal usage chunk already settled (an OK
                    // record was written at exhaustion), do NOT write a second
                    // ERROR_UPSTREAM record (the exactly-once pin); the
                    // reservation must not leak either way.
                    if (settledOrReleased.compareAndSet(false, true)) {
                        writeCallRecord(
                                key,
                                request,
                                null,
                                0,
                                true,
                                CallStatus.ERROR_UPSTREAM,
                                elapsedMillis(startNanos),
                                provider);
                        // Guarded release: the CAS is already won, so a throwing
                        // ledger must neither leak the throw over the ORIGINAL
                        // upstream error below (masking it) nor skip the upstream
                        // close the close hook performs.
                        release(preflight);
                    }
                    throw t;
                }
                if (advanced) {
                    StreamChunk chunk = box[0];
                    if (chunk != null && chunk.usage() != null) {
                        lastUsageChunk.set(chunk);
                    }
                    action.accept(chunk);
                } else {
                    exhausted.set(true);
                    if (settledOrReleased.compareAndSet(false, true)) {
                        StreamChunk terminal = lastUsageChunk.get();
                        if (terminal != null) {
                            settleChunk(
                                    preflight,
                                    rateForUsage(request, terminal.usage()),
                                    request,
                                    key,
                                    terminal.usage(),
                                    elapsedMillis(startNanos),
                                    provider);
                        } else {
                            settleZero(
                                    preflight,
                                    rateForUsage(request, null),
                                    request,
                                    key,
                                    elapsedMillis(startNanos),
                                    provider);
                        }
                    }
                }
                return advanced;
            }

            @Override
            public Spliterator<StreamChunk> trySplit() {
                return null;
            }

            @Override
            public long estimateSize() {
                return delegate.estimateSize();
            }

            @Override
            public int characteristics() {
                return delegate.characteristics();
            }
        };
        return StreamSupport.stream(wrapped, false).onClose(() -> {
            if (!settledOrReleased.get()) {
                if (exhausted.get()) {
                    // Rare race: the terminal tryAdvance settled via the branch above;
                    // reaching here unset after exhaustion means the settle threw in a
                    // contained way — the guard path below still releases.
                    releaseIfUnsettled(preflight, settledOrReleased);
                } else {
                    // Abort/cancel before exhaustion: release the reservation (no-op for
                    // NONE). Guarded: a throwing ledger must not skip the
                    // upstream.close below (a leaked adapter connection).
                    releaseIfUnsettled(preflight, settledOrReleased);
                }
            }
            // Propagate the close to the router/adapter stream. A close on a
            // StreamSupport-wrapped stream does NOT cascade to its source (JDK 25),
            // so without this the router wrap's onClose → underlying.close → the
            // adapter's socket release never runs on cancel/stall/exhaustion — leaking
            // the upstream connection and defeating the close-releases-the-
            // connection contract and the stall watchdog's unblock. The router wrap's
            // underlying.close is idempotent (JDK close-once) and the settle above
            // is CAS-gated, so double-close is safe.
            try {
                upstream.close();
            } catch (RuntimeException e) {
                // Best-effort: a throwing router/adapter close must never mask the
                // governance settle/release above or escape the stream close.
                LOG.warn("upstream close failed on stream-wrap close: {}", e.toString());
            }
        });
    }

    /** Package-private accessor for the factory test (soft-cap default resolution). */
    double softCapFraction() {
        return softCapFraction;
    }

    private void settleChunk(
            Preflight preflight,
            PricingRate rate,
            ChatRequest request,
            KeyRecord key,
            Usage usage,
            long durationMillis,
            String provider) {
        // The stream settle runs OUTSIDE tryAdvance's delegate catch and AFTER
        // the settledOrReleased CAS flipped — a throwing ledger settle/recordSpend or
        // CostCalculator rejection must release the reservation here, or onClose's
        // `settledOrReleased.get` skip would leak it (the flag is already set, so the
        // flag never sticks without accounting). The usage metric records BEFORE the
        // OK row and the OK CallRecord write stays LAST: a throwing recorder or a
        // mid-settle throw leaves no OK row behind (a recorder failure would otherwise
        // hand the client a 5xx error frame after a cleanly settled stream).
        try {
            long actualMicro = CostCalculator.costMicroUsd(usage, rate);
            if (preflight.reserved()) {
                spendLedger.settle(
                        key.id(), preflight.estimateMicroUsd(), actualMicro, preflight.reservationWindowStart());
            }
            if (key != null) {
                spendLedger.recordSpend(key.id(), actualMicro);
                if (key.tpm() != null) {
                    accumulateTpm(key, usage.promptTokens() + usage.completionTokens());
                }
                if (preflight.soft()) {
                    notifySoft(key);
                }
            }
            // the stream records its usage from the terminal usage-bearing chunk.
            metricsRecorder.recordUsage(key == null ? null : key.id(), usage, actualMicro);
            // one Tier-1 CallRecord per stream, settled from the terminal usage chunk.
            writeCallRecord(key, request, usage, actualMicro, true, CallStatus.OK, durationMillis, provider);
        } catch (RuntimeException e) {
            release(preflight);
            throw e;
        }
    }

    private void settleZero(
            Preflight preflight,
            PricingRate rate,
            ChatRequest request,
            KeyRecord key,
            long durationMillis,
            String provider) {
        try {
            if (preflight.reserved()) {
                spendLedger.settle(key.id(), preflight.estimateMicroUsd(), 0, preflight.reservationWindowStart());
            }
            if (key != null) {
                spendLedger.recordSpend(key.id(), 0);
                if (preflight.soft()) {
                    notifySoft(key);
                }
            }
            // clean exhaustion without a usage chunk records a zero entry
            // (documented limitation — Janus never forces include_usage).
            metricsRecorder.recordUsage(key == null ? null : key.id(), null, 0);
            // one Tier-1 CallRecord per stream (zero tokens — no usage chunk) — the OK
            // write stays LAST, matching every other settle path.
            writeCallRecord(key, request, null, 0, true, CallStatus.OK, durationMillis, provider);
        } catch (RuntimeException e) {
            // This runs in the close hook of an already-200 stream — a ledger
            // settle/recordSpend throw must release the reservation and be dropped (the
            // client's stream is done; a rethrow would skip the upstream close below).
            LOG.warn("stream zero-settle dropped (ledger failure): {}", e.toString());
            release(preflight);
        }
    }

    private void releaseIfUnsettled(Preflight preflight, AtomicBoolean settledOrReleased) {
        if (settledOrReleased.compareAndSet(false, true)) {
            release(preflight);
        }
    }

    /**
     * Settle real TPM tokens at finalize: the fixed-window variant's
     * {@code Math.addExact} throws at the ~9.2e18-token wrap point (Postgres bigint
     * parity — both throw rather than silently wrap, so the two stores stay
     * behaviorally identical), but that throw must never escape the settle path and
     * turn a completed 200 into a 5xx (the stream settle runs outside
     * {@code tryAdvance}'s catch). The accumulate is guarded exactly like the
     * call-ledger write: a pathological counter failure is logged and dropped, and
     * the response/accounting continue.
     */
    private void accumulateTpm(KeyRecord key, long actualTokens) {
        try {
            rateLimiter.accumulate(key.id(), key.tpm(), actualTokens);
        } catch (RuntimeException e) {
            LOG.warn("TPM settle dropped (rate limiter failure): {}", e.toString());
        }
    }

    /**
     * Fire the soft-cap notifier and return the committed micro-USD it reported —
     * the <b>window</b> view ({@code spendByKey(keyId, windowSeconds)}): for a windowed
     * key the header and payload carry the current reset window's spend, not the
     * all-time total. LOW: the whole dispatch — the <b>ledger read included</b> — is
     * guarded exactly like the call-ledger write and the TPM settle: it runs after a
     * successful settle, so a read or notify failure must never escape {@code
     * finalize}/{@code settleChunk} (it would turn a completed 200 into a 5xx —
     * metrics says 5xx, the ledger carries OK+ERROR for one request — and release a
     * reservation that was settled correctly). Best-effort by the {@link Notifier}
     * contract, defended here at the call site regardless.
     *
     * <p>For a windowed key the payload gains {@code window_reset_epoch_seconds} (the
     * epoch second the current window ends) so a webhook sink can say "resets at …"
     * without re-deriving the window arithmetic; lifetime keys keep the exact
     * pre-window payload shape. A failed ledger read drops the event with the read
     * (a payload without the committed figure would be worse than no event) and
     * returns 0 for the header value.
     */
    private long notifySoft(KeyRecord key) {
        long windowSeconds = key.budgetDuration() != null && key.budgetDuration() > 0 ? key.budgetDuration() : 0;
        long used = 0;
        try {
            used = spendLedger.spendByKey(key.id(), windowSeconds);
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("key_id", key.id());
            payload.put("tier", TIER_SOFT);
            payload.put("committed_micro_usd", used);
            payload.put("cap_micro_usd", toMicroUsd(key.budgetUsd()));
            if (windowSeconds > 0) {
                payload.put("window_reset_epoch_seconds", currentWindowStart(windowSeconds) + windowSeconds);
            }
            notifier.notify(Notifier.EVENT_BUDGET_EXCEEDED, Map.copyOf(payload));
        } catch (RuntimeException e) {
            LOG.warn("budget_exceeded notify dropped (ledger/notifier failure): {}", e.toString());
        }
        return used;
    }

    private static KeyRecord keyFrom(HttpRequest<?> httpRequest) {
        return httpRequest
                .getAttribute(KeyAuthFilter.KEY_ATTRIBUTE, KeyRecord.class)
                .orElse(null);
    }

    /**
     * record a rejected/failed request (mutually exclusive with finalize — the
     * two paths never both fire for one request). The status is the coarse HTTP class
     * the exception handler produces (the / mappers are the classification
     * vocabulary): 429 → limit, other 4xx → client, 502/504 → upstream, else internal.
     * {@code httpRequest} carries the governing key the filter attached — a
     * pre-dispatch denial (m1: the RPM/TPM/budget 429) throws before {@code preflight}
     * is built, so the key is threaded from the attribute, never the auth-off sentinel.
     * {@code durationMillis} is the controller-measured wall-clock duration.
     * {@code provider} is the actually-dispatched backend threaded from the dispatch
     * seam — null when no dispatch happened (a decode failure or a pre-dispatch denial
     * never reached any backend, so the row correctly carries no provider).
     */
    void recordFailure(
            HttpRequest<?> httpRequest,
            Preflight preflight,
            ChatRequest request,
            int httpStatus,
            long durationMillis,
            String provider) {
        if (noop) {
            return;
        }
        KeyRecord key =
                preflight == Preflight.NONE ? (httpRequest == null ? null : keyFrom(httpRequest)) : preflight.key();
        CallStatus status =
                switch (httpStatus / 100) {
                    case 4 ->
                        httpStatus == HttpStatus.TOO_MANY_REQUESTS.getCode()
                                ? CallStatus.ERROR_LIMIT
                                : CallStatus.ERROR_CLIENT;
                    case 5 ->
                        (httpStatus == HttpStatus.BAD_GATEWAY.getCode()
                                        || httpStatus == HttpStatus.GATEWAY_TIMEOUT.getCode())
                                ? CallStatus.ERROR_UPSTREAM
                                : CallStatus.ERROR_INTERNAL;
                    default -> CallStatus.ERROR_INTERNAL;
                };
        // A failed streaming request keeps its stream=true marker (request.stream).
        writeCallRecord(key, request, null, 0, request != null && request.stream(), status, durationMillis, provider);
    }

    /**
     * the one {@link CallRecord} writer — maps the seam fields from the
     * gateway vocabulary (Tier-1: token fields + cost only, never bodies). {@code
     * usage == null} (failure paths) records zero tokens; {@code key == null}
     * (auth-off) records a null keyId; {@code provider} is the actually-dispatched
     * backend threaded from the dispatch seam (null when no backend was dispatched —
     * decode failure, unknown model, pre-dispatch denial). No-op when no
     * {@link CallStore} is wired (pre- test construction).
     */
    private void writeCallRecord(
            KeyRecord key,
            ChatRequest request,
            Usage usage,
            long costMicroUsd,
            boolean stream,
            CallStatus status,
            long durationMillis,
            String provider) {
        if (callStore == null) {
            return;
        }
        long now = clock.millis();
        String keyId = key == null ? null : key.id();
        String model = request == null ? null : request.model();
        CallRecord record;
        if (usage == null) {
            record = new CallRecord(
                    java.util.UUID.randomUUID().toString(),
                    keyId,
                    model,
                    provider,
                    0,
                    0,
                    0,
                    null,
                    null,
                    costMicroUsd,
                    durationMillis,
                    stream,
                    status,
                    now);
        } else {
            record = new CallRecord(
                    java.util.UUID.randomUUID().toString(),
                    keyId,
                    model,
                    provider,
                    usage,
                    costMicroUsd,
                    durationMillis,
                    stream,
                    status,
                    now);
        }
        long writeStartNanos = System.nanoTime();
        try {
            callStore.recordCall(record);
        } catch (RuntimeException e) {
            // Call-ledger persistence must NEVER alter the request path —
            // a throwing store (e.g. Postgres down mid-write) is logged and dropped,
            // matching the Notifier "never raises" posture. Without this, a write
            // failure inside recordFailure propagated OUT of the controller's catch,
            // replacing the client's true 429/401/502 envelope with a 500; and inside
            // finalize it would abort settle/accumulate before the accounting ran.
            // The record itself is Tier-1 (no bodies, no keys), so the log line is
            // privacy-safe; the request's own envelope and ledger accounting continue.
            LOG.warn("call-ledger write dropped (store failure): {}", e.toString());
        } finally {
            // time every store-write ATTEMPT (success and contained
            // failure alike — a pool-exhaustion timeout is the tail this timer
            // exists to see) through the writer's own best-effort posture: a
            // recording failure is logged and dropped exactly like a write
            // failure, never observed by the request path.
            try {
                metricsRecorder.recordLedgerWrite(System.nanoTime() - writeStartNanos);
            } catch (RuntimeException e) {
                LOG.warn("call-ledger write timing dropped: {}", e.toString());
            }
        }
    }

    /** Wall-clock millis since {@code startNanos} (the controllers' measurement). */
    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * Resolve the pricing row for settle: actual prompt tokens when usage is
     * present, else the pre-dispatch prompt estimate. Applies the long-context
     * tier when the prompt is at or above {@code long-context-threshold}.
     */
    private PricingRate rateForUsage(ChatRequest request, Usage usage) {
        long prompt = usage != null ? usage.billedPromptTokens() : estimatePromptTokens(request);
        String model = request == null ? null : request.model();
        return priceTable.rateFor(model).forPromptTokens(prompt);
    }

    /**
     * The conservative pre-dispatch token estimate: the request's
     * {@code max_tokens} when present, else the pricing row's {@code defaultMaxTokens},
     * else {@link #DEFAULT_MAX_TOKENS} (4096, the reference implementation semantics default). Package-
     * private so the precedence chain and the fallback are directly pinned by a test.
     */
    static int estimateTokens(ChatRequest request, PricingRate rate) {
        if (request.maxTokens() != null && request.maxTokens() > 0) {
            return request.maxTokens();
        }
        if (rate.defaultMaxTokens() > 0) {
            return rate.defaultMaxTokens();
        }
        return DEFAULT_MAX_TOKENS;
    }

    /**
     * The conservative pre-dispatch <b>prompt</b>-token estimate: the sum
     * of the message contents' UTF-16 code-unit lengths (the {@code system} field plus
     * every message's plain-text {@code content}) divided by 4 — the standard
     * ~4-chars-per-token heuristic. It feeds {@link CostCalculator#estimatePromptMicroUsd}
     * so the budget admission estimate prices the prompt's input cost up front (the old
     * reserve that priced prompt at 0). A heuristic, not a bound: tool-call
     * argument JSON is not counted and dense-script tokens can exceed chars/4, so a
     * pathological prompt can still overshoot before the next-request gate catches it —
     * the common large-plain-text-prompt overshoot is priced, and {@code settle}
     * corrects the reservation to actual. Package-private so the content-length sum and
     * the /4 divisor are directly pinned by a test.
     */
    static long estimatePromptTokens(ChatRequest request) {
        if (request == null) {
            return 0;
        }
        long chars = 0;
        if (request.system() != null) {
            chars += request.system().length();
        }
        if (request.messages() != null) {
            for (Message message : request.messages()) {
                String content =
                        switch (message) {
                            case SystemMessage m -> m.content();
                            case UserMessage m -> m.plainText();
                            case AssistantMessage m -> m.content();
                            case ToolMessage m -> m.content();
                            case DeveloperMessage m -> m.content();
                        };
                if (content != null) {
                    chars += content.length();
                }
            }
        }
        return chars / 4;
    }

    /**
     * The aligned window start for {@code windowSeconds} (the ledger's derivation —
     * {@code floorDiv(nowSeconds, windowSeconds) × windowSeconds}).
     */
    private long currentWindowStart(long windowSeconds) {
        long nowSeconds = clock.millis() / 1000;
        return Math.floorDiv(nowSeconds, windowSeconds) * windowSeconds;
    }

    /**
     * Seconds until the next aligned window end (the TPM pre-check Retry-After at 60s;
     * a windowed budget's Retry-After at the key's {@code budgetDuration} — the
     * duration-aware form). Always in {@code [1, windowSeconds]} for a monotonic
     * clock (the exact-boundary second starts a fresh window, so the reset is a full
     * window away). Exact for the fixed-window variants; for the sliding/token-bucket
     * TPM variant this is the conservative window-aligned value — the bucket may
     * refill sooner ({@code deficit ÷ rate}), but {@code wouldExceed} surfaces only a
     * boolean, so the aligned value is the documented upper bound (never
     * under-promises).
     */
    private long secondsUntilWindowReset(long windowSeconds) {
        long nowSeconds = clock.millis() / 1000;
        return windowSeconds - Math.floorMod(nowSeconds, windowSeconds);
    }

    /**
     * USD budget → integer micro-USD. {@code null} and non-positive map to {@code 0} =
     * "no cap" (the invariant: null means no cap, not zero). Non-finite values are
     * clamped to {@code 0} too — {@code AdminKeysController.validateCaps} rejects them
     * at creation, but a non-finite value that ever reaches a record (e.g. seeded
     * directly through {@code KeyStore}) must degrade to the explicit no-cap semantics,
     * never to a fabricated {@code Long.MAX_VALUE} cap or a NaN micro-USD total. A
     * finite-but-huge budget (≥ {@code Long.MAX_VALUE / 1e6} USD) saturates to
     * {@code Long.MAX_VALUE} micro — the largest representable cap, i.e. a de-facto no
     * cap ({@code validateCaps} rejects such budgets at the API, so this clamp is
     * defense-in-depth for directly-seeded records).
     */
    private static long toMicroUsd(Double usd) {
        if (usd == null || !Double.isFinite(usd) || usd <= 0) {
            return 0;
        }
        double micro = usd * 1_000_000.0 + 0.5;
        if (micro >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) Math.floor(micro);
    }

    /**
     * Hosted web searches on the response (each {@code WebSearchCall} is one billed
     * search — Anthropic bills per 1k searches besides result tokens). Non-streaming
     * settle only: the stream deltas carry no hosted calls (documented — streaming
     * hosted-event synthesis is deferred), so stream settles price tokens only.
     */
    private static long searchCount(ChatResponse response) {
        if (response == null || response.hostedToolCalls() == null) {
            return 0;
        }
        return response.hostedToolCalls().stream()
                .filter(call -> call instanceof io.amscotti.janus.core.model.HostedToolCall.WebSearchCall)
                .count();
    }
}
