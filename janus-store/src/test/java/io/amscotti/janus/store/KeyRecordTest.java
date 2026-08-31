package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link KeyRecord} hygiene: the credential-bearing record is never serialized
 * to a wire surface (that is {@link KeyRecordView}'s structural job), but a record's
 * auto-generated {@code toString} would print the hex of {@code salt} and
 * {@code secretHash} — offline-crackable credential material if a future debug-logging
 * change ever logs a record. This suite pins the {@link KeyRecord#toString}
 * redaction and the defensive-copy accessors.
 */
class KeyRecordTest {

    private static final HexFormat HEX = HexFormat.of();

    private static final byte[] SALT = {1, 2, 3, 4};
    private static final byte[] HASH = {5, 6, 7, 8, 9, 10, 11, 12};

    private static KeyRecord record() {
        return new KeyRecord(
                "id-1",
                "abc123",
                SALT,
                HASH,
                "owner",
                List.of("deepseek-v4-flash"),
                KeyStatus.ACTIVE,
                1L,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void toStringNeverLeaksSaltOrHashMaterial() {
        KeyRecord record = record();
        String text = record.toString();
        assertTrue(text.contains("[redacted]"), "the credential bytes must be explicitly redacted: " + text);
        assertFalse(text.contains(HEX.formatHex(record.salt())), "salt hex must never appear: " + text);
        assertFalse(text.contains(HEX.formatHex(record.secretHash())), "secretHash hex must never appear: " + text);
    }

    @Test
    void saltAndHashAccessorsReturnDefensiveCopies() {
        KeyRecord record = record();
        byte[] saltBefore = record.salt();
        byte[] hashBefore = record.secretHash();
        byte[] saltCopy = record.salt();
        byte[] hashCopy = record.secretHash();
        saltCopy[0] = 99;
        hashCopy[0] = 99;
        assertArrayEquals(saltBefore, record.salt(), "mutating a returned salt copy must not corrupt the stored salt");
        assertArrayEquals(
                hashBefore, record.secretHash(), "mutating a returned hash copy must not corrupt the stored hash");
    }
}
