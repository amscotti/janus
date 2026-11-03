package io.amscotti.janus.core.codec;

import io.amscotti.janus.core.model.DecisionAnswer;
import io.amscotti.janus.core.model.DecisionQuestion;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import io.amscotti.janus.core.model.Usage;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canonical ⇄ System One wire codec — the Jev-shaped decision dialect
 * ({@code POST /v1/systemone}: {@code {model, state, questions{name →
 * type/instructions/criteria}}}, plus the Ollama/Clef {@code images[]}
 * extension). This is both Janus's one client face for decisions and the
 * outbound dialect for TypeSafe, OpenRouter's System One surface, Ollama
 * (local and cloud), and Cloudflare Clef (docs/system-one.md).
 *
 * <p>Tree-based like the chat codecs' extras discipline: unmodeled top-level
 * fields ride {@code extras} untouched in both directions (Ollama's {@code
 * prompt_eval_cached_count}, {@code keep_alive}, future members). Validation
 * is structural (types, required members, primitive minimums); per-backend
 * limits (question counts, option caps, image support) are the adapters'
 * job, since they differ per upstream.
 */
public final class SystemOneCodec {

    private static final Set<String> REQUEST_KEYS = Set.of("model", "state", "images", "questions");
    private static final Set<String> RESPONSE_KEYS = Set.of("model", "answers", "usage");

    /** Capabilities the face does not implement: accept-and-forward would silently change the contract. */
    private static final Set<String> UNSUPPORTED_REQUEST_KEYS = Set.of("stream", "box");

    private final ObjectMapper mapper;

    public SystemOneCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Default mapper per the codecs' shared contract (snake_case, tolerant, single-value arrays). */
    public static SystemOneCodec create() {
        return new SystemOneCodec(DecisionJson.mapper());
    }

    // ------------------------------------------------------------------ request

    /**
     * Inline a TypeSafe multipart request's file parts into its {@code assets}
     * map: every asset's {@code part} reference is replaced by
     * {@code data_base64} + {@code format}, so the normal JSON pipeline (and the
     * upstream {@code systemone} call) sees the base64 form. The SDKs send the
     * JSON body as the {@code request} part and each image as a file part; Janus
     * accepts that shape and forwards JSON, never multipart.
     *
     * <p>Validation: every file part must be named by exactly one asset, every
     * {@code part} reference must resolve, and the image must be PNG/JPEG
     * (TypeSafe's {@code format} enum). {@code request} is reserved for the JSON
     * body and is not in {@code parts}.
     */
    public String inlineMultipartAssets(String requestJson, Map<String, byte[]> parts) throws DecisionCodecException {
        Map<String, byte[]> files = parts == null ? Map.of() : parts;
        JsonNode root = DecisionJson.readTree(
                mapper, requestJson, DecisionCodecException.TYPE_INVALID_REQUEST, "multipart request");
        JsonNode assets = root.get("assets");
        if (assets == null || assets.isNull()) {
            if (!files.isEmpty()) {
                throw DecisionJson.invalid("multipart file parts require a top-level assets map");
            }
            return requestJson;
        }
        if (!assets.isObject()) {
            throw DecisionJson.invalid("assets must be an object");
        }
        if (assets.isEmpty() && !files.isEmpty()) {
            throw DecisionJson.invalid("multipart file parts require a non-empty assets map");
        }
        Set<String> used = new HashSet<>();
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        assets.properties().forEach(entries::add);
        for (Map.Entry<String, JsonNode> entry : entries) {
            String assetName = entry.getKey();
            JsonNode asset = entry.getValue();
            if (asset == null || !asset.isObject()) {
                throw DecisionJson.invalid("asset \"" + assetName + "\" must be an object");
            }
            JsonNode type = asset.get("type");
            if (type != null && !type.isNull() && (!type.isString() || !"image".equals(type.asString()))) {
                throw DecisionJson.invalid("asset \"" + assetName + "\" type must be \"image\"");
            }
            JsonNode part = asset.get("part");
            if (part == null || part.isNull()) {
                continue;
            }
            if (!part.isString() || part.asString().isBlank()) {
                throw DecisionJson.invalid("asset \"" + assetName + "\" part must be a non-blank string");
            }
            String partName = part.asString();
            byte[] data = files.get(partName);
            if (data == null) {
                throw DecisionJson.invalid(
                        "asset \"" + assetName + "\" references missing multipart part \"" + partName + "\"");
            }
            if (!used.add(partName)) {
                throw DecisionJson.invalid("two assets reference the same multipart part \"" + partName + "\"");
            }
            String format = imageFormat(data, asset.get("format"));
            ObjectNode out = (ObjectNode) asset;
            out.remove("part");
            out.put("data_base64", Base64.getEncoder().encodeToString(data));
            out.put("format", format);
        }
        for (String partName : files.keySet()) {
            if (!used.contains(partName)) {
                throw DecisionJson.invalid("multipart part \"" + partName + "\" is not named by any asset's part");
            }
        }
        return DecisionJson.write(mapper, root);
    }

