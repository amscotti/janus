package io.amscotti.janus.core.codec;

import java.util.List;

/**
 * One content part of a {@code message} output item — {@code output_text} with an
 * (currently always empty) annotations list.
 */
public record OpenAiResponsesOutputText(String type, String text, List<String> annotations) {

    public OpenAiResponsesOutputText {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }
}
