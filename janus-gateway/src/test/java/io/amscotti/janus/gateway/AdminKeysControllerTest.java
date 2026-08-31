package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.store.KeyGenerator;
import io.amscotti.janus.store.KeyHash;
import io.amscotti.janus.store.KeyRecord;
import io.amscotti.janus.store.KeyStatus;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@link AdminKeysController} ({@code /key/generate|delete|list},
 * master-key-authed by {@link KeyAuthFilter}): generation returns the full
 * {@code sk-janus-…} key <b>exactly once</b> while the store holds only the salted
 * hash; delete revokes (subsequent request auth → 403); list returns redacted records
 * with no hash/salt/secret. The master key works via Bearer and {@code x-api-key};
 * missing/wrong master → 401 (the filter's {@code MISSING}/{@code BAD_MASTER} rows).
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
@Property(name = "janus.test.governance", value = "true")
@Property(name = "janus.test.metrics", value = "true")
class AdminKeysControllerTest {

    private static final String MASTER_KEY = "test-master-key-000";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    KeyStore keyStore;

    @Inject
    Notifier notifier;

    @BeforeEach
    void resetRegistry() {
        // The admin-metrics test asserts an exact counter — clear the shared
        // registry so cross-test accumulation cannot mask a missing recordRequest.
        TestMetricsFactory.REGISTRY.clear();
    }

    // --------------------------------------------------------------- generate

    @Test
    void generateWithMasterKeyReturnsFullKeyOnceAndStoreHoldsOnlyHash() {
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/generate", "{\"models\":[\"deepseek-v4-flash\"],\"name\":\"app-a\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        String body = http.body();
        assertTrue(body.contains("\"key\":\"sk-janus-"), body);
        assertTrue(body.contains("\"name\":\"app-a\""), body);
        assertTrue(body.contains("\"models\":[\"deepseek-v4-flash\"]"), body);

        // The full key string is returned exactly once: the store holds only hash+salt.
        JsonNode node = readTree(body);
        String fullKey = node.get("key").stringValue();
        KeyGenerator.Parsed parsed = KeyGenerator.parse(fullKey).orElseThrow();
        KeyRecord record = keyStore.findByPrefix(parsed.prefix()).orElseThrow();
        assertTrue(
                KeyHash.verify(record.salt(), record.secretHash(), parsed.secret()),
                "the stored hash must verify the returned secret");
        assertEquals("app-a", record.owner());
        assertEquals(List.of("deepseek-v4-flash"), record.models());
        assertEquals("sk-janus", fullKey.substring(0, 8), "brand");
        assertEquals(node.get("key_id").stringValue(), record.id(), "response key_id matches the stored record");
        assertEquals(
                1,
                countOccurrences(body, parsed.secret()),
                "the secret must appear exactly once — only inside the \"key\" field");
        assertFalse(
                keyStore.list().toString().contains(parsed.secret()),
                "the store's list view must never expose the secret");
    }

    @Test
    void generateWithoutMasterKeyIs401() {
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/key/generate", "{\"models\":[\"deepseek-v4-flash\"]}")
                        .contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.UNAUTHORIZED, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"authentication_error\""), http.body());
    }

    @Test
    void generateWithWrongMasterKeyIs401() {
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/key/generate", "{\"models\":[\"deepseek-v4-flash\"]}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer wrong-master"));
        assertEquals(HttpStatus.UNAUTHORIZED, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"authentication_error\""), http.body());
        assertTrue(http.body().contains("invalid master key"), http.body());
    }

    @Test
    void masterKeyWorksViaXApiKeyHeader() {
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/generate", "{\"models\":[]}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-api-key", MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
    }

    @Test
    void generateWithMalformedBodyIs400OpenAiEnvelope() {
        HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/generate", "{not json")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + MASTER_KEY));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
    }

    @Test
    void generateWithNullLiteralBodyIs400AndNotRecordedAs5xx() {
        // A JSON literal `null` body (and an empty body) used to bind to a
        // null DTO and NPE inside validateCaps — a 500 api_error with an ERROR_INTERNAL
        // record for purely client-malformed input. Both must be 400 invalid_request_error.
        for (String body : List.of("null", "")) {
            HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/generate", body)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + MASTER_KEY));
            assertEquals(HttpStatus.BAD_REQUEST, http.getStatus(), body);
            assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), body + " → " + http.body());
        }
        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"admin\",status=\"4xx\"}"),
                "a null body is a client 400, recorded in the admin 4xx bucket:\n" + scrape);
        assertFalse(
                scrape.contains("face=\"admin\",status=\"5xx\""),
                "a null body must never land in the admin 5xx bucket:\n" + scrape);
    }

    @Test
    void deleteWithNullLiteralBodyIs400AndNotRecordedAs5xx() {
        for (String body : List.of("null", "")) {
            HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/delete", body)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + MASTER_KEY));
            assertEquals(HttpStatus.BAD_REQUEST, http.getStatus(), body);
            assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), body + " → " + http.body());
        }
        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"admin\",status=\"4xx\"}"),
                "a null delete body is a client 400:\n" + scrape);
        assertFalse(scrape.contains("face=\"admin\",status=\"5xx\""), scrape);
    }

    @Test
    void generateWithNullModelsEntryIs400AndCreatesNoKey() {
        // Coverage gap: validateScope rejects a null models entry, but only blank-string
        // entries were tested — the JSON literal null element must be rejected too.
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/key/generate", "{\"models\":[null],\"name\":\"null-scope\"}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + MASTER_KEY));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
        assertTrue(
                keyStore.list().stream().noneMatch(view -> "null-scope".equals(view.owner())),
                "a rejected null-scope generate must not create a key");
    }

    @Test
    void deleteWithBothKeyIdAndKeyPrefersTheKeyId() {
        // Coverage gap: the keyId-wins precedence — a body carrying both identifiers
        // revokes by key_id, ignoring the key string entirely.
        KeyStore.CreatedKey created = createKey("app-both", List.of("deepseek-v4-flash"));
        HttpResponse<String> deleted = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/key/delete",
                                        "{\"key_id\":\"" + created.record().id()
                                                + "\",\"key\":\"sk-janus-zzz-0123456789abcdef0123456789abcdef\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, deleted.getStatus());
        assertTrue(deleted.body().contains("\"deleted\":true"), deleted.body());
        assertTrue(deleted.body().contains("\"key_id\":\"" + created.record().id() + "\""), deleted.body());
        assertEquals(
                KeyStatus.REVOKED,
                keyStore.findByPrefix(created.record().prefix()).orElseThrow().status(),
                "the key_id wins — the bogus key string is ignored");
    }

    @Test
    void getKeyListWithoutMasterKeyIs401() {
        // Coverage gap: only /key/generate's 401 was exercised — the list route must be
        // equally master-key-guarded (the KeyAuthFilter guards the whole /key/* tree).
        HttpResponse<String> http = errorResponse(HttpRequest.GET("/key/list"));
        assertEquals(HttpStatus.UNAUTHORIZED, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"authentication_error\""), http.body());
    }

    // ----------------------------------------------------------------- delete

    @Test
    void deleteByKeyIdRevokesAndSubsequentAuthFails() {
        KeyStore.CreatedKey created = createKey("app-b", List.of("deepseek-v4-flash"));
        HttpResponse<String> deleted = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/key/delete",
                                        "{\"key_id\":\"" + created.record().id() + "\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, deleted.getStatus());
        assertTrue(deleted.body().contains("\"deleted\":true"), deleted.body());
        assertEquals(
                KeyStatus.REVOKED,
                keyStore.findByPrefix(created.record().prefix()).orElseThrow().status());

        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        HttpResponse<String> auth =
                errorResponse(HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-api-key", created.fullKey()));
        assertEquals(HttpStatus.FORBIDDEN, auth.getStatus(), "a deleted key must no longer authenticate");
        assertTrue(auth.body().contains("\"type\":\"permission_error\""), auth.body());
    }

    @Test
    void deleteByFullKeyStringRevokes() {
        KeyStore.CreatedKey created = createKey("app-c", List.of("deepseek-v4-flash"));
        HttpResponse<String> deleted = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/delete", "{\"key\":\"" + created.fullKey() + "\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, deleted.getStatus());
        assertTrue(deleted.body().contains("\"deleted\":true"), deleted.body());
        assertTrue(deleted.body().contains("\"key_id\":\"" + created.record().id() + "\""), deleted.body());
    }

    @Test
    void deleteUnknownKeyIdReportsDeletedFalse() {
        HttpResponse<String> deleted = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/delete", "{\"key_id\":\"no-such-id\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, deleted.getStatus());
        assertTrue(deleted.body().contains("\"deleted\":false"), deleted.body());
    }

    @Test
    void deleteWithNeitherKeyNorKeyIdIs400() {
        HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/delete", "{}")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + MASTER_KEY));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
    }

    @Test
    void deleteByUnknownFullKeyStringReportsDeletedFalseWithNoKeyId() {
        // A well-formed key string whose prefix is not in the store → deleted(false),
        // and the null key_id must be omitted entirely (m3: pins the first
        // @JsonInclude(NON_NULL) on a record component in the repo — a silent
        // "key_id":null would fail here).
        KeyGenerator.Generated unknown = KeyGenerator.generate();
        HttpResponse<String> deleted = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/delete", "{\"key\":\"" + unknown.fullKey() + "\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, deleted.getStatus());
        assertTrue(deleted.body().contains("\"deleted\":false"), deleted.body());
        assertFalse(deleted.body().contains("key_id"), "unknown-prefix delete must omit key_id, not emit null");
    }

    @Test
    void deleteWithMalformedKeyStringIs400() {
        HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/delete", "{\"key\":\"garbage\"}")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + MASTER_KEY));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
    }

    @Test
    void deleteByFullKeyWithWrongSecretDoesNotRevoke() {
        // The key form must verify the presented secret — the prefix alone is
        // public (GET /key/list), so a prefix match is not proof of possession. A
        // wrong/typo'd secret (same prefix, well-formed but different secret) must not
        // silently revoke the key; it returns the same deleted:false shape as an
        // unknown prefix.
        KeyStore.CreatedKey created = createKey("app-wrong-secret", List.of("deepseek-v4-flash"));
        KeyGenerator.Parsed parsed = KeyGenerator.parse(created.fullKey()).orElseThrow();
        String wrongKey = KeyGenerator.fullKey(parsed.prefix(), "x".repeat(KeyGenerator.SECRET_LENGTH));

        HttpResponse<String> deleted = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/delete", "{\"key\":\"" + wrongKey + "\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, deleted.getStatus());
        assertTrue(deleted.body().contains("\"deleted\":false"), deleted.body());
        assertFalse(deleted.body().contains("key_id"), "a wrong-secret delete must omit key_id, not emit null");
        assertEquals(
                KeyStatus.ACTIVE,
                keyStore.findByPrefix(created.record().prefix()).orElseThrow().status(),
                "a wrong-secret delete must not revoke the key");
    }

    // ------------------------------------------------ notifier forgetKey wiring

    @Test
    void deletePrunesTheDedupNotifierWindowEntry() {
        // The dedup notifier's per-key window state used to grow
        // unboundedly across key churn with no prune hook. A successful POST /key/delete
        // must drop the key's entry (Notifier.forgetKey) so a deleted-and-re-created key
        // fires budget_exceeded again in the same window.
        KeyStore.CreatedKey created = createKey("app-dedup", List.of("deepseek-v4-flash"));
        DedupNotifier dedup = (DedupNotifier) notifier;
        dedup.notify(
                Notifier.EVENT_BUDGET_EXCEEDED,
                Map.of("key_id", created.record().id(), "tier", "soft"));
        assertTrue(dedup.remembers(created.record().id()), "the fresh key holds a dedup window entry");

        HttpResponse<String> deleted = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/key/delete",
                                        "{\"key_id\":\"" + created.record().id() + "\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, deleted.getStatus());
        assertTrue(deleted.body().contains("\"deleted\":true"), deleted.body());
        assertFalse(dedup.remembers(created.record().id()), "a successful delete prunes the dedup window entry");
    }

    @Test
    void deleteOfUnknownKeyLeavesNotifierStateUntouched() {
        // A failed delete (deleted:false) must not prune notifier state — the prune is
        // gated on an actual revoke, like MetricsRecorder.forgetKey.
        DedupNotifier dedup = (DedupNotifier) notifier;
        dedup.notify(Notifier.EVENT_BUDGET_EXCEEDED, Map.of("key_id", "keep-me", "tier", "soft"));

        HttpResponse<String> deleted = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/delete", "{\"key_id\":\"no-such-id\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, deleted.getStatus());
        assertTrue(deleted.body().contains("\"deleted\":false"), deleted.body());
        assertTrue(dedup.remembers("keep-me"), "a failed delete must not prune notifier state");
    }

    // ------------------------------------------------------------------- list

    @Test
    void listReturnsRecordsWithoutSecrets() {
        KeyStore.CreatedKey a = createKey("app-d", List.of("deepseek-v4-flash"));
        KeyStore.CreatedKey b = createKey("app-e", null);
        HttpResponse<String> http = client.toBlocking()
                .exchange(HttpRequest.GET("/key/list").header("Authorization", "Bearer " + MASTER_KEY), String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        String body = http.body();
        assertTrue(body.contains("\"id\":\"" + a.record().id() + "\""), body);
        assertTrue(body.contains("\"id\":\"" + b.record().id() + "\""), body);
        assertTrue(body.contains("\"name\":\"app-d\""), body);
        assertTrue(body.contains("\"status\":\"ACTIVE\""), body);
        assertTrue(body.contains("\"models\":[\"deepseek-v4-flash\"]"), body);
        assertFalse(body.contains("secretHash"), "list must never expose the hash");
        assertFalse(body.contains("salt"), "list must never expose the salt");
        assertFalse(body.contains(a.fullKey()), "list must never expose the full key");
        assertFalse(body.contains(b.fullKey()), "list must never expose the full key");
    }

    // --------------------------------------------- budget_usd / rpm / tpm on generate

    @Test
    void generateWithBudgetRpmTpmPersistsCapsAndEnforcesThem() {
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/key/generate",
                                        "{\"models\":[\"deepseek-v4-flash\"],\"name\":\"app-f\",\"budget_usd\":5.0,"
                                                + "\"rpm\":2,\"tpm\":1000}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        String fullKey = readTree(http.body()).get("key").stringValue();

        // GET /key/list carries the caps (KeyRecordView, shape unchanged).
        HttpResponse<String> list = client.toBlocking()
                .exchange(HttpRequest.GET("/key/list").header("Authorization", "Bearer " + MASTER_KEY), String.class);
        assertTrue(list.body().contains("\"budget_usd\":5.0"), list.body());
        assertTrue(list.body().contains("\"rpm\":2"), list.body());
        assertTrue(list.body().contains("\"tpm\":1000"), list.body());

        // Enforcement end-to-end through the filter + controllers: rpm=2 ⇒ the third
        // request is a 429 rate_limit_error with Retry-After (no upstream call).
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> ok = client.toBlocking()
                    .exchange(
                            HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-flash"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .header("x-api-key", fullKey),
                            String.class);
            assertEquals(HttpStatus.OK, ok.getStatus());
        }
        HttpResponse<?> denied =
                errorResponse(HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-api-key", fullKey));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertTrue(
                denied.getBody(String.class).orElse("").contains("\"type\":\"rate_limit_error\""),
                denied.getBody(String.class).orElse(""));
        assertEquals("60", denied.getHeaders().get("Retry-After"));
    }

    @Test
    void generateWithDurationSetsExpiresAtFromTheStoreClock() {
        // /key/generate used to hardcode expiresAt=null while the response
        // advertised expires_at — a dead wire surface. A `duration` (seconds, LiteLLM
        // /key/generate parity) now computes expires_at against the shared store clock,
        // so the value the response reports equals the value the store enforces and GET
        // /key/list echoes.
        long durationSeconds = 3600;
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/key/generate",
                                        "{\"models\":[],\"name\":\"app-expiring\",\"duration\":" + durationSeconds
                                                + "}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        JsonNode node = readTree(http.body());
        long expectedExpiresAt = TestKeyAuthFactory.CLOCK.millis() + durationSeconds * 1000;
        assertEquals(
                expectedExpiresAt,
                node.get("expires_at").longValue(),
                "expires_at must be now + duration, computed from the store clock");

        KeyRecord record = keyStore.findByPrefix(
                        KeyGenerator.parse(node.get("key").stringValue())
                                .orElseThrow()
                                .prefix())
                .orElseThrow();
        assertEquals(expectedExpiresAt, record.expiresAt().longValue(), "the store record carries the same expiresAt");

        HttpResponse<String> list = client.toBlocking()
                .exchange(HttpRequest.GET("/key/list").header("Authorization", "Bearer " + MASTER_KEY), String.class);
        assertTrue(list.body().contains("\"expires_at\":" + expectedExpiresAt), list.body());
    }

    @Test
    void generateWithoutDurationKeepsExpiresAtNull() {
        // Absent duration ⇒ never expires — the response reports expires_at:null (the
        // honest "no expiry" spelling, matching LiteLLM's null expires).
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/generate", "{\"models\":[],\"name\":\"app-forever\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        JsonNode node = readTree(http.body());
        assertTrue(node.get("expires_at").isNull(), "absent duration ⇒ never expires (expires_at null)");
    }

    @Test
    void generateWithNonPositiveDurationIs400AndCreatesNoKey() {
        for (String body : List.of(
                "{\"models\":[],\"name\":\"dur-reject\",\"duration\":0}",
                "{\"models\":[],\"name\":\"dur-reject\",\"duration\":-10}")) {
            HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/generate", body)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + MASTER_KEY));
            assertEquals(HttpStatus.BAD_REQUEST, http.getStatus(), body);
            assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), body + " → " + http.body());
        }
        assertTrue(
                keyStore.list().stream().noneMatch(view -> "dur-reject".equals(view.owner())),
                "rejected duration must not create a key");
    }

    @Test
    void generateWithoutBudgetRpmTpmKeepsCapsNull() {
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/generate", "{\"models\":[],\"name\":\"app-g\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        String keyId = readTree(http.body()).get("key_id").stringValue();
        KeyRecord record = keyStore.findByPrefix(
                        KeyGenerator.parse(readTree(http.body()).get("key").stringValue())
                                .orElseThrow()
                                .prefix())
                .orElseThrow();
        assertEquals(keyId, record.id());
        assertNull(record.budgetUsd(), "absent budget_usd ⇒ null cap (backward compatible)");
        assertNull(record.rpm(), "absent rpm ⇒ null cap");
        assertNull(record.tpm(), "absent tpm ⇒ null cap");
    }

    @Test
    void generateWithNonPositiveCapsIs400AndCreatesNoKey() {
        // A stored ≤ 0 cap would make Governance's limiter throw on every
        // request for that key (500 on the hot path) — reject at creation instead.
        // Null caps are the "no cap" spelling (null ≠ zero), so 0 is invalid input.
        for (String body : List.of(
                "{\"models\":[],\"name\":\"cap-reject\",\"rpm\":0}",
                "{\"models\":[],\"name\":\"cap-reject\",\"rpm\":-5}",
                "{\"models\":[],\"name\":\"cap-reject\",\"tpm\":0}",
                "{\"models\":[],\"name\":\"cap-reject\",\"tpm\":-1}",
                "{\"models\":[],\"name\":\"cap-reject\",\"budget_usd\":0.0}",
                "{\"models\":[],\"name\":\"cap-reject\",\"budget_usd\":-2.5}")) {
            HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/generate", body)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + MASTER_KEY));
            assertEquals(HttpStatus.BAD_REQUEST, http.getStatus(), body);
            assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), body + " → " + http.body());
        }
        assertTrue(
                keyStore.list().stream().noneMatch(view -> "cap-reject".equals(view.owner())),
                "rejected generate calls must not create a key");
    }

    @Test
    void generateWithNonFiniteBudgetUsdIs400AndCreatesNoKey() {
        // Jackson parses "1e999" (double overflow) to POSITIVE_INFINITY and a NaN
        // literal to NaN — both pass the old `<= 0` check and would be stored, silently
        // unbinding the cap at enforcement and echoing non-JSON literals in /key/list.
        for (String body : List.of(
                "{\"models\":[],\"name\":\"cap-reject\",\"budget_usd\":1e999}",
                "{\"models\":[],\"name\":\"cap-reject\",\"budget_usd\":NaN}")) {
            HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/generate", body)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + MASTER_KEY));
            assertEquals(HttpStatus.BAD_REQUEST, http.getStatus(), body);
            assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), body + " → " + http.body());
        }
        assertTrue(
                keyStore.list().stream().noneMatch(view -> "cap-reject".equals(view.owner())),
                "rejected generate calls must not create a key");
    }

    @Test
    void generateWithFiniteButImpossiblyLargeBudgetUsdIs400AndCreatesNoKey() {
        // A finite budget at/above the long micro-USD conversion limit (≈9.22e12 USD)
        // saturates Governance.toMicroUsd to Long.MAX_VALUE — a silently unbounded cap.
        // Both 1e13 and 1e308 are finite and positive, so the existing finite/<= 0 checks
        // do not catch them; the explicit upper bound does.
        for (String body : List.of(
                "{\"models\":[],\"name\":\"cap-reject\",\"budget_usd\":1e13}",
                "{\"models\":[],\"name\":\"cap-reject\",\"budget_usd\":1e308}")) {
            HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/generate", body)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + MASTER_KEY));
            assertEquals(HttpStatus.BAD_REQUEST, http.getStatus(), body);
            assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), body + " → " + http.body());
        }
        assertTrue(
                keyStore.list().stream().noneMatch(view -> "cap-reject".equals(view.owner())),
                "rejected generate calls must not create a key");
    }

    @Test
    void generateWithBudgetAtMicroUsdSaturationBoundaryIs400() {
        // A budget whose double representation equals Long.MAX_VALUE/1e6 was
        // accepted by the old `> MAX_BUDGET_USD` test (they are the same double) yet
        // saturates Governance.toMicroUsd (usd * 1e6 + 0.5 >= Long.MAX_VALUE) — the
        // rejection must use the saturation predicate so a stored cap can never silently
        // become unbounded. Long.MAX_VALUE / 1e6 is that exact boundary double.
        double saturating = Long.MAX_VALUE / 1_000_000.0;
        HttpResponse<String> http = errorResponse(HttpRequest.POST(
                        "/key/generate", "{\"models\":[],\"name\":\"boundary\",\"budget_usd\":" + saturating + "}")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + MASTER_KEY));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), http.body());
        assertTrue(
                keyStore.list().stream().noneMatch(view -> "boundary".equals(view.owner())),
                "a saturating boundary budget must not create a key");
    }

    @Test
    void generateWithBudgetBelowMicroUsdRoundingIs400AndCreatesNoKey() {
        // A tiny-but-positive budget (0.0000001 USD = 0.1 micro) rounds to 0
        // micro-USD in Governance.toMicroUsd, which is the "no cap" sentinel — the
        // operator would believe a cap exists while the key is unbounded. Reject it at
        // creation with the exact toMicroUsd predicate.
        for (String body : List.of(
                "{\"models\":[],\"name\":\"tiny-budget\",\"budget_usd\":0.0000001}",
                "{\"models\":[],\"name\":\"tiny-budget\",\"budget_usd\":0.00000049}")) {
            HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/generate", body)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + MASTER_KEY));
            assertEquals(HttpStatus.BAD_REQUEST, http.getStatus(), body);
            assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), body + " → " + http.body());
        }
        assertTrue(
                keyStore.list().stream().noneMatch(view -> "tiny-budget".equals(view.owner())),
                "rejected tiny budgets must not create a key");
    }

    @Test
    void generateWithBudgetJustAtMicroUsdRoundingBoundarySucceeds() {
        // The other side of the same boundary: 0.0000005 USD (0.5 micro, rounds up to
        // 1 micro-USD) is a representable positive cap and must be accepted.
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/key/generate",
                                        "{\"models\":[],\"name\":\"tiny-budget-ok\",\"budget_usd\":0.0000005}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        assertTrue(keyStore.list().stream().anyMatch(view -> "tiny-budget-ok".equals(view.owner())));
    }

    @Test
    void generateWithBudgetJustBelowSaturationBoundarySucceeds() {
        // The other side of the same boundary: a finite positive budget safely below the
        // micro-USD saturation threshold (1e6 USD of headroom) is accepted — the
        // rejection is the exact toMicroUsd predicate, not a band.
        double tolerated = Long.MAX_VALUE / 1_000_000.0 - 1_000_000.0;
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/key/generate",
                                        "{\"models\":[],\"name\":\"boundary-ok\",\"budget_usd\":" + tolerated + "}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        assertTrue(keyStore.list().stream().anyMatch(view -> "boundary-ok".equals(view.owner())));
    }

    // --------------------------------------------- budget_duration (reset windows)

    @Test
    void generateWithBudgetDurationIsAcceptedEchoedAndListed() {
        // budget_duration (seconds) turns the budget into a reset window: accepted on
        // /key/generate, echoed in the response, carried by GET /key/list, and stored
        // on the record. 2592000 = 30 days — plain integer seconds (the plan's
        // documented example), never "30d" strings.
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/key/generate",
                                        "{\"models\":[\"deepseek-v4-flash\"],\"name\":\"app-windowed\","
                                                + "\"budget_usd\":50.0,\"budget_duration\":2592000}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        JsonNode node = readTree(http.body());
        assertEquals(2_592_000L, node.get("budget_duration").longValue(), "the response echoes budget_duration");

        KeyRecord record = keyStore.findByPrefix(
                        KeyGenerator.parse(node.get("key").stringValue())
                                .orElseThrow()
                                .prefix())
                .orElseThrow();
        assertEquals(Long.valueOf(2_592_000L), record.budgetDuration(), "the store record carries budget_duration");
        assertEquals(Double.valueOf(50.0), record.budgetUsd(), "the cap itself is unchanged");

        HttpResponse<String> list = client.toBlocking()
                .exchange(HttpRequest.GET("/key/list").header("Authorization", "Bearer " + MASTER_KEY), String.class);
        assertTrue(list.body().contains("\"budget_duration\":2592000"), list.body());
    }

    @Test
    void generateWithoutBudgetDurationIsLifetimeBackCompatible() {
        // Absent budget_duration ⇒ null ⇒ the lifetime budget of 1.0 — exactly the
        // pre-window semantics; the response omits the field (never a null literal).
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/key/generate", "{\"models\":[],\"name\":\"app-lifetime\",\"budget_usd\":5.0}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        JsonNode node = readTree(http.body());
        assertTrue(node.get("budget_duration").isNull(), "absent budget_duration ⇒ lifetime (null echo)");
        KeyRecord record = keyStore.findByPrefix(
                        KeyGenerator.parse(node.get("key").stringValue())
                                .orElseThrow()
                                .prefix())
                .orElseThrow();
        assertNull(record.budgetDuration(), "the stored record keeps the null lifetime window");
    }

    @Test
    void generateWithInvalidBudgetDurationIs400AndCreatesNoKey() {
        // Non-positive and >10-year (315,360,000s) durations are rejected with the
        // typed invalid-request error: a duration above ~nowSec would derive window
        // epoch 0 — the lifetime row — silently aliasing the key's windowed spend.
        for (String body : List.of(
                "{\"models\":[],\"name\":\"dur-reject\",\"budget_usd\":5.0,\"budget_duration\":0}",
                "{\"models\":[],\"name\":\"dur-reject\",\"budget_usd\":5.0,\"budget_duration\":-60}",
                "{\"models\":[],\"name\":\"dur-reject\",\"budget_usd\":5.0,\"budget_duration\":315360001}",
                "{\"models\":[],\"name\":\"dur-reject\",\"budget_usd\":5.0,\"budget_duration\":315360000000}")) {
            HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/generate", body)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + MASTER_KEY));
            assertEquals(HttpStatus.BAD_REQUEST, http.getStatus(), body);
            assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), body + " → " + http.body());
            assertTrue(http.body().contains("budget_duration"), "the error names the field: " + http.body());
        }
        assertTrue(
                keyStore.list().stream().noneMatch(view -> "dur-reject".equals(view.owner())),
                "rejected budget_duration must not create a key");
    }

    @Test
    void budgetDurationExactlyTenYearsIsAccepted() {
        // The bound itself (315,360,000s = 10 years) is a legitimate operator ask —
        // only values ABOVE it alias the lifetime window.
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/key/generate",
                                        "{\"models\":[],\"name\":\"dur-ten-years\",\"budget_usd\":5.0,"
                                                + "\"budget_duration\":315360000}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        assertTrue(keyStore.list().stream().anyMatch(view -> "dur-ten-years".equals(view.owner())));
    }

    @Test
    void controllerLevelAdminFailuresAreRecordedInMetrics() {
        // Filter-level /key rejections were already metered (KeyAuthFilter), but
        // controller-level failures — rejected caps here — fell through to the exception
        // handler without a recordRequest. A 400 /key/generate must land in
        // janus_requests_total{face="admin",status="4xx"}.
        HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/generate", "{\"models\":[],\"rpm\":0}")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + MASTER_KEY));
        assertEquals(HttpStatus.BAD_REQUEST, http.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"admin\",status=\"4xx\"} 1.0"),
                "controller-level admin failures must be recorded:\n" + scrape);
        assertFalse(scrape.contains("face=\"openai\""), "an admin failure is not an OpenAI-face request:\n" + scrape);
    }

    @Test
    void successfulAdminOperationsAreRecordedInAdmin2xxSeries() {
        // Admin success traffic used to be invisible (only the catch paths and
        // the filter's rejections were recorded), so face="admin" never had a 2xx row — a
        // dashboard summing by face saw admin grow only on errors. A successful generate,
        // delete and list must each land in the admin 2xx bucket.
        HttpResponse<String> generate = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/generate", "{\"models\":[],\"name\":\"admin-metrics\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, generate.getStatus());

        HttpResponse<String> list = client.toBlocking()
                .exchange(HttpRequest.GET("/key/list").header("Authorization", "Bearer " + MASTER_KEY), String.class);
        assertEquals(HttpStatus.OK, list.getStatus());

        HttpResponse<String> deleted = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/delete", "{\"key_id\":\"no-such-id\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, deleted.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"admin\",status=\"2xx\"} 3.0"),
                "generate+list+delete must each be recorded in the admin 2xx series:\n" + scrape);
        assertFalse(scrape.contains("face=\"openai\""), "admin operations are not OpenAI-face requests:\n" + scrape);
    }

    @Test
    void generateWithBlankModelsOrBlankOrOverlongNameIs400() {
        // A stored "" scope entry authenticates but denies every real model call
        // (403 on everything — a confusing footgun), and name is echoed into /key/list.
        String overlong = "n".repeat(257);
        for (String body : List.of(
                "{\"models\":[\"\"],\"name\":\"scope-reject\"}",
                "{\"models\":[\"deepseek-v4-flash\",\"  \"],\"name\":\"scope-reject\"}",
                "{\"models\":[],\"name\":\"\"}",
                "{\"models\":[],\"name\":\"   \"}",
                "{\"models\":[],\"name\":\"" + overlong + "\"}")) {
            HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/generate", body)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + MASTER_KEY));
            assertEquals(HttpStatus.BAD_REQUEST, http.getStatus(), body);
            assertTrue(http.body().contains("\"type\":\"invalid_request_error\""), body + " → " + http.body());
        }
        assertTrue(
                keyStore.list().stream().noneMatch(view -> "scope-reject".equals(view.owner())),
                "rejected generate calls must not create a key");
    }

    @Test
    void listEmitsOnlyFiniteJson() {
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/generate", "{\"models\":[],\"name\":\"finite\",\"budget_usd\":5.0}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
        HttpResponse<String> list = client.toBlocking()
                .exchange(HttpRequest.GET("/key/list").header("Authorization", "Bearer " + MASTER_KEY), String.class);
        assertTrue(list.body().contains("\"budget_usd\":5.0"), list.body());
        assertFalse(
                list.body().contains("Infinity"),
                "a finite budget must never serialize non-JSON literals: " + list.body());
        assertFalse(
                list.body().contains("NaN"), "a finite budget must never serialize non-JSON literals: " + list.body());
    }

    // ---------------------------------------------------------------- helpers

    private KeyStore.CreatedKey createKey(String owner, List<String> models) {
        return keyStore.create(new KeyStore.KeyCreateRequest(owner, models, null, null, null, null, null));
    }

    private static JsonNode readTree(String json) {
        try {
            return GatewayJson.mapper().readTree(json);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("test response was not JSON: " + json, e);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static ChatResponse chatResponse() {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new io.amscotti.janus.core.model.AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static String requestBody(String model) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false,"
                + "\"max_tokens\":16}";
    }

    private HttpResponse<String> errorResponse(HttpRequest<?> request) {
        HttpClientResponseException exception = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class, () -> client.toBlocking().exchange(request, String.class));
        HttpResponse<?> response = exception.getResponse();
        MutableHttpResponse<String> rebuilt = HttpResponse.status(response.getStatus())
                .body(response.getBody(String.class).orElse(""));
        response.getHeaders().forEach((name, values) -> values.forEach(value -> rebuilt.header(name, value)));
        return rebuilt;
    }
}
