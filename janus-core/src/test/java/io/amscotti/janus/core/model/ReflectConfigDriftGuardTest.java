package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Drift guard for the native-image reflect config: a new record in the canonical model
 * ({@code core.model}) or the OpenAI wire codec ({@code core.codec}) must not land
 * without a matching {@code reflect-config.json} entry (otherwise it fails only at the
 * native gate, late). Asserts the config covers exactly the model + codec classes on
 * the main classpath, and vice versa.
 *
 * <p>The scan mirrors the gateway guard's filter — only <em>anonymous</em>
 * nested classes ({@code Outer$1}, instantiated directly, never reflected on) are
 * excluded, so a <em>named</em> nested DTO record added to these packages is demanded in
 * the config instead of silently escaping exact coverage. Records additionally must
 * register {@code allRecordComponents} (the registration shape the audit verified —
 * consistency with {@code GatewayReflectConfigDriftGuardTest}).
 */
class ReflectConfigDriftGuardTest {

    private static final String CONFIG_PATH = "META-INF/native-image/io.amscotti.janus/janus-core/reflect-config.json";
    private static final String MODEL_PACKAGE = "io.amscotti.janus.core.model";
    private static final String CODEC_PACKAGE = "io.amscotti.janus.core.codec";
    private static final String[] COVERED_PACKAGES = {MODEL_PACKAGE, CODEC_PACKAGE};

    @Test
    void reflectConfigCoversExactlyTheModelAndCodecClasses() throws Exception {
        Set<String> classes = mainClassesFromClasspath();
        Set<String> configured = new TreeSet<>();
        for (JsonNode entry : readConfig()) {
            String name = entry.get("name").asString();
            assertTrue(
                    entry.get("allDeclaredConstructors").asBoolean(), name + ": must register declared constructors");
            assertTrue(entry.get("allDeclaredMethods").asBoolean(), name + ": must register declared methods");
            if (isRecord(name)) {
                assertTrue(
                        entry.get("allRecordComponents").asBoolean(),
                        name + ": a record must register record components");
            }
            configured.add(name);
        }
        assertEquals(classes, configured, "reflect-config.json must cover exactly core.model + core.codec");
        for (String name : configured) {
            Class<?> clazz = Class.forName(name);
            assertTrue(
                    clazz.getPackageName().equals(MODEL_PACKAGE)
                            || clazz.getPackageName().equals(CODEC_PACKAGE),
                    "configured class must live in core.model or core.codec: " + name);
        }
    }

    /**
     * Named nested classes must stay in the expected set (a {@code $Named}
     * record reflectively serialized by Jackson would otherwise escape the exact-coverage
     * assertion), while anonymous {@code $N} classes — instantiated directly, never
     * reflected on — stay excluded. Mirrors the gateway guard's {@code.*\$\d+\.class}
     * filter.
     */
    @Test
    void namedNestedClassesAreEnumeratedButAnonymousOnesAreNot() {
        // Pinned against the real nested classes the guard cares about (the
        // narrowing): the named impl nested classes stay, the anonymous TypeReference
        // token ($1) is dropped.
        assertEquals(
                List.of(
                        "io.amscotti.janus.core.codec.AnthropicMessageCodec",
                        "io.amscotti.janus.core.codec.AnthropicMessageCodec$AnthropicStreamDecoderImpl",
                        "io.amscotti.janus.core.codec.AnthropicMessageCodec$AnthropicStreamEncoderImpl",
                        "io.amscotti.janus.core.codec.AnthropicMessageCodec$AnthropicStreamEncoderImpl$OpenBlock"),
                classNamesFromFilesRaw(
                        "io.amscotti.janus.core.codec",
                        List.of(
                                "AnthropicMessageCodec.class",
                                "AnthropicMessageCodec$1.class",
                                "AnthropicMessageCodec$AnthropicStreamDecoderImpl.class",
                                "AnthropicMessageCodec$AnthropicStreamEncoderImpl.class",
                                "AnthropicMessageCodec$AnthropicStreamEncoderImpl$OpenBlock.class")));
    }

