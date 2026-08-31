package io.amscotti.janus.core.codec;

import io.amscotti.janus.core.model.ChatResponse;

/**
 * Single home for the canonical ↔ wire stop-reason tables — both directions, both
 * providers. /'s per-codec
 * private tables are deleted in and both codecs delegate here.
 *
 * <p><b>Known values</b> (the reference implementation's complete sets): OpenAI decode
 * {@code "stop" | "length" | "tool_calls" | "function_call" (→ tool_calls) |
 * "content_filter"}; Anthropic decode
 * {@code "end_turn" | "stop_sequence" | "stop" (→ stop) | "max_tokens" (→ length) |
 * "tool_use" (→ tool_calls) | "content_filter"}. Encode reverses; canonical
 * {@code "error"} → {@code "stop"} (OpenAI) / {@code "end_turn"} (Anthropic).
 *
 * <p><b>Reconciliation decision (documented divergence from the reference):</b> the reference implementation's
 * {@code StopReason} falls back to {@code :stop} for unknown values; this table keeps
 * <em>unknown-verbatim pass-through</em> instead (pinned with round-trip tests),
 * because canonical → wire → canonical must stay idempotent — a hard {@code :stop}
 * fallback would corrupt unknown upstream values. Decision: unknown values pass through verbatim
 * here; null → null. The divergence is confined to this table. One scoped exception:
 * Anthropic's {@code refusal}/{@code pause_turn} stop reasons (real values the
 * Anthropic face can hand to the OpenAI face) map to a valid OpenAI
 * {@code finish_reason} ({@code "stop"}) on the <em>OpenAI encode side only</em> — the
 * canonical keeps the verbatim value so the Anthropic round trip stays idempotent, while
 * the OpenAI wire never leaks a value most OpenAI SDKs reject.
 */
public final class StopReasonTable {

    private StopReasonTable() {}

    /** OpenAI wire finish reason → canonical stop reason (unknown verbatim). */
    public static String openAiToCanonical(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "stop" -> ChatResponse.STOP_REASON_STOP;
            case "length" -> ChatResponse.STOP_REASON_LENGTH;
            case "tool_calls", "function_call" -> ChatResponse.STOP_REASON_TOOL_CALLS;
            case "content_filter" -> ChatResponse.STOP_REASON_CONTENT_FILTER;
            default -> raw; // unknown values pass through verbatim (tolerant)
        };
    }

    /** Canonical stop reason → OpenAI wire finish reason (unknown verbatim). */
    public static String canonicalToOpenAi(String canonical) {
        if (canonical == null) {
            return null;
        }
        return switch (canonical) {
            case ChatResponse.STOP_REASON_STOP -> "stop";
            case ChatResponse.STOP_REASON_LENGTH -> "length";
            case ChatResponse.STOP_REASON_TOOL_CALLS -> "tool_calls";
            case ChatResponse.STOP_REASON_CONTENT_FILTER -> "content_filter";
            case ChatResponse.STOP_REASON_ERROR -> "stop";
            // Anthropic now emits "refusal" and "pause_turn"; on the ao cross-format leg
            // these become OpenAI finish_reason values outside the SDK vocabulary most
            // clients validate. Map them to a valid OpenAI value on the OpenAI *encode*
            // side only — the canonical keeps the verbatim value (Anthropic idempotence).
            case "refusal", "pause_turn" -> "stop";
            default -> canonical; // unknown values pass through verbatim (tolerant)
        };
    }

    /** Anthropic wire stop reason → canonical stop reason (unknown verbatim). */
    public static String anthropicToCanonical(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "end_turn", "stop_sequence", "stop" -> ChatResponse.STOP_REASON_STOP;
            case "max_tokens" -> ChatResponse.STOP_REASON_LENGTH;
            case "tool_use" -> ChatResponse.STOP_REASON_TOOL_CALLS;
            case "content_filter" -> ChatResponse.STOP_REASON_CONTENT_FILTER;
            default -> raw; // unknown values pass through verbatim (tolerant)
        };
    }

    /** Canonical stop reason → Anthropic wire stop reason (unknown verbatim). */
    public static String canonicalToAnthropic(String canonical) {
        if (canonical == null) {
            return null;
        }
        return switch (canonical) {
            case ChatResponse.STOP_REASON_STOP, ChatResponse.STOP_REASON_ERROR -> "end_turn";
            case ChatResponse.STOP_REASON_LENGTH -> "max_tokens";
            case ChatResponse.STOP_REASON_TOOL_CALLS -> "tool_use";
            case ChatResponse.STOP_REASON_CONTENT_FILTER -> "content_filter";
            default -> canonical; // unknown values pass through verbatim (tolerant)
        };
    }
}
