package io.amscotti.janus.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical System One decision request — the normalized form the decision
 * codecs translate to/from (docs/system-one.md).
 *
 * <p>{@code state} is the evidence under evaluation: a {@link String}, or a
 * JSON object/array carried as {@code Map}/{@code List} trees (the Jev wire
 * allows all three). It is user content with the same privacy contract as
 * chat prompts: never in {@link #toString()} unless content logging is on,
 * never in metrics.
 *
 * <p>{@code images} are raw base64 strings (PNG/JPEG/WebP) shared by all
 * questions — the Ollama/Clef extension to the System One shape. Null means
 * no images.
 *
 * <p>{@code extras} is the opaque pass-through map (every unmodeled wire
 * field rides here untouched, merged back on encode); {@code meta} is
 * gateway-internal context, never serialized — the {@link ChatRequest}
 * contracts, verbatim.
 */
public record DecisionRequest(
        String model,
        Object state,
        List<String> images,
        Map<String, DecisionQuestion> questions,
        Map<String, Object> extras,
        @JsonIgnore Map<String, Object> meta) {

    /** Gateway-internal meta key: the client sent {@code TypeSafe-Danger-Zone-Images: true}. */
    public static final String META_DANGER_ZONE_IMAGES = "danger_zone_images";

    /** Gateway-internal meta key: the client sent {@code TypeSafe-Danger-Zone-Long-Context: true}. */
    public static final String META_DANGER_ZONE_LONG_CONTEXT = "danger_zone_long_context";

    public DecisionRequest {
        images = images == null ? null : Collections.unmodifiableList(new ArrayList<>(images));
        questions = questions == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(questions));
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
        meta = meta == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(meta));
    }

    /** Copy naming a different (upstream) model — the gateway's upstream-model remap seam. */
    public DecisionRequest withModel(String model) {
        return new DecisionRequest(model, state, images, questions, extras, meta);
    }

    /** Copy carrying extra gateway-internal context (e.g. danger-zone header flags). */
    public DecisionRequest withMeta(Map<String, Object> additional) {
        if (additional == null || additional.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new HashMap<>(meta);
        merged.putAll(additional);
        return new DecisionRequest(model, state, images, questions, extras, merged);
    }

    /**
     * Whether the request carries image evidence in <b>any</b> dialect: the
     * Ollama/Clef {@code images[]} extension, TypeSafe's top-level {@code assets}
     * map, or a Box question (which requires an image). The router uses this to
     * pick {@code vision = true} backends; the dialogs differ, the capability
     * does not.
     */
    @JsonIgnore
    public boolean hasImageEvidence() {
        if (images != null && !images.isEmpty()) {
            return true;
        }
        if (extras.containsKey("assets")) {
            return true;
        }
        if (questions != null) {
            for (DecisionQuestion question : questions.values()) {
                if (question instanceof DecisionQuestion.BoxQuestion) {
                    return true;
                }
            }
        }
        // Cloudflare/OpenRouter decision models accept images as state-array
        // parts ({"type":"image_url","image_url":{"url":"data:..."}}); TypeSafe
        // structured content can also nest image references. Detect them so a
        // mixed pool never sends image evidence to a text-only backend.
        return containsImagePart(state);
    }

    private static boolean containsImagePart(Object value) {
        return switch (value) {
            case Map<?, ?> map -> {
                Object type = map.get("type");
                if ("image_url".equals(type) || "image".equals(type)) {
                    yield true;
                }
                Object imageUrl = map.get("image_url");
                if (imageUrl instanceof String || imageUrl instanceof Map<?, ?>) {
                    yield true;
                }
                for (Object nested : map.values()) {
                    if (containsImagePart(nested)) {
                        yield true;
                    }
                }
                yield false;
            }
            case Iterable<?> items -> {
                for (Object item : items) {
                    if (containsImagePart(item)) {
                        yield true;
                    }
                }
                yield false;
            }
            default -> false;
        };
    }

    /** State, images, and question text are user content: structure only, unless content logging is on. */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "DecisionRequest[model=" + model + ", state=" + state + ", images=" + images + ", questions="
                    + questions + ", extras=" + extras + "]";
        }
        return "DecisionRequest[model=" + model + ", questions=" + (questions == null ? 0 : questions.size())
                + ", images=" + (images == null ? 0 : images.size()) + ", extras=" + extras.size()
                + ", state/questions content not logged]";
    }
}
