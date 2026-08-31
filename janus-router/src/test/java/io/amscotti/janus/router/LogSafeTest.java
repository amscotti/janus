package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Review L2 — direct unit pin of the router's log-forgery hygiene (previously exercised
 * only indirectly through Router exception messages): a client-controlled string echoed
 * into a log line must never carry control characters (an embedded newline must not
 * forge a second log record) and must be length-capped.
 */
class LogSafeTest {

    @Test
    void controlCharactersBecomeSpaces() {
        assertEquals("a b  c", LogSafe.text("a\nb\r\nc"), "each control char maps to one space");
        assertEquals("x  y", LogSafe.text("x\t y"));
        assertEquals("del ", LogSafe.text("del\u007f"), "DEL (0x7f) is a control char");
        assertEquals("high chars pass", LogSafe.text("high chars pass"));
    }

    @Test
    void longValuesAreTruncatedWithAnEllipsis() {
        String longValue = "m".repeat(500);
        String safe = LogSafe.text(longValue);
        assertEquals(128 + 3, safe.length(), "capped at MAX_LENGTH plus the ellipsis");
        assertEquals("m".repeat(128) + "...", safe);
    }

    @Test
    void shortValuesPassThroughUnchanged() {
        assertEquals("deepseek-v4-flash", LogSafe.text("deepseek-v4-flash"));
        assertEquals("", LogSafe.text(""));
        assertEquals(128, LogSafe.text("e".repeat(128)).length());
    }
}
