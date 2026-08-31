package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.store.InMemoryKeyStore;
import io.amscotti.janus.store.KeyRecord;
import io.amscotti.janus.store.KeyRecordView;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@link AdminKeysController} accounting guards, unit-level (direct
 * construction, no Micronaut context): a successful {@code POST /key/delete} records
 * its 2xx <b>after</b> the revoke (so a throwing revoke cannot double-count the
 * request); the {@code forgetKey} hooks (metrics + notifier) are best-effort by
 * contract — a throwing adapter must never turn a successful revoke into a 500 after
 * the key is gone; and the <b>success-path</b> {@code recordRequest} is best-effort
 * the same way — a throwing recorder must never fail an already-committed operation
 * (generate would swallow the one response carrying the full key, delete would report
 * a successful revoke as a 500).
 */
class AdminKeysControllerUnitTest {

    private static final Clock CLOCK = TestKeyAuthFactory.CLOCK;

    @Test
    void deleteWithThrowingRevokeRecordsExactlyOne5xxAndNo2xx() {
        FakeKeyStore store = new FakeKeyStore();
        store.revokeThrows = true;
        CountingMetricsRecorder recorder = new CountingMetricsRecorder();
        AdminKeysController controller = new AdminKeysController(store, recorder, new FakeNotifier(), CLOCK);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> controller.delete("{\"key_id\":\"some-id\"}"),
                "a store failure propagates unchanged");

