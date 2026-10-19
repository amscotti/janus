package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ContentLogging;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code [janus.privacy] log-content} binds at boot and sets the process-wide mode:
 * absent/false ⇒ conversation content is excluded from log output (the default);
 * true ⇒ content logging is on for debugging, with a boot warning.
 *
 * <p>One top-level class per boot shape: Micronaut 5.2 rejects
 * {@code @MicronautTest} on {@code @Nested} classes (a nested class shares the
 * enclosing context, so the per-boot config would be ignored). Each class
 * resets the process-wide flag after its test so same-JVM order cannot leak
 * the enabled state into a later boot.
 */
@MicronautTest
@Property(name = "janus.privacy.log-content", value = "true")
class ContentLoggingEnabledBootTest {

    @AfterEach
    void reset() {
        ContentLogging.disable();
    }

    @Test
    void contentLoggingIsOnAtBoot() {
        assertTrue(ContentLogging.enabled(), "log-content = true enables content logging at boot");
    }
}

@MicronautTest
class ContentLoggingDefaultBootTest {

    @AfterEach
    void reset() {
        ContentLogging.disable();
    }

    @Test
    void contentLoggingIsOffByDefault() {
        assertFalse(ContentLogging.enabled(), "absent [janus.privacy] ⇒ content never logged");
    }
}

@MicronautTest
@Property(name = "janus.privacy.log-content", value = "false")
class ContentLoggingExplicitOffBootTest {

    @AfterEach
    void reset() {
        ContentLogging.disable();
    }

    @Test
    void explicitFalseKeepsContentExcluded() {
        assertFalse(ContentLogging.enabled());
    }
}
