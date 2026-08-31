package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * step 2: {@link UnknownModelException} — unchecked, carries the un-routable alias for
 * The OpenAI error envelope (no message sniffing).
 */
class UnknownModelExceptionTest {

    @Test
    void isUnchecked() {
        UnknownModelException e = new UnknownModelException("gpt-4");
        assertInstanceOf(RuntimeException.class, e);
    }

    @Test
    void carriesModelAndMessage() {
        UnknownModelException e = new UnknownModelException("deepseek-nope");
        assertEquals("deepseek-nope", e.model());
        assertTrue(e.getMessage().contains("deepseek-nope"), e.getMessage());
    }

    @Test
    void rejectsNullModel() {
        // The guard must fire BEFORE the super message is built: LogSafe.text
        // dereferences its input, so a null model used to escape as a raw NPE from the
        // sanitizer (value.length) with no message, and the constructor's own
        // requireNonNull was unreachable.
        NullPointerException e = assertThrows(NullPointerException.class, () -> new UnknownModelException(null));
        assertEquals("model", e.getMessage(), "the intended guard, not the sanitizer's NPE");
    }

    @Test
    void messageIsSanitizedButTypedAliasStaysRaw() {
        // A hostile alias must not forge a log record through the message, while
        // the typed model accessor keeps the exact client value for the gateway's
        // envelope mapping (ErrorMapper reads model, never the message).
        UnknownModelException e = new UnknownModelException("a\nb");
        assertFalse(e.getMessage().contains("\n"), e.getMessage()); // newline neutralized
        assertEquals("a\nb", e.model()); // raw value preserved for the typed mapping
        String longAlias = "x".repeat(300);
        UnknownModelException truncated = new UnknownModelException(longAlias);
        assertTrue(truncated.getMessage().length() < 200, truncated.getMessage()); // length-capped
        assertEquals(longAlias, truncated.model());
    }
}
