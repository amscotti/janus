package io.amscotti.janus.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One typed answer in a {@link DecisionResponse} — the System One answer
 * shapes, keyed by question name. Probabilities are the upstream model's own
 * estimates: Janus routes them, it never blends probabilities across backends.
 */
public sealed interface DecisionAnswer {

    /**
     * The selected option, a probability per option (insertion-ordered), and
     * the upstream confidence (probability concentration — not a calibrated
     * chance of correctness; null when the upstream omits it).
     */
    record ChoiceAnswer(String choice, Map<String, Double> probabilities, Double confidence) implements DecisionAnswer {

        public ChoiceAnswer {
            probabilities =
                    probabilities == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
        }

        @Override
        public String toString() {
            return ContentLogging.enabled()
                    ? "ChoiceAnswer[choice=" + choice + ", probabilities=" + probabilities + ", confidence="
                            + confidence + "]"
                    : "ChoiceAnswer[answer content not logged]";
        }
    }

    /** P(true) for a {@code noul} question, 0–1. */
    record NoulAnswer(double noul) implements DecisionAnswer {
        @Override
        public String toString() {
            return "NoulAnswer[noul=" + noul + "]";
        }
    }

    /**
     * The probability-weighted level index, the level descriptions keyed by
     * index string ({@code "0"}, {@code "1"}, …), a probability per level, and
     * the upstream confidence (null when omitted). Legend values are any JSON
     * value (TypeSafe permits structured level descriptions).
     */
    record ScoreAnswer(double score, Map<String, Object> legend, Map<String, Double> probabilities, Double confidence)
            implements DecisionAnswer {

        public ScoreAnswer {
            legend = legend == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(legend));
            probabilities =
                    probabilities == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
        }

        @Override
        public String toString() {
            return ContentLogging.enabled()
                    ? "ScoreAnswer[score=" + score + ", legend=" + legend + ", probabilities=" + probabilities
                            + ", confidence=" + confidence + "]"
                    : "ScoreAnswer[answer content not logged]";
        }
    }

    /**
     * The upstream declined to answer this question (the OpenAI Decisions
     * per-question refusal). System One upstreams never produce it; on the
     * Jev-shaped face it encodes as {@code {"type":"refusal"}} — TypeSafe SDK
     * clients drop unknown answer types, so a refusal reads as a missing key
     * there (documented in docs/system-one.md).
     */
    record RefusalAnswer() implements DecisionAnswer {}

    /**
     * A located box (TypeSafe's Box answer): normalized edges as fractions of
     * image width/height, top-left origin. Confidence is null when omitted.
     */
    record BoxAnswer(double top, double bottom, double left, double right, Double confidence)
            implements DecisionAnswer {
        @Override
        public String toString() {
            return "BoxAnswer[box=(" + top + "," + bottom + "," + left + "," + right + "), confidence=" + confidence
                    + "]";
        }
    }
}
