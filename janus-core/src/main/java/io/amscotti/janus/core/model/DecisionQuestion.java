package io.amscotti.janus.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One typed question in a {@link DecisionRequest} — the System One primitives
 * (docs/system-one.md). The question's identity is its key in the request's
 * questions map, not a field here (the Jev wire shape; the OpenAI Decisions
 * codec maps the key to its {@code name} member).
 *
 * <p>{@code instructions} and every criteria value are <b>any JSON value</b>
 * ({@link String}, {@code Map}, {@code List}, number, boolean or null): the
 * TypeSafe/Jev {@code EntryType} accepts structure, and the {@code systemone}
 * leg passes it through verbatim. The OpenAI Decisions leg has no structured
 * field, so it folds structure into the instructions/description text (the
 * documented loss).
 */
public sealed interface DecisionQuestion {

    /**
     * The question the decision model evaluates: a string or a structured JSON
     * value, null when the criteria alone describe it (TypeSafe permits an
     * omitted/null instructions).
     */
    Object instructions();

    /**
     * Pick exactly one option. {@code criteria} maps each option to its
     * description (insertion-ordered; a null description means the option name
     * describes itself). TypeSafe accepts one option; the OpenAI leg requires
     * at least two (enforced per backend, never globally).
     */
    record ChoiceQuestion(Object instructions, Map<String, Object> criteria) implements DecisionQuestion {

        public ChoiceQuestion {
            criteria = criteria == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
        }

        @Override
        public String toString() {
            return ContentLogging.enabled()
                    ? "ChoiceQuestion[instructions=" + instructions + ", criteria=" + criteria + "]"
                    : "ChoiceQuestion[instructions/criteria content not logged]";
        }
    }

    /**
     * Estimate P(true). {@code criteria} optionally describes the {@code "true"}
     * and {@code "false"} sides; null when the instructions stand alone.
     */
    record NoulQuestion(Object instructions, Map<String, Object> criteria) implements DecisionQuestion {

        public NoulQuestion {
            criteria = criteria == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
        }

        @Override
        public String toString() {
            return ContentLogging.enabled()
                    ? "NoulQuestion[instructions=" + instructions + ", criteria=" + criteria + "]"
                    : "NoulQuestion[instructions/criteria content not logged]";
        }
    }

    /**
     * Rate against ordered levels. {@code criteria} lists the level descriptions
     * lowest-first (level indices start at 0); each entry may be any JSON value.
     * TypeSafe accepts one level; OpenAI requires 2–10.
     */
    record ScoreQuestion(Object instructions, List<Object> criteria) implements DecisionQuestion {

        public ScoreQuestion {
            criteria = criteria == null ? null : Collections.unmodifiableList(new ArrayList<>(criteria));
        }

        @Override
        public String toString() {
            return ContentLogging.enabled()
                    ? "ScoreQuestion[instructions=" + instructions + ", criteria=" + criteria + "]"
                    : "ScoreQuestion[instructions/criteria content not logged]";
        }
    }

    /**
     * Locate something in an image (TypeSafe's Box primitive; requires the
     * {@code TypeSafe-Danger-Zone-Images: true} request header). The answer is
     * four normalized edges. No criteria.
     */
    record BoxQuestion(Object instructions) implements DecisionQuestion {
        @Override
        public String toString() {
            return ContentLogging.enabled()
                    ? "BoxQuestion[instructions=" + instructions + "]"
                    : "BoxQuestion[instructions content not logged]";
        }
    }
}