        assertEquals(
                1,
                recorder.requests.size(),
                "exactly one recordRequest for the failed delete (the 2xx must not fire before the revoke)");
        assertEquals(
                "admin:" + HttpStatus.INTERNAL_SERVER_ERROR.getCode(),
                recorder.requests.get(0),
                "the only record is the catch path's 5xx — no prior 2xx double-count");
        assertEquals(0, recorder.forgotten.size(), "a failed revoke must not run the forget hooks");
    }

    @Test
    void deleteWithThrowingForgetHooksStillReturns200AndRecordsOne2xx() {
        FakeKeyStore store = new FakeKeyStore();
        store.revoked = true;
        CountingMetricsRecorder recorder = new CountingMetricsRecorder();
        recorder.forgetThrows = true;
        FakeNotifier notifier = new FakeNotifier();
        notifier.forgetThrows = true;
        AdminKeysController controller = new AdminKeysController(store, recorder, notifier, CLOCK);

        HttpResponse<String> deleted = controller.delete("{\"key_id\":\"some-id\"}");

        assertEquals(HttpStatus.OK, deleted.getStatus());
        assertTrue(deleted.body().contains("\"deleted\":true"), deleted.body());
        assertEquals(
                1,
                recorder.requests.size(),
                "exactly one recordRequest for the successful delete — the 2xx, recorded after the revoke");
        assertTrue(
                recorder.requests.get(0).startsWith("admin:2"),
                "a delete whose forget hooks throw still records its 2xx (best-effort hooks are contained): "
                        + recorder.requests);
        assertEquals(List.of("some-id"), recorder.forgotten, "the recorder forget hook was still attempted");
        assertEquals(List.of("some-id"), notifier.forgotten, "the notifier forget hook was still attempted");
    }

    @Test
    void deleteByFullKeyWithThrowingRevokeAlsoRecordsExactlyOne5xx() {
        // Same pin on the key-string resolution path: the 2xx must fire only after the
        // revoke regardless of how the key id was resolved (the record must exist so the
        // full-key form reaches the revoke).
        FakeKeyStore store = new FakeKeyStore();
        store.revokeThrows = true;
        KeyStore.CreatedKey created =
                store.create(new KeyStore.KeyCreateRequest("t", null, null, null, null, null, null));
        CountingMetricsRecorder recorder = new CountingMetricsRecorder();
        AdminKeysController controller = new AdminKeysController(store, recorder, new FakeNotifier(), CLOCK);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> controller.delete("{\"key\":\"" + created.fullKey() + "\"}"));

        assertEquals(1, recorder.requests.size(), "exactly one recordRequest (the 5xx)");
        assertTrue(recorder.requests.get(0).startsWith("admin:5"), recorder.requests.get(0));
    }

    @Test
    void throwingRecorderCannotMaskTheClientsTrueEnvelope() {
        // The catch-path recordRequest is best-effort (the writeCallRecord/
        // forget-hook guard pattern) — a throwing recorder must not replace the client's
        // true envelope (the store's IllegalStateException, which the handler maps to
        // 500) with the recorder's own failure, and must not skip the catch accounting.
        FakeKeyStore store = new FakeKeyStore();
        store.revokeThrows = true;
        CountingMetricsRecorder recorder = new CountingMetricsRecorder();
        recorder.recordThrows = true;
        AdminKeysController controller = new AdminKeysController(store, recorder, new FakeNotifier(), CLOCK);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> controller.delete("{\"key_id\":\"some-id\"}"),
                "the client's true envelope propagates unchanged (recording never alters the path)");
    }

    @Test
    void throwingRecorderCannotTurnACommittedGenerateIntoA500() {
        // The success-path recordRequest is best-effort too (the same guard as the
        // catch path): the key is already persisted when it runs, and the response is
        // the ONLY place the full sk-janus-… secret is ever shown — a throwing recorder
        // must not retroactively fail the operation and swallow it.
        FakeKeyStore store = new FakeKeyStore();
        CountingMetricsRecorder recorder = new CountingMetricsRecorder();
        recorder.recordThrows = true;
        AdminKeysController controller = new AdminKeysController(store, recorder, new FakeNotifier(), CLOCK);

        HttpResponse<String> generated = controller.generate("{\"models\":[\"deepseek-v4-flash\"],\"name\":\"gen\"}");

        assertEquals(HttpStatus.OK, generated.getStatus(), "a committed generate is never failed by metrics");
        assertTrue(generated.body().contains("sk-janus-"), "the one-time secret must still reach the client");
        assertEquals(
                1,
                store.createCount,
                "the key was persisted exactly once (no retry/duplicate after the recording failure)");
    }

    @Test
    void throwingRecorderCannotTurnACommittedDeleteIntoA500() {
        // The delete analogue: the revoke already succeeded when the 2xx recording
        // runs, so a throwing recorder must not report the successful revoke as a 500.
        FakeKeyStore store = new FakeKeyStore();
        store.revoked = true;
        CountingMetricsRecorder recorder = new CountingMetricsRecorder();
        recorder.recordThrows = true;
        FakeNotifier notifier = new FakeNotifier();
        AdminKeysController controller = new AdminKeysController(store, recorder, notifier, CLOCK);

        HttpResponse<String> deleted = controller.delete("{\"key_id\":\"some-id\"}");

        assertEquals(HttpStatus.OK, deleted.getStatus(), "a committed delete is never failed by metrics");
        assertTrue(deleted.body().contains("\"deleted\":true"), deleted.body());
        assertEquals(List.of("some-id"), notifier.forgotten, "the forget hooks still ran (best-effort, unaffected)");
    }

    // ---------------------------------------------------------------- fakes

    /** Delegating {@link KeyStore} whose revoke can be configured to throw or report revoked. */
    private static final class FakeKeyStore implements KeyStore {

        private final KeyStore delegate = new InMemoryKeyStore(CLOCK);
        boolean revokeThrows;
        boolean revoked;
        int createCount;

        @Override
        public CreatedKey create(KeyCreateRequest request) {
            createCount++;
            return delegate.create(request);
        }

        @Override
        public Optional<KeyRecord> findByPrefix(String prefix) {
            return delegate.findByPrefix(prefix);
        }

        @Override
        public boolean revoke(String id) {
            if (revokeThrows) {
                throw new IllegalStateException("postgres is down");
            }
            return revoked;
        }

        @Override
        public AuthResult authenticate(String prefix, String secret) {
            return delegate.authenticate(prefix, secret);
        }

        @Override
        public List<KeyRecordView> list() {
            return delegate.list();
        }

        @Override
        public void touch(String prefix) {
            delegate.touch(prefix);
        }
    }

    /** {@link MetricsRecorder} that records every request and can throw on {@code forgetKey}. */
    private static final class CountingMetricsRecorder implements MetricsRecorder {

        final List<String> requests = new ArrayList<>();
        final List<String> forgotten = new ArrayList<>();
        boolean forgetThrows;
        boolean recordThrows;

        @Override
        public void recordRequest(String face, int status, long durationMillis, String keyId) {
            if (recordThrows) {
                throw new IllegalStateException("registry cleared");
            }
            requests.add(face + ":" + status);
        }

        @Override
        public void recordUsage(String keyId, Usage usage, long costMicroUsd) {}

        @Override
        public void forgetKey(String keyId) {
            forgotten.add(keyId);
            if (forgetThrows) {
                throw new IllegalStateException("registry removed");
            }
        }
    }

    /** {@link Notifier} that records forgets and can throw on {@code forgetKey}. */
    private static final class FakeNotifier implements Notifier {

        final List<String> forgotten = new ArrayList<>();
        boolean forgetThrows;

        @Override
        public void notify(String event, Map<String, Object> payload) {}

        @Override
        public void forgetKey(String keyId) {
            forgotten.add(keyId);
            if (forgetThrows) {
                throw new IllegalStateException("webhook closed");
            }
        }
    }
}