    /** The record-narrowing — a named nested record is a reflect candidate,
     * a top-level class is always one, and the private implementation nested classes
     * ({@code AnthropicMessageCodec$AnthropicStreamEncoderImpl} etc.) are not
     * (they are instantiated directly, never reflectively registered). */
    @Test
    void nestedRecordsAreReflectCandidatesButPrivateImplClassesAreNot() {
        assertTrue(isReflectCandidate(Probe.class.getName()), "a named nested record must be a reflect candidate");
        assertTrue(isReflectCandidate(ReflectConfigDriftGuardTest.class.getName()), "a top-level class is a candidate");
        assertTrue(
                !isReflectCandidate("io.amscotti.janus.core.codec.AnthropicMessageCodec$AnthropicStreamEncoderImpl"),
                "a private nested implementation class must not be a reflect candidate");
    }

    /** Test-scoped named nested record — the probe for the scanning logic. */
    private record Probe(String value) {}

    /**
     * The anonymous {@code $N} classes currently on the model/codec main
     * classpath are pinned. They are excluded from the reflect config <em>by
     * assumption</em> (instantiated directly — {@code TypeReference} tokens and the
     * compiler's synthetic enum-switch map — never reflectively serialized), but nothing
     * enforces that a future anonymous class (e.g. a {@code mapper.valueToTree(new
     * Object{…})} DTO) stays out of Jackson. A new anonymous class trips this probe so
     * a human reviews whether it needs reflect coverage before it silently escapes.
     */
    @Test
    void anonymousNestedClassesArePinnedAndCheckedForHumanReview() throws Exception {
        assertEquals(
                Set.of(
                        "io.amscotti.janus.core.codec.AnthropicMessageCodec$1",
                        "io.amscotti.janus.core.codec.OpenAiMessageCodec$1",
                        "io.amscotti.janus.core.codec.OpenAiMessageCodec$2"),
                anonymousClassesFromClasspath(),
                "the anonymous-class exclusion is an assumption; a NEW anonymous class on the "
                        + "model/codec classpath must be reviewed for reflect coverage");
    }

    /**
     * The test-classes-dir skip must be anchored to the actual output layout
     * ({@code build/classes/java/test}, {@code out/test}), not a bare {@code /test/}
     * substring — a checkout or workspace path containing {@code test} (e.g.
     * {@code …/code/test-projects/janus}) must not cause the <em>main</em> classes dir to
     * be skipped.
     */
    @Test
    void testClassesDirectoriesAreSkippedButWorkspacePathsWithTestSubstringAreNot() throws Exception {
        assertFalse(
                isTestClassesUrl(URI.create(
                                "file:/Users/me/code/test-projects/janus/build/classes/java/main/io/amscotti/janus/core/model/")
                        .toURL()),
                "a main dir under a /test/-containing workspace path must still be enumerated");
        assertFalse(isTestClassesUrl(
                URI.create("file:/Users/me/code/janus/build/classes/java/main/io/amscotti/janus/core/model/")
                        .toURL()));
        assertTrue(isTestClassesUrl(URI.create(
                        "file:/Users/me/code/test-projects/janus/build/classes/java/test/io/amscotti/janus/core/model/")
                .toURL()));
        assertTrue(isTestClassesUrl(URI.create("file:/Users/me/code/janus/out/test/io/amscotti/janus/core/model/")
                .toURL()));
    }

