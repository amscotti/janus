package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@link JsonProbe} — the minimal structural JSON probe the adapters use to recognize
 * upstream error envelopes without pulling Jackson into janus-provider (module boundary,
 * AGENTS.md). Pins the well-formed member-extraction paths plus the malformed-input matrix
 * (a top-level string value ending in a lone backslash must yield null, never
 * throw a raw {@link StringIndexOutOfBoundsException} — every upstream failure must surface as
 * a {@link ProviderException}, with non-JSON bodies falling back to the status mapping).
 */
class JsonProbeTest {

    // --------------------------------------------------------- member value

    @Test
    void memberValueExtractsNestedObject() {
        assertEquals(
                "{\"type\":\"rate_limit_error\"}",
                JsonProbe.jsonMemberValue("{\"error\":{\"type\":\"rate_limit_error\"},\"ok\":1}", "error"));
    }

    @Test
    void memberValueExtractsWellFormedStringIncludingQuotes() {
        assertEquals("\"abc\"", JsonProbe.jsonMemberValue("{\"error\":\"abc\"}", "error"));
    }

    @Test
    void memberValueExtractsPrimitives() {
        assertEquals("true", JsonProbe.jsonMemberValue("{\"error\":true}", "error"));
        assertEquals("123", JsonProbe.jsonMemberValue("{\"error\":123}", "error"));
    }

    @Test
    void memberValueExtractsArrayValue() {
        // Coverage: an array-valued member is returned raw (the probe is structural,
        // not a JSON parser) — no crash, no truncation at the first closing bracket.
        assertEquals("[1,2]", JsonProbe.jsonMemberValue("{\"error\":[1,2]}", "error"));
        assertEquals("[]", JsonProbe.jsonMemberValue("{\"error\":[]}", "error"));
    }

    @Test
    void topLevelMemberWinsOverNestedOccurrence() {
        // Coverage: the scan is top-level-only — a nested occurrence of the searched
        // name must not shadow (or be returned instead of) the top-level member.
        assertEquals("429", JsonProbe.jsonMemberValue("{\"status\":429,\"nested\":{\"status\":\"x\"}}", "status"));
        assertEquals(
                Integer.valueOf(429),
                JsonProbe.jsonNumberMember("{\"status\":429,\"nested\":{\"status\":\"x\"}}", "status"));
    }

    @Test
    void memberValueHandlesEscapedQuotesInsideStringValue() {
        assertEquals("\"a\\\"b\"", JsonProbe.jsonMemberValue("{\"error\":\"a\\\"b\"}", "error"));
    }

    @Test
    void memberValueHandlesDeeplyNestedObjects() {
        assertEquals(
                "{\"b\":{\"c\":{\"type\":\"x\"}}}",
                JsonProbe.jsonMemberValue("{\"a\":{\"b\":{\"c\":{\"type\":\"x\"}}},\"z\":2}", "a"));
    }

    @Test
    void memberValueWithEscapedKeyParsesKeyStringAware() {
        // A \" inside a key is an escaped quote, not the key's closing quote — the key text
        // is scanned string-aware and compared raw (the probe does not unescape).
        assertEquals("{\"type\":\"x\"}", JsonProbe.jsonMemberValue("{\"er\\\"ror\":{\"type\":\"x\"}}", "er\\\"ror"));
    }

    // --------------------------------------- malformed input

    @Test
    void stringValueEndingInLoneBackslashReturnsNullWithoutThrowing() {
        // A top-level string value ending in a lone backslash (a truncated error
        // body cut mid-escape) used to throw StringIndexOutOfBoundsException. It must return
        // null — the value is not a well-formed JSON string — and the adapter falls back to
        // the status mapping.
        assertNull(JsonProbe.jsonMemberValue("{\"error\":\"abc\\", "error"));
    }

    @Test
    void stringMemberEndingInLoneBackslashReturnsNullWithoutThrowing() {
        assertNull(JsonProbe.jsonStringMember("{\"error\":\"abc\\", "error"));
    }

    @Test
    void malformedKeyWithoutColonIsSkippedAndLaterMembersStillFound() {
        // The recovery skip must not terminate the member scan on a separator
        // inside a nested structure; the malformed "a" key is skipped and the later
        // well-formed "b" member is still found. The "error" object inside the malformed
        // segment cannot be trusted → null.
        String json = "{\"a\"\"error\":{\"type\":\"rate_limit_error\"},\"b\":1}";
        assertNull(JsonProbe.jsonMemberValue(json, "error"));
        assertEquals("1", JsonProbe.jsonMemberValue(json, "b"));
    }

