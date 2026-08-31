package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.CallStatus;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * steps 9–10 — end-to-end governance enforcement through the filter + controllers
 * on <b>both faces</b> (auth on via the test-only master-key property, real {@link
 * Governance} via {@link TestGovernanceFactory} with the shared fixed clock and
 * DeepSeek pricing):
 *
 * <ul>
 * <li>pre-dispatch: the third RPM-exceeding request → 429 {@code rate_limit_error}
 * with {@code Retry-After} on the OpenAI and Anthropic envelopes; an over-budget
 * key → 429 <b>before dispatch</b> (the fake backend's call count stays 0); a
 * TPM pre-check denial via {@code max_tokens}; a null-cap key passes through;
 * <li>post-dispatch: exact micro-USD cost lands in the shared ledger, TPM is
 * accumulated at finalize, a soft-cap crossing attaches the
 * {@code X-Janus-Budget-Warning} header on the success response and fires the
 * {@code :budget_exceeded} notifier (recording notifier asserts the event shape);
 * <li>abort paths: an upstream 500 after the reservation releases it (a follow-up
 * request succeeds — a leaked reservation would deny); streams settle at the
 * terminal usage chunk, release on abort/cancel, and soft-exceed is
 * notifier-only (no header possible post-SSE-start).
 * </ul>
 *
 * <p>No network: the router is the {@link TestRouterFactory} fake backend; the fixed
 * clock (2026-08-03T00:00:00Z, epoch-aligned) makes {@code Retry-After} exactly 60.
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
@Property(name = "janus.test.governance", value = "true")
class GovernanceControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    EmbeddedServer server;

    @Inject
    KeyStore keyStore;

    // --------------------------------------------------- pre-dispatch: RPM on both faces

    @Test
    void thirdRequestBeyondRpmIs429WithRetryAfterOnOpenAiFace() {
        KeyStore.CreatedKey created = createKey(null, 2, null);
        TestRouterFactory.BACKEND.completeReturns(chatResponse());

        for (int i = 0; i < 2; i++) {
            assertEquals(
                    HttpStatus.OK,
                    postOpenAi(created.fullKey(), 16).getStatus(),
                    "request " + (i + 1) + " within rpm=2");
        }
        HttpResponse<?> denied =
                errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody(false, 16)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertTrue(
                denied.getBody(String.class).orElse("").contains("\"type\":\"rate_limit_error\""),
                denied.getBody(String.class).orElse(""));
        assertEquals(
                "60",
                denied.getHeaders().get(HttpHeaders.RETRY_AFTER),
                "Retry-After = seconds until the aligned window end (fixed clock)");
    }

    @Test
    void thirdRequestBeyondRpmIs429WithRetryAfterOnAnthropicFace() {
        KeyStore.CreatedKey created = createKey(null, 2, null);
        TestRouterFactory.BACKEND.completeReturns(chatResponse());

        for (int i = 0; i < 2; i++) {
            assertEquals(HttpStatus.OK, postAnthropic(created.fullKey(), 16).getStatus());
        }
        HttpResponse<?> denied = errorResponse(postRequest("/v1/messages", created.fullKey(), anthropicBody(16)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        String body = denied.getBody(String.class).orElse("");
        assertTrue(body.contains("\"type\":\"error\""), body);
        assertTrue(body.contains("\"type\":\"rate_limit_error\""), body);
        assertEquals("60", denied.getHeaders().get(HttpHeaders.RETRY_AFTER));
    }

    // ------------------------------------------------- pre-dispatch: budget + TPM + null caps

    @Test
    void overBudgetKeyIs429BeforeDispatchWithNoUpstreamCall() {
        KeyStore.CreatedKey created = createKey(0.001, null, null); // $0.001 = 1000 micro cap
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        TestRouterFactory.BACKEND.completeCalls.clear();

        HttpResponse<?> denied =
                errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody(false, 1024)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        String body = denied.getBody(String.class).orElse("");
        assertTrue(body.contains("\"type\":\"rate_limit_error\""), body);
        assertTrue(body.contains("key budget exceeded"), body);
        assertEquals(
                0,
                TestRouterFactory.BACKEND.completeCalls.size(),
                "a throttled request must never be dispatched upstream");
    }

    @Test
    void overBudgetKeyIs429BeforeDispatchOnAnthropicFace() {
        KeyStore.CreatedKey created = createKey(0.001, null, null);
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        TestRouterFactory.BACKEND.completeCalls.clear();

        HttpResponse<?> denied = errorResponse(postRequest("/v1/messages", created.fullKey(), anthropicBody(1024)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertTrue(
                denied.getBody(String.class).orElse("").contains("\"type\":\"rate_limit_error\""),
                denied.getBody(String.class).orElse(""));
        assertEquals(0, TestRouterFactory.BACKEND.completeCalls.size());
    }

    @Test
    void tpmPreCheckDeniesConservativelyViaMaxTokens() {
        KeyStore.CreatedKey created = createKey(null, null, 100); // tpm = 100
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        TestRouterFactory.BACKEND.completeCalls.clear();

        HttpResponse<?> denied =
                errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody(false, 150)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertTrue(
                denied.getBody(String.class).orElse("").contains("\"type\":\"rate_limit_error\""),
                denied.getBody(String.class).orElse(""));
        assertEquals(
                "60",
                denied.getHeaders().get(HttpHeaders.RETRY_AFTER),
                "TPM denials carry the seconds until the token window resets");
        assertEquals(
                0,
                TestRouterFactory.BACKEND.completeCalls.size(),
                "a TPM-pre-checked request must never be dispatched upstream");
    }

    @Test
    void openAiRequestOmittingMaxTokensOnSmallTpmKeyIs429Pinned() {
        // A keyed OpenAI request that omits max_tokens falls back to the
        // pricing row's default-max-tokens (4096) for the conservative TPM pre-check —
        // a tpm=100 key denies even a request whose real usage would be tiny (the
        // estimate is deliberately conservative; the request would still be a 429).
        // Pinned so this documented behavior cannot silently change.
        KeyStore.CreatedKey created = createKey(null, null, 100);
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        TestRouterFactory.BACKEND.completeCalls.clear();

        HttpResponse<?> denied = errorResponse(
                postRequest(
                        "/v1/chat/completions",
                        created.fullKey(),
                        "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertTrue(
                denied.getBody(String.class).orElse("").contains("\"type\":\"rate_limit_error\""),
                denied.getBody(String.class).orElse(""));
        assertEquals("60", denied.getHeaders().get(HttpHeaders.RETRY_AFTER));
        assertEquals(0, TestRouterFactory.BACKEND.completeCalls.size());
    }

    @Test
    void promptHeavyRequestIsTpmDeniedBeforeDispatchPinned() {
        // The TPM pre-check prices the prompt side too — the accumulator
        // counts prompt + completion, so a prompt-heavy request with a small max_tokens
        // would otherwise cross the cap undetected until the NEXT request (the exact class
        // of overshoot the budget gate already prices up front via estimatePromptMicroUsd).
        // The old output-only estimate (16 ≤ 100) passed this; the combined estimate
        // (500 prompt + 16 output = 516 > 100) denies before dispatch.
        KeyStore.CreatedKey created = createKey(null, null, 100); // tpm = 100
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        TestRouterFactory.BACKEND.completeCalls.clear();

        String bigPrompt = "x".repeat(2_000); // 500 prompt tokens by the /4 heuristic
        String body = "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"" + bigPrompt
                + "\"}],\"stream\":false,\"max_tokens\":16}";
        HttpResponse<?> denied = errorResponse(postRequest("/v1/chat/completions", created.fullKey(), body));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertTrue(
                denied.getBody(String.class).orElse("").contains("\"type\":\"rate_limit_error\""),
                denied.getBody(String.class).orElse(""));
        assertEquals(
                0,
                TestRouterFactory.BACKEND.completeCalls.size(),
                "a TPM-pre-checked request must never be dispatched upstream");
    }

    @Test
    void rpmSlotIsConsumedBeforeTheBudgetDenialPinned() {
        // The RPM consume-on-allow runs before the budget gate, so a key
        // with a tiny rpm and an exhausted budget flips from budget-429 to rpm-429 on
        // retries (reference-aligned — the reference increments before checking). Pinned so the
        // ordering cannot silently change and surprise a retrying client.
        KeyStore.CreatedKey created = createKey(0.001, 1, null); // budget 1000 micro, rpm 1
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        TestRouterFactory.BACKEND.completeCalls.clear();

        HttpResponse<?> first =
                errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody(false, 1024)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, first.getStatus());
        assertTrue(
                first.getBody(String.class).orElse("").contains("key budget exceeded"),
                "attempt 1: the RPM slot is consumed, then the budget reserve denies");

        HttpResponse<?> second =
                errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody(false, 1024)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getStatus());
        assertTrue(
                second.getBody(String.class).orElse("").contains("rate limit exceeded"),
                "attempt 2: the already-consumed RPM slot denies first — reference-aligned ordering");
        assertEquals("60", second.getHeaders().get(HttpHeaders.RETRY_AFTER));
        assertEquals(0, TestRouterFactory.BACKEND.completeCalls.size(), "neither denial is dispatched upstream");
    }

    @Test
    void nullCapKeyPassesThroughUnenforced() {
        KeyStore.CreatedKey created = createKey(null, null, null); // all caps null ⇒ unenforced
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        assertEquals(HttpStatus.OK, postOpenAi(created.fullKey(), 16).getStatus());
        assertEquals(1, TestRouterFactory.BACKEND.completeCalls.size());
    }

    @Test
    void promptHeavyRequestIsBudgetDeniedBeforeDispatchViaPromptEstimate() {
        // The pre-dispatch reserve must price the prompt's input cost, not
        // just the output estimate — otherwise a prompt-heavy request with a tiny
        // max_tokens slips past a hard cap it then overshoots on settle. A 40k-char
        // prompt × 0.14/1K input ≈ 1 400 000 micro, far above the 1000-micro cap even
        // though max_tokens=1 prices only ~280 micro of output.
        KeyStore.CreatedKey created = createKey(0.001, null, null); // $0.001 = 1000 micro cap
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        TestRouterFactory.BACKEND.completeCalls.clear();

        String bigPrompt = "x".repeat(40_000);
        String body = "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"" + bigPrompt
                + "\"}],\"stream\":false,\"max_tokens\":1}";
        HttpResponse<?> denied = errorResponse(postRequest("/v1/chat/completions", created.fullKey(), body));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertTrue(
                denied.getBody(String.class).orElse("").contains("\"type\":\"rate_limit_error\""),
                denied.getBody(String.class).orElse(""));
        assertTrue(
                denied.getBody(String.class).orElse("").contains("key budget exceeded"),
                denied.getBody(String.class).orElse(""));
        assertEquals(
                0,
                TestRouterFactory.BACKEND.completeCalls.size(),
                "a prompt-overshoot request must be denied before dispatch");
    }

    // ----------------------------------------------- post-dispatch: accounting + soft cap

    @Test
    void softCapCrossingWarnsAndNotifiesOnNonStreamingSuccess() {
        KeyStore.CreatedKey created = createKey(0.3, null, null); // cap 300_000, soft 240_000
        TestRouterFactory.BACKEND.completeReturns(chatResponse()); // usage 10/5 ⇒ 2_800 micro

        HttpResponse<String> ok = postOpenAi(created.fullKey(), 1024); // estimate 286_720 ≥ soft
        assertEquals(HttpStatus.OK, ok.getStatus());
        assertEquals(
                Governance.WARNING_SOFT,
                ok.getHeaders().get(Governance.HEADER_BUDGET_WARNING),
                "soft crossing attaches the warning header on non-streaming successes");
        assertTrue(
                ok.getHeaders().get(Governance.HEADER_BUDGET_USED) != null,
                "the settled micro-USD header accompanies the warning");

        List<RecordingNotifier.Event> events = TestGovernanceFactory.NOTIFIER.snapshot();
        RecordingNotifier.Event event = events.stream()
                .filter(e -> e.payload().get("key_id").equals(created.record().id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no budget_exceeded event for key " + created.record().id()));
        assertEquals("budget_exceeded", event.name());
        assertEquals("soft", event.payload().get("tier"));
        assertEquals(2_800L, event.payload().get("committed_micro_usd"), "exact micro-USD after settle");
    }

    @Test
    void softWarningFiresOnReserveTimeEstimateEvenWhenActualCostIsZeroPinned() {
        // The soft flag is decided at reserve time from settled + pending
        // (the estimate), not the settled actual — a request whose estimate crosses the
        // soft line but whose actual cost is zero still carries the soft header.
        // Documented as accepted (fires on the reserve-time total); pinned.
        KeyStore.CreatedKey created = createKey(0.3, null, null); // cap 300_000, soft 240_000
        TestRouterFactory.BACKEND.completeReturns(chatResponse(new Usage(0, 0, 0))); // actual $0

        HttpResponse<String> ok = postOpenAi(created.fullKey(), 1024); // estimate 286_720 ≥ soft
        assertEquals(HttpStatus.OK, ok.getStatus());
        assertEquals(
                Governance.WARNING_SOFT,
                ok.getHeaders().get(Governance.HEADER_BUDGET_WARNING),
                "the soft flag is decided at reserve time (estimate), not the settled actual — pinned");
        assertEquals(
                "0",
                ok.getHeaders().get(Governance.HEADER_BUDGET_USED),
                "the header carries the settled micro-USD — the zero actual, not the estimate");
    }

    @Test
    void unbudgetedKeySpendByKeyStaysZeroWhileRecentRingHoldsEntriesPinned() {
        // Settle runs only for reserved (budgeted) preflights — an
        // unbudgeted key's all-time spendByKey stays 0 (the value feeds only the
        // soft-warning path, which needs a budget), while recordSpend still fills the
        // recent ring. Budgeted-keys-only semantics; pinned so a future
        // "total spend by key" report cannot silently under-report.
        KeyStore.CreatedKey created = createKey(null, null, null);
        TestRouterFactory.BACKEND.completeReturns(chatResponse()); // usage 10/5 ⇒ 2_800 micro
        assertEquals(HttpStatus.OK, postOpenAi(created.fullKey(), 16).getStatus());

        assertEquals(
                0,
                TestGovernanceFactory.LEDGER.spendByKey(created.record().id(), 0),
                "an unbudgeted key never settles → all-time spend stays 0 (budgeted-keys-only)");
        assertEquals(
                2_800L,
                TestGovernanceFactory.LEDGER
                        .recent(created.record().id(), 5)
                        .getFirst()
                        .microUsd(),
                "recordSpend still fills the recent ring for unbudgeted keys");
    }

    @Test
    void reservationReleasedWhenUpstreamThrows() {
        KeyStore.CreatedKey created = createKey(0.3, null, null);
        TestRouterFactory.BACKEND.completeFails(new ProviderException(ProviderException.TYPE_UPSTREAM_5XX, "boom"));
        assertEquals(
                HttpStatus.BAD_GATEWAY,
                errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody(false, 1024)))
                        .getStatus());

        // A leaked reservation would deny the follow-up (573_440 ≥ 300_000); a 200
        // proves the abort path released it.
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        assertEquals(HttpStatus.OK, postOpenAi(created.fullKey(), 1024).getStatus());
    }

    @Test
    void negativeUsageFromUpstreamIs500ReleasesReservationAndWritesExactlyOneErrorRow() {
        // A malformed/broken upstream that yields negative token counts reaches
        // the CostCalculator in finalize (OpenAiMessageCodec clamps only the cache claim —
        // prompt/completion pass through raw). The reservation must release (a leaked
        // 286_720-micro pending balance would deny the follow-up against the 500_000 cap)
        // and the call ledger must carry exactly one (ERROR) row — never an OK row plus an
        // ERROR row for one request (the OK write is finalize's last side effect).
        KeyStore.CreatedKey created = createKey(0.5, null, null); // cap 500_000 micro
        TestRouterFactory.BACKEND.completeReturns(chatResponse(new Usage(-5, 10, 5)));
        TestRouterFactory.BACKEND.completeCalls.clear();

        HttpResponse<?> failed =
                errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody(false, 1024)));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failed.getStatus());
        String body = failed.getBody(String.class).orElse("");
        assertTrue(body.contains("\"type\":\"api_error\""), body);

        List<CallRecord> records =
                TestGovernanceFactory.CALLS.recentCalls(created.record().id(), 10);
        assertEquals(1, records.size(), "exactly one Tier-1 CallRecord per request");
        assertEquals(CallStatus.ERROR_INTERNAL, records.get(0).status(), "the cost rejection is a 500 → internal");
        assertEquals(
                0,
                TestGovernanceFactory.LEDGER.spendByKey(created.record().id(), 0),
                "a rejected settle must never commit spend");

        // The reservation was released, not leaked: a follow-up request succeeds (a leaked
        // 286_720-micro reservation would deny the 500_000-micro-cap key).
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        assertEquals(HttpStatus.OK, postOpenAi(created.fullKey(), 1024).getStatus());
    }

    // -------------------------- post-dispatch encode failure (exactly-once)

    @Test
    void nonStreamingEncodeFailureWritesOneErrorRecordAndNoSettledSpendOnOpenAiFace() {
        // The encode must happen before finalize. A canonical whose blank id
        // fails codec.validateResponse (the provider adapters decode without that
        // validation, so a fake/custom backend can reach the encode) must skip every
        // ledger/spend side effect: HTTP 500 api_error, exactly one ERROR CallRecord, no
        // settled spend for the 500, and the reservation released — the
        // "one Tier-1 CallRecord per request" invariant end-to-end.
        KeyStore.CreatedKey created = createKey(0.3, null, null);
        TestRouterFactory.BACKEND.completeReturns(new ChatResponse(
                "",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of()));
        TestRouterFactory.BACKEND.completeCalls.clear();

        HttpResponse<?> failed =
                errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody(false, 1024)));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failed.getStatus());
        String body = failed.getBody(String.class).orElse("");
        assertTrue(body.contains("\"type\":\"api_error\""), body);

        List<CallRecord> records =
                TestGovernanceFactory.CALLS.recentCalls(created.record().id(), 10);
        assertEquals(1, records.size(), "exactly one Tier-1 CallRecord per request");
        assertEquals(CallStatus.ERROR_INTERNAL, records.get(0).status(), "the encode failure is a 500 → internal");
        assertEquals(
                0,
                TestGovernanceFactory.LEDGER.spendByKey(created.record().id(), 0),
                "a 500 must never settle spend (encode happens before finalize)");

        // The reservation was released, not leaked: a follow-up request succeeds (a
        // leaked 286_720 reservation would deny the 300_000-cap key).
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        assertEquals(HttpStatus.OK, postOpenAi(created.fullKey(), 1024).getStatus());
    }

    @Test
    void nonStreamingEncodeFailureWritesOneErrorRecordAndNoSettledSpendOnAnthropicFace() {
        // The Anthropic face's encode validates id/model/choices the same way (
        // mirrors the OpenAI fix — the two controllers must share the ordering).
        KeyStore.CreatedKey created = createKey(0.3, null, null);
        TestRouterFactory.BACKEND.completeReturns(new ChatResponse(
                "",
                "message",
                0L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "end_turn")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of()));
        TestRouterFactory.BACKEND.completeCalls.clear();

        HttpResponse<?> failed = errorResponse(postRequest("/v1/messages", created.fullKey(), anthropicBody(1024)));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failed.getStatus());
        String body = failed.getBody(String.class).orElse("");
        assertTrue(body.contains("\"type\":\"api_error\""), body);

        List<CallRecord> records =
                TestGovernanceFactory.CALLS.recentCalls(created.record().id(), 10);
        assertEquals(1, records.size(), "exactly one Tier-1 CallRecord per request");
        assertEquals(CallStatus.ERROR_INTERNAL, records.get(0).status());
        assertEquals(
                0,
                TestGovernanceFactory.LEDGER.spendByKey(created.record().id(), 0),
                "a 500 must never settle spend (encode happens before finalize)");

        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        assertEquals(HttpStatus.OK, postAnthropic(created.fullKey(), 1024).getStatus());
    }

    // ---------------------------------------------------- streaming: settle / release / soft

    @Test
    void streamingTerminalUsageChunkSettlesLedgerAndAccumulatesTpm() throws Exception {
        KeyStore.CreatedKey created = createKey(1.0, null, 100); // budget $1, tpm 100
        StreamChunk c1 = contentChunk("Hello");
        StreamChunk c2 = usageChunk(new Usage(10, 5, 15));
        TestRouterFactory.BACKEND.streamReturns(Stream.of(c1, c2));

        java.net.http.HttpResponse<InputStream> response = streamResponse(openAiBody(true, 16), created.fullKey());
        assertEquals(
                "text/event-stream",
                response.headers().firstValue("Content-Type").orElse(""));
        String all;
        try (InputStream body = response.body()) {
            all = readAll(body);
        }
        assertTrue(all.contains("data: [DONE]"), all);

        // The wrap settles from the terminal usage chunk: 10×0.14 + 5×0.28 = 2 800 micro.
        awaitCondition(
                () -> TestGovernanceFactory.LEDGER.spendByKey(created.record().id(), 0) == 2_800);
        assertTrue(
                TestGovernanceFactory.LEDGER.recent(created.record().id(), 5).stream()
                        .anyMatch(entry -> entry.microUsd() == 2_800),
                "recent holds the settled entry");

        // TPM accumulate at finalize: the next request's pre-check sees the 15 tokens
        // (15 + 100 estimate > tpm 100 ⇒ 429 before dispatch).
        HttpResponse<?> denied =
                errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody(false, 100)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
    }

    @Test
    void streamingAbortReleasesReservation() throws Exception {
        KeyStore.CreatedKey created = createKey(0.3, null, null);
        TestRouterFactory.BACKEND.streamReturns(Stream.of(contentChunk("Hello"), contentChunk(" world")));

        java.net.http.HttpResponse<InputStream> response = streamResponse(openAiBody(true, 1024), created.fullKey());
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals(1, reader.lines().limit(1).count(), "one SSE line arrives"); // then abort
        }

        // The abort releases the reservation (asynchronously via the publisher's close
        // hook): a follow-up request succeeds — a leaked reservation would deny it.
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        awaitCondition(() -> probeOpenAi(created.fullKey(), 1024) == HttpStatus.OK);
    }

    @Test
    void streamingSoftExceedIsNotifierOnlyNoHeader() throws Exception {
        KeyStore.CreatedKey created = createKey(0.3, null, null); // estimate 286_720 ≥ soft 240_000
        TestRouterFactory.BACKEND.streamReturns(Stream.of(contentChunk("Hello"), usageChunk(new Usage(10, 5, 15))));

        java.net.http.HttpResponse<InputStream> response = streamResponse(openAiBody(true, 1024), created.fullKey());
        try (InputStream body = response.body()) {
            assertFalse(
                    response.headers()
                            .firstValue(Governance.HEADER_BUDGET_WARNING)
                            .isPresent(),
                    "SSE headers are already sent — the soft warning must be notifier-only");
            readAll(body);
        }

        List<RecordingNotifier.Event> events = TestGovernanceFactory.NOTIFIER.snapshot();
        assertTrue(
                events.stream()
                        .filter(e -> e.payload()
                                .get("key_id")
                                .equals(created.record().id()))
                        .anyMatch(e -> "soft".equals(e.payload().get("tier"))),
                "soft crossing fires the notifier even when no header is possible");
    }

    @Test
    void streamingWithoutUsageChunkSettlesZeroEntryAndReleasesReservation() throws Exception {
        KeyStore.CreatedKey created = createKey(0.3, null, null);
        // No usage chunk on the terminal chunk (the client did not request
        // include_usage): the wrap settles a $0 / tokens-unknown entry and the
        // reservation is released (documented limitation — Janus never forces
        // include_usage).
        TestRouterFactory.BACKEND.streamReturns(Stream.of(contentChunk("Hello"), contentChunk(" world")));

        java.net.http.HttpResponse<InputStream> response = streamResponse(openAiBody(true, 1024), created.fullKey());
        try (InputStream body = response.body()) {
            readAll(body);
        }

        // The $0 settle is pinned: nothing committed, but a zero entry lands in recent.
        awaitCondition(
                () -> TestGovernanceFactory.LEDGER.spendByKey(created.record().id(), 0) == 0);
        assertTrue(
                TestGovernanceFactory.LEDGER.recent(created.record().id(), 5).stream()
                        .anyMatch(entry -> entry.microUsd() == 0),
                "the zero-token stream settles a $0 ledger entry");

        // The reservation was settled (pending released): a follow-up request succeeds
        // — a leaked reservation (2 × 286_720 ≥ 300_000) would deny it.
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        awaitCondition(() -> probeOpenAi(created.fullKey(), 1024) == HttpStatus.OK);
    }

    @Test
    void anthropicStreamingSettlesFromTerminalUsageChunk() throws Exception {
        KeyStore.CreatedKey created = createKey(1.0, null, 100); // budget $1, tpm 100
        TestRouterFactory.BACKEND.streamReturns(Stream.of(contentChunk("Hello"), usageChunk(new Usage(10, 5, 15))));

        java.net.http.HttpResponse<InputStream> response = anthropicStreamResponse(created.fullKey());
        String all;
        try (InputStream body = response.body()) {
            all = readAll(body);
        }
        assertTrue(all.contains("event: message_stop"), all);

        // The Anthropic face settles from the terminal usage chunk too:
        // 10×0.14 + 5×0.28 = 2 800 micro committed to the ledger.
        awaitCondition(
                () -> TestGovernanceFactory.LEDGER.spendByKey(created.record().id(), 0) == 2_800);

        // TPM accumulated at finalize: 15 real tokens ⇒ 85 remain; a follow-up
        // estimate of 100 crosses ⇒ 429 before dispatch on the Anthropic face.
        TestRouterFactory.BACKEND.completeCalls.clear();
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        HttpResponse<?> denied = errorResponse(postRequest("/v1/messages", created.fullKey(), anthropicBody(100)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertEquals(0, TestRouterFactory.BACKEND.completeCalls.size(), "no upstream call for the pre-checked request");
    }

    // ------------------------------------------------- windowed budget (reset windows)

    @Test
    void windowedBudgetDeniesWithRetryAfterAndResetsAtTheBoundary() {
        // End-to-end through /v1/chat/completions: a key with budget_usd AND
        // budget_duration enforces the cap per aligned window — the denial carries
        // Retry-After (seconds until the window resets; a lifetime budget carries
        // none — pinned by overBudgetKeyIs429BeforeDispatchWithNoUpstreamCall), and
        // crossing the window boundary (the mutable test clock, no real sleeps)
        // refills the cap. The shared clock is advanced and reset within this one
        // method (the shared-instance discipline on the gateway MutableClock).
        MutableClock clock = TestKeyAuthFactory.CLOCK;
        try {
            KeyStore.CreatedKey created = keyStore.create(new KeyStore.KeyCreateRequest(
                    "governance-test", List.of("deepseek-v4-flash"), null, 0.005, 60L, null, null));
            String keyId = created.record().id();
            TestRouterFactory.BACKEND.completeReturns(chatResponse()); // usage 10/5 ⇒ 2_800 micro
            TestRouterFactory.BACKEND.completeCalls.clear();

            // Request 1: estimate 4_480 < 5_000 cap → allowed; soft (4_480 ≥ 4_000)
            // → the header carries the WINDOW view of settled spend.
            HttpResponse<String> first = postOpenAi(created.fullKey(), 16);
            assertEquals(HttpStatus.OK, first.getStatus());
            assertEquals(Governance.WARNING_SOFT, first.getHeaders().get(Governance.HEADER_BUDGET_WARNING));
            assertEquals(
                    "2800",
                    first.getHeaders().get(Governance.HEADER_BUDGET_USED),
                    "the budget-used header is window-scoped (the settled actual, not all-time)");
            assertEquals(2_800L, TestGovernanceFactory.LEDGER.spendByKey(keyId, 60));
            assertEquals(2_800L, TestGovernanceFactory.LEDGER.totalSpendByKey(keyId));

            // The soft notifier payload gains window_reset_epoch_seconds for windowed keys.
            RecordingNotifier.Event softEvent = TestGovernanceFactory.NOTIFIER.snapshot().stream()
                    .filter(e -> keyId.equals(e.payload().get("key_id")))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    TestKeyAuthFactory.CLOCK_START.getEpochSecond() + 60,
                    softEvent.payload().get("window_reset_epoch_seconds"),
                    "the windowed payload carries the reset epoch");
            assertEquals(2_800L, softEvent.payload().get("committed_micro_usd"), "the payload is the window view");

            // Request 2: 2_800 settled + 4_480 estimate ≥ 5_000 → 429 BEFORE dispatch,
            // with Retry-After = seconds until the window resets (60 at the window start).
            TestRouterFactory.BACKEND.completeCalls.clear();
            HttpResponse<?> denied =
                    errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody(false, 16)));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
            assertTrue(
                    denied.getBody(String.class).orElse("").contains("key budget exceeded"),
                    denied.getBody(String.class).orElse(""));
            assertEquals(
                    "60",
                    denied.getHeaders().get(HttpHeaders.RETRY_AFTER),
                    "a windowed budget denial carries Retry-After until the window resets");
            assertEquals(0, TestRouterFactory.BACKEND.completeCalls.size());

            // Across the boundary: the window resets, the same request is allowed
            // again, and the window view restarts at zero while the all-time view keeps
            // both windows' spend.
            clock.advanceSeconds(61);
            TestRouterFactory.BACKEND.completeReturns(chatResponse());
            HttpResponse<String> next = postOpenAi(created.fullKey(), 16);
            assertEquals(HttpStatus.OK, next.getStatus(), "the window rollover refilled the cap");
            assertEquals(
                    2_800L, TestGovernanceFactory.LEDGER.spendByKey(keyId, 60), "the new window restarted at zero");
            assertEquals(5_600L, TestGovernanceFactory.LEDGER.totalSpendByKey(keyId), "the all-time view accumulates");
        } finally {
            clock.reset(); // restore the JVM-wide fixed start for every other suite
            TestRouterFactory.BACKEND.completeCalls.clear(); // leave the shared backend state clean
        }
    }

    // ------------------------------------------------------------------------ helpers

    private KeyStore.CreatedKey createKey(Double budgetUsd, Integer rpm, Integer tpm) {
        return keyStore.create(new KeyStore.KeyCreateRequest(
                "governance-test", List.of("deepseek-v4-flash"), null, budgetUsd, null, rpm, tpm));
    }

    private HttpResponse<String> postOpenAi(String fullKey, int maxTokens) {
        return client.toBlocking()
                .exchange(postRequest("/v1/chat/completions", fullKey, openAiBody(false, maxTokens)), String.class);
    }

    private HttpResponse<String> postAnthropic(String fullKey, int maxTokens) {
        return client.toBlocking()
                .exchange(postRequest("/v1/messages", fullKey, anthropicBody(maxTokens)), String.class);
    }

    private HttpRequest<String> postRequest(String path, String fullKey, String body) {
        return HttpRequest.POST(path, body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", fullKey);
    }

    private HttpStatus probeOpenAi(String fullKey, int maxTokens) {
        try {
            return postOpenAi(fullKey, maxTokens).getStatus();
        } catch (HttpClientResponseException e) {
            return e.getResponse().getStatus();
        }
    }

    /** {@code exchange()} throws on non-2xx; return the exception's response (headers kept). */
    private HttpResponse<?> errorResponse(HttpRequest<?> request) {
        HttpClientResponseException exception = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class, () -> client.toBlocking().exchange(request, String.class));
        return exception.getResponse();
    }

    private java.net.http.HttpResponse<InputStream> streamResponse(String body, String fullKey) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("x-api-key", fullKey)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        return java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
    }

    /** Streaming variant on the Anthropic face (m4: pins the Anthropic streaming-governance path). */
    private java.net.http.HttpResponse<InputStream> anthropicStreamResponse(String fullKey) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.getPort() + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", fullKey)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(anthropicBody(true, 16)))
                .build();
        return java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
    }

    private static String readAll(InputStream body) throws Exception {
        StringBuilder all = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
            }
        }
        return all.toString();
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("condition not met within 5s");
            }
            Thread.sleep(10);
        }
    }

    private static String openAiBody(boolean stream, int maxTokens) {
        return "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":"
                + stream + ",\"max_tokens\":" + maxTokens + "}";
    }

    private static String anthropicBody(int maxTokens) {
        return anthropicBody(false, maxTokens);
    }

    private static String anthropicBody(boolean stream, int maxTokens) {
        return "{\"model\":\"deepseek-v4-flash\",\"max_tokens\":" + maxTokens + ",\"stream\":" + stream
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private static ChatResponse chatResponse() {
        return chatResponse(new Usage(10, 5, 15));
    }

    private static ChatResponse chatResponse(Usage usage) {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new io.amscotti.janus.core.model.AssistantMessage("Hello!", null), "stop")),
                usage,
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static StreamChunk contentChunk(String text) {
        return new StreamChunk(
                "chatcmpl-1",
                "chat.completion.chunk",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, text, null), null)),
                null,
                Map.of());
    }

    private static StreamChunk usageChunk(Usage usage) {
        return new StreamChunk(
                "chatcmpl-1", "chat.completion.chunk", 1_700_000_000L, "deepseek-v4-flash", List.of(), usage, Map.of());
    }
}
