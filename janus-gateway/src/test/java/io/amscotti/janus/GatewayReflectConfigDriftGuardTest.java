package io.amscotti.janus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.gateway.dto.ModelEntry;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drift guard for the gateway's native-image reflect config (the gateway mirror
 * of {@code core.model.ReflectConfigDriftGuardTest}): a new Jackson-serialized HTTP DTO
 * or a new {@code JanusConfig} record must not land without a matching
 * {@code reflect-config.json} entry — otherwise it fails only at the slow
 * {@code nativeCompile} gate, late. Asserts the config covers exactly:
 *
 * <ol>
 * <li>the {@code JanusConfig} record tree ({@code JanusConfig} + every nested
 * configuration record — {@code getNestMembers}, so a new {@code [janus.*]}
 * record is picked up automatically),
 * <li>every class in the designated DTO subpackage {@code io.amscotti.janus.gateway.dto}
 * — <b>annotation-independent</b> (annotation-driven discovery let a
 * serialized-but-unannotated DTO escape coverage; the subpackage convention makes
 * it mechanical — any class added there is demanded in the config),
 * <li>every remaining {@code gateway} package class whose record components carry a
 * {@code com.fasterxml.jackson.annotation} annotation (the non-DTO-annotated
 * surface),
 * <li>the pinned JDBC entries the image needs in postgres mode
 * ({@code org.postgresql.Driver}, {@code org.postgresql.util.PGobject}).
 * </ol>
 *
 * <p>Every entry must register {@code allDeclaredConstructors} +
 * {@code allDeclaredMethods}, and records additionally {@code allRecordComponents} —
 * the registration shape the audit verified (the JDBC classes are not records).
 * Core's own guard is untouched; the Anthropic error payload serialized by
 * {@code GatewayJson#anthropicErrorBody} is a {@code core.codec} type and stays
 * covered by core's config (no gateway entry — decision, pinned by the core
 * guard's exact-coverage assertion).
 */
class GatewayReflectConfigDriftGuardTest {

    private static final String CONFIG_PATH =
            "META-INF/native-image/io.amscotti.janus/janus-gateway/reflect-config.json";
    private static final String GATEWAY_PACKAGE = "io.amscotti.janus.gateway";
    private static final String DTO_PACKAGE = "io.amscotti.janus.gateway.dto";
    private static final String JACKSON_ANNOTATION_PACKAGE = "com.fasterxml.jackson.annotation";
    private static final Set<String> PINNED_JDBC_ENTRIES =
            Set.of("org.postgresql.Driver", "org.postgresql.util.PGobject");

    @Test
    void reflectConfigCoversExactlyTheDtosConfigRecordsAndJdbcEntries() throws Exception {
        Set<String> expected = new TreeSet<>();
        // (a) the JanusConfig record tree — every nest member is a config record
        for (Class<?> member : JanusConfig.class.getNestMembers()) {
            expected.add(member.getName());
        }
        // (b) the designated DTO subpackage — every class, annotation-independent
        expected.addAll(dtoPackageClasses());
        // (c) remaining gateway Jackson DTOs — classes with com.fasterxml.jackson.annotation on
        // the class or its record components (annotation-driven beyond the dto package)
        expected.addAll(jacksonAnnotatedGatewayClasses());
        // (d) pinned JDBC entries (postgres driver + PGobject, /)
        expected.addAll(PINNED_JDBC_ENTRIES);

        assertEquals(
                expected,
                configuredNames(),
                "reflect-config.json must cover exactly the gateway DTO + JanusConfig record + JDBC entries");
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
        }
    }

    /**
     * The annotation-independence probe: every class in the designated DTO
     * subpackage is demanded by the guard regardless of whether it carries a Jackson
     * annotation (a serialized-but-unannotated DTO must not escape native-image
     * coverage). Concretely: the DTO subpackage set is exactly the configured
     * {@code gateway.dto} entries, and {@link ModelEntry} (the shape probe) is a member
     * of both.
     */
    @Test
    void everyDtoSubpackageClassIsRequiredAnnotationIndependently() throws Exception {
        Set<String> dtoClasses = dtoPackageClasses();
        assertTrue(dtoClasses.contains(ModelEntry.class.getName()), "ModelEntry must be in the DTO subpackage");
        assertEquals(
                dtoClasses,
                configuredNames().stream()
                        .filter(name -> name.startsWith(DTO_PACKAGE + "."))
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                "every gateway.dto class must have a reflect-config entry (annotation-independent)");
    }

    /**
     * A jar-scheme classpath (a distribution test run) must be enumerated, not
     * crash the guard — the core guard was hardened for exactly this run and the gateway
     * guard must behave the same. A synthetic jar stands in for the distribution image:
     * the entry set is read off the jar (nested subpackages included), never via
     * {@code Path.of(url.toURI)}.
     */
    @Test
    void jarSchemeGatewayEntriesAreEnumeratedRecursively() throws Exception {
        Path jarFile = Files.createTempFile("gateway-guard", ".jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarFile))) {
            // Directory entries mirror a real distribution jar, whose layout
            // JarURLConnection requires for the package URL (jar:…!/io/amscotti/janus/gateway).
            jar.putNextEntry(new JarEntry("io/amscotti/janus/gateway/"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("io/amscotti/janus/gateway/v2/dto/"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("io/amscotti/janus/gateway/dto/"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("io/amscotti/janus/gateway/AnthropicErrorMapper.class"));
            jar.write(new byte[] {1});
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("io/amscotti/janus/gateway/AnthropicErrorMapper$1.class"));
            jar.write(new byte[] {1});
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("io/amscotti/janus/gateway/v2/dto/JacksonDto.class"));
            jar.write(new byte[] {1});
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("io/amscotti/janus/gateway/dto/KeyDeleteRequest.class"));
            jar.write(new byte[] {1});
            jar.closeEntry();
        }
        URL url = URI.create("jar:" + jarFile.toUri().toURL() + "!/" + GATEWAY_PACKAGE.replace('.', '/'))
                .toURL();
        assertEquals(
                Set.of(
                        "io.amscotti.janus.gateway.AnthropicErrorMapper",
                        "io.amscotti.janus.gateway.AnthropicErrorMapper$1",
                        "io.amscotti.janus.gateway.v2.dto.JacksonDto",
                        "io.amscotti.janus.gateway.dto.KeyDeleteRequest"),
                classNamesUnder(url, GATEWAY_PACKAGE),
                "a jar-scheme classpath (a distribution test run) must be enumerated, not crash the guard");
    }

    /**
     * The annotation-driven scan is recursive over the gateway package tree
     * (everything except the pinned {@code dto} subpackage), so a Jackson-serialized record
     * introduced into a <em>new</em> subpackage (e.g. {@code gateway.v2.dto}) is enumerated
     * and demanded in the config rather than escaping reflect coverage with the guard green.
     */
    @Test
    void gatewayScanIsRecursiveOverNewSubpackages() throws Exception {
        Path gatewayDir = Files.createTempDirectory("gateway-scan");
        Files.writeString(gatewayDir.resolve("RootDto.class"), "");
        Files.createDirectories(gatewayDir.resolve("v2/dto"));
        Files.writeString(gatewayDir.resolve("v2/dto/JacksonDto.class"), "");
        Files.createDirectories(gatewayDir.resolve("dto"));
        Files.writeString(gatewayDir.resolve("dto/KeyDeleteRequest.class"), "");

        Set<String> names = classNamesUnder(gatewayDir.toUri().toURL(), GATEWAY_PACKAGE);
        assertTrue(names.contains("io.amscotti.janus.gateway.RootDto"), "the gateway root package is enumerated");
        assertTrue(
                names.contains("io.amscotti.janus.gateway.v2.dto.JacksonDto"),
                "a class in a NEW gateway subpackage must be enumerated by the recursive scan, "
                        + "so it cannot escape reflect coverage");
        assertTrue(
                names.contains("io.amscotti.janus.gateway.dto.KeyDeleteRequest"),
                "the dto subpackage is part of the tree (callers exclude it when covering it "
                        + "annotation-independently)");
    }

    /**
     * The anonymous {@code $N} classes currently on the gateway main classpath
     * are pinned. They are excluded from the reflect config <em>by assumption</em>
     * (instantiated directly, never reflected on), but nothing enforces that a future
     * anonymous class (e.g. a {@code mapper.valueToTree(new Object{…})} DTO) stays out of
     * Jackson. A new anonymous class trips this probe so a human reviews whether it needs
     * reflect coverage before it silently escapes at the slow native gate.
     */
    @Test
    void anonymousGatewayClassesArePinnedAndCheckedForHumanReview() throws Exception {
        assertEquals(
                Set.of(
                        "io.amscotti.janus.gateway.AnthropicErrorMapper$1",
                        "io.amscotti.janus.gateway.ErrorMapper$1",
                        "io.amscotti.janus.gateway.Governance$1",
                        "io.amscotti.janus.gateway.KeyAuthFilter$1",
                        "io.amscotti.janus.gateway.MetricsFactory$1"),
                anonymousGatewayClasses(),
                "the anonymous-class exclusion is an assumption; a NEW anonymous gateway class "
                        + "must be reviewed for reflect coverage");
    }

    /**
     * The test-classes-dir skip must be anchored to the actual output layout
     * ({@code build/classes/java/test}, {@code out/test}), not a bare {@code /test/}
     * substring — a checkout or workspace path containing {@code test} (e.g.
     * {@code …/code/test-projects/janus}) must not cause the <em>main</em> classes dir to be
     * skipped (which would collapse the exact-coverage set).
     */
    @Test
    void testClassesDirectoriesAreSkippedButWorkspacePathsWithTestSubstringAreNot() throws Exception {
        assertFalse(
                isTestClassesUrl(URI.create(
                                "file:/Users/me/code/test-projects/janus/build/classes/java/main/io/amscotti/janus/gateway/")
                        .toURL()),
                "a main dir under a /test/-containing workspace path must still be enumerated");
        assertFalse(isTestClassesUrl(
                URI.create("file:/Users/me/code/janus/build/classes/java/main/io/amscotti/janus/gateway/")
                        .toURL()));
        assertTrue(isTestClassesUrl(
                URI.create("file:/Users/me/code/test-projects/janus/build/classes/java/test/io/amscotti/janus/gateway/")
                        .toURL()));
        assertTrue(isTestClassesUrl(URI.create("file:/Users/me/code/janus/out/test/io/amscotti/janus/gateway/")
                .toURL()));
    }

    /** Every class in the designated DTO subpackage — annotation-independent by design. */
    private static Set<String> dtoPackageClasses() throws Exception {
        Set<String> names = new TreeSet<>();
        Enumeration<URL> urls =
                GatewayReflectConfigDriftGuardTest.class.getClassLoader().getResources(DTO_PACKAGE.replace('.', '/'));
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (isTestClassesUrl(url)) {
                continue; // only main classes are DTOs; test classes are not
            }
            for (String name : classNamesUnder(url, DTO_PACKAGE)) {
                if (!isAnonymousName(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Every gateway main class carrying a Jackson annotation (class-level or on a record
     * component), recursively over the gateway package tree except the pinned {@code dto}
     * subpackage (covered annotation-independently above).
     */
    private static Set<String> jacksonAnnotatedGatewayClasses() throws Exception {
        Set<String> names = new TreeSet<>();
        Enumeration<URL> urls = GatewayReflectConfigDriftGuardTest.class
                .getClassLoader()
                .getResources(GATEWAY_PACKAGE.replace('.', '/'));
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (isTestClassesUrl(url)) {
                continue; // only main classes are DTOs; test classes are not
            }
            for (String name : classNamesUnder(url, GATEWAY_PACKAGE)) {
                if (name.startsWith(DTO_PACKAGE + ".") || isAnonymousName(name)) {
                    continue;
                }
                if (isJacksonDto(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** The anonymous {@code $N} classes currently on the gateway main classpath. */
    private static Set<String> anonymousGatewayClasses() throws Exception {
        Set<String> anonymous = new TreeSet<>();
        Enumeration<URL> urls = GatewayReflectConfigDriftGuardTest.class
                .getClassLoader()
                .getResources(GATEWAY_PACKAGE.replace('.', '/'));
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (isTestClassesUrl(url)) {
                continue;
            }
            for (String name : classNamesUnder(url, GATEWAY_PACKAGE)) {
                if (isAnonymousName(name)) {
                    anonymous.add(name);
                }
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

    /** Anonymous {@code $N} nested classes are instantiated directly, never reflected on. */
    private static boolean isAnonymousName(String binaryName) {
        return binaryName.matches(".*\\$\\d+$");
    }

    /**
     * Every {@code.class} binary name in a package tree (recursive over subpackages)
     * contributed by a URL. Handles both the filesystem layout (Gradle
     * {@code build/classes/java/main}) and a jar-scheme classpath (a distribution
     * test run), which must not fail the guard: the entry set is read from the jar instead
     * of {@code Path.of(url.toURI)}. Anonymous {@code $N} classes are included; callers
     * drop or keep them as needed.
     */
    private static Set<String> classNamesUnder(URL url, String basePackage) throws Exception {
        Set<String> names = new TreeSet<>();
        String prefix = basePackage.replace('.', '/') + "/";
        if ("jar".equals(url.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            try (var jar = connection.getJarFile()) {
                jar.stream()
                        .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix))
                        .map(JarEntry::getName)
                        .map(name -> name.substring(prefix.length()))
                        .filter(name -> name.endsWith(".class"))
                        .map(name -> basePackage + "." + name.replace('/', '.').replaceFirst("\\.class$", ""))
                        .forEach(names::add);
            }
        } else {
            Path dir = Path.of(url.toURI());
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".class"))
                        .map(path -> basePackage + "."
                                + dir.relativize(path)
                                        .toString()
                                        .replace('/', '.')
                                        .replaceFirst("\\.class$", ""))
                        .forEach(names::add);
            }
        }
        return names;
    }

    private static boolean isJacksonDto(String name) {
        try {
            Class<?> clazz = Class.forName(name, false, GatewayReflectConfigDriftGuardTest.class.getClassLoader());
            if (hasJacksonAnnotation(clazz.getAnnotations())) {
                return true;
            }
            // Record-component annotations are NOT visible via RecordComponent.getAnnotations
            // (@JsonProperty's @Target excludes RECORD_COMPONENT); JLS 8.10.3 propagates them to
            // the accessor method, the field and the constructor parameter — inspect those instead.
            for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                if (hasJacksonAnnotation(method.getAnnotations())) {
                    return true;
                }
            }
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (hasJacksonAnnotation(field.getAnnotations())) {
                    return true;
                }
            }
            for (java.lang.reflect.Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                if (hasJacksonAnnotation(constructor.getAnnotations())) {
                    return true;
                }
            }
            return false;
        } catch (ClassNotFoundException e) {
            throw new AssertionError("class listed in the gateway package dir could not be loaded: " + name, e);
        }
    }

    private static boolean hasJacksonAnnotation(java.lang.annotation.Annotation[] annotations) {
        for (java.lang.annotation.Annotation annotation : annotations) {
            if (annotation.annotationType().getPackageName().equals(JACKSON_ANNOTATION_PACKAGE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRecord(String name) {
        try {
            return Class.forName(name, false, GatewayReflectConfigDriftGuardTest.class.getClassLoader())
                    .isRecord();
        } catch (ClassNotFoundException e) {
            throw new AssertionError("configured class not on the classpath: " + name, e);
        }
    }

    private static Set<String> configuredNames() throws Exception {
        Set<String> configured = new TreeSet<>();
        for (JsonNode entry : readConfig()) {
            configured.add(entry.get("name").asString());
        }
        return configured;
    }

    private static Iterable<JsonNode> readConfig() throws Exception {
        try (InputStream in =
                GatewayReflectConfigDriftGuardTest.class.getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            assertNotNull(in, "reflect-config.json must be on the test classpath");
            return JsonMapper.builder().build().readTree(in);
        }
    }
}