    @Test
    void unterminatedKeyReturnsNullWithoutThrowing() {
        assertNull(JsonProbe.jsonMemberValue("{\"erro\\", "error"));
    }

    @Test
    void truncatedNestedObjectDegradesToInBoundsRawValue() {
        // The depth-scan path falls out to s.length — in bounds, never a crash.
        assertEquals(
                "{\"type\":\"rate_limit_error\"}",
                JsonProbe.jsonMemberValue("{\"error\":{\"type\":\"rate_limit_error\"}", "error"));
    }

    @Test
    void nonObjectInputReturnsNull() {
        assertNull(JsonProbe.jsonMemberValue("not json", "error"));
        assertNull(JsonProbe.jsonMemberValue("[]", "error"));
    }

    // ------------------------------------------------------- string member

    @Test
    void stringMemberStripsQuotes() {
        assertEquals("rate_limit_error", JsonProbe.jsonStringMember("{\"type\":\"rate_limit_error\"}", "type"));
    }

    @Test
    void stringMemberReturnsNullForNonStringValue() {
        assertNull(JsonProbe.jsonStringMember("{\"type\":true}", "type"));
        assertNull(JsonProbe.jsonStringMember("{\"type\":123}", "type"));
        assertNull(JsonProbe.jsonStringMember("{\"type\":null}", "type"));
    }

    @Test
    void stringMemberReturnsEmptyStringForEmptyValue() {
        // Coverage: `"type":""` is a well-formed string whose text is "" — the probe
        // returns "" (not null); the adapters' envelopeTypeToProviderType("") falls back.
        assertEquals("", JsonProbe.jsonStringMember("{\"type\":\"\"}", "type"));
    }

    // ------------------------------------------------------- number member

    @Test
    void numberMemberParsesBareInteger() {
        assertEquals(Integer.valueOf(429), JsonProbe.jsonNumberMember("{\"status\":429}", "status"));
    }

    @Test
    void numberMemberParsesQuotedInteger() {
        assertEquals(Integer.valueOf(429), JsonProbe.jsonNumberMember("{\"status\":\"429\"}", "status"));
    }

    @Test
    void numberMemberParsesFloatingPointStatus() {
        assertEquals(Integer.valueOf(429), JsonProbe.jsonNumberMember("{\"status\":429.0}", "status"));
        assertEquals(Integer.valueOf(429), JsonProbe.jsonNumberMember("{\"status\":\"429.0\"}", "status"));
    }

    @Test
    void numberMemberParsesScientificAndHexFloatNotation() {
        // Coverage: the Double fallback accepts scientific and hex-float forms, so
        // they parse into the int range rather than degrading to null.
        assertEquals(Integer.valueOf(429), JsonProbe.jsonNumberMember("{\"status\":429e0}", "status"));
        assertEquals(Integer.valueOf(8), JsonProbe.jsonNumberMember("{\"status\":0x1p3}", "status"));
    }

    @Test
    void numberMemberParsesNegativeInteger() {
        assertEquals(Integer.valueOf(-1), JsonProbe.jsonNumberMember("{\"status\":-1}", "status"));
    }

    @Test
    void numberMemberParsesWhitespacePaddedQuotedInteger() {
        // Some upstreams pad a quoted status with spaces; the Double fallback tolerates the
        // surrounding whitespace.
        assertEquals(Integer.valueOf(429), JsonProbe.jsonNumberMember("{\"status\":\" 429 \"}", "status"));
    }

    @Test
    void numberMemberReturnsNullForOversizedOrGarbage() {
        assertNull(JsonProbe.jsonNumberMember("{\"status\":429000000000}", "status"));
        assertNull(JsonProbe.jsonNumberMember("{\"status\":\"oops\"}", "status"));
        assertNull(JsonProbe.jsonNumberMember("{\"status\":true}", "status"));
    }

    @Test
    void numberMemberReturnsNullWhenMemberAbsent() {
        assertNull(JsonProbe.jsonNumberMember("{\"type\":\"x\"}", "status"));
    }
}
