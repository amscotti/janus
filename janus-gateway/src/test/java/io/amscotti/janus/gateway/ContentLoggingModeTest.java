package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ContentLogging;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code [janus.privacy] log-content} binds at boot and sets the process-wide mode:
 * absent/false ⇒ conversation content is excluded from log output (the default);
 * true ⇒ content logging is on for debugging, with a boot warning.
 */
class ContentLoggingModeTest {

    @AfterEach
    void reset() {
        ContentLogging.disable();
    }

    @Nested
    @MicronautTest
    @Property(name = "janus.privacy.log-content", value = "true")
    class EnabledBoot {

        @Test
        void contentLoggingIsOnAtBoot() {
            assertTrue(ContentLogging.enabled(), "log-content = true enables content logging at boot");
        }
    }

    @Nested
    @MicronautTest
    class DefaultBoot {

        @Test
        void contentLoggingIsOffByDefault() {
            assertFalse(ContentLogging.enabled(), "absent [janus.privacy] ⇒ content never logged");
        }
    }

    @Nested
    @MicronautTest
    @Property(name = "janus.privacy.log-content", value = "false")
    class ExplicitOffBoot {

        @Test
        void explicitFalseKeepsContentExcluded() {
            assertFalse(ContentLogging.enabled());
        }
    }
}
