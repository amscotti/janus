package io.amscotti.janus.gateway;

import io.amscotti.janus.JanusConfig;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admin <b>master key</b>: resolved from the environment at bean creation —
 * the env var name comes from {@code [janus.keys] master-key-env} (default
 * {@code JANUS_MASTER_KEY}); <b>never the value in TOML</b> ( env-reference
 * pattern), never logged, never in exception messages. The master key authenticates
 * the admin API only ({@code /key/generate|delete|list}, via {@link KeyAuthFilter});
 * model routes require Janus-issued virtual keys (the master key is not a virtual
 * key).
 *
 * <p><b>Auth posture (hardened): ON with a key, or explicitly OFF.</b> With
 * {@code auth = "on"} (the default), a resolvable key enables auth and a missing one
 * <b>fails the boot fast</b> — a deployment that forgets the env var must not
 * silently run an unauthenticated admin API (it mints keys). Auth-off remains a
 * first-class posture for development and benchmarks via an explicit
 * {@code [janus.keys] auth = "off"} line — declared in a config the operator
 * controls, loudly logged here, and it wins even when a key resolves (intent
 * from the file beats ambient env). */
@Singleton
final class MasterKeyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MasterKeyProvider.class);

    private final String masterKey;

    @Inject
    MasterKeyProvider(JanusConfig config) {
        this(resolveMasterKey(config.keys(), System.getenv(envNameOf(config.keys()))));
    }

    private static String envNameOf(JanusConfig.KeysConfig keys) {
        return keys == null ? JanusConfig.KeysConfig.DEFAULT_MASTER_KEY_ENV : keys.effectiveMasterKeyEnv();
    }

    /**
     * The hardened resolution (unit-pinned in {@link MasterKeyProviderTest}):
     * <ul>
     * <li>{@code auth = "off"} (explicit): null — loudly logged, even when a key
     * resolves (operator intent from the config file wins over ambient env);</li>
     * <li>{@code auth = "on"} (default) + resolvable key: the key (blank ≡ unset —
     * the compose {@code ${JANUS_MASTER_KEY:-}} default must not become
     * auth-ON-with-an-empty-key);</li>
     * <li>{@code auth = "on"} + no key: {@link IllegalStateException} — the boot
     * fails fast with an actionable message naming the env var AND the explicit
     * opt-out, never a silent unauthenticated gateway.</li>
     * </ul>
     */
    static String resolveMasterKey(@Nullable JanusConfig.KeysConfig keys, @Nullable String envValue) {
        JanusConfig.KeysConfig effectiveKeys = keys == null ? new JanusConfig.KeysConfig(null, null) : keys;
        String envName = effectiveKeys.effectiveMasterKeyEnv();
        String auth = effectiveKeys.effectiveAuth(); // validates the spelling, fail-fast
        String resolved = envValue == null || envValue.isBlank() ? null : envValue;
        if (JanusConfig.KeysConfig.AUTH_OFF.equals(auth)) {
            if (resolved != null) {
                LOG.warn(
                        "[janus.keys] auth = \"off\" — auth is deliberately OFF and the master key resolved"
                                + " from env '{}' is IGNORED: /key/* admin routes and model routes run"
                                + " unauthenticated (development/benchmark posture).",
                        envName);
            } else {
                LOG.warn("[janus.keys] auth = \"off\" — auth is deliberately OFF: /key/* admin routes and"
                        + " model routes run unauthenticated (development/benchmark posture).");
            }
            return null;
        }
        if (resolved == null) {
            // ON without a key: fail fast — a forgotten env var must not silently
            // expose the admin API (it mints keys). The message names both fixes.
            throw new IllegalStateException("auth is ON but no master key resolved from env '"
                    + envName
                    + "' — either set "
                    + envName
                    + " to a strong secret, or explicitly declare development auth-off with"
                    + " [janus.keys] auth = \"off\" in the config file");
        }
        // Strength hint — not a rejection (operators may rotate through the warning),
        // but a guessable master key guards an API that mints keys. A placeholder
        // value from a copied example is almost certainly an oversight.
        if (resolved.length() < 32 || resolved.equalsIgnoreCase("change-me") || resolved.equalsIgnoreCase("changeme")) {
            LOG.warn(
                    "[janus.keys] master key from env '{}' looks weak (shorter than 32 chars or a"
                            + " placeholder) — use a long random secret (e.g. `openssl rand -hex 32`);"
                            + " the admin plane is only as strong as this key.",
                    envName);
        }
        return resolved;
    }

    /** Package-private: tests inject a fixed value. */
    MasterKeyProvider(@Nullable String masterKey) {
        this.masterKey = masterKey;
    }

    /** The admin master key, or null when none is configured — null ⇒ auth off. */
    @Nullable
    String masterKey() {
        return masterKey;
    }
}
