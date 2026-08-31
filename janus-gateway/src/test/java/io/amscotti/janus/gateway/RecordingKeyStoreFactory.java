package io.amscotti.janus.gateway;

import io.amscotti.janus.store.InMemoryKeyStore;
import io.amscotti.janus.store.KeyRecord;
import io.amscotti.janus.store.KeyRecordView;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only {@link KeyStore} seam (the {@link TestKeyAuthFactory} pattern): when a
 * class opts in with {@value #ENABLED_PROPERTY}, the store bean is replaced with a
 * delegating wrapper that records the name of the thread each admin operation
 * ({@code create}/{@code revoke}/{@code list}) — and each {@code authenticate} —
 * executed on, so {@code AdminExecuteOnTest} can assert the
 * {@code @ExecuteOn(TaskExecutors.BLOCKING)} handlers run off the Netty event loop and
 * {@code KeyAuthExecuteOnTest} can assert the auth filter's deferred virtual-key
 * decision does too.
 */
@Factory
@Requires(property = "janus.test.record-threads", value = "true")
final class RecordingKeyStoreFactory {

    /** Opt-in property; the execute-on test classes set it. */
    static final String ENABLED_PROPERTY = "janus.test.record-threads";

    @Singleton
    @Replaces(KeyStore.class)
    KeyStore recordingKeyStore(Clock clock) {
        return new RecordingKeyStore(clock);
    }

    /** Delegating wrapper that records the executing thread name per store operation. */
    static final class RecordingKeyStore implements KeyStore {

        /** Admin operations ({@code create}/{@code revoke}/{@code list}). */
        static final List<String> EXECUTED_ON = new CopyOnWriteArrayList<>();

        /** {@link #authenticate} calls (the auth filter's deferred virtual-key leg). */
        static final List<String> AUTH_EXECUTED_ON = new CopyOnWriteArrayList<>();

        private final KeyStore delegate;

        RecordingKeyStore(Clock clock) {
            this.delegate = new InMemoryKeyStore(clock);
        }

        static void clear() {
            EXECUTED_ON.clear();
            AUTH_EXECUTED_ON.clear();
        }

        @Override
        public CreatedKey create(KeyCreateRequest request) {
            EXECUTED_ON.add(Thread.currentThread().getName());
            return delegate.create(request);
        }

        @Override
        public Optional<KeyRecord> findByPrefix(String prefix) {
            return delegate.findByPrefix(prefix);
        }

        @Override
        public boolean revoke(String id) {
            EXECUTED_ON.add(Thread.currentThread().getName());
            return delegate.revoke(id);
        }

        @Override
        public AuthResult authenticate(String prefix, String secret) {
            AUTH_EXECUTED_ON.add(Thread.currentThread().getName());
            return delegate.authenticate(prefix, secret);
        }

        @Override
        public List<KeyRecordView> list() {
            EXECUTED_ON.add(Thread.currentThread().getName());
            return delegate.list();
        }

        @Override
        public void touch(String prefix) {
            delegate.touch(prefix);
        }
    }
}
