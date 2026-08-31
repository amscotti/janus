package io.amscotti.janus;

import io.micronaut.runtime.Micronaut;

/**
 * Janus gateway entry point. Booted by {@code janus-cli} or directly.
 *
 * <p><b>Native-image boot asymmetry.</b> This class is also the native
 * image's {@code mainClass}, and the native image reads config from {@code
 * MICRONAUT_CONFIG_FILES}, not the CLI's {@code --config}. An operator following the
 * JVM-only {@code janus-cli --config config.toml} wording against the native binary would
 * otherwise boot unauthenticated on the packaged defaults — the CLI's flag is parsed
 * away before delegation, so any {@code --config}/{@code -c} seen here is a mistake.
 * {@link #rejectNativeConfigFlag} fails fast instead of silently booting the wrong
 * configuration.
 */
public final class JanusApplication {

    private JanusApplication() {}

    public static void main(String[] args) {
        rejectNativeConfigFlag(args);
        Micronaut.run(JanusApplication.class, args);
    }

    /**
     * The native-image entry point does not take {@code --config} — config comes from
     * {@code MICRONAUT_CONFIG_FILES}. Rejects any stray config flag with a usage line
     * naming the environment variable, mirroring the CLI's fail-fast philosophy: booting
     * the native binary on the packaged defaults (no master key, empty model list) is the
     * one silent misconfiguration this entry point must never make.
     */
    static void rejectNativeConfigFlag(String[] args) {
        for (String arg : args) {
            if (arg.equals("--config") || arg.equals("-c") || arg.startsWith("--config=") || arg.startsWith("-c=")) {
                throw new IllegalArgumentException(
                        "the native image does not take --config — set MICRONAUT_CONFIG_FILES=<path> and re-run "
                                + "(README 'Native image'); the JVM CLI (janus-cli) accepts --config");
            }
        }
    }
}
