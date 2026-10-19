package io.amscotti.janus.core.codec;

import java.util.List;

/**
 * The {@code reasoning} output item: the model's reasoning for one choice,
 * carried as summary text. Janus has no provider-encrypted reasoning payload
 * on this stateless face, so {@code encrypted_content} is never emitted.
 */
public record OpenAiResponsesReasoningItem(String id, String type, String status, List<SummaryText> summary)
        implements OpenAiResponsesOutputItem {

    public OpenAiResponsesReasoningItem {
        summary = summary == null ? null : List.copyOf(summary);
    }

    /** One summary segment ({@code {"type":"summary_text","text":"..."}}). */
    public record SummaryText(String type, String text) {}
}
