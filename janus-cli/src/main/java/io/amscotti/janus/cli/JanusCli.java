package io.amscotti.janus.cli;

import io.amscotti.janus.JanusApplication;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Composition root. Parses {@code --config <path>} (or {@code -c}, or the
 * {@code --config=<path>} / {@code -c=<path>} equals forms) and boots the gateway
 * with that configuration file, then delegates to {@link JanusApplication}.
 *
 * <p>Parsing is fail-fast: any dangling flag (missing value, a value that is itself
 * another flag, or a blank value — including a whitespace-only equals-form value, and
 * a flag-like value in either form, even one padded with leading whitespace) is
 * rejected with a usage line printed to stderr
 * and a non-zero exit instead of silently booting on the packaged defaults — booting
 * a gateway
 * with the wrong (or no) configuration is the one silent misconfiguration this entry
 * point must never make. Relative, scheme-less values are absolutized against the
 * process working directory so {@code janus-cli --config config.toml} resolves the same
 * file regardless of where the JVM was launched; only {@code classpath:}/ {@code file:}
 * values pass through verbatim as scheme-prefixed.
 *
 * <p>This is the only {@code --config}-capable entry point: it ships as the
 * {@code janus-cli} distribution script (or {@code :janus-cli:run}). Every
 * {@code janus}-named artifact — the native binary and the {@code janus-<ver>.jar}
 * fat jar — boots {@link JanusApplication} directly and takes {@code
 * MICRONAUT_CONFIG_FILES} instead, so the usage line names {@code janus-cli}, never a
 * {@code janus} command that would only fail fast.
 *
 * <p>The consumed flag is stripped before delegation, so {@link JanusApplication}'s
 * native-image pre-check never fires for the CLI's legitimate {@code
 * --config}, and Micronaut never sees it as a stray property. The {@code
 * micronaut.config.files} property is only ever set/cleared by this class for a flag it
 * itself consumed, so a repeated {@code main} invocation in one JVM cannot leak a stale
 * config file into a boot that explicitly omitted {@code --config}.
 */
public final class JanusCli {

    /** The usage line carried by the {@link IllegalArgumentException} on a malformed flag. */
    static final String USAGE = "usage: janus-cli --config <path> | -c <path> | --config=<path> | -c=<path>";

    /** Whether this JVM's {@link #applyConfigFlag} set the property (only it may clear it). */
    static boolean configFileSetByCli = false;

    private JanusCli() {}

    /** Exit seam, package-private so tests can observe the usage exit without dying. */
    static volatile IntConsumer exitHook = System::exit;

    public static void main(String[] args) {
        String[] bootArgs;
        try {
            bootArgs = applyConfigFlag(args);
        } catch (IllegalArgumentException malformedFlag) {
            System.err.println(malformedFlag.getMessage());
            exitHook.accept(2);
            return;
        }
        JanusApplication.main(bootArgs);
    }

    /**
     * Applies the {@code --config} flag to the {@code micronaut.config.files} system
     * property and returns the args to boot with. When the flag is present the resolved
     * (absolutized) path is set and the flag itself stripped; when it is absent a
     * property set by an <em>earlier</em> {@code main} invocation in this JVM is cleared
     * so no stale config leaks into this boot. Package-private: {@link JanusCliTest}
     * pins the property lifecycle without booting the gateway.
     */
    static String[] applyConfigFlag(String[] args) {
        String configPath = extractConfigPath(args);
        if (configPath != null) {
            System.setProperty("micronaut.config.files", resolveConfigPath(configPath));
            configFileSetByCli = true;
            return withoutConfigFlag(args);
        }
        if (configFileSetByCli) {
            System.clearProperty("micronaut.config.files");
            configFileSetByCli = false;
        }
        return args;
    }

    /**
     * Parses the {@code --config} flag out of {@code args}. Returns {@code null} when
     * the flag is absent; the first well-formed occurrence wins. <em>Every</em>
     * occurrence is validated — a malformed duplicate (e.g. a dangling trailing
     * {@code --config}) is not silently swallowed by the first-occurrence rule — and
     * throws {@link IllegalArgumentException} with a usage line when any flag is
     * dangling: its value is missing, the next argument is itself a flag, the value is
     * blank, or the equals form carries a blank or flag-like value — a flag-like value
     * is rejected in either form even when padded with whitespace (e.g.
     * {@code --config=-c}, {@code --config= -c}).
     */
    static String extractConfigPath(String[] args) {
        String first = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--config".equals(arg) || "-c".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(arg + " is missing its value — " + USAGE);
                }
                String value = args[i + 1];
                if (value.strip().startsWith("-")) {
                    throw new IllegalArgumentException(
                            arg + " is missing its value (the next argument is a flag) — " + USAGE);
                }
                if (value.isBlank()) {
                    throw new IllegalArgumentException(arg + " is missing its value (the value is blank) — " + USAGE);
                }
                if (first == null) {
                    first = value;
                }
            } else if (arg.startsWith("--config=") || arg.startsWith("-c=")) {
                String flag = arg.substring(0, arg.indexOf('='));
                String value = arg.substring(flag.length() + 1);
                if (value.isBlank()) {
                    throw new IllegalArgumentException(flag + "= is missing its value — " + USAGE);
                }
                if (value.strip().startsWith("-")) {
                    throw new IllegalArgumentException(
                            flag + "= is missing its value (the value is a flag) — " + USAGE);
                }
                if (first == null) {
                    first = value;
                }
            }
        }
        return first;
    }

    /**
     * Resolves a {@code --config} value to a path Micronaut can load: a relative,
     * scheme-less value is absolutized against the process working directory;
     * {@code classpath:}-/ {@code file:}-prefixed values are passed through verbatim
     * (the only schemes Micronaut resolves). Any other {@code :}-containing value is a
     * POSIX-legal filename, not a scheme, and is absolutized like any other path.
     */
    static String resolveConfigPath(String value) {
        if (value.startsWith("classpath:") || value.startsWith("file:")) {
            return value;
        }
        return Path.of(value).toAbsolutePath().toString();
    }

    /**
     * Returns {@code args} with every config flag removed (the space forms plus their
     * value, the equals forms as single tokens), preserving the remaining arguments'
     * order. Used so the consumed flag never reaches {@link JanusApplication}'s
     * native-image pre-check or Micronaut's argument parser.
     */
    static String[] withoutConfigFlag(String[] args) {
        List<String> out = new ArrayList<>(args.length);
        boolean skipNext = false;
        for (String arg : args) {
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if ("--config".equals(arg) || "-c".equals(arg)) {
                skipNext = true;
                continue;
            }
            if (arg.startsWith("--config=") || arg.startsWith("-c=")) {
                continue;
            }
            out.add(arg);
        }
        return out.toArray(String[]::new);
    }
}
