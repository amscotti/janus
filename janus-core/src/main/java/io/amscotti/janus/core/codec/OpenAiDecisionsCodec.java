package io.amscotti.janus.core.codec;

import io.amscotti.janus.core.model.DecisionAnswer;
import io.amscotti.janus.core.model.DecisionQuestion;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import io.amscotti.janus.core.model.Usage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canonical ⇄ OpenAI Decisions wire codec — the outbound translation leg to
 * OpenAI's {@code POST /v1/decisions} (docs/system-one.md). Same concepts,
 * renamed: {@code state}→{@code input}, the questions map→an array carrying
 * {@code name}, {@code noul}→{@code predicate}, choice criteria
 * map→{@code choices[{value, description}]}, score criteria list→{@code
 * levels[{label, description}]}, answers map→{@code answers[]} with
 * per-option probability arrays, plus per-question {@code refusal} answers
 * System One lacks.
 *
 * <p>Documented lossy folds (never silent drops at the design level):
 *
 * <ul>
 * <li>{@code noul} true/false criteria have no OpenAI home — they are folded
 * into the predicate instructions ({@code "True when: … / False when: …"}).
 * <li>Score level descriptions become the OpenAI level {@code label}
 * (OpenAI's richer level descriptions decode back to the plain strings).
 * <li>Boolean choice values stringify on the round trip — the canonical's
 * {@code Map<String,String>} criteria cannot carry a boolean {@code value}
 * shape, so {@code true}/{@code false} re-encode as the strings {@code
 * "true"}/{@code "false"}.
 * <li>{@code input_json} caps structured state at 256 KiB and questions at
 * 400 — over-limit requests are typed client errors before dispatch.
 * </ul>
 */
public final class OpenAiDecisionsCodec {

    private static final Set<String> REQUEST_KEYS = Set.of("model", "input", "questions");
    private static final Set<String> RESPONSE_KEYS = Set.of("model", "answers", "usage");

    /** OpenAI's documented per-request question cap. */
    static final int MAX_QUESTIONS = 400;

    /** OpenAI's documented choice-option cap. */
    static final int MAX_CHOICES = 255;

    /** OpenAI's documented input_json compact-JSON byte cap. */
    static final int MAX_INPUT_JSON_BYTES = 256 * 1024;

    /** OpenAI's documented plain-string input cap. */
    static final int MAX_INPUT_STRING_BYTES = 10 * 1024 * 1024;

    /** OpenAI's documented input_json container/value caps. */
    static final int MAX_INPUT_JSON_DEPTH = 32;

    static final int MAX_INPUT_JSON_VALUES = 10_000;

    private final ObjectMapper mapper;

    public OpenAiDecisionsCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Default mapper per the codecs' shared contract (snake_case, tolerant, single-value arrays). */
    public static OpenAiDecisionsCodec create() {
        return new OpenAiDecisionsCodec(DecisionJson.mapper());
    }

    // ------------------------------------------------------------------ request

    public String encodeRequest(DecisionRequest canonical) throws DecisionCodecException {
        validateRequest(canonical);
        ObjectNode root = mapper.createObjectNode();
        root.put("model", canonical.model());
        root.set("input", encodeInput(canonical));
        ArrayNode questions = root.putArray("questions");
        canonical.questions().forEach((name, q) -> questions.add(encodeQuestion(name, q)));
        DecisionJson.mergeExtras(mapper, root, canonical.extras(), REQUEST_KEYS);
        return DecisionJson.write(mapper, root);
    }

    public DecisionRequest decodeRequest(String json) throws DecisionCodecException {
        JsonNode root = DecisionJson.readTree(mapper, json, DecisionCodecException.TYPE_INVALID_REQUEST, "request");
        String model = DecisionJson.requireModel(root, DecisionCodecException.TYPE_INVALID_REQUEST);
        List<String> images = new ArrayList<>();
        Object state = decodeInput(root.get("input"), images);
        JsonNode questionsNode = root.get("questions");
        if (questionsNode == null || !questionsNode.isArray() || questionsNode.isEmpty()) {
            throw DecisionJson.invalid("questions must be a non-empty array");
        }
        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
        for (JsonNode q : questionsNode) {
            if (!q.isObject()) {
                throw DecisionJson.invalid("each question must be an object");
            }
            String name = DecisionJson.stringField(q, "name", DecisionCodecException.TYPE_INVALID_REQUEST, "question");
            if (name.isBlank()) {
                throw DecisionJson.invalid("question names must be non-blank");
            }
            if (questions.put(name, decodeQuestion(name, q)) != null) {
                throw DecisionJson.invalid("duplicate question name \"" + name + "\"");
            }
        }
        if (questions.size() > MAX_QUESTIONS) {
            throw DecisionJson.invalid("OpenAI Decisions accepts at most " + MAX_QUESTIONS + " questions per request");
        }
        return new DecisionRequest(
                model,
                state,
                images.isEmpty() ? null : images,
                questions,
                DecisionJson.extrasOf(mapper, root, REQUEST_KEYS, DecisionCodecException.TYPE_INVALID_REQUEST),
                Map.of());
    }

    private JsonNode encodeInput(DecisionRequest canonical) {
        boolean hasImages = canonical.images() != null && !canonical.images().isEmpty();
        Object state = canonical.state();
        if (state instanceof String text && !hasImages) {
            requireInputStringSize(text);
            return mapper.valueToTree(text);
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        if (state instanceof String text) {
            requireInputStringSize(text);
            ObjectNode part = content.addObject();
            part.put("type", "input_text");
            part.put("text", text);
        } else if (state instanceof List<?> list && !isObjectList(list)) {
            // A System One array state may be an array of text values; OpenAI's
            // input_json accepts an object or an array of objects only. Join the
            // scalar array into one text part instead of emitting an invalid
            // input_json array.
            StringBuilder joined = new StringBuilder();
            for (Object value : list) {
                if (joined.length() > 0) {
                    joined.append('\n');
                }
                joined.append(value instanceof String s ? s : DecisionJson.textOf(mapper, value));
            }
            requireInputStringSize(joined.toString());
            ObjectNode part = content.addObject();
            part.put("type", "input_text");
            part.put("text", joined.toString());
        } else if (state instanceof Map<?, ?> || state instanceof List<?>) {
            JsonNode data = mapper.valueToTree(state);
            validateInputJson(data);
            ObjectNode part = content.addObject();
            part.put("type", "input_json");
            part.set("data", data);
        } else {
            throw DecisionJson.invalid("state must be a string, a JSON object, or a JSON array");
        }
        if (hasImages) {
            for (String image : canonical.images()) {
                ObjectNode part = content.addObject();
                part.put("type", "input_image");
                part.put("image_url", toImageUrl(image));
            }
        }
        ArrayNode input = mapper.createArrayNode();
        input.add(message);
        return input;
    }

    private static void requireInputStringSize(String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_INPUT_STRING_BYTES) {
            throw DecisionJson.invalid("text state exceeds the OpenAI Decisions input limit (" + bytes + " > "
                    + MAX_INPUT_STRING_BYTES + " bytes)");
        }
    }

    private static boolean isObjectList(List<?> list) {
        if (list.isEmpty()) {
            return true;
        }
        for (Object value : list) {
            if (!(value instanceof Map<?, ?>)) {
                return false;
            }
        }
        return true;
    }

    private void validateInputJson(JsonNode data) {
        int bytes = DecisionJson.write(mapper, data).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_INPUT_JSON_BYTES) {
            throw DecisionJson.invalid("structured state exceeds the OpenAI Decisions input_json limit (" + bytes
                    + " > " + MAX_INPUT_JSON_BYTES + " bytes)");
        }
        int[] counts = new int[2]; // values, max depth
        countInputJson(data, 1, counts);
        if (counts[1] > MAX_INPUT_JSON_DEPTH) {
            throw DecisionJson.invalid("structured state exceeds the OpenAI Decisions input_json depth limit ("
                    + counts[1] + " > " + MAX_INPUT_JSON_DEPTH + ")");
        }
        if (counts[0] > MAX_INPUT_JSON_VALUES) {
            throw DecisionJson.invalid("structured state exceeds the OpenAI Decisions input_json value limit ("
                    + counts[0] + " > " + MAX_INPUT_JSON_VALUES + ")");
        }
    }

    private static void countInputJson(JsonNode node, int depth, int[] counts) {
        counts[0]++;
        counts[1] = Math.max(counts[1], depth);
        if (node.isObject()) {
            node.properties().forEach(e -> countInputJson(e.getValue(), depth + 1, counts));
        } else if (node.isArray()) {
            node.forEach(child -> countInputJson(child, depth + 1, counts));
        }
    }

    private Object decodeInput(JsonNode input, List<String> imagesOut) {
        if (input == null || input.isNull()) {
            throw DecisionJson.invalid("input is required");
        }
        if (input.isString()) {
            return input.asString();
        }
        if (!input.isArray()) {
            throw DecisionJson.invalid("input must be a string or an array of messages");
        }
        Object state = null;
        StringBuilder text = new StringBuilder();
        boolean sawText = false;
        for (JsonNode message : input) {
            JsonNode content = message.get("content");
            if (content == null) {
                continue;
            }
            if (content.isString()) {
                appendText(text, content.asString());
                sawText = true;
                continue;
            }
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                String type = part.path("type").asString("");
                switch (type) {
                    case "input_json" ->
                        state = DecisionJson.toPlain(
                                mapper, part.get("data"), DecisionCodecException.TYPE_INVALID_REQUEST);
                    case "input_text", "output_text" -> {
                        appendText(text, part.path("text").asString(""));
                        sawText = true;
                    }
                    case "input_image" -> {
                        JsonNode url = part.get("image_url");
                        if (url == null || !url.isString() || url.asString().isBlank()) {
                            throw DecisionJson.invalid("input_image must carry a non-blank string image_url");
                        }
                        imagesOut.add(stripDataUrl(url.asString()));
                    }
                    default -> {
                        // Tool calls/outputs and other message items are upstream
                        // conversation evidence the canonical model does not carry.
                    }
                }
            }
        }
        if (state != null) {
            return state;
        }
        if (!sawText && imagesOut.isEmpty()) {
            // Silently returning "" would dispatch a decision whose evidence
            // never made it into the request — the client must hear about it.
            throw DecisionJson.invalid("input carried no text or JSON evidence");
        }
        return text.toString();
    }

    private static void appendText(StringBuilder text, String chunk) {
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(chunk);
    }

    private ObjectNode encodeQuestion(String name, DecisionQuestion question) {
        ObjectNode node = mapper.createObjectNode();
        switch (question) {
            case DecisionQuestion.NoulQuestion q -> {
                node.put("type", "predicate");
                node.put("name", name);
                if (q.criteria() == null || q.criteria().isEmpty()) {
                    putInstructions(node, q.instructions());
                } else {
                    String folded = foldNoulCriteria(q);
                    if (folded != null) {
                        node.put("instructions", folded);
                    }
                }
            }
            case DecisionQuestion.ChoiceQuestion q -> {
                node.put("type", "choice");
                node.put("name", name);
                putInstructions(node, q.instructions());
                ArrayNode choices = node.putArray("choices");
                q.criteria().forEach((value, description) -> {
                    ObjectNode choice = choices.addObject();
                    choice.put("value", value);
                    String text = DecisionJson.textOf(mapper, description);
                    if (text != null) {
                        choice.put("description", text);
                    }
                });
            }
            case DecisionQuestion.ScoreQuestion q -> {
                node.put("type", "score");
                node.put("name", name);
                putInstructions(node, q.instructions());
                ArrayNode levels = node.putArray("levels");
                q.criteria().forEach(label -> levels.addObject().put("label", DecisionJson.textOf(mapper, label)));
            }
            case DecisionQuestion.BoxQuestion ignored ->
                throw DecisionJson.invalid("box questions are TypeSafe-only; the OpenAI Decisions leg cannot"
                        + " represent them (route this request to a systemone backend)");
        }
        return node;
    }

    private void putInstructions(ObjectNode node, Object instructions) {
        if (instructions instanceof List<?> parts
                && !parts.isEmpty()
                && parts.stream().allMatch(this::isOpenAiPart)) {
            // OpenAI's per-question image instructions: a nonempty array of
            // input_text/input_image parts. Pass it through instead of folding the
            // structured value to JSON text (the client already chose this dialect).
            ArrayNode array = node.putArray("instructions");
            for (Object part : parts) {
                Map<?, ?> map = (Map<?, ?>) part;
                ObjectNode out = array.addObject();
                String type = (String) map.get("type");
                out.put("type", type);
                if ("input_text".equals(type)) {
                    out.put("text", (String) map.get("text"));
                } else {
                    out.put("image_url", toImageUrl((String) map.get("image_url")));
                }
            }
            return;
        }
        String text = DecisionJson.textOf(mapper, instructions);
        if (text != null) {
            node.put("instructions", text);
        }
    }

    private boolean isOpenAiPart(Object part) {
        if (!(part instanceof Map<?, ?> map)) {
            return false;
        }
        Object type = map.get("type");
        if ("input_text".equals(type)) {
            return map.get("text") instanceof String;
        }
        if ("input_image".equals(type)) {
            return map.get("image_url") instanceof String;
        }
        return false;
    }

    private DecisionQuestion decodeQuestion(String name, JsonNode node) {
        String what = "question \"" + name + "\"";
        String errorType = DecisionCodecException.TYPE_INVALID_REQUEST;
        String type = DecisionJson.stringField(node, "type", errorType, what);
        String instructions = instructionsText(node.get("instructions"), what);
        return switch (type) {
            case "predicate" -> new DecisionQuestion.NoulQuestion(instructions, null);
            case "choice" -> {
                JsonNode choices = node.get("choices");
                if (choices == null || !choices.isArray() || choices.size() < 2) {
                    throw DecisionJson.invalid(what + ": choices must list at least two options");
                }
                Map<String, Object> criteria = new LinkedHashMap<>();
                for (JsonNode choice : choices) {
                    JsonNode value = choice.get("value");
                    if (value == null || !(value.isString() || value.isBoolean())) {
                        throw DecisionJson.invalid(what + ": choice values must be strings or booleans");
                    }
                    String key = value.isString() ? value.asString() : Boolean.toString(value.asBoolean());
                    if (criteria.containsKey(key)) {
                        throw DecisionJson.invalid(what + ": duplicate choice value \"" + key + "\"");
                    }
                    JsonNode description = choice.get("description");
                    criteria.put(key, description != null && description.isString() ? description.asString() : null);
                }
                if (criteria.size() < 2) {
                    throw DecisionJson.invalid(what + ": choices must resolve to at least two distinct values");
                }
                yield new DecisionQuestion.ChoiceQuestion(instructions, criteria);
            }
            case "score" -> {
                JsonNode levels = node.get("levels");
                if (levels == null || !levels.isArray() || levels.size() < 2) {
                    throw DecisionJson.invalid(what + ": levels must list at least two levels");
                }
                List<Object> criteria = new ArrayList<>();
                for (JsonNode level : levels) {
                    JsonNode description = level.get("description");
                    String label = DecisionJson.stringField(level, "label", errorType, what);
                    criteria.add(
                            description != null
                                            && description.isString()
                                            && !description.asString().isBlank()
                                    ? description.asString()
                                    : label);
                }
                yield new DecisionQuestion.ScoreQuestion(instructions, criteria);
            }
            default ->
                throw DecisionJson.invalid(what + ": type must be predicate, choice, or score (got \"" + type + "\")");
        };
    }

    /** The noul→predicate criteria fold: side descriptions ride the instructions text (the documented loss). */
    private String foldNoulCriteria(DecisionQuestion.NoulQuestion question) {
        String base = DecisionJson.textOf(mapper, question.instructions());
        if (question.criteria() == null || question.criteria().isEmpty()) {
            return base;
        }
        StringBuilder folded = new StringBuilder(base == null ? "" : base);
        Object whenTrue = question.criteria().get("true");
        Object whenFalse = question.criteria().get("false");
        if (whenTrue != null) {
            folded.append("\nTrue when: ").append(DecisionJson.textOf(mapper, whenTrue));
        }
        if (whenFalse != null) {
            folded.append("\nFalse when: ").append(DecisionJson.textOf(mapper, whenFalse));
        }
        return folded.toString();
    }

    private String instructionsText(JsonNode instructions, String what) {
        if (instructions == null || instructions.isNull()) {
            return null;
        }
        if (instructions.isString()) {
            return instructions.asString();
        }
        if (instructions.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : instructions) {
                if (!"input_text".equals(part.path("type").asString(""))) {
                    throw DecisionJson.invalid(what + ": per-question image instructions are not supported");
                }
                text.append(part.path("text").asString(""));
            }
            return text.toString();
        }
        if (instructions.isObject()) {
            return DecisionJson.textOf(
                    mapper, DecisionJson.toPlain(mapper, instructions, DecisionCodecException.TYPE_INVALID_REQUEST));
        }
        throw DecisionJson.invalid(what + ": instructions must be a string, object, array, or null");
    }

    private static void validateRequest(DecisionRequest request) {
        if (request == null || request.model() == null || request.model().isBlank() || request.state() == null) {
            throw DecisionJson.invalid("model and state are required");
        }
        DecisionJson.requireFiniteTree(request.state(), DecisionCodecException.TYPE_INVALID_REQUEST, "state");
        DecisionJson.requireFiniteTree(request.extras(), DecisionCodecException.TYPE_INVALID_REQUEST, "extras");
        if (request.questions() == null || request.questions().isEmpty()) {
            throw DecisionJson.invalid("questions must be non-empty");
        }
        if (request.extras().containsKey("assets")) {
            throw DecisionJson.invalid("assets are TypeSafe-only; the OpenAI Decisions leg cannot represent them"
                    + " (route this request to a systemone backend)");
        }
        if (request.questions().size() > MAX_QUESTIONS) {
            throw DecisionJson.invalid("OpenAI Decisions accepts at most " + MAX_QUESTIONS
                    + " questions per request (got " + request.questions().size() + ")");
        }
        for (Map.Entry<String, DecisionQuestion> e : request.questions().entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                throw DecisionJson.invalid("questions must map non-blank names to non-null questions");
            }
            DecisionQuestion q = e.getValue();
            switch (q) {
                case DecisionQuestion.ChoiceQuestion c -> {
                    if (c.criteria() == null
                            || c.criteria().size() < 2
                            || c.criteria().size() > MAX_CHOICES) {
                        throw DecisionJson.invalid("OpenAI Decisions choice options must number 2–" + MAX_CHOICES);
                    }
                }
                case DecisionQuestion.ScoreQuestion s -> {
                    if (s.criteria() == null
                            || s.criteria().size() < 2
                            || s.criteria().size() > 10) {
                        throw DecisionJson.invalid("OpenAI Decisions score levels must number 2–10");
                    }
                }
                case DecisionQuestion.NoulQuestion n -> {
                    if (n.criteria() != null) {
                        for (String key : n.criteria().keySet()) {
                            if (!"true".equals(key) && !"false".equals(key)) {
                                throw DecisionJson.invalid("noul criteria keys must be \"true\" or \"false\" (got \""
                                        + key + "\") — only those two sides have an OpenAI fold");
                            }
                        }
                    }
                }
                case DecisionQuestion.BoxQuestion ignored ->
                    throw DecisionJson.invalid(
                            "box questions are TypeSafe-only; the OpenAI Decisions leg cannot" + " represent them");
            }
        }
        if (request.images() != null) {
            for (String image : request.images()) {
                if (image == null || image.isBlank()) {
                    throw DecisionJson.invalid("images must be non-blank base64 strings");
                }
            }
        }
    }

    // ------------------------------------------------------------------ response

    public DecisionResponse decodeResponse(String json) throws DecisionCodecException {
        JsonNode root = DecisionJson.readTree(mapper, json, DecisionCodecException.TYPE_API_ERROR, "response");
        JsonNode answersNode = root.get("answers");
        if (answersNode == null || !answersNode.isArray()) {
            throw DecisionJson.api("response answers must be an array");
        }
        Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
        for (JsonNode answer : answersNode) {
            JsonNode nameNode = answer.get("name");
            if (nameNode == null || !nameNode.isString() || nameNode.asString().isBlank()) {
                throw DecisionJson.api("every answer must carry its question name");
            }
            if (answers.put(nameNode.asString(), decodeAnswer(nameNode.asString(), answer)) != null) {
                throw DecisionJson.api(
                        "duplicate answer name \"" + nameNode.asString() + "\" — one answer per question");
            }
        }
        JsonNode modelNode = root.get("model");
        String model = modelNode != null && modelNode.isString() ? modelNode.asString() : null;
        return new DecisionResponse(
                model,
                answers,
                decodeUsage(root.get("usage")),
                DecisionJson.extrasOf(mapper, root, RESPONSE_KEYS, DecisionCodecException.TYPE_API_ERROR),
                Map.of());
    }

    public String encodeResponse(DecisionResponse canonical) throws DecisionCodecException {
        if (canonical == null) {
            throw DecisionJson.api("response must not be null");
        }
        ObjectNode root = mapper.createObjectNode();
        ArrayNode answers = root.putArray("answers");
        if (canonical.answers() != null) {
            canonical.answers().forEach((name, a) -> answers.add(encodeAnswer(name, a)));
        }
        if (canonical.model() != null) {
            root.put("model", canonical.model());
        }
        if (canonical.usage() != null) {
            ObjectNode usage = root.putObject("usage");
            Usage u = canonical.usage();
            usage.put("input_tokens", u.billedPromptTokens());
            ObjectNode details = usage.putObject("input_tokens_details");
            details.put("cached_tokens", u.cacheReadInputTokens() == null ? 0 : u.cacheReadInputTokens());
            details.put("cache_write_tokens", u.cacheCreationInputTokens() == null ? 0 : u.cacheCreationInputTokens());
            usage.put("output_tokens", u.completionTokens());
            ObjectNode outDetails = usage.putObject("output_tokens_details");
            outDetails.put("reasoning_tokens", u.reasoningTokens() == null ? 0 : u.reasoningTokens());
            usage.put("total_tokens", u.totalTokens());
        }
        DecisionJson.mergeExtras(mapper, root, canonical.extras(), RESPONSE_KEYS);
        return DecisionJson.write(mapper, root);
    }

    private DecisionAnswer decodeAnswer(String name, JsonNode node) {
        String what = "answer \"" + name + "\"";
        String errorType = DecisionCodecException.TYPE_API_ERROR;
        String type = DecisionJson.stringField(node, "type", errorType, what);
        return switch (type) {
            case "refusal" -> new DecisionAnswer.RefusalAnswer();
            case "predicate" ->
                new DecisionAnswer.NoulAnswer(unitInterval(
                        DecisionJson.numberField(node, "probability", errorType, what), what + ": probability"));
            case "choice" -> {
                JsonNode choiceNode = node.get("choice");
                if (choiceNode == null || !(choiceNode.isString() || choiceNode.isBoolean())) {
                    throw DecisionJson.api(what + ": choice must be a string or boolean");
                }
                String choice =
                        choiceNode.isString() ? choiceNode.asString() : Boolean.toString(choiceNode.asBoolean());
                Map<String, Double> probabilities = new LinkedHashMap<>();
                JsonNode probs = node.get("probabilities");
                if (probs == null || !probs.isArray() || probs.isEmpty()) {
                    throw DecisionJson.api(what + ": probabilities must be a non-empty array");
                }
                for (JsonNode p : probs) {
                    JsonNode value = p.get("value");
                    if (value == null || !(value.isString() || value.isBoolean())) {
                        throw DecisionJson.api(what + ": probability value must be a string or boolean");
                    }
                    String key = value.isString() ? value.asString() : Boolean.toString(value.asBoolean());
                    if (probabilities.put(
                                    key,
                                    unitInterval(
                                            DecisionJson.numberField(p, "probability", errorType, what),
                                            what + ": probability"))
                            != null) {
                        throw DecisionJson.api(what + ": duplicate probability value \"" + key + "\"");
                    }
                }
                Double confidence = DecisionJson.optionalNumber(node, "confidence", errorType, what);
                yield new DecisionAnswer.ChoiceAnswer(
                        choice,
                        probabilities,
                        confidence == null ? null : unitInterval(confidence, what + ": confidence"));
            }
            case "score" -> {
                Map<String, Object> legend = new LinkedHashMap<>();
                Map<String, Double> probabilities = new LinkedHashMap<>();
                double weighted = 0;
                JsonNode probs = node.get("probabilities");
                if (probs == null || !probs.isArray() || probs.isEmpty()) {
                    throw DecisionJson.api(what + ": probabilities must be a non-empty array");
                }
                for (JsonNode p : probs) {
                    long index = DecisionJson.integralField(p, "value", errorType, what);
                    double probability = unitInterval(
                            DecisionJson.numberField(p, "probability", errorType, what), what + ": probability");
                    String key = Long.toString(index);
                    JsonNode label = p.get("label");
                    legend.put(key, label != null && label.isString() ? label.asString() : null);
                    probabilities.put(key, probability);
                    weighted += index * probability;
                }
                Double score = DecisionJson.optionalNumber(node, "score", errorType, what);
                Double confidence = DecisionJson.optionalNumber(node, "confidence", errorType, what);
                yield new DecisionAnswer.ScoreAnswer(
                        score == null ? weighted : score,
                        legend,
                        probabilities,
                        confidence == null ? null : unitInterval(confidence, what + ": confidence"));
            }
            default -> throw DecisionJson.api(what + ": unknown answer type \"" + type + "\"");
        };
    }

    private static double unitInterval(double value, String what) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw DecisionJson.api(what + " must be a number in [0, 1] (got " + value + ")");
        }
        return value;
    }

    private ObjectNode encodeAnswer(String name, DecisionAnswer answer) {
        ObjectNode node = mapper.createObjectNode();
        switch (answer) {
            case DecisionAnswer.NoulAnswer a -> {
                node.put("type", "predicate");
                node.put("name", name);
                node.put("probability", a.noul());
            }
            case DecisionAnswer.RefusalAnswer ignored -> {
                node.put("type", "refusal");
                node.put("name", name);
            }
            case DecisionAnswer.ChoiceAnswer a -> {
                node.put("type", "choice");
                node.put("name", name);
                node.put("choice", a.choice());
                if (a.confidence() != null) {
                    node.put("confidence", a.confidence());
                }
                ArrayNode probabilities = node.putArray("probabilities");
                if (a.probabilities() != null) {
                    a.probabilities().forEach((value, probability) -> {
                        ObjectNode p = probabilities.addObject();
                        p.put("value", value);
                        p.put("probability", probability);
                    });
                }
            }
            case DecisionAnswer.ScoreAnswer a -> {
                node.put("type", "score");
                node.put("name", name);
                node.put("score", a.score());
                if (a.confidence() != null) {
                    node.put("confidence", a.confidence());
                }
                ArrayNode probabilities = node.putArray("probabilities");
                if (a.probabilities() != null) {
                    a.probabilities().forEach((index, probability) -> {
                        ObjectNode p = probabilities.addObject();
                        Object label = a.legend() == null ? null : a.legend().get(index);
                        String text = DecisionJson.textOf(mapper, label);
                        p.put("label", text == null ? "" : text);
                        p.put("value", levelIndex(index));
                        p.put("probability", probability);
                    });
                }
            }
            case DecisionAnswer.BoxAnswer ignored ->
                throw DecisionJson.api("box answers are TypeSafe-only and cannot be encoded for OpenAI Decisions");
        }
        return node;
    }

    private static Usage decodeUsage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        long input = zeroIfNull(
                DecisionJson.optionalLongField(node, "input_tokens", DecisionCodecException.TYPE_API_ERROR, "usage"));
        long output = zeroIfNull(
                DecisionJson.optionalLongField(node, "output_tokens", DecisionCodecException.TYPE_API_ERROR, "usage"));
        JsonNode details = node.get("input_tokens_details");
        long cached = details == null
                ? 0
                : zeroIfNull(DecisionJson.optionalLongField(
                        details, "cached_tokens", DecisionCodecException.TYPE_API_ERROR, "usage"));
        long cacheWrite = details == null
                ? 0
                : zeroIfNull(DecisionJson.optionalLongField(
                        details, "cache_write_tokens", DecisionCodecException.TYPE_API_ERROR, "usage"));
        if (cached + cacheWrite > input) {
            throw DecisionJson.api("usage cache tokens exceed input_tokens");
        }
        JsonNode outDetails = node.get("output_tokens_details");
        Long reasoning = outDetails == null
                ? null
                : DecisionJson.optionalLongField(
                        outDetails, "reasoning_tokens", DecisionCodecException.TYPE_API_ERROR, "usage");
        long total;
        Long wireTotal =
                DecisionJson.optionalLongField(node, "total_tokens", DecisionCodecException.TYPE_API_ERROR, "usage");
        if (wireTotal != null) {
            total = wireTotal;
        } else {
            try {
                total = Math.addExact(input, output);
            } catch (ArithmeticException e) {
                throw DecisionJson.api("usage token counts overflow the 64-bit range");
            }
        }
        // Canonical Usage stores regular-only prompt tokens (cached split out),
        // matching the chat codecs' OpenAI usage convention.
        long regular = input - cached - cacheWrite;
        return new Usage(
                regular, output, total, cacheWrite == 0 ? null : cacheWrite, cached == 0 ? null : cached, reasoning);
    }

    private static long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    // ------------------------------------------------------------------ images

    /** Canonical score keys are level indices on this leg — anything else is a typed server error, never a raw NFE. */
    private static long levelIndex(String index) {
        try {
            return Long.parseLong(index);
        } catch (NumberFormatException e) {
            throw DecisionJson.api("score probability key \"" + index
                    + "\" is not a level index (the OpenAI leg keys levels numerically)");
        }
    }

    /** Raw base64 → a data URL with the sniffed media type; URLs and data URLs pass through. */
    private static String toImageUrl(String image) {
        if (image.startsWith("data:") || image.startsWith("http://") || image.startsWith("https://")) {
            return image;
        }
        return "data:" + sniffMime(image) + ";base64," + image;
    }

    private static String stripDataUrl(String imageUrl) {
        int marker = imageUrl.indexOf(";base64,");
        return marker >= 0 && imageUrl.startsWith("data:")
                ? imageUrl.substring(marker + ";base64,".length())
                : imageUrl;
    }

    private static String sniffMime(String base64) {
        try {
            int headChars = Math.min(16, base64.length() - base64.length() % 4);
            byte[] head = Base64.getDecoder().decode(base64.substring(0, Math.max(0, headChars)));
            if (head.length >= 4 && head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return "image/png";
            }
            if (head.length >= 2 && head[0] == (byte) 0xFF && head[1] == (byte) 0xD8) {
                return "image/jpeg";
            }
            if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[8] == 'W' && head[9] == 'E') {
                return "image/webp";
            }
        } catch (IllegalArgumentException ignored) {
            // Not decodable base64 head — fall through to the png default below.
        }
        return "image/png";
    }
}
