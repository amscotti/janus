package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * ServiceLoader registration guard for the decision SPI — the {@link
 * ProviderAdapterSpiTest} analogue: exactly the two decision adapters must be
 * discoverable, the services file must match discovery in both directions,
 * and discovery instances must be inert (blank base, blank secret).
 */
class DecisionAdapterSpiTest {

    private static final String SERVICES_PATH = "META-INF/services/io.amscotti.janus.provider.DecisionAdapter";

    private static final Set<String> EXPECTED_ADAPTER_CLASSES =
            Set.of(SystemOneAdapter.class.getName(), OpenAiDecisionsAdapter.class.getName());

    @Test
    void serviceLoaderFindsExactlyTheTwoDecisionAdapters() {
        List<DecisionAdapter> adapters = new ArrayList<>();
        for (DecisionAdapter adapter : ServiceLoader.load(DecisionAdapter.class)) {
            adapters.add(adapter);
        }
        assertEquals(2, adapters.size(), "ServiceLoader must find exactly two decision adapters");
        assertTrue(adapters.stream().anyMatch(SystemOneAdapter.class::isInstance));
        assertTrue(adapters.stream().anyMatch(OpenAiDecisionsAdapter.class::isInstance));
        for (DecisionAdapter adapter : adapters) {
            assertEquals("bearer", adapter.auth().type(), adapter.name() + " auth type");
            assertEquals("", adapter.baseUrl(), adapter.name() + " discovery instance must be blank-base inert");
            assertEquals("", adapter.auth().secret(), adapter.name() + " discovery instance must be secret-less");
        }
    }

    @Test
    void servicesFileMatchesDiscoveredAdapters() throws Exception {
        Set<String> discovered = new TreeSet<>();
        ServiceLoader.load(DecisionAdapter.class).stream()
                .map(provider -> provider.type().getName())
                .forEach(discovered::add);
        Set<String> fileEntries = readServicesFile();
        assertEquals(discovered, fileEntries, "services file must match ServiceLoader discovery");
        assertEquals(EXPECTED_ADAPTER_CLASSES, fileEntries);
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
