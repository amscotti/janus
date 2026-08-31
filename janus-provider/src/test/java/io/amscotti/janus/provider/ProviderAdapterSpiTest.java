package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * ServiceLoader registration guard (AGENTS.md — registrations are explicit and checked,
 * no dynamic classloading). pinned the single {@link DeepSeekAdapter}; (this
 * test) pins the exact three-adapter set — {@link DeepSeekAdapter} ({"@code deepseek}",
 * bearer), {@link OpenAiCompatibleAdapter} ({"@code openai-compatible}", bearer) and
 * {@link AnthropicAdapter} ({"@code anthropic}", {@code x-api-key} auth) — and doubles as
 * the services-file drift guard: the {@code META-INF/services} content must match the
 * discovered adapter set exactly, in both directions.
 */
class ProviderAdapterSpiTest {

    private static final String SERVICES_PATH = "META-INF/services/io.amscotti.janus.provider.ProviderAdapter";

    private static final Set<String> EXPECTED_ADAPTER_CLASSES = Set.of(
            DeepSeekAdapter.class.getName(), OpenAiCompatibleAdapter.class.getName(), AnthropicAdapter.class.getName());

    @Test
    void serviceLoaderFindsExactlyTheThreeAdapters() {
        List<ProviderAdapter> providers = new ArrayList<>();
        for (ProviderAdapter provider : ServiceLoader.load(ProviderAdapter.class)) {
            providers.add(provider);
        }

        assertEquals(3, providers.size(), "ServiceLoader must find exactly three providers");
        Map<String, String> authTypes = Map.of(
                "deepseek", "bearer",
                "openai-compatible", "bearer",
                "anthropic", "x-api-key");
        for (ProviderAdapter adapter : providers) {
            assertEquals(authTypes.get(adapter.name()), adapter.auth().type(), adapter.name() + " auth type");
        }
        assertTrue(providers.stream().anyMatch(DeepSeekAdapter.class::isInstance));
        assertTrue(providers.stream().anyMatch(OpenAiCompatibleAdapter.class::isInstance));
        assertTrue(providers.stream().anyMatch(AnthropicAdapter.class::isInstance));
    }

    @Test
    void servicesFileMatchesDiscoveredAdapters() throws Exception {
        Set<String> discovered = new TreeSet<>();
        ServiceLoader.load(ProviderAdapter.class).stream()
                .map(provider -> provider.type().getName())
                .forEach(discovered::add);

        Set<String> fileEntries = readServicesFile();

        assertEquals(discovered, fileEntries, "services file must match ServiceLoader discovery");
        assertEquals(
                EXPECTED_ADAPTER_CLASSES,
                fileEntries,
                "services file must list exactly DeepSeekAdapter, OpenAiCompatibleAdapter, AnthropicAdapter");
    }

    @Test
    void discoveryInstancesAreInert() {
        // Every ServiceLoader discovery instance is inert-by-shape (blank base,
        // blank secret) — never an armed-but-unauthenticated client (the old DeepSeek /
        // Anthropic discovery forms normalized a real default base URL). An accidental
        // call on one fails fast in the adapter's endpoint guard, not by issuing a
        // real upstream request.
        List<ProviderAdapter> providers = new ArrayList<>();
        for (ProviderAdapter provider : ServiceLoader.load(ProviderAdapter.class)) {
            providers.add(provider);
        }
        for (ProviderAdapter adapter : providers) {
            assertEquals("", adapter.baseUrl(), adapter.name() + " discovery instance must be blank-base inert");
            assertEquals("", adapter.auth().secret(), adapter.name() + " discovery instance must be secret-less");
        }
    }

    private static Set<String> readServicesFile() throws Exception {
        Set<String> entries = new TreeSet<>();
        try (InputStream in = ProviderAdapterSpiTest.class.getClassLoader().getResourceAsStream(SERVICES_PATH)) {
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
