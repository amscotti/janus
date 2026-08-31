package io.amscotti.janus.core.codec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prompt-cache wire markers on the OpenAI and Anthropic faces.
 *
 * <p>OpenAI GPT-5.6+ (Chat Completions and Responses) marks a reusable prefix with
 * {@code prompt_cache_breakpoint: {"mode":"explicit"}} on a content part, plus optional
 * request-level {@code prompt_cache_key} / {@code prompt_cache_options}. Boolean
 * {@code true} is not a legal breakpoint (upstream 400) but is still accepted on decode
 * as the same intent. Models older than GPT-5.6 400 those fields — encode emits them
 * only for the GPT-5.6+ family.
 *
 * <p>Anthropic marks a prefix with {@code cache_control: {"type":"ephemeral"}} on a
 * content block (or request-level for automatic caching). Codecs translate:
 *
 * <ul>
 * <li>OpenAI breakpoint ↔ Anthropic block {@code cache_control}
 * <li>OpenAI {@code prompt_cache_key}/{@code prompt_cache_options} ride extras on the
 * OpenAI wire and are dropped on Anthropic encode (no home)
 * </ul>
 */
public final class PromptCache {

    /** Canonical / Anthropic {@code cache_control} for an explicit prefix breakpoint. */
    public static final Map<String, Object> EPHEMERAL = Map.of("type", "ephemeral");

    /** OpenAI GPT-5.6+ content-part breakpoint object. */
    public static final Map<String, Object> EXPLICIT_BREAKPOINT = Map.of("mode", "explicit");

    /** OpenAI GPT-5.6+ request-level options: explicit-only, default TTL. */
    public static final Map<String, Object> EXPLICIT_OPTIONS = Map.of("mode", "explicit", "ttl", "30m");

    /**
     * GPT-5.6 and later family ids ({@code gpt-5.6}, {@code gpt-5.6-luna},
     * {@code openai/gpt-5.6-luna}, {@code gpt-5.7}, …). Pre-5.6 ids (including
     * {@code gpt-5.5}) 400 {@code prompt_cache_breakpoint} / {@code prompt_cache_options}.
     */
    private static final Pattern GPT_56_OR_LATER = Pattern.compile("(?i)(?:^|/)gpt-5\\.(?:[6-9]|[1-9]\\d)");

    /**
     * Alibaba Qwen ids on OpenAI-compatible wires (OpenRouter {@code qwen/…},
     * {@code qwen3.8-max}, …). Those upstreams 400 GPT-5.6 breakpoints and require
     * Anthropic-shaped {@code cache_control} on content parts instead.
     */
    private static final Pattern QWEN_FAMILY = Pattern.compile("(?i)(?:^|/)qwen");

    private PromptCache() {}

    /** True when the alias may legally carry OpenAI explicit cache fields. */
    public static boolean supportsExplicitOpenAiBreakpoints(String model) {
        return model != null && GPT_56_OR_LATER.matcher(model).find();
    }

    /**
     * True when the OpenAI-compatible encode should emit Anthropic-shaped
     * {@code cache_control} on content parts (Qwen / Alibaba via OpenRouter).
     */
    public static boolean supportsOpenAiWireCacheControl(String model) {
        return model != null && QWEN_FAMILY.matcher(model).find();
    }

    /** True when {@code cacheControl} is Anthropic-shaped {@code {type: ephemeral}}. */
    public static boolean isEphemeral(Object cacheControl) {
        if (!(cacheControl instanceof Map<?, ?> map)) {
            return false;
        }
        Object type = map.get("type");
        return type != null && "ephemeral".equals(String.valueOf(type));
    }

    /**
     * True for the official object {@code {"mode":"explicit"}}, the illegal-but-seen
     * boolean {@code true}, or the string {@code "true"}/{@code "explicit"}.
     */
    public static boolean isOpenAiBreakpoint(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s) || "explicit".equalsIgnoreCase(s);
        }
        if (value instanceof Map<?, ?> map) {
            Object mode = map.get("mode");
            return mode != null && "explicit".equals(String.valueOf(mode));
        }
        return false;
    }

    /** Content-part list → canonical ephemeral marker, or null when none. */
    public static Object cacheControlFromOpenAiContent(Object content) {
        if (!(content instanceof List<?> list)) {
            return null;
        }
        Object found = null;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object cacheControl = map.get("cache_control");
            if (isEphemeral(cacheControl)) {
                found = cacheControl;
            } else if (isOpenAiBreakpoint(map.get("prompt_cache_breakpoint"))) {
                found = EPHEMERAL;
            }
        }
        return found;
    }

    /**
     * One content-part map → canonical cache marker. OpenRouter Qwen uses
     * {@code cache_control}; GPT-5.6 uses {@code prompt_cache_breakpoint}.
     */
    public static Object cacheControlFromOpenAiPart(Map<String, Object> part) {
        if (part == null) {
            return null;
        }
        Object cacheControl = part.get("cache_control");
        if (isEphemeral(cacheControl)) {
            return cacheControl;
        }
        return isOpenAiBreakpoint(part.get("prompt_cache_breakpoint")) ? EPHEMERAL : null;
    }

    /**
     * String content passes through; an array of {@code {text}} / {@code {type:text}}
     * parts concatenates. Empty / unknown → null.
     */
    public static String flattenText(Object content) {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text != null) {
                        sb.append(text);
                    }
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    /** One OpenAI Chat Completions {@code text} part carrying an explicit breakpoint. */
    public static List<Map<String, Object>> openAiTextWithBreakpoint(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        part.put("prompt_cache_breakpoint", EXPLICIT_BREAKPOINT);
        return List.of(part);
    }

    /** One OpenAI Chat Completions {@code text} part carrying Anthropic-shaped {@code cache_control}. */
    public static List<Map<String, Object>> openAiTextWithCacheControl(String text, Object cacheControl) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", text);
        part.put("cache_control", cacheControl == null ? EPHEMERAL : cacheControl);
        return List.of(part);
    }

    /**
     * Drop GPT-5.6-only cache fields from an OpenAI extras copy when the upstream
     * model would 400 them. {@code prompt_cache_key} stays (older, widely accepted).
     */
    public static void stripUnsupportedOpenAiCacheFields(Map<String, Object> extras, String model) {
        if (supportsExplicitOpenAiBreakpoints(model)) {
            return;
        }
        extras.remove("prompt_cache_options");
        extras.remove("prompt_cache_retention");
        extras.remove("prompt_cache_breakpoint");
    }

    /** Ensure explicit-only options so a placed breakpoint is not joined by an implicit one. */
    public static void ensureExplicitOptions(Map<String, Object> extras) {
        if (!extras.containsKey("prompt_cache_options")) {
            extras.put("prompt_cache_options", EXPLICIT_OPTIONS);
        }
    }
}
