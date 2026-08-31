package io.amscotti.janus.gateway;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Per-dispatch SSE stream-idle resolution : the global default from
 * {@code [janus.timeouts] stream-idle-timeout-seconds} plus one override per
 * {@code [janus.providers.<name>]} block that sets {@code
 * stream-idle-timeout-seconds}, keyed by the <b>resolved backend name</b> the
 * block's entries dispatch under. Produced by {@code
 * RouterFactory} (the {@code CallStoreFactory}-produces-{@code Clock} bean
 * pattern — it replaces the earlier single resolved-{@code Duration} producer) and
 * injected into all three face controllers, which resolve <b>once</b>, at
 * publisher construction — {@code router.stream(...)} has returned and the
 * dispatch observer has already named the serving backend in {@code
 * ModelFaceControllerSupport}'s {@code dispatchedProvider} holder (the observer
 * fires before {@code stream} returns). The holder's last-delivery-wins
 * matters across attempts <b>before</b> the stream opens — a connect-failover
 * walk updates the holder per attempt, so the attempt that actually opened the
 * stream is the provider whose deadline is resolved. After the stream opens it
 * does not: a mid-stream failover updates the holder after the publisher (and
 * its deadline) was built, so the first dispatched provider's deadline stays
 * for that stream.
 *
 * <p><b>Why per dispatch, not per alias or per backend.</b> Under balancing one
 * alias serves from a pool of providers whose deadlines may differ; the
 * watchdog that guards an SSE response must be the one its serving provider
 * configured, so resolution happens at the only moment the serving provider is
 * known — after dispatch. Backend-name keying matches the holder's value
 * exactly (the holder IS the dispatched backend's name): for registered
 * providers and openai-compatible-family fallbacks that is the entry's provider
 * name — the block's key — while a fixed-name family fallback
 * ({@code wire-format = "anthropic"} under a custom provider name) dispatches
 * under the family's fixed adapter name, so the override is keyed by that name
 * (with a boot warning when it differs from the block key — see {@code
 * RouterFactory.providerStreamIdleOverrides}). A null
 * holder (undispatched — decode failure, pre-dispatch denial, unknown model)
 * and an unconfigured provider both resolve the global — the no-override boot
 * is byte-identical to the single-value wiring.
 *
 * <p>The adapter trio (connect / header / body-read) does NOT resolve here:
 * those deadlines are static per backend, merged entry-over-provider-over-global
 * in {@code ModelListFactory} at boot. Only the stream-idle watchdog is
 * per-dispatch.
 */
final class StreamIdleTimeoutResolver {

    private final Duration globalDefault;
    private final Map<String, Duration> providerOverrides;

    /**
     * @param globalDefault the resolved {@code [janus.timeouts]
     * stream-idle-timeout-seconds} (absent section ⇒ the pinned 60 s constant);
     * must be positive
     * @param providerOverrides per-provider overrides keyed by resolved backend
     * name (the {@code RouterFactory.providerStreamIdleOverrides} map — the name
     * the dispatch observer reports); values must be positive. Copied
     * unmodifiable — the resolver is a shared singleton read on every streaming
     * request.
     */
    StreamIdleTimeoutResolver(Duration globalDefault, Map<String, Duration> providerOverrides) {
        this.globalDefault = requirePositive(Objects.requireNonNull(globalDefault, "globalDefault"), "globalDefault");
        Objects.requireNonNull(providerOverrides, "providerOverrides");
        for (Map.Entry<String, Duration> override : providerOverrides.entrySet()) {
            requirePositive(
                    Objects.requireNonNull(override.getValue(), "providerOverrides[" + override.getKey() + "]"),
                    "providerOverrides[" + override.getKey() + "]");
        }
        this.providerOverrides = Map.copyOf(providerOverrides);
    }

    /**
     * The stream-idle deadline for a request served by {@code providerName}:
     * the provider's block override when one is configured, else the global
     * default. {@code null} (nothing dispatched) resolves the global — the
     * undispatched paths never constructed a publisher anyway, but the resolver
     * stays total so callers need no null branch.
     */
    Duration resolve(String providerName) {
        if (providerName == null) {
            return globalDefault;
        }
        return providerOverrides.getOrDefault(providerName, globalDefault);
    }

    /** The global fallback (the single value — pinned by {@code RouterFactoryTest}). */
    Duration globalDefault() {
        return globalDefault;
    }

    /** Duration mirror of the seconds-config positivity guard (defense-in-depth). */
    private static Duration requirePositive(Duration duration, String name) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive (got " + duration + ")");
        }
        return duration;
    }
}
