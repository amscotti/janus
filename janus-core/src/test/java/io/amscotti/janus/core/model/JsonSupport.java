package io.amscotti.janus.core.model;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plain Jackson mapper for tests — no Micronaut test harness. Mirrors gateway
 * tolerance: unknown JSON properties are ignored (the gateway's Micronaut mapper is
 * configured the same way; reconciles any divergence).
 */
final class JsonSupport {

    private JsonSupport() {}

    static ObjectMapper mapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Mapper contract for the canonical model: absent/null primitives (e.g. a
                // request without "stream") deserialize to Java defaults instead of failing.
                // Codecs (+) must configure their mappers the same way.
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }
}
