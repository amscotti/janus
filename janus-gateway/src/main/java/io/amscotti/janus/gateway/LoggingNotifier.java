package io.amscotti.janus.gateway;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default {@link Notifier}: logs every event at {@code WARN} — the
 * operator's zero-config sink. Never raises (the
 * logging framework swallows its own failures; a null payload is a caller bug handled
 * by the SLF4J contract). The log line carries the event name + payload so soft-cap
 * crossings are audible without any webhook configuration.
 */
final class LoggingNotifier implements Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingNotifier.class);

    @Override
    public void notify(String event, Map<String, Object> payload) {
        LOG.warn("governance event '{}': {}", event, payload);
    }

    @Override
    public void forgetKey(String keyId) {
        // no per-key state — nothing to prune (the Notifier.forgetKey contract)
    }
}
