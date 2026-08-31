package io.amscotti.janus.core.codec;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plain Jackson mapper for tests — no Micronaut test harness. Implements the codec's
 * mapper contract exactly (snake_case naming, tolerant decode, single-value-to-array
 * coercion) so tests exercise the same wire behavior the gateway's mapper must provide
 *.
 */
final class JsonSupport {

    private JsonSupport() {}

    static ObjectMapper mapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .build();
    }

    /**
     * Order-insensitive deep JSON-tree equality: object fields are compared by key
     * (key order ignored — decode→re-encode reorders per DTO component order), arrays
     * element-wise in order (a reordered {@code choices} array would be a real bug), and
     * primitives by value.
     */
    static boolean treeEquals(JsonNode left, JsonNode right) {
        // JSON null and absent are equivalent (the codec's @JsonInclude(NON_NULL) omits
        // explicit nulls on re-encode — a wire-shape decision, not a value loss).
        if (left == null || right == null || left.isNull() || right.isNull()) {
            return (left == null || left.isNull()) && (right == null || right.isNull());
        }
        if (left.isObject() && right.isObject()) {
            // Union of keys: a key absent on one side must be null-equivalent on the
            // other (the codec's @JsonInclude(NON_NULL) omits explicit nulls — e.g.
            // "finish_reason":null on non-terminal chunks — on re-encode).
            java.util.Set<String> keys = new java.util.HashSet<>();
            left.properties().forEach(e -> keys.add(e.getKey()));
            right.properties().forEach(e -> keys.add(e.getKey()));
            for (String key : keys) {
                if (!treeEquals(left.get(key), right.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!treeEquals(left.get(i), right.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }
}