    private static Set<String> mainClassesFromClasspath() throws Exception {
        Set<String> names = new TreeSet<>();
        for (String packagePath : new String[] {MODEL_PACKAGE.replace('.', '/'), CODEC_PACKAGE.replace('.', '/')}) {
            Enumeration<URL> urls =
                    ReflectConfigDriftGuardTest.class.getClassLoader().getResources(packagePath);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (isTestClassesUrl(url)) {
                    continue; // only main classes are model/codec types; test classes are not
                }
                names.addAll(classNamesIn(packagePath, url));
            }
        }
        return names;
    }

    /** The anonymous {@code $N} classes a package URL contributes, as binary names. */
    private static Set<String> anonymousClassNamesIn(String packagePath, URL url) throws Exception {
        String packageName = packagePath.replace('/', '.');
        Set<String> names = new TreeSet<>();
        for (String fileName : fileNamesIn(packagePath, url)) {
            if (fileName.endsWith(".class") && fileName.matches(".*\\$\\d+\\.class")) {
                names.add(packageName + "." + fileName.replaceFirst("\\.class$", ""));
            }
        }
        return names;
    }

    private static Set<String> anonymousClassesFromClasspath() throws Exception {
        Set<String> anonymous = new TreeSet<>();
        for (String packagePath : new String[] {MODEL_PACKAGE.replace('.', '/'), CODEC_PACKAGE.replace('.', '/')}) {
            Enumeration<URL> urls =
                    ReflectConfigDriftGuardTest.class.getClassLoader().getResources(packagePath);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (isTestClassesUrl(url)) {
                    continue;
                }
                anonymous.addAll(anonymousClassNamesIn(packagePath, url));
            }
        }
        return anonymous;
    }

    /**
     * A URL whose path points at a test-classes output dir. Anchored to the actual
     * Gradle/IDE output layout ({@code build/classes/java/test} / {@code out/test}) rather
     * than a bare {@code /test/} substring — the bare substring also matches any workspace
     * or checkout path containing {@code test} (e.g. {@code …/code/test-projects/janus}),
     * which would wrongly skip the main classes dir and break the exact-coverage assertion
     * for a reason unrelated to the code under review.
     */
    static boolean isTestClassesUrl(URL url) {
        String path = url.getPath();
        return path.contains("/build/classes/java/test/") || path.contains("/out/test/");
    }

    /**
     * The {@code.class} names a package URL contributes, mapped to binary class names,
     * then narrowed to reflect candidates (anonymous + non-record named nested dropped).
     */
    private static Set<String> classNamesIn(String packagePath, URL url) throws Exception {
        return new TreeSet<>(classNamesFromFiles(packagePath.replace('/', '.'), fileNamesIn(packagePath, url)));
    }

    /**
     * The {@code.class} file names a package URL contributes (single level, no
     * subdirectories). Handles the filesystem layout (Gradle {@code build/classes/java/main})
     * and — a jar-scheme classpath (a distribution test run), which must not fail
     * the guard: the entry set is read from the jar instead of {@code Path.of(url.toURI)}.
     */
    private static List<String> fileNamesIn(String packagePath, URL url) throws Exception {
        if ("jar".equals(url.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            try (var jar = connection.getJarFile()) {
                String prefix = packagePath + "/";
                return jar.stream()
                        .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix))
                        .map(JarEntry::getName)
                        .map(name -> name.substring(prefix.length()))
                        .filter(name -> !name.contains("/"))
                        .toList();
            }
        }
        Path dir = Path.of(url.toURI());
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }

    /**
     * File names → binary class names, dropping only anonymous {@code $N} nested classes
     * at the name level, then narrowing the named nested remainder to records.
     */
    private static List<String> classNamesFromFiles(String packageName, List<String> fileNames) {
        return classNamesFromFilesRaw(packageName, fileNames).stream()
                .filter(ReflectConfigDriftGuardTest::isReflectCandidate)
                .toList();
    }

    /** Name-level filter: drops only anonymous {@code $N} nested classes. */
    private static List<String> classNamesFromFilesRaw(String packageName, List<String> fileNames) {
        return fileNames.stream()
                .filter(name -> name.endsWith(".class"))
                // Skip ANONYMOUS nested classes (e.g. TypeReference$1): instantiated
                // directly, never reflected on.
                .filter(name -> !name.matches(".*\\$\\d+\\.class"))
                .map(name -> packageName + "." + name.replaceFirst("\\.class$", ""))
                .sorted()
                .toList();
    }

    /**
     * A class the reflect config must cover. Top-level types (records, enums, sealed
     * interfaces, the codec classes) are always covered; <b>named nested</b> types are
     * covered only when they are <b>records</b> — the Jackson-serialized DTO shape (a
     * nested {@code $Named} record would otherwise escape exact coverage while being
     * reflectively serialized in native-image). Non-record named nested classes are
     * private implementation classes instantiated directly and deliberately <em>not</em>
     * reflectively registered ({@code AnthropicMessageCodec$AnthropicStreamDecoderImpl},
     * {@code $AnthropicStreamEncoderImpl}, {@code $AnthropicStreamEncoderImpl$OpenBlock}
     * — the audit, pinned here).
     */
    private static boolean isReflectCandidate(String name) {
        Class<?> clazz = load(name);
        return clazz.isRecord() || clazz.getEnclosingClass() == null;
    }

    private static boolean isRecord(String name) {
        return load(name).isRecord();
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, ReflectConfigDriftGuardTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("class on the test classpath could not be loaded: " + name, e);
        }
    }

    private static Iterable<JsonNode> readConfig() throws Exception {
        try (InputStream in = ReflectConfigDriftGuardTest.class.getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            assertNotNull(in, "reflect-config.json must be on the test classpath");
            return JsonSupport.mapper().readTree(in);
        }
    }
}