    private static String imageFormat(byte[] data, JsonNode declared) {
        if (declared != null && !declared.isNull()) {
            if (!declared.isString()) {
                throw DecisionJson.invalid("asset format must be a string");
            }
            String value = declared.asString().toLowerCase(Locale.ROOT);
            if ("png".equals(value) || "jpg".equals(value) || "jpeg".equals(value)) {
                return value;
            }
            throw DecisionJson.invalid("TypeSafe assets accept png, jpg, or jpeg (got \"" + value + "\")");
        }
        if (data.length >= 8 && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "png";
        }
        if (data.length >= 3 && data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) {
            return "jpg";
        }
        throw DecisionJson.invalid("multipart image parts must be PNG or JPEG (TypeSafe asset formats)");
    }

    public DecisionRequest decodeRequest(String json) throws DecisionCodecException {
        JsonNode root = DecisionJson.readTree(mapper, json, DecisionCodecException.TYPE_INVALID_REQUEST, "request");
        String model = DecisionJson.requireModel(root, DecisionCodecException.TYPE_INVALID_REQUEST);
        JsonNode stateNode = root.get("state");
        if (stateNode == null || stateNode.isNull()) {
            throw DecisionJson.invalid("state is required");
        }
        if (!stateNode.isString() && !stateNode.isObject() && !stateNode.isArray()) {
            throw DecisionJson.invalid("state must be a string, a JSON object, or a JSON array");
        }
        for (String key : UNSUPPORTED_REQUEST_KEYS) {
            if (root.has(key)) {
                throw DecisionJson.invalid(
                        "\"" + key + "\" is not supported on the System One face (no streaming, no box)");
            }
        }
        List<String> images = null;
        JsonNode imagesNode = root.get("images");
        if (imagesNode != null && !imagesNode.isNull()) {
            if (!imagesNode.isArray()) {
                throw DecisionJson.invalid("images must be an array of base64 strings");
            }
            images = new ArrayList<>();
            for (JsonNode image : imagesNode) {
                if (!image.isString() || image.asString().isBlank()) {
                    throw DecisionJson.invalid("images must be an array of non-blank base64 strings");
                }
                requireBase64Image(image.asString());
                images.add(image.asString());
            }
            // images:[] is the same absence as no images key — one canonical shape,
            // so the decode→encode round trip is stable.
            if (images.isEmpty()) {
                images = null;
            }
        }
        return new DecisionRequest(
                model,
                DecisionJson.toPlain(mapper, stateNode, DecisionCodecException.TYPE_INVALID_REQUEST),
                images,
                decodeQuestions(root.get("questions"), DecisionCodecException.TYPE_INVALID_REQUEST),
                DecisionJson.extrasOf(mapper, root, REQUEST_KEYS, DecisionCodecException.TYPE_INVALID_REQUEST),
                Map.of());
    }

