package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link UserMessage} multimodal helpers and the privacy-aware string form.
 * Role/equality live in {@link MessageTest}; this pins the logic those tests skip.
 */
class UserMessageTest {

    @AfterEach
    void resetLogging() {
        ContentLogging.disable();
    }

    @Test
    void partsAreCopiedAndUnmodifiable() {
        List<ContentPart> original = new ArrayList<>(List.of(new TextContent("hi")));
        UserMessage message = UserMessage.multimodal("alice", original);
        original.clear();
        assertEquals(1, message.parts().size());
        assertEquals("alice", message.name());
        assertThrows(UnsupportedOperationException.class, () -> message.parts().add(new TextContent("x")));
    }

    @Test
    void partsTolerateNullElements() {
        // Malformed wire input (a content array with a null block) must not escape the
        // compact constructor as a raw NullPointerException from List.copyOf — the null
        // element survives (construct-and-fail-later) so the codecs can reject it with
        // a typed error.
        List<ContentPart> withNull = new ArrayList<>();
        withNull.add(new TextContent("hi"));
        withNull.add(null);
        UserMessage message = UserMessage.multimodal(withNull);
        assertEquals(2, message.parts().size());
        assertNull(message.parts().get(1));
        assertThrows(UnsupportedOperationException.class, () -> message.parts().add(null));
    }

    @Test
    void isMultimodalRequiresNonEmptyParts() {
        assertFalse(new UserMessage("hi").isMultimodal());
        assertFalse(new UserMessage("hi", "n", null).isMultimodal());
        assertFalse(new UserMessage(null, null, List.of()).isMultimodal());
        assertTrue(UserMessage.multimodal(List.of(new TextContent("hi"))).isMultimodal());
    }

    @Test
    void plainTextPrefersStringContentThenConcatenatesTextParts() {
        assertEquals("hello", new UserMessage("hello").plainText());
        assertEquals("", new UserMessage(null, null, null).plainText());
        assertEquals("", new UserMessage(null, null, List.of()).plainText());
        assertEquals(
                "ab",
                UserMessage.multimodal(List.of(new TextContent("a"), new TextContent("b")))
                        .plainText());
        assertEquals(
                "keep",
                UserMessage.multimodal(List.of(
                                new TextContent("keep"),
                                new ImageUrlContent("https://example.test/x.png"),
                                ImageSourceContent.base64("image/png", "qq"),
                                new TextContent(null)))
                        .plainText());
    }

    @Test
    void toStringOmitsContentButCountsPartsUnlessLoggingIsOn() {
        UserMessage multimodal = UserMessage.multimodal("bob", List.of(new TextContent("secret")));
        assertFalse(multimodal.toString().contains("secret"));
        assertTrue(multimodal.toString().contains("parts=1"));
        assertTrue(multimodal.toString().contains("name=bob"));

        ContentLogging.enable();
        assertTrue(multimodal.toString().contains("secret"));
    }
}
