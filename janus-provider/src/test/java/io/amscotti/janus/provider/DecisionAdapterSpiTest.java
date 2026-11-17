package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * ServiceLoader registration guard for the decision SPI — discovers every concrete
 * {@link DecisionAdapter} on the main classpath, verifies bidirectional parity with
 * {@code META-INF/services}, checks expected auth schemes, and confirms discovery
 * instances are inert (blank base, blank secret).
 */
class DecisionAdapterSpiTest {

    private static final String SERVICES_PATH = "META-INF/services/io.amscotti.janus.provider.DecisionAdapter";

    private static final Map<String, String> EXPECTED_AUTH_TYPES = Map.of(
            SystemOneAdapter.NAME, ProviderAuth.TYPE_BEARER,
            OpenAiDecisionsAdapter.NAME, ProviderAuth.TYPE_BEARER,
            GeminiDecisionsAdapter.NAME, ProviderAuth.TYPE_X_GOOG_API_KEY);

    @Test
    void serviceLoaderFindsEveryNonAbstractDecisionAdapterWithExpectedAuthAndInertState() throws Exception {
        Set<String> classpathAdapters = scanConcreteDecisionAdapters();
        assertTrue(classpathAdapters.contains(GeminiDecisionsAdapter.class.getName()));

        List<DecisionAdapter> adapters = new ArrayList<>();
        for (DecisionAdapter adapter : ServiceLoader.load(DecisionAdapter.class)) {
            adapters.add(adapter);
        }
        assertEquals(classpathAdapters.size(), adapters.size(), "ServiceLoader count must match concrete adapters");

        Set<String> discoveredClasses = new TreeSet<>();
        for (DecisionAdapter adapter : adapters) {
            discoveredClasses.add(adapter.getClass().getName());
            String expectedAuth = EXPECTED_AUTH_TYPES.get(adapter.name());
            assertNotNull(expectedAuth, "unexpected adapter name: " + adapter.name());
            assertEquals(expectedAuth, adapter.auth().type(), adapter.name() + " auth type");
            assertEquals("", adapter.baseUrl(), adapter.name() + " discovery instance must be blank-base inert");
            assertEquals("", adapter.auth().secret(), adapter.name() + " discovery instance must be secret-less");
        }
        assertEquals(
                classpathAdapters, discoveredClasses, "all concrete DecisionAdapter classes must register via SPI");
    }

    @Test
    void servicesFileMatchesDiscoveredAndClasspathAdapters() throws Exception {
        Set<String> discovered = new TreeSet<>();
        ServiceLoader.load(DecisionAdapter.class).stream()
                .map(provider -> provider.type().getName())
                .forEach(discovered::add);
        Set<String> fileEntries = readServicesFile();
        assertEquals(discovered, fileEntries, "services file must match ServiceLoader discovery");
        assertEquals(scanConcreteDecisionAdapters(), fileEntries, "services file must cover every concrete adapter");
    }

    private static Set<String> scanConcreteDecisionAdapters() throws Exception {
        Set<String> classes = new TreeSet<>();
        ClassLoader cl = DecisionAdapter.class.getClassLoader();
        URL codeLocation =
                DecisionAdapter.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(codeLocation, "DecisionAdapter code source must be resolvable");
        Path locationPath = Path.of(codeLocation.toURI());
        if (Files.isDirectory(locationPath)) {
            Path pkgDir = locationPath.resolve("io/amscotti/janus/provider");
            try (var walk = Files.walk(pkgDir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".class")).forEach(p -> {
                    String rel = locationPath
                            .relativize(p)
                            .toString()
                            .replace('/', '.')
                            .replace('\\', '.');
                    maybeAddAdapter(cl, rel.substring(0, rel.length() - ".class".length()), classes);
                });
            }
        } else {
            try (JarFile jar = new JarFile(locationPath.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("io/amscotti/janus/provider/") && name.endsWith(".class")) {
                        String cls = name.replace('/', '.');
                        maybeAddAdapter(cl, cls.substring(0, cls.length() - ".class".length()), classes);
                    }
                }
            }
        }
        assertTrue(!classes.isEmpty(), "expected at least one DecisionAdapter implementation");
        return classes;
    }

    private static void maybeAddAdapter(ClassLoader cl, String className, Set<String> sink) {
        if (className.contains("$")) {
            return;
        }
        try {
            Class<?> cls = Class.forName(className, false, cl);
            if (DecisionAdapter.class.isAssignableFrom(cls)
                    && !cls.isInterface()
                    && !Modifier.isAbstract(cls.getModifiers())) {
                sink.add(cls.getName());
            }
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Failed to inspect class " + className, e);
        }
    }

    private static Set<String> readServicesFile() throws Exception {
        Set<String> entries = new TreeSet<>();
        try (InputStream in = DecisionAdapterSpiTest.class.getClassLoader().getResourceAsStream(SERVICES_PATH)) {
            assertNotNull(in, "services file must be on the test classpath");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    entries.add(trimmed);
                }
            }
        }
        return entries;
    }
}
