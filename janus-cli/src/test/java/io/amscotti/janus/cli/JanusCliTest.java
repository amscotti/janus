package io.amscotti.janus.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unit coverage for the CLI entry point's config-flag parsing. */
class JanusCliTest {

    @AfterEach
    void resetCliState() {
        // A --config run in one test must never leak its property or lifecycle state into
        // the next (the stale micronaut.config.files hazard), so every test
        // leaves both cleared.
        System.clearProperty("micronaut.config.files");
        JanusCli.configFileSetByCli = false;
        JanusCli.exitHook = System::exit;
    }

    @Test
    void configFlagReturnsFollowingPath() {
        assertEquals("/abs/x.toml", JanusCli.extractConfigPath(new String[] {"--config", "/abs/x.toml"}));
    }

    @Test
    void shortConfigFlagReturnsFollowingPath() {
        assertEquals("/abs/x.toml", JanusCli.extractConfigPath(new String[] {"-c", "/abs/x.toml"}));
    }

    @Test
    void absentConfigFlagReturnsNull() {
        assertNull(JanusCli.extractConfigPath(new String[] {}));
        assertNull(JanusCli.extractConfigPath(new String[] {"--port", "8080"}));
    }

    @Test
    void configFlagIsFoundAfterOtherArgs() {
        assertEquals(
                "/abs/x.toml", JanusCli.extractConfigPath(new String[] {"--port", "8080", "--config", "/abs/x.toml"}));
    }

    @Test
    void equalsFormsReturnThePath() {
        assertEquals("/abs/x.toml", JanusCli.extractConfigPath(new String[] {"--config=/abs/x.toml"}));
        assertEquals("/abs/x.toml", JanusCli.extractConfigPath(new String[] {"-c=/abs/x.toml"}));
    }

