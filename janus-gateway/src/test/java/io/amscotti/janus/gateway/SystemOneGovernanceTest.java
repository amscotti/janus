package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.DecisionAnswer;
import io.amscotti.janus.core.model.DecisionResponse;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.CallStatus;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Decision-face accounting through the real controller ladder (not the direct
 * {@link Governance} unit tests): keyed preflight denial before dispatch,
 * exactly one call-ledger row per request, input-only settle (the OpenAI
 * fixture's cache/output split folded into the input term), and release after
 * an upstream failure. The shared {@link TestGovernanceFactory} fixed clock and
 * price table make the micro-USD assertions exact.
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
@Property(name = "janus.test.governance", value = "true")
class SystemOneGovernanceTest {

    private static final String BODY = """
            {"model":"jev-latest","state":"I was charged twice.","questions":{
              "refund":{"type":"noul","instructions":"Is the customer asking for money back?"}}}
            """;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    KeyStore keyStore;

    @BeforeEach
    void resetBackend() {
        TestRouterFactory.DECISION_BACKEND.reset();
        TestRouterFactory.DECISION_VISION_BACKEND.reset();
    }

    @Test
    void successfulDecisionWritesOneOkRowAndSettlesInputOnly() {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.97)),
                // OpenAI-style split: 42 regular + 100 cached + 5 cache-write input;
                // decisions bill the full 147 input and zero the output/cache side.
                new Usage(42, 12, 159, 5L, 100L, 2L),
                Map.of(),
                Map.of()));
        KeyStore.CreatedKey key = key(null, null, null);

        HttpResponse<String> http = post(key.fullKey(), BODY);

        assertEquals(HttpStatus.OK, http.getStatus());
        assertEquals(
                "20580",
                http.getHeaders().get("X-Janus-Cost-Total-Micro-Usd"),
                "147 input tokens x $0.14/1k = $0.02058 = 20,580 micro-USD");
        List<CallRecord> rows =
                TestGovernanceFactory.CALLS.recentCalls(key.record().id(), 10);
        assertEquals(1, rows.size(), "exactly one Tier-1 call record");
        assertEquals(CallStatus.OK, rows.get(0).status());
        assertEquals("jev-latest", rows.get(0).model());
        assertEquals("fake-decision", rows.get(0).provider());
        assertFalse(rows.get(0).stream(), "decisions never stream");
        assertEquals(147, rows.get(0).promptTokens());
        assertEquals(0, rows.get(0).completionTokens(), "decisions never bill output tokens");
        assertNull(rows.get(0).cacheReadInputTokens(), "cache columns stay null on the decision face");
        assertNull(rows.get(0).cacheCreationInputTokens());
    }

    @Test
    void overBudgetDecisionIs429BeforeDispatchWithOneLimitRow() {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.5)),
                new Usage(42, 0, 42),
                Map.of(),
                Map.of()));
        KeyStore.CreatedKey key = key(0.000001, null, null); // 1 micro cap; the estimate already exceeds it

        HttpResponse<?> denied = errorPost(key.fullKey(), BODY);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertTrue(denied.getBody(String.class).orElse("").contains("\"type\":\"rate_limit_error\""));
        assertTrue(
                TestRouterFactory.DECISION_BACKEND.calls.isEmpty(),
                "a budget-denied request must never be dispatched upstream");
        List<CallRecord> rows =
                TestGovernanceFactory.CALLS.recentCalls(key.record().id(), 10);
        assertEquals(1, rows.size());
        assertEquals(CallStatus.ERROR_LIMIT, rows.get(0).status());
        assertEquals("jev-latest", rows.get(0).model());
        assertNull(rows.get(0).provider(), "pre-dispatch denial has no serving backend");
    }

    @Test
    void unknownDecisionAliasIs404BeforeAnyPricingCheck() {
        KeyStore.CreatedKey key =
                keyStore.create(new KeyStore.KeyCreateRequest("no-scope", List.of(), null, null, null, null, null));

        HttpResponse<?> missing = errorPost(key.fullKey(), BODY.replace("jev-latest", "no-such-alias"));

        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
        assertTrue(missing.getBody(String.class).orElse("").contains("model_not_found"));
        assertTrue(TestRouterFactory.DECISION_BACKEND.calls.isEmpty());
        List<CallRecord> rows =
                TestGovernanceFactory.CALLS.recentCalls(key.record().id(), 10);
        assertEquals(1, rows.size());
        assertEquals(CallStatus.ERROR_CLIENT, rows.get(0).status());
        assertEquals("no-such-alias", rows.get(0).model());
    }

    @Test
    void malformedDecisionBodyWritesOneClientErrorRow() {
        KeyStore.CreatedKey key = key(null, null, null);

        HttpResponse<?> bad = errorPost(key.fullKey(), "{}");

        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatus());
        List<CallRecord> rows =
                TestGovernanceFactory.CALLS.recentCalls(key.record().id(), 10);
        assertEquals(1, rows.size());
        assertEquals(CallStatus.ERROR_CLIENT, rows.get(0).status());
        assertNull(rows.get(0).model(), "a decode failure has no canonical model");
        assertNull(rows.get(0).provider(), "a decode failure never dispatches");
    }

    @Test
    void softBudgetCrossingAddsWarningAndUsedHeaders() {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.5)),
                new Usage(42, 0, 42),
                Map.of(),
                Map.of()));
        KeyStore.CreatedKey key = key(0.1, null, null); // 100,000 micro cap, 80,000 micro soft line
        String body = BODY.replace("\"I was charged twice.\"", "\"" + "x".repeat(2500) + "\"");

        HttpResponse<String> http = post(key.fullKey(), body);

        assertEquals(HttpStatus.OK, http.getStatus(), http.body());
        assertEquals(Governance.WARNING_SOFT, http.getHeaders().get(Governance.HEADER_BUDGET_WARNING));
        assertEquals("5880", http.getHeaders().get(Governance.HEADER_BUDGET_USED));
        assertEquals("5880", http.getHeaders().get("X-Janus-Cost-Total-Micro-Usd"));
        List<CallRecord> rows =
                TestGovernanceFactory.CALLS.recentCalls(key.record().id(), 10);
        assertEquals(1, rows.size());
        assertEquals(CallStatus.OK, rows.get(0).status());
    }

    @Test
    void rpmDeniedDecisionWritesOneLimitRow() {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.5)),
                new Usage(42, 0, 42),
                Map.of(),
                Map.of()));
        KeyStore.CreatedKey key = key(null, 1, null);

        assertEquals(HttpStatus.OK, post(key.fullKey(), BODY).getStatus());
        HttpResponse<?> denied = errorPost(key.fullKey(), BODY);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertEquals(1, TestRouterFactory.DECISION_BACKEND.calls.size(), "denied call never dispatches");
        List<CallRecord> rows =
                TestGovernanceFactory.CALLS.recentCalls(key.record().id(), 10);
        assertEquals(2, rows.size(), "one OK row + one limit row");
        assertEquals(CallStatus.ERROR_LIMIT, rows.get(0).status());
        assertEquals(CallStatus.OK, rows.get(1).status());
    }

    @Test
    void tpmDeniedDecisionWritesOneLimitRow() {
        KeyStore.CreatedKey key = key(null, null, 1);

        HttpResponse<?> denied = errorPost(key.fullKey(), BODY);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertTrue(TestRouterFactory.DECISION_BACKEND.calls.isEmpty());
        List<CallRecord> rows =
                TestGovernanceFactory.CALLS.recentCalls(key.record().id(), 10);
        assertEquals(1, rows.size());
        assertEquals(CallStatus.ERROR_LIMIT, rows.get(0).status());
    }

    @Test
    void scopeDeniedDecisionWritesOneClientErrorRow() {
        KeyStore.CreatedKey key = keyStore.create(
                new KeyStore.KeyCreateRequest("scoped-elsewhere", List.of("d3"), null, null, null, null, null));

        HttpResponse<?> denied = errorPost(key.fullKey(), BODY);

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
        assertTrue(TestRouterFactory.DECISION_BACKEND.calls.isEmpty(), "403 never dispatches");
        List<CallRecord> rows =
                TestGovernanceFactory.CALLS.recentCalls(key.record().id(), 10);
        assertEquals(1, rows.size());
        assertEquals(CallStatus.ERROR_CLIENT, rows.get(0).status());
        assertEquals("jev-latest", rows.get(0).model());
        assertNull(rows.get(0).provider());
    }

    @Test
    void encodeFailureWritesOneInternalRowAndReleasesTheReservation() {
        Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
        answers.put("refund", null);
        TestRouterFactory.DECISION_BACKEND.decideReturns(
                new DecisionResponse("jev-latest", answers, new Usage(42, 0, 42), Map.of(), Map.of()));
        KeyStore.CreatedKey key = key(1.0, null, null);

        HttpResponse<?> failed = errorPost(key.fullKey(), BODY);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failed.getStatus());
        List<CallRecord> rows =
                TestGovernanceFactory.CALLS.recentCalls(key.record().id(), 10);
        assertEquals(1, rows.size(), "an encode failure is still exactly one ledger row");
        assertEquals(CallStatus.ERROR_INTERNAL, rows.get(0).status());
        assertEquals("jev-latest", rows.get(0).model());
        assertEquals("fake-decision", rows.get(0).provider(), "the backend was dispatched before encode");

        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.5)),
                new Usage(42, 0, 42),
                Map.of(),
                Map.of()));
        assertEquals(HttpStatus.OK, post(key.fullKey(), BODY).getStatus());
    }

    @Test
    void upstreamFailureWritesOneUpstreamRowAndReleasesTheReservation() {
        TestRouterFactory.DECISION_BACKEND.decideFails(
                new ProviderException(ProviderException.TYPE_UPSTREAM_5XX, "boom"));
        KeyStore.CreatedKey key = key(1.0, null, null);

        HttpResponse<?> failed = errorPost(key.fullKey(), BODY);

        assertEquals(HttpStatus.BAD_GATEWAY, failed.getStatus());
        List<CallRecord> rows =
                TestGovernanceFactory.CALLS.recentCalls(key.record().id(), 10);
        assertEquals(1, rows.size());
        assertEquals(CallStatus.ERROR_UPSTREAM, rows.get(0).status());
        assertEquals("jev-latest", rows.get(0).model());
        assertEquals("fake-decision", rows.get(0).provider());

        // A leaked reservation would deny this follow-up; the release on the
        // failure path must return the pending balance.
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.5)),
                new Usage(42, 0, 42),
                Map.of(),
                Map.of()));
        assertEquals(HttpStatus.OK, post(key.fullKey(), BODY).getStatus());
    }

    private KeyStore.CreatedKey key(Double budgetUsd, Integer rpm, Integer tpm) {
        return keyStore.create(new KeyStore.KeyCreateRequest(
                "systemone-governance", List.of("jev-latest"), null, budgetUsd, null, rpm, tpm));
    }

    private HttpResponse<String> post(String fullKey, String body) {
        return client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", body)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + fullKey),
                        String.class);
    }

    private HttpResponse<?> errorPost(String fullKey, String body) {
        try {
            return post(fullKey, body);
        } catch (HttpClientResponseException e) {
            return e.getResponse();
        }
    }
}
