package io.amscotti.janus.gateway;

import io.amscotti.janus.store.CallStore;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;

/**
 * Postgres-down-at-boot must refuse the node: the {@link CallStore}
 * bean is lazily instantiated (Micronaut singletons), so an unreachable database
 * would otherwise boot fine and fail only on the first store-touching request. This
 * listener probes the store at {@link ServerStartupEvent} (the CLI/gateway boot —
 * raw test contexts without an embedded server never fire it), so a node whose
 * {@code [janus.store] type = "postgres"} cannot reach its database exits with a
 * loud {@code PoolInitializationException}-wrapped boot failure instead of silently
 * serving (never a silent memory fallback — the read-your-writes violation the
 * decision records). The probe is a no-op spend read on a never-created key.
 */
@Singleton
final class StoreBootProbe implements ApplicationEventListener<ServerStartupEvent> {

    private static final String PROBE_KEY = "__janus_boot_probe__";

    private final CallStore callStore;

    StoreBootProbe(CallStore callStore) {
        this.callStore = java.util.Objects.requireNonNull(callStore, "callStore");
    }

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        // Unknown key ⇒ 0; the point is the connection, not the value. The lifetime
        // window (0) — the probe never touches budget state.
        callStore.spendByKey(PROBE_KEY, 0);
    }
}