    @Test
    void trailingConfigFlagThrows() {
        assertThrows(IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"--config"}));
        assertThrows(IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"-c"}));
    }

    @Test
    void usageLineNamesTheRealJanusCliInvocation() {
        // Every shipped 'janus'-named artifact (the native binary and the
        // janus-<ver>.jar fat jar) boots JanusApplication and rejects --config; the
        // only --config-capable JVM entry point is the janus-cli dist script /
        // :janus-cli:run, so the usage line must never point an operator at a
        // 'janus --config' command that does not exist.
        assertTrue(
                JanusCli.USAGE.startsWith("usage: janus-cli "),
                "the usage line names the janus-cli entry point, not the flag-rejecting 'janus' artifacts");
    }

    @Test
    void configFlagWhoseValueIsAnotherFlagThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> JanusCli.extractConfigPath(new String[] {"--config", "--verbose"}));
        assertTrue(ex.getMessage().contains(JanusCli.USAGE));
    }

    @Test
    void emptyEqualsFormThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"--config="}));
        assertTrue(ex.getMessage().contains(JanusCli.USAGE));
        IllegalArgumentException shortEx =
                assertThrows(IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"-c="}));
        assertTrue(shortEx.getMessage().contains(JanusCli.USAGE));
    }

    @Test
    void blankSpaceFormValueThrows() {
        // --config "" (space form) silently resolved to the working directory;
        // a blank value is as malformed as an empty equals form and must fail fast.
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"--config", ""}));
        assertTrue(ex.getMessage().contains(JanusCli.USAGE));
        IllegalArgumentException shortEx = assertThrows(
                IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"-c", "   "}));
        assertTrue(shortEx.getMessage().contains(JanusCli.USAGE));
    }

    @Test
    void equalsFormWhoseValueIsAFlagThrows() {
        // --config=-c / --config=--verbose were accepted (the equals form only checked
        // isBlank), absolutized to $CWD/-c, and surfaced later as a raw Micronaut
        // ConfigurationException instead of the usage line; the equals form must reject
        // flag-like values exactly like the space form does.
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"--config=-c"}));
        assertTrue(ex.getMessage().contains(JanusCli.USAGE));
        IllegalArgumentException longEx = assertThrows(
                IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"--config=--verbose"}));
        assertTrue(longEx.getMessage().contains(JanusCli.USAGE));
        IllegalArgumentException shortEx =
                assertThrows(IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"-c=-v"}));
        assertTrue(shortEx.getMessage().contains(JanusCli.USAGE));
    }

    @Test
    void whitespacePaddedFlagLikeValueThrows() {
        // --config " -c" (space form) and --config= -x (equals form) passed the
        // untrimmed startsWith("-") guard and were accepted as literal filenames,
        // surfacing later as a Micronaut config-resolution error instead of the usage
        // line; a flag-like value must be rejected in either form even when padded.
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"--config", " -c"}));
        assertTrue(ex.getMessage().contains(JanusCli.USAGE));
        IllegalArgumentException spaceShort = assertThrows(
                IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"-c", " --verbose"}));
        assertTrue(spaceShort.getMessage().contains(JanusCli.USAGE));
        IllegalArgumentException eq = assertThrows(
                IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"--config= -x"}));
        assertTrue(eq.getMessage().contains(JanusCli.USAGE));
        IllegalArgumentException eqShort =
                assertThrows(IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"-c= -x"}));
        assertTrue(eqShort.getMessage().contains(JanusCli.USAGE));
    }

    @Test
    void blankEqualsFormThrows() {
        // --config= with whitespace was accepted (only isEmpty was checked) and
        // absolutized to the working directory; must fail fast like the blank space form.
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"--config=   "}));
        assertTrue(ex.getMessage().contains(JanusCli.USAGE));
        IllegalArgumentException shortEx =
                assertThrows(IllegalArgumentException.class, () -> JanusCli.extractConfigPath(new String[] {"-c= "}));
        assertTrue(shortEx.getMessage().contains(JanusCli.USAGE));
    }

    @Test
    void duplicateConfigFlagsFirstWins() {
        assertEquals(
                "/a.toml", JanusCli.extractConfigPath(new String[] {"--config", "/a.toml", "--config", "/b.toml"}));
    }

    @Test
    void malformedDuplicateConfigFlagThrows() {
        // Parsing stopped at the first --config, so a dangling duplicate was silently
        // accepted and then stripped by withoutConfigFlag; every occurrence is validated.
        assertThrows(
                IllegalArgumentException.class,
                () -> JanusCli.extractConfigPath(new String[] {"--config", "/a.toml", "--config"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> JanusCli.extractConfigPath(new String[] {"--config", "/a.toml", "-c", "--verbose"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> JanusCli.extractConfigPath(new String[] {"--config=/a.toml", "--config="}));
        assertThrows(
                IllegalArgumentException.class,
                () -> JanusCli.extractConfigPath(new String[] {"--config=/a.toml", "-c=--verbose"}));
        assertEquals("/a.toml", JanusCli.extractConfigPath(new String[] {"--config=/a.toml", "-c", "/b.toml"}));
    }

    @Test
    void mainPrintsUsageToStderrAndExitsNonZeroOnMalformedFlag() {
        // The IllegalArgumentException from flag parsing used to escape main as a raw
        // stack trace; it must print the usage line to stderr and exit non-zero.
        List<Integer> exits = new ArrayList<>();
        JanusCli.exitHook = exits::add;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(stderr, true));
        try {
            JanusCli.main(new String[] {"--config"});
        } finally {
            System.setErr(originalErr);
        }
        assertEquals(List.of(2), exits, "a malformed flag exits non-zero");
        assertTrue(stderr.toString().contains(JanusCli.USAGE), "stderr carries the usage line, not a stack trace");
        assertNull(System.getProperty("micronaut.config.files"), "a rejected flag never sets the config property");
    }

    @Test
    void relativePathIsAbsolutized() {
        assertTrue(Path.of(JanusCli.resolveConfigPath("x.toml")).isAbsolute());
        assertEquals(Path.of("x.toml").toAbsolutePath().toString(), JanusCli.resolveConfigPath("x.toml"));
    }

    @Test
    void absolutePathPassesThroughVerbatim() {
        assertEquals("/abs/x.toml", JanusCli.resolveConfigPath("/abs/x.toml"));
    }

    @Test
    void knownSchemesPassThroughVerbatim() {
        assertEquals("classpath:config.toml", JanusCli.resolveConfigPath("classpath:config.toml"));
        assertEquals("file:/tmp/x.toml", JanusCli.resolveConfigPath("file:/tmp/x.toml"));
    }

    @Test
    void colonInFilenameIsNotAScheme() {
        // Any ':' was treated as a scheme prefix, so the POSIX-legal filename
        // "a:b.toml" passed through verbatim and Micronaut failed to resolve the phantom
        // scheme. Only classpath:/file: are schemes; everything else absolutizes.
        assertEquals(Path.of("a:b.toml").toAbsolutePath().toString(), JanusCli.resolveConfigPath("a:b.toml"));
    }

    @Test
    void withoutConfigFlagStripsTheConsumedFlag() {
        assertArrayEquals(
                new String[] {"--port", "8080", "--verbose"},
                JanusCli.withoutConfigFlag(new String[] {"--port", "8080", "--config", "/a.toml", "--verbose"}));
        assertArrayEquals(new String[] {}, JanusCli.withoutConfigFlag(new String[] {"--config", "/a.toml"}));
        assertArrayEquals(
                new String[] {"--port", "8080"},
                JanusCli.withoutConfigFlag(new String[] {"--port", "8080", "--config=/a.toml"}));
        assertArrayEquals(
                new String[] {"--port", "8080"},
                JanusCli.withoutConfigFlag(new String[] {"--port", "8080", "-c=/a.toml"}));
    }

    @Test
    void applyConfigFlagSetsThePropertyAndClearsItOnTheNextFlaglessRun() {
        // micronaut.config.files was set but never cleared, so a later main
        // without --config in the same JVM booted against the previous run's file.
        assertArrayEquals(new String[] {}, JanusCli.applyConfigFlag(new String[] {"--config", "/abs/x.toml"}));
        assertEquals("/abs/x.toml", System.getProperty("micronaut.config.files"));

        assertArrayEquals(new String[] {}, JanusCli.applyConfigFlag(new String[] {}));
        assertNull(System.getProperty("micronaut.config.files"), "a flagless run must clear a stale property");
    }

    @Test
    void applyConfigFlagLeavesAnOperatorSetPropertyAlone() {
        // Only a property set by the CLI itself is cleared; a JVM -Dmicronaut.config.files
        // (or the MICRONAUT_CONFIG_FILES env mapping) survives a flagless run.
        System.setProperty("micronaut.config.files", "/operator/x.toml");
        JanusCli.applyConfigFlag(new String[] {});
        assertEquals("/operator/x.toml", System.getProperty("micronaut.config.files"));
    }

    @Test
    void mainSetsMicronautConfigFilesAndFailsFastOnBogusPath() {
        String bogus =
                Path.of("definitely-missing-janus-config.toml").toAbsolutePath().toString();
        assertThrows(RuntimeException.class, () -> JanusCli.main(new String[] {"--config", bogus}));
        assertEquals(bogus, System.getProperty("micronaut.config.files"));
    }

    @Test
    void mainAbsolutizesARelativeConfigPath() {
        String bogus = "definitely-missing-janus-config-relative.toml";
        assertThrows(RuntimeException.class, () -> JanusCli.main(new String[] {"--config", bogus}));
        assertEquals(
                Path.of(bogus).toAbsolutePath().toString(),
                System.getProperty("micronaut.config.files"),
                "the property must hold an absolute path so resolution does not depend on the JVM CWD");
    }
}
