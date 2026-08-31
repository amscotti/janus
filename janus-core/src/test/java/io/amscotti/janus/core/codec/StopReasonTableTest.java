package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.amscotti.janus.core.model.ChatResponse;
import org.junit.jupiter.api.Test;

/**
 * The shared stop-reason table : every known value in both directions for
 * both providers, the OpenAI {@code "function_call"} → {@code tool_calls} alias (the reference
 * {@code StopReason} precedent), unknown-verbatim pass-through (documented divergence
 * from the reference implementation's {@code :stop} fallback — pinned by / round-trip tests and the
 * canonical → wire → canonical idempotence), and null tolerance.
 */
class StopReasonTableTest {

    // ------------------------------------------------------------ OpenAI

    @Test
    void openAiToCanonicalCoversKnownValuesAndFunctionCallAlias() {
        assertEquals(ChatResponse.STOP_REASON_STOP, StopReasonTable.openAiToCanonical("stop"));
        assertEquals(ChatResponse.STOP_REASON_LENGTH, StopReasonTable.openAiToCanonical("length"));
        assertEquals(ChatResponse.STOP_REASON_TOOL_CALLS, StopReasonTable.openAiToCanonical("tool_calls"));
        assertEquals(
                ChatResponse.STOP_REASON_TOOL_CALLS,
                StopReasonTable.openAiToCanonical("function_call"), // the reference alias
                "the legacy function_call finish reason must alias tool_calls");
        assertEquals(ChatResponse.STOP_REASON_CONTENT_FILTER, StopReasonTable.openAiToCanonical("content_filter"));
    }

    @Test
    void canonicalToOpenAiCoversKnownValues() {
        assertEquals("stop", StopReasonTable.canonicalToOpenAi(ChatResponse.STOP_REASON_STOP));
        assertEquals("length", StopReasonTable.canonicalToOpenAi(ChatResponse.STOP_REASON_LENGTH));
        assertEquals("tool_calls", StopReasonTable.canonicalToOpenAi(ChatResponse.STOP_REASON_TOOL_CALLS));
        assertEquals("content_filter", StopReasonTable.canonicalToOpenAi(ChatResponse.STOP_REASON_CONTENT_FILTER));
        assertEquals("stop", StopReasonTable.canonicalToOpenAi(ChatResponse.STOP_REASON_ERROR));
    }

    @Test
    void canonicalToOpenAiMapsAnthropicRefusalAndPauseTurnToValidValues() {
        // Anthropic's "refusal"/"pause_turn" stop reasons (real values) would
        // leak as invalid OpenAI finish_reason values on the ao cross-format leg — most
        // OpenAI SDKs validate against stop/length/tool_calls/content_filter/function_call.
        // The OpenAI encode side maps them to "stop"; the Anthropic encode side keeps them
        // verbatim so the Anthropic round trip stays idempotent.
        assertEquals("stop", StopReasonTable.canonicalToOpenAi("refusal"));
        assertEquals("stop", StopReasonTable.canonicalToOpenAi("pause_turn"));
        assertEquals("refusal", StopReasonTable.canonicalToAnthropic("refusal"));
        assertEquals("refusal", StopReasonTable.anthropicToCanonical("refusal"));
    }

    // ----------------------------------------------------------- Anthropic

    @Test
    void anthropicToCanonicalCoversKnownValues() {
        for (String wire : new String[] {"end_turn", "stop_sequence", "stop"}) {
            assertEquals(ChatResponse.STOP_REASON_STOP, StopReasonTable.anthropicToCanonical(wire), wire);
        }
        assertEquals(ChatResponse.STOP_REASON_LENGTH, StopReasonTable.anthropicToCanonical("max_tokens"));
        assertEquals(ChatResponse.STOP_REASON_TOOL_CALLS, StopReasonTable.anthropicToCanonical("tool_use"));
        assertEquals(ChatResponse.STOP_REASON_CONTENT_FILTER, StopReasonTable.anthropicToCanonical("content_filter"));
    }

    @Test
    void canonicalToAnthropicCoversKnownValues() {
        assertEquals("end_turn", StopReasonTable.canonicalToAnthropic(ChatResponse.STOP_REASON_STOP));
        assertEquals("max_tokens", StopReasonTable.canonicalToAnthropic(ChatResponse.STOP_REASON_LENGTH));
        assertEquals("tool_use", StopReasonTable.canonicalToAnthropic(ChatResponse.STOP_REASON_TOOL_CALLS));
        assertEquals("content_filter", StopReasonTable.canonicalToAnthropic(ChatResponse.STOP_REASON_CONTENT_FILTER));
        assertEquals("end_turn", StopReasonTable.canonicalToAnthropic(ChatResponse.STOP_REASON_ERROR));
    }

    // -------------------------------------------------- tolerance contract

    @Test
    void unknownValuesPassThroughVerbatimInBothDirections() {
        assertEquals("weird_reason", StopReasonTable.openAiToCanonical("weird_reason"));
        assertEquals("weird_reason", StopReasonTable.canonicalToOpenAi("weird_reason"));
        assertEquals("weird_reason", StopReasonTable.anthropicToCanonical("weird_reason"));
        assertEquals("weird_reason", StopReasonTable.canonicalToAnthropic("weird_reason"));
        // A wire value that looks like a canonical constant must not alias across
        // providers (the tables are direction-specific).
        assertEquals(ChatResponse.STOP_REASON_STOP, StopReasonTable.anthropicToCanonical("stop"));
        assertEquals("stop", StopReasonTable.canonicalToOpenAi(ChatResponse.STOP_REASON_STOP));
    }

    @Test
    void nullIsNullInBothDirections() {
        assertNull(StopReasonTable.openAiToCanonical(null));
        assertNull(StopReasonTable.canonicalToOpenAi(null));
        assertNull(StopReasonTable.anthropicToCanonical(null));
        assertNull(StopReasonTable.canonicalToAnthropic(null));
    }

    @Test
    void knownValuesRoundTripIdempotently() {
        for (String canonical : new String[] {
            ChatResponse.STOP_REASON_STOP,
            ChatResponse.STOP_REASON_LENGTH,
            ChatResponse.STOP_REASON_TOOL_CALLS,
            ChatResponse.STOP_REASON_CONTENT_FILTER
        }) {
            assertEquals(canonical, StopReasonTable.openAiToCanonical(StopReasonTable.canonicalToOpenAi(canonical)));
            assertEquals(
                    canonical, StopReasonTable.anthropicToCanonical(StopReasonTable.canonicalToAnthropic(canonical)));
        }
    }
}
