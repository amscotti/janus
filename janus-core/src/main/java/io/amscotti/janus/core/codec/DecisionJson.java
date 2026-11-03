package io.amscotti.janus.core.codec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared tree-level helpers for the two decision codecs ({@link SystemOneCodec},
 * {@link OpenAiDecisionsCodec}): tolerant reads, the extras pass-through merge,
 * and the string/number map shapes both wires use. Package-private — the
 * codecs are the public surface.
 */
final class DecisionJson {

    private DecisionJson() {}

    /** The codecs' shared mapper contract (snake_case, tolerant, strict duplicates) — one factory, two codecs. */
    static ObjectMapper mapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                // Duplicate object keys would otherwise silently last-win: a proxy/WAF
                // that validates the first `model`/`questions` key would disagree with
                // Janus's routing/scope/pricing key.
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    static DecisionCodecException invalid(String message) {
        return new DecisionCodecException(DecisionCodecException.TYPE_INVALID_REQUEST, message);
    }

    static DecisionCodecException api(String message) {
        return new DecisionCodecException(DecisionCodecException.TYPE_API_ERROR, message);
    }

    static JsonNode readTree(ObjectMapper mapper, String json, String errorType, String what) {
        if (json == null || json.isBlank()) {
            throw new DecisionCodecException(errorType, what + " body must be a JSON object");
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new DecisionCodecException(errorType, what + " body must be a JSON object");
            }
            return root;
        } catch (JacksonException e) {
            throw new DecisionCodecException(errorType, "invalid " + what + ": " + e.getMessage(), e);
        }
    }

    static String write(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JacksonException e) {
            // Encode-side failures are the server's fault on every path (the
            // chat codecs' convention: api_error/500, never a client 400).
            throw api("failed to encode decision payload: " + e.getMessage());
        }
    }

    /** Any JSON value → the plain {@code Map}/{@code List}/{@code String}/number tree the canonical model carries. */
    static Object toPlain(ObjectMapper mapper, JsonNode node) {
        return toPlain(mapper, node, DecisionCodecException.TYPE_API_ERROR);
    }

    static Object toPlain(ObjectMapper mapper, JsonNode node, String errorType) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            Object plain = mapper.treeToValue(node, Object.class);
            requireFiniteTree(plain, errorType, "JSON value");
            return plain;
        } catch (JacksonException e) {
            throw new DecisionCodecException(errorType, "unconvertible JSON value: " + e.getMessage(), e);
        }
    }

    /**
     * Reject non-finite doubles in a plain JSON tree. Jackson parses {@code 1e999}
     * as Infinity and re-serializes it as the <b>string</b> {@code "Infinity"} — a
     * silent number→string type change on a proxy's wire.
     */
    static void requireFiniteTree(Object value, String errorType, String what) {
        switch (value) {
            case null -> {}
            case Double d -> {
                if (!Double.isFinite(d)) {
                    throw new DecisionCodecException(errorType, what + " must not contain non-finite numbers");
                }
            }
            case Float f -> {
                if (!Float.isFinite(f)) {
                    throw new DecisionCodecException(errorType, what + " must not contain non-finite numbers");
                }
            }
            case Map<?, ?> map -> {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    requireFiniteTree(e.getKey(), errorType, what);
                    requireFiniteTree(e.getValue(), errorType, what);
                }
            }
            case Iterable<?> items -> {
                for (Object item : items) {
                    requireFiniteTree(item, errorType, what);
                }
            }
            default -> {}
        }
    }

    /** Top-level members outside {@code mappedKeys} → the canonical extras map (plain trees). */
    static Map<String, Object> extrasOf(ObjectMapper mapper, JsonNode root, Set<String> mappedKeys, String errorType) {
        Map<String, Object> extras = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            if (!mappedKeys.contains(e.getKey())) {
                extras.put(e.getKey(), toPlain(mapper, e.getValue(), errorType));
            }
        }
        return extras;
    }

    /** Merge canonical extras back into an outbound node (mapped fields always win). */
    static void mergeExtras(ObjectMapper mapper, ObjectNode node, Map<String, Object> extras, Set<String> mappedKeys) {
        if (extras == null) {
            return;
        }
        for (Map.Entry<String, Object> e : extras.entrySet()) {
            if (!mappedKeys.contains(e.getKey())) {
                node.set(e.getKey(), mapper.valueToTree(e.getValue()));
            }
        }
    }

    static String requireModel(JsonNode root, String errorType) {
        JsonNode model = root.get("model");
        if (model == null || !model.isString() || model.asString().isBlank()) {
            throw new DecisionCodecException(errorType, "model must be a non-blank string");
        }
        String value = model.asString();
        if (value.length() > MAX_MODEL_CHARS) {
            throw new DecisionCodecException(errorType, "model must be at most " + MAX_MODEL_CHARS + " characters");
        }
        return value;
    }

    /** Model aliases are router keys, not content; cap them so a hostile value cannot reach the ledger. */
    static final int MAX_MODEL_CHARS = 256;

    static String stringField(JsonNode node, String field, String errorType, String what) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new DecisionCodecException(errorType, what + ": " + field + " must be a string");
        }
        return value.asString();
    }

    /** Any JSON value (including null) → the canonical {@code Object} tree; absent → null. */
    static Object valueOrNull(ObjectMapper mapper, JsonNode node, String errorType) {
        return node == null || node.isNull() ? null : toPlain(mapper, node, errorType);
    }

    static double numberField(JsonNode node, String field, String errorType, String what) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new DecisionCodecException(errorType, what + ": " + field + " must be a number");
        }
        return finiteDouble(value, errorType, what + ": " + field);
    }

    static Double optionalNumber(JsonNode node, String field, String errorType, String what) {
        JsonNode value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isNumber()) {
            throw new DecisionCodecException(errorType, what + ": " + field + " must be a number");
        }
        return finiteDouble(value, errorType, what + ": " + field);
    }

    /** Strict non-negative integral wire field (usage/token counts); absent → 0. */
    static long longField(JsonNode node, String field, String errorType, String what) {
        Long value = optionalLongField(node, field, errorType, what);
        return value == null ? 0L : value;
    }

    /** Strict non-negative integral wire field; absent → null (present-but-malformed → typed error). */
    static Long optionalLongField(JsonNode node, String field, String errorType, String what) {
        JsonNode value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isNumber() || !value.canConvertToExactIntegral()) {
            throw new DecisionCodecException(errorType, what + ": " + field + " must be an integer");
        }
        OptionalLong integral = value.asLongOpt();
        if (integral.isEmpty() || integral.getAsLong() < 0) {
            throw new DecisionCodecException(errorType, what + ": " + field + " must be a non-negative 64-bit integer");
        }
        return integral.getAsLong();
    }

    /** A level index: any JSON number whose value is integral (2 and 2.0 are level 2; "2" and 2.5 are not). */
    static long integralField(JsonNode node, String field, String errorType, String what) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !value.canConvertToExactIntegral()) {
            throw new DecisionCodecException(errorType, what + ": " + field + " must be an integer level index");
        }
        OptionalLong integral = value.asLongOpt();
        if (integral.isEmpty()) {
            throw new DecisionCodecException(errorType, what + ": " + field + " must be an integer level index");
        }
        return integral.getAsLong();
    }

    private static double finiteDouble(JsonNode value, String errorType, String what) {
        OptionalDouble number = value.asDoubleOpt();
        if (number.isEmpty() || !Double.isFinite(number.getAsDouble())) {
            throw new DecisionCodecException(errorType, what + " must be a finite number");
        }
        return number.getAsDouble();
    }

    /**
     * {@code {label: any-JSON|null}} object → ordered map (null descriptions preserved).
     * TypeSafe's {@code EntryType} permits structure; the systemone leg passes it through.
     */
    static Map<String, Object> objectOrNullMap(ObjectMapper mapper, JsonNode node, String errorType, String what) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            out.put(e.getKey(), valueOrNull(mapper, e.getValue(), errorType));
        }
        return out;
    }

    /** A structured criteria value folded to text for the OpenAI leg (the documented lossy translation). */
    static String textOf(ObjectMapper mapper, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw api("failed to serialize structured decision entry: " + e.getMessage());
        }
    }

    /** {@code {label: 0.42}} object → ordered probability map (finite values only). */
    static Map<String, Double> doubleMap(JsonNode node, String errorType, String what) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (!e.getValue().isNumber()) {
                throw new DecisionCodecException(
                        errorType, what + ": probability for \"" + e.getKey() + "\" must be a number");
            }
            out.put(
                    e.getKey(),
                    finiteDouble(e.getValue(), errorType, what + ": probability for \"" + e.getKey() + "\""));
        }
        return out;
    }
}
