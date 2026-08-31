package io.amscotti.janus.core.codec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical {@code ChatRequest.toolChoice} ↔ wire {@code tool_choice} translation
 *.
 *
 * <p><b>Canonical form is OpenAI-idiomatic</b> (the canonical model is OpenAI-named):
 * {@code null | "auto" | "none" | "required" | {"type":"function","function":{"name":N}}}.
 *
 * <ul>
 * <li>canonical → Anthropic: {@code "auto"} → {@code {"type":"auto"}}; {@code "none"}
 * → {@code {"type":"none"}}; {@code "required"} → {@code {"type":"any"}}; the
 * OpenAI function object → {@code {"type":"tool","name":N}}; any other {@code Object}
 * → verbatim passthrough (idempotence).
 * <li>Anthropic → canonical: {@code {"type":"auto"}} → {@code "auto"}; {@code
 * {"type":"none"}} → {@code "none"}; {@code {"type":"any"}} → {@code "required"};
 * {@code {"type":"tool","name":N}} → the OpenAI function object; other → verbatim.
 * <li>{@code disable_parallel_tool_use: true} (Anthropic-only, no canonical/OpenAI
 * home — legal on {@code auto}/{@code none}/{@code any} <em>and</em> the
 * specific-tool {@code {"type":"tool","name":N}} form) → request {@code extras}
 * key {@code "tool_choice_disable_parallel_tool_use"} (Boolean); re-emitted on
 * Anthropic encode when present; absent/false → nothing.
 * </ul>
 *
 * <p>{@link #normalizeOpenAi(Object)} converts Anthropic-idiomatic shapes that leaked
 * into a canonical/OpenAI request back to the canonical OpenAI-idiomatic form (the
 * Anthropic-sourced request path); it is the identity for canonical forms.
 */
public final class ToolChoiceMapper {

    /** Extras key carrying the Anthropic-only {@code disable_parallel_tool_use} flag. */
    public static final String EXTRAS_DISABLE_PARALLEL = "tool_choice_disable_parallel_tool_use";

    private ToolChoiceMapper() {}

    /** Canonical (OpenAI-idiomatic) tool choice → Anthropic wire form. */
    public static Object canonicalToAnthropic(Object toolChoice) {
        if (toolChoice instanceof String choice) {
            return switch (choice) {
                case "auto" -> Map.of("type", "auto");
                case "none" -> Map.of("type", "none");
                case "required" -> Map.of("type", "any");
                default -> toolChoice; // unknown string → verbatim (tolerant)
            };
        }
        if (toolChoice instanceof Map<?, ?> map) {
            if ("function".equals(map.get("type"))) {
                Object function = map.get("function");
                if (function instanceof Map<?, ?> fn && fn.get("name") != null) {
                    // OpenAI {"type":"function","function":{"name":N}} → {"type":"tool","name":N}
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("type", "tool");
                    result.put("name", fn.get("name"));
                    return result;
                }
            }
            // Any other object → verbatim passthrough (idempotence).
        }
        return toolChoice;
    }

    /** Anthropic wire form → canonical (OpenAI-idiomatic) tool choice. */
    public static Object anthropicToCanonical(Object toolChoice, Map<String, Object> extras) {
        if (toolChoice instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if ("auto".equals(type) || "none".equals(type) || "any".equals(type)) {
                Object disableParallel = map.get("disable_parallel_tool_use");
                if (Boolean.TRUE.equals(disableParallel) && extras != null) {
                    extras.put(EXTRAS_DISABLE_PARALLEL, Boolean.TRUE);
                }
                return switch ((String) type) {
                    case "auto" -> "auto";
                    case "none" -> "none";
                    default -> "required"; // "any"
                };
            }
            if ("tool".equals(type) && map.get("name") != null) {
                // {"type":"tool","name":N, "disable_parallel_tool_use":?} → OpenAI
                // {"type":"function","function":{"name":N}} (+ the disable-parallel extras
                // flag — Anthropic allows the flag on the specific-tool form too).
                Object disableParallel = map.get("disable_parallel_tool_use");
                if (Boolean.TRUE.equals(disableParallel) && extras != null) {
                    extras.put(EXTRAS_DISABLE_PARALLEL, Boolean.TRUE);
                }
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", map.get("name"));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "function");
                result.put("function", function);
                return result;
            }
        }
        return toolChoice; // other → verbatim (idempotence)
    }

    /**
     * Normalize a tool choice that may be Anthropic-idiomatic (e.g. placed in canonical
     * by an Anthropic-sourced request) into the canonical OpenAI-idiomatic form. Canonical
     * forms (strings, the OpenAI function object, other objects) pass through unchanged.
     */
    public static Object normalizeOpenAi(Object toolChoice) {
        return anthropicToCanonical(toolChoice, null);
    }
}
