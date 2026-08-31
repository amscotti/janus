package io.amscotti.janus.core.model;

/**
 * Controls whether conversation content (prompts, completions, tool arguments and
 * results, image payloads) may appear in log output.
 *
 * <p><b>Default: OFF.</b> With content logging off, the string form of the canonical
 * records prints structure only — message counts, model names, sizes. The text of
 * the conversation is simply not logged. Operational telemetry (metrics, call
 * records, token counts, costs, latencies, router and breaker events) is never
 * affected by this switch; it never contains conversation text in the first place.
 *
 * <p>Operators can enable content logging for local debugging with
 * {@code [janus.privacy] log-content = true} in the config file. The gateway logs a
 * warning at boot when it is on, so the mode is never accidental. The records
 * themselves are never modified in either mode — this switch only governs what
 * their string form prints.
 */
public final class ContentLogging {

    private static volatile boolean enabled;

    private ContentLogging() {}

    /** True when the operator explicitly enabled content logging in the config. */
    public static boolean enabled() {
        return enabled;
    }

    /** Enable content logging (called once at boot from the resolved config). */
    public static void enable() {
        enabled = true;
    }

    /** Disable content logging (the default; also used by tests to reset state). */
    public static void disable() {
        enabled = false;
    }
}
