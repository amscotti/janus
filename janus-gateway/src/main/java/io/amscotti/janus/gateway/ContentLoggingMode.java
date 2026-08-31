package io.amscotti.janus.gateway;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.core.model.ContentLogging;
import io.micronaut.context.annotation.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies {@code [janus.privacy] log-content} at boot: conversation content (prompts,
 * completions, tool arguments and results, image payloads) is excluded from log output
 * unless the operator explicitly enabled it. Metrics, call records, token counts and
 * costs are always logged and are never affected by this switch.
 *
 * <p>Eager ({@code @Context}) so the mode is fixed before any request is served, and
 * loud when on: a warning at boot makes the degraded privacy posture visible.
 */
@Context
final class ContentLoggingMode {

    private static final Logger LOG = LoggerFactory.getLogger(ContentLoggingMode.class);

    ContentLoggingMode(JanusConfig config) {
        boolean enabled = config.privacy() != null && config.privacy().effectiveLogContent();
        if (enabled) {
            ContentLogging.enable();
            LOG.warn("[janus.privacy] log-content = true — conversation content (prompts and completions) "
                    + "WILL appear in this gateway's logs. Intended for local debugging only; remove "
                    + "the setting to restore the default, where content is never logged.");
        }
    }
}
