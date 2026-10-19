package io.amscotti.janus.core.model;

/**
 * One unit of model reasoning content on an assistant message (a complete block)
 * or a streaming {@link Delta} (a fragment). Two wire forms share the slot:
 *
 * <ul>
 * <li><b>Visible thinking</b> — {@code text} plus the provider {@code signature}
 * (Anthropic {@code thinking} blocks). The signature is replay-critical: an
 * Anthropic tool loop may reject an assistant turn whose thinking blocks were
 * dropped or altered, so codecs round-trip it byte-identically. Foreign
 * reasoning text without a signature (OpenAI-wire {@code reasoning_content})
 * carries a {@code null} signature and must never be re-emitted as a signed
 * Anthropic block.
 * <li><b>Redacted thinking</b> — opaque {@code redactedData} (Anthropic
 * {@code redacted_thinking}); {@code text}/{@code signature} are null.
 * </ul>
 *
 * On a {@link Delta} any subset may be present (a text fragment, a signature
 * fragment, or a complete redacted block).
 */
public record ReasoningContent(String text, String signature, String redactedData) {

    /** Visible (optionally signed) thinking text. */
    public static ReasoningContent thinking(String text, String signature) {
        return new ReasoningContent(text, signature, null);
    }

    /** Opaque redacted thinking payload. */
    public static ReasoningContent redacted(String data) {
        return new ReasoningContent(null, null, data);
    }

    /** True when this unit carries nothing translatable (empty strings count
     * as absent — a thinking block-open ships {@code "signature":""}). */
    public boolean isEmpty() {
        return (text == null || text.isEmpty())
                && (signature == null || signature.isEmpty())
                && (redactedData == null || redactedData.isEmpty());
    }

    /** Reasoning text is conversation content: excluded from log output unless
     * content logging is explicitly enabled ({@code [janus.privacy] log-content}). */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "ReasoningContent[text=" + text + ", signature=" + signature + ", redactedData=" + redactedData
                    + "]";
        }
        return "ReasoningContent[text=" + (text == null ? "absent" : "present") + ", signature="
                + (signature == null ? "absent" : "present") + ", redactedData="
                + (redactedData == null ? "absent" : "present") + ", content not logged]";
    }
}