    public String encodeRequest(DecisionRequest canonical) throws DecisionCodecException {
        validateRequest(canonical);
        ObjectNode root = mapper.createObjectNode();
        root.put("model", canonical.model());
        root.set("state", mapper.valueToTree(canonical.state()));
        if (canonical.images() != null && !canonical.images().isEmpty()) {
            ArrayNode images = root.putArray("images");
            canonical.images().forEach(images::add);
        }
        ObjectNode questions = root.putObject("questions");
        canonical.questions().forEach((name, q) -> questions.set(name, encodeQuestion(q)));
        DecisionJson.mergeExtras(mapper, root, canonical.extras(), REQUEST_KEYS);
        return DecisionJson.write(mapper, root);
    }

    private Map<String, DecisionQuestion> decodeQuestions(JsonNode node, String errorType) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new DecisionCodecException(errorType, "questions must be a non-empty object of named questions");
        }
        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String name = e.getKey();
            if (name.isBlank()) {
                throw new DecisionCodecException(errorType, "question names must be non-blank");
            }
            questions.put(name, decodeQuestion(name, e.getValue(), errorType));
        }
        return questions;
    }

    private DecisionQuestion decodeQuestion(String name, JsonNode node, String errorType) {
        String what = "question \"" + name + "\"";
        if (node == null || !node.isObject()) {
            throw new DecisionCodecException(errorType, what + " must be an object");
        }
        String type = DecisionJson.stringField(node, "type", errorType, what);
        Object instructions = DecisionJson.valueOrNull(mapper, node.get("instructions"), errorType);
        return switch (type) {
            case "choice" -> {
                JsonNode criteria = node.get("criteria");
                if (criteria == null || !criteria.isObject() || criteria.isEmpty()) {
                    throw new DecisionCodecException(
                            errorType, what + ": choice criteria must map at least one option to a description");
                }
                yield new DecisionQuestion.ChoiceQuestion(
                        instructions, DecisionJson.objectOrNullMap(mapper, criteria, errorType, what));
            }
            case "noul" -> {
                JsonNode criteria = node.get("criteria");
                Map<String, Object> sides = null;
                if (criteria != null && !criteria.isNull()) {
                    if (!criteria.isObject()) {
                        throw new DecisionCodecException(errorType, what + ": noul criteria must be an object");
                    }
                    sides = DecisionJson.objectOrNullMap(mapper, criteria, errorType, what);
                    for (String key : sides.keySet()) {
                        if (!"true".equals(key) && !"false".equals(key)) {
                            throw new DecisionCodecException(
                                    errorType,
                                    what + ": noul criteria keys must be \"true\" or \"false\" (got \"" + key
                                            + "\") — the OpenAI leg folds only those two sides into "
                                            + "the instructions, anything else would be silently dropped");
                        }
                    }
                }
                yield new DecisionQuestion.NoulQuestion(instructions, sides);
            }
            case "score" -> {
                JsonNode criteria = node.get("criteria");
                if (criteria == null || !criteria.isArray() || criteria.isEmpty()) {
                    throw new DecisionCodecException(
                            errorType, what + ": score criteria must list at least one level description");
                }
                List<Object> levels = new ArrayList<>();
                for (JsonNode level : criteria) {
                    levels.add(DecisionJson.valueOrNull(mapper, level, errorType));
                }
                yield new DecisionQuestion.ScoreQuestion(instructions, levels);
            }
            case "box" -> {
                if (instructions == null) {
                    throw new DecisionCodecException(errorType, what + ": box instructions are required");
                }
                yield new DecisionQuestion.BoxQuestion(instructions);
            }
            default ->
                throw new DecisionCodecException(
                        errorType, what + ": type must be choice, noul, or score (got \"" + type + "\")");
        };
    }

    private ObjectNode encodeQuestion(DecisionQuestion question) {
        ObjectNode node = mapper.createObjectNode();
        switch (question) {
            case DecisionQuestion.ChoiceQuestion q -> {
                node.put("type", "choice");
                putInstructions(node, q.instructions());
                ObjectNode criteria = node.putObject("criteria");
                q.criteria().forEach((k, v) -> criteria.set(k, mapper.valueToTree(v)));
            }
            case DecisionQuestion.NoulQuestion q -> {
                node.put("type", "noul");
                putInstructions(node, q.instructions());
                if (q.criteria() != null) {
                    ObjectNode criteria = node.putObject("criteria");
                    q.criteria().forEach((k, v) -> criteria.set(k, mapper.valueToTree(v)));
                }
            }
            case DecisionQuestion.ScoreQuestion q -> {
                node.put("type", "score");
                putInstructions(node, q.instructions());
                ArrayNode criteria = node.putArray("criteria");
                q.criteria().forEach(level -> criteria.add(mapper.valueToTree(level)));
            }
            case DecisionQuestion.BoxQuestion q -> {
                node.put("type", "box");
                putInstructions(node, q.instructions());
            }
        }
        return node;
    }

    private void putInstructions(ObjectNode node, Object instructions) {
        if (instructions != null) {
            node.set("instructions", mapper.valueToTree(instructions));
        }
    }

    private static void validateRequest(DecisionRequest request) {
        if (request == null) {
            throw DecisionJson.invalid("request must not be null");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw DecisionJson.invalid("model must be a non-blank string");
        }
        if (request.state() == null) {
            throw DecisionJson.invalid("state is required");
        }
        DecisionJson.requireFiniteTree(request.state(), DecisionCodecException.TYPE_INVALID_REQUEST, "state");
        DecisionJson.requireFiniteTree(request.extras(), DecisionCodecException.TYPE_INVALID_REQUEST, "extras");
        if (request.questions() == null || request.questions().isEmpty()) {
            throw DecisionJson.invalid("questions must be a non-empty object of named questions");
        }
        for (Map.Entry<String, DecisionQuestion> e : request.questions().entrySet()) {
            DecisionQuestion q = e.getValue();
            String what = "question \"" + e.getKey() + "\"";
            if (q == null) {
                throw DecisionJson.invalid(what + " must not be null");
            }
            if (e.getKey() == null || e.getKey().isBlank()) {
                throw DecisionJson.invalid("question names must be non-blank");
            }
            switch (q) {
                case DecisionQuestion.ChoiceQuestion c -> {
                    if (c.criteria() == null || c.criteria().isEmpty()) {
                        throw DecisionJson.invalid(
                                what + ": choice criteria must map at least one option to a description");
                    }
                }
                case DecisionQuestion.ScoreQuestion s -> {
                    if (s.criteria() == null || s.criteria().isEmpty()) {
                        throw DecisionJson.invalid(what + ": score criteria must list at least one level description");
                    }
                }
                case DecisionQuestion.NoulQuestion n -> {
                    if (n.criteria() != null) {
                        for (String key : n.criteria().keySet()) {
                            if (!"true".equals(key) && !"false".equals(key)) {
                                throw DecisionJson.invalid(what
                                        + ": noul criteria keys must be \"true\" or \"false\" (got \"" + key + "\")");
                            }
                        }
                    }
                }
                case DecisionQuestion.BoxQuestion b -> {
                    if (b.instructions() == null) {
                        throw DecisionJson.invalid(what + ": box instructions are required");
                    }
                }
            }
        }
        if (request.images() != null) {
            for (String image : request.images()) {
                if (image == null || image.isBlank()) {
                    throw DecisionJson.invalid("images must be non-blank base64 strings");
                }
                requireBase64Image(image);
            }
        }
    }

    // ------------------------------------------------------------------ response

    public DecisionResponse decodeResponse(String json) throws DecisionCodecException {
        return decodeResponseNode(
                DecisionJson.readTree(mapper, json, DecisionCodecException.TYPE_API_ERROR, "response"));
    }

    /**
     * Unwrap Cloudflare Workers AI's REST envelope ({@code {result, success,
     * errors, messages}}) into the model's own response object. Cloudflare's
     * {@code /ai/run/} endpoint always wraps the model output; without this the
     * generic decoder sees no top-level {@code answers} and every successful
     * Clef call becomes a 502.
     *
     * <p>Only the Cloudflare adapter calls this. A body without a {@code result}
     * member is returned unchanged (an upstream that serves the bare model shape
     * from a run URL still works); {@code success:false} is a typed upstream error.
     */
    public String unwrapCloudflareEnvelope(String json) throws DecisionCodecException {
        JsonNode root = DecisionJson.readTree(mapper, json, DecisionCodecException.TYPE_API_ERROR, "response");
        JsonNode result = root.get("result");
        if (result == null) {
            return json;
        }
        JsonNode success = root.get("success");
        if (success != null && success.isBoolean() && !success.asBoolean()) {
            throw DecisionJson.api("Cloudflare Workers AI returned success=false for the decision request");
        }
        if (result.isNull()) {
            throw DecisionJson.api("Cloudflare Workers AI response carried no result object");
        }
        if (result.isString()) {
            return result.asString();
        }
        return DecisionJson.write(mapper, result);
    }

    private DecisionResponse decodeResponseNode(JsonNode root) {
        JsonNode modelNode = root.get("model");
        String model = null;
        if (modelNode != null && !modelNode.isNull()) {
            if (!modelNode.isString() || modelNode.asString().isBlank()) {
                throw DecisionJson.api("response model must be a non-blank string when present");
            }
            model = modelNode.asString();
        }
        JsonNode answersNode = root.get("answers");
        if (answersNode == null || !answersNode.isObject()) {
            throw DecisionJson.api("response answers must be an object keyed by question name");
        }
        Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : answersNode.properties()) {
            if (e.getKey().isBlank()) {
                throw DecisionJson.api("answer names must be non-blank");
            }
            answers.put(e.getKey(), decodeAnswer(e.getKey(), e.getValue()));
        }
        return new DecisionResponse(
                model,
                answers,
                decodeUsage(root.get("usage")),
                DecisionJson.extrasOf(mapper, root, RESPONSE_KEYS, DecisionCodecException.TYPE_API_ERROR),
                Map.of());
    }

    public String encodeResponse(DecisionResponse canonical) throws DecisionCodecException {
        validateResponse(canonical);
        ObjectNode root = mapper.createObjectNode();
        if (canonical.model() != null) {
            root.put("model", canonical.model());
        }
        ObjectNode answers = root.putObject("answers");
        if (canonical.answers() != null) {
            canonical.answers().forEach((name, a) -> answers.set(name, encodeAnswer(a)));
        }
        if (canonical.usage() != null) {
            ObjectNode usage = root.putObject("usage");
            usage.put("input_tokens", canonical.usage().billedPromptTokens());
            usage.put("output_tokens", canonical.usage().completionTokens());
        }
        DecisionJson.mergeExtras(mapper, root, canonical.extras(), RESPONSE_KEYS);
        return DecisionJson.write(mapper, root);
    }

    private void validateResponse(DecisionResponse canonical) {
        if (canonical == null) {
            throw DecisionJson.api("response must not be null");
        }
        if (canonical.model() != null && canonical.model().isBlank()) {
            throw DecisionJson.api("response model must be non-blank when present");
        }
        if (canonical.answers() != null) {
            for (Map.Entry<String, DecisionAnswer> e : canonical.answers().entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                    throw DecisionJson.api("answers must map non-blank names to non-null answers");
                }
                validateAnswer(e.getValue());
            }
        }
    }

    private DecisionAnswer decodeAnswer(String name, JsonNode node) {
        String what = "answer \"" + name + "\"";
        String errorType = DecisionCodecException.TYPE_API_ERROR;
        if (node == null || !node.isObject()) {
            throw DecisionJson.api(what + " must be an object");
        }
        String type = DecisionJson.stringField(node, "type", errorType, what);
        return switch (type) {
            case "noul" ->
                new DecisionAnswer.NoulAnswer(
                        unitInterval(DecisionJson.numberField(node, "noul", errorType, what), what + ": noul"));
            case "refusal" -> new DecisionAnswer.RefusalAnswer();
            case "choice" -> {
                String choice = DecisionJson.stringField(node, "choice", errorType, what);
                JsonNode probabilities = node.get("probabilities");
                if (probabilities == null || !probabilities.isObject() || probabilities.isEmpty()) {
                    throw DecisionJson.api(what + ": probabilities must be a non-empty object");
                }
                Map<String, Double> probs = DecisionJson.doubleMap(probabilities, errorType, what);
                probs.replaceAll((key, value) -> unitInterval(value, what + ": probability for \"" + key + "\""));
                Double confidence = DecisionJson.optionalNumber(node, "confidence", errorType, what);
                yield new DecisionAnswer.ChoiceAnswer(
                        choice, probs, confidence == null ? null : unitInterval(confidence, what + ": confidence"));
            }
            case "score" -> {
                double score = DecisionJson.numberField(node, "score", errorType, what);
                JsonNode probabilities = node.get("probabilities");
                if (probabilities == null || !probabilities.isObject() || probabilities.isEmpty()) {
                    throw DecisionJson.api(what + ": probabilities must be a non-empty object");
                }
                Map<String, Double> probs = DecisionJson.doubleMap(probabilities, errorType, what);
                probs.replaceAll((key, value) -> unitInterval(value, what + ": probability for \"" + key + "\""));
                JsonNode legend = node.get("legend");
                Map<String, Object> legendMap = null;
                if (legend != null && !legend.isNull()) {
                    if (!legend.isObject()) {
                        throw DecisionJson.api(what + ": legend must be an object");
                    }
                    legendMap = DecisionJson.objectOrNullMap(mapper, legend, errorType, what);
                }
                Double confidence = DecisionJson.optionalNumber(node, "confidence", errorType, what);
                yield new DecisionAnswer.ScoreAnswer(
                        score,
                        legendMap,
                        probs,
                        confidence == null ? null : unitInterval(confidence, what + ": confidence"));
            }
            case "box" -> {
                JsonNode box = node.get("box");
                if (box == null || !box.isObject()) {
                    throw DecisionJson.api(what + ": box must be an object");
                }
                Double confidence = DecisionJson.optionalNumber(node, "confidence", errorType, what);
                yield new DecisionAnswer.BoxAnswer(
                        unitInterval(DecisionJson.numberField(box, "top", errorType, what), what + ": box.top"),
                        unitInterval(DecisionJson.numberField(box, "bottom", errorType, what), what + ": box.bottom"),
                        unitInterval(DecisionJson.numberField(box, "left", errorType, what), what + ": box.left"),
                        unitInterval(DecisionJson.numberField(box, "right", errorType, what), what + ": box.right"),
                        confidence == null ? null : unitInterval(confidence, what + ": confidence"));
            }
            default -> throw DecisionJson.api(what + ": unknown answer type \"" + type + "\"");
        };
    }

    private void validateAnswer(DecisionAnswer answer) {
        switch (answer) {
            case DecisionAnswer.NoulAnswer a -> unitInterval(a.noul(), "noul answer");
            case DecisionAnswer.RefusalAnswer ignored -> {
                // no fields
            }
            case DecisionAnswer.ChoiceAnswer a -> {
                if (a.probabilities() == null || a.probabilities().isEmpty()) {
                    throw DecisionJson.api("choice answer probabilities must be non-empty");
                }
                a.probabilities().values().forEach(v -> unitInterval(v, "choice probability"));
                if (a.confidence() != null) {
                    unitInterval(a.confidence(), "choice confidence");
                }
            }
            case DecisionAnswer.ScoreAnswer a -> {
                if (a.probabilities() == null || a.probabilities().isEmpty()) {
                    throw DecisionJson.api("score answer probabilities must be non-empty");
                }
                a.probabilities().values().forEach(v -> unitInterval(v, "score probability"));
                if (a.confidence() != null) {
                    unitInterval(a.confidence(), "score confidence");
                }
            }
            case DecisionAnswer.BoxAnswer a -> {
                unitInterval(a.top(), "box.top");
                unitInterval(a.bottom(), "box.bottom");
                unitInterval(a.left(), "box.left");
                unitInterval(a.right(), "box.right");
                if (a.confidence() != null) {
                    unitInterval(a.confidence(), "box confidence");
                }
            }
        }
    }

    private static double unitInterval(double value, String what) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw DecisionJson.api(what + " must be a number in [0, 1] (got " + value + ")");
        }
        return value;
    }

    private ObjectNode encodeAnswer(DecisionAnswer answer) {
        ObjectNode node = mapper.createObjectNode();
        switch (answer) {
            case DecisionAnswer.NoulAnswer a -> {
                node.put("type", "noul");
                node.put("noul", a.noul());
            }
            case DecisionAnswer.RefusalAnswer ignored -> node.put("type", "refusal");
            case DecisionAnswer.ChoiceAnswer a -> {
                node.put("type", "choice");
                node.put("choice", a.choice());
                ObjectNode probabilities = node.putObject("probabilities");
                if (a.probabilities() != null) {
                    a.probabilities().forEach(probabilities::put);
                }
                if (a.confidence() != null) {
                    node.put("confidence", a.confidence());
                }
            }
            case DecisionAnswer.ScoreAnswer a -> {
                node.put("type", "score");
                node.put("score", a.score());
                if (a.legend() != null) {
                    ObjectNode legend = node.putObject("legend");
                    a.legend().forEach((k, v) -> legend.set(k, mapper.valueToTree(v)));
                }
                ObjectNode probabilities = node.putObject("probabilities");
                if (a.probabilities() != null) {
                    a.probabilities().forEach(probabilities::put);
                }
                if (a.confidence() != null) {
                    node.put("confidence", a.confidence());
                }
            }
            case DecisionAnswer.BoxAnswer a -> {
                node.put("type", "box");
                ObjectNode box = node.putObject("box");
                box.put("top", a.top());
                box.put("bottom", a.bottom());
                box.put("left", a.left());
                box.put("right", a.right());
                if (a.confidence() != null) {
                    node.put("confidence", a.confidence());
                }
            }
        }
        return node;
    }

    private static Usage decodeUsage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        long input = DecisionJson.longField(node, "input_tokens", DecisionCodecException.TYPE_API_ERROR, "usage");
        long output = DecisionJson.longField(node, "output_tokens", DecisionCodecException.TYPE_API_ERROR, "usage");
        long total;
        try {
            total = Math.addExact(input, output);
        } catch (ArithmeticException e) {
            throw DecisionJson.api("usage token counts overflow the 64-bit range");
        }
        return new Usage(input, output, total);
    }

    /**
     * The face accepts raw base64 images only (docs/system-one.md): forwarding an
     * arbitrary URL/path to a self-hosted upstream would turn the gateway into an
     * SSRF/LFI pivot, and a non-base64 string is not an image at all.
     */
    private static void requireBase64Image(String image) {
        if (image.startsWith("data:")
                || image.contains("://")
                || image.startsWith("file:")
                || image.length() % 4 != 0) {
            throw DecisionJson.invalid("images must be raw base64 (PNG/JPEG/WebP), not URLs or data URLs");
        }
        try {
            Base64.getDecoder().decode(image);
        } catch (IllegalArgumentException e) {
            throw DecisionJson.invalid("images must be raw base64 (PNG/JPEG/WebP)");
        }
    }
}
