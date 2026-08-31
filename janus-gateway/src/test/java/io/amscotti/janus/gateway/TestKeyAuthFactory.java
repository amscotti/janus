package io.amscotti.janus.gateway;

import io.amscotti.janus.store.InMemoryKeyStore;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;

/**
 * shared gateway test composition root (the {@link TestRouterFactory} pattern):
 * replaces the production {@link CallStoreFactory} with a <b>fixed-clock</b> {@link
 * InMemoryKeyStore} <em>and</em> the shared {@link Clock} bean (deterministic
 * timestamps — expiry/lastUsedAt assertions, discipline — and the
 * {@link KeyAuthFilter} gets the same fixed clock via DI, never a hardcoded
 * {@code Clock.systemUTC}), plus the {@link MasterKeyProvider} with one whose
 * value comes from the test-only {@code janus.test.master-key} property.
 *
 * <p><b>Auth opt-in per class.</b> Test classes enable auth with
 * {@code @Property(name = "janus.test.master-key", value =...)}; classes without it
 * get a null master key ⇒ the {@link KeyAuthFilter} bean is a passthrough — exactly
 * why the existing / suites (no property) keep passing <b>unchanged</b>: the
 * auth-off test posture proves zero behavioral drift for keyless configs. The fixed
 * clock (2026-08-03T00:00:00Z) is in the past relative to real "now", so keys whose
 * {@code expiresAt} precedes it are expired from the filter's perspective.
 *
 * <p><b>The clock is mutable (the gateway seam).</b> The shared bean is a
 * {@link MutableClock} parked at {@link #CLOCK_START} — behaviorally identical to
 * the old {@code Clock.fixed} for every suite that never touches it, and the
 * windowed-budget e2e suite can cross a reset-window boundary without real sleeps
 * (it advances and {@link MutableClock#reset resets} within one test method; the
 * shared-instance discipline is documented on the clock type).
 */
@Factory
@Requires(property = "janus.test.production-factories", notEquals = "true")
final class TestKeyAuthFactory {

    /** Test-only property holding the master key value when a class opts auth in. */
    static final String MASTER_KEY_PROPERTY = "janus.test.master-key";

    /** Fixed store clock start shared by every test context (deterministic, in the past). */
    static final Instant CLOCK_START = Instant.parse("2026-08-03T00:00:00Z");

    /** Shared store clock (a {@link MutableClock} parked at {@link #CLOCK_START}). */
    static final MutableClock CLOCK = new MutableClock(CLOCK_START);

    @Singleton
    @Replaces(factory = CallStoreFactory.class)
    Clock clock() {
        return CLOCK;
    }

    @Singleton
    @Replaces(factory = CallStoreFactory.class)
    KeyStore keyStore() {
        return new InMemoryKeyStore(CLOCK);
    }

    @Singleton
    @Replaces(MasterKeyProvider.class)
    MasterKeyProvider masterKeyProvider(Environment environment) {
        String masterKey =
                environment.getProperty(MASTER_KEY_PROPERTY, String.class).orElse(null);
        return new MasterKeyProvider(masterKey);
    }
}
