package io.amscotti.janus.core.codec;

import io.amscotti.janus.core.model.DecisionAnswer;
import io.amscotti.janus.core.model.DecisionQuestion;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import io.amscotti.janus.core.model.Usage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canonical ⇄ Google Gemini Decisions ({@code POST /v1beta/decisions}) wire codec.
 *
 * <p>Translates {@link DecisionRequest}/{@link DecisionResponse} to and from Google's
 * Interactions-style {@code /v1beta/decisions} dialect ({@code {model, input, questions}}):
 *
 * <ul>
 *   <li>Plain string {@code state} encodes as a bare string {@code input} (or a {@code text} block
 *       plus {@code image} blocks when shared {@code images[]} are present).
 *   <li>Multi-format {@code state} accepts both native Gemini blocks ({@code text}, {@code image},
 *       {@code document}, {@code audio}, {@code video}) and cross-dialect blocks ({@code
 *       input_text}, {@code image_url}, {@code input_image}, {@code input_audio}, {@code
 *       video_url}, {@code file}, {@code input_file}).
 *   <li>Non-content domain JSON {@code Map}/{@code List} {@code state} serializes into a compact
 *       JSON string inside a {@code text} block (live Gemini rejects raw objects/arrays).
 *   <li>{@code noul.criteria} maps {@code "true"}/{@code "false"} to {@code "true_description"}/
 *       {@code "false_description"}; {@code BoxQuestion} and non-empty {@code assets} are rejected
 *       with {@link DecisionCodecException}.
 *   <li>Responses omit {@code model} (null on canonical so the adapter backend echoes the alias)
 *       and map {@code usage.total_input_tokens} to input-only billing ({@code completionTokens = 0}).
 * </ul>
 */
public final class GeminiDecisionsCodec {

    private static final Set<String> REQUEST_KEYS = Set.of("model", "input", "questions");
    private static final Set<String> RESPONSE_KEYS = Set.of("answers", "usage", "model");

    static final int MAX_QUESTIONS = 1000;
    static final int MIN_CHOICE_OPTIONS = 2;
    static final int MAX_CHOICE_OPTIONS = 255;
    static final int MIN_SCORE_LEVELS = 2;
    static final int MAX_SCORE_LEVELS = 255;
    static final int MAX_CHOICE_KEY_CHARS = 128;
    static final int MAX_CHOICE_KEY_UTF8_BYTES = 512;

    private static final Pattern QUESTION_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final Set<String> NATIVE_BLOCK_TYPES = Set.of("text", "image", "document", "audio", "video");
    private static final Set<String> CROSS_DIALECT_BLOCK_TYPES =
            Set.of("input_text", "image_url", "input_image", "input_audio", "video_url", "file", "input_file");
    private static final Set<String> MEDIA_BLOCK_TYPES = Set.of(
            "image",
            "document",
            "audio",
            "video",
            "image_url",
            "input_image",
            "input_audio",
            "video_url",
            "file",
            "input_file");

    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/heic",
            "image/heif",
            "image/gif",
            "image/bmp",
            "image/tiff");
    private static final Set<String> IMAGE_RESOLUTIONS = Set.of("low", "medium", "high", "ultra_high");

    private static final Set<String> DOCUMENT_MIME_TYPES = Set.of(
            "application/pdf",
            "text/csv",
            "text/plain",
            "text/html",
            "text/xml",
            "text/markdown",
            "text/rtf",
            "application/rtf",
            "application/json",
            "application/xml",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation");

    private static final Set<String> AUDIO_MIME_TYPES = Set.of(
            "audio/wav",
            "audio/mp3",
            "audio/mpeg",
            "audio/aiff",
            "audio/aac",
            "audio/ogg",
            "audio/flac",
            "audio/m4a",
            "audio/x-m4a",
            "audio/l16",
            "audio/opus",
            "audio/alaw",
            "audio/mulaw",
            "audio/webm");

    private static final Set<String> VIDEO_MIME_TYPES = Set.of(
            "video/mp4",
            "video/mpeg",
            "video/mpg",
            "video/mov",
            "video/avi",
            "video/x-flv",
            "video/webm",
            "video/wmv",
            "video/3gpp");
    private static final Set<String> VIDEO_RESOLUTIONS = Set.of("low", "medium", "high");

    private static final Map<String, String> AUDIO_FORMAT_TO_MIME = Map.ofEntries(
            Map.entry("wav", "audio/wav"),
            Map.entry("mp3", "audio/mp3"),
            Map.entry("mpeg", "audio/mpeg"),
            Map.entry("aiff", "audio/aiff"),
            Map.entry("aac", "audio/aac"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("flac", "audio/flac"),
            Map.entry("m4a", "audio/m4a"),
            Map.entry("l16", "audio/l16"),
            Map.entry("pcm16", "audio/l16"),
            Map.entry("opus", "audio/opus"),
            Map.entry("alaw", "audio/alaw"),
            Map.entry("mulaw", "audio/mulaw"),
            Map.entry("webm", "audio/webm"));

    private final ObjectMapper mapper;

    public GeminiDecisionsCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Default mapper per the codecs' shared contract (snake_case, tolerant, strict duplicates). */
    public static GeminiDecisionsCodec create() {
        return new GeminiDecisionsCodec(DecisionJson.mapper());
    }

    // ------------------------------------------------------------------ request

    public String encodeRequest(DecisionRequest canonical) throws DecisionCodecException {
        validateRequest(canonical);
        ObjectNode root = mapper.createObjectNode();
        root.put("model", canonical.model());
        root.set("input", encodeInput(canonical));
        ObjectNode questions = root.putObject("questions");
        canonical.questions().forEach((name, q) -> questions.set(name, encodeQuestion(name, q)));
        return DecisionJson.write(mapper, root);
    }

    public DecisionRequest decodeRequest(String json) throws DecisionCodecException {
        JsonNode root = DecisionJson.readTree(mapper, json, DecisionCodecException.TYPE_INVALID_REQUEST, "request");
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            if (!REQUEST_KEYS.contains(entry.getKey())) {
                throw DecisionJson.invalid("Gemini Decisions does not support top-level field \"" + entry.getKey()
                        + "\" (only model, input, and questions are allowed)");
            }
        }
        String model = DecisionJson.requireModel(root, DecisionCodecException.TYPE_INVALID_REQUEST);
        List<String> images = new ArrayList<>();
        Object state = decodeInput(root.get("input"), images);
        JsonNode questionsNode = root.get("questions");
        if (questionsNode == null || !questionsNode.isObject() || questionsNode.isEmpty()) {
            throw DecisionJson.invalid("questions must be a non-empty object of named questions");
        }
        if (questionsNode.size() > MAX_QUESTIONS) {
            throw DecisionJson.invalid("Gemini Decisions accepts at most " + MAX_QUESTIONS + " questions per request");
        }
        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : questionsNode.properties()) {
            String name = entry.getKey();
            validateQuestionKey(name);
            questions.put(name, decodeQuestion(name, entry.getValue()));
        }
        return new DecisionRequest(model, state, images.isEmpty() ? null : images, questions, Map.of(), Map.of());
    }

    private JsonNode encodeInput(DecisionRequest canonical) {
        boolean hasImages = canonical.images() != null && !canonical.images().isEmpty();
        Object state = canonical.state();
        if (state instanceof String text && !hasImages) {
            if (text.isEmpty()) {
                throw DecisionJson.invalid("state must not be empty");
            }
            return mapper.valueToTree(text);
        }
        ArrayNode blocks = mapper.createArrayNode();
        if (state instanceof String text) {
            if (!text.isEmpty()) {
                blocks.addObject().put("type", "text").put("text", text);
            }
        } else if (state instanceof Map<?, ?> map) {
            if (isContentBlockMap(map)) {
                blocks.add(encodeContentBlock(map));
            } else {
                String compact = DecisionJson.write(mapper, mapper.valueToTree(map));
                blocks.addObject().put("type", "text").put("text", compact);
            }
        } else if (state instanceof List<?> list) {
            boolean hasContentBlock =
                    list.stream().anyMatch(item -> item instanceof Map<?, ?> m && isContentBlockMap(m));
            if (hasContentBlock) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> blockMap)) {
                        throw DecisionJson.invalid("multi-format state array items must be content block objects");
                    }
                    blocks.add(encodeContentBlock(blockMap));
                }
            } else {
                String compact = DecisionJson.write(mapper, mapper.valueToTree(list));
                blocks.addObject().put("type", "text").put("text", compact);
            }
        } else {
            throw DecisionJson.invalid("state must be a string, a JSON object, or a JSON array");
        }
        if (hasImages) {
            for (String image : canonical.images()) {
                ObjectNode imgBlock = blocks.addObject();
                imgBlock.put("type", "image");
                imgBlock.put("data", image);
                imgBlock.put("mime_type", sniffImageMime(Base64.getDecoder().decode(image)));
            }
        }
        if (blocks.isEmpty()) {
            throw DecisionJson.invalid("input must not be empty");
        }
        return blocks;
    }

    private static boolean isContentBlockMap(Map<?, ?> map) {
        Object type = map.get("type");
        return type instanceof String s && (NATIVE_BLOCK_TYPES.contains(s) || CROSS_DIALECT_BLOCK_TYPES.contains(s));
    }

    private ObjectNode encodeContentBlock(Map<?, ?> raw) {
        Object typeObj = raw.get("type");
        if (!(typeObj instanceof String type) || type.isBlank()) {
            throw DecisionJson.invalid("content block must declare a string type");
        }
        return switch (type) {
            case "text", "input_text" -> encodeTextBlock(raw);
            case "image" -> encodeNativeImageBlock(raw);
            case "image_url", "input_image" -> encodeCrossDialectImageBlock(raw, type);
            case "document" -> encodeNativeDocumentBlock(raw);
            case "file", "input_file" -> encodeCrossDialectFileBlock(raw);
            case "audio" -> encodeNativeAudioBlock(raw);
            case "input_audio" -> encodeCrossDialectAudioBlock(raw);
            case "video" -> encodeNativeVideoBlock(raw);
            case "video_url" -> encodeCrossDialectVideoBlock(raw);
            default -> throw DecisionJson.invalid("unsupported Gemini content block type \"" + type + "\"");
        };
    }

    private ObjectNode encodeTextBlock(Map<?, ?> raw) {
        Object textObj = raw.get("text");
        if (!(textObj instanceof String text) || text.isEmpty()) {
            throw DecisionJson.invalid("text content block must carry a non-empty string \"text\"");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("type", "text");
        out.put("text", text);
        return out;
    }

    private ObjectNode encodeNativeImageBlock(Map<?, ?> raw) {
        if (raw.get("data") != null && raw.get("uri") != null) {
            throw DecisionJson.invalid("image block must not specify both \"data\" and \"uri\"");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("type", "image");
        String explicitMime = normalizeImageMime(optionalString(raw, "mime_type", "image block"));
        if (raw.get("data") != null) {
            String rawData = requireString(raw, "data", "image block");
            StrippedMedia media = parseInlineBase64(rawData, "image block");
            String resolvedMime = resolveMediaMime(explicitMime, media.dataUrlMime(), true, media.bytes(), null);
            out.put("data", media.rawBase64());
            out.put("mime_type", resolvedMime);
        } else if (raw.get("uri") != null) {
            String uri = requireString(raw, "uri", "image block");
            validateGeminiUri(uri, false);
            out.put("uri", uri);
            if (explicitMime != null) {
                out.put("mime_type", explicitMime);
            }
        } else {
            throw DecisionJson.invalid("image block must specify either \"data\" or \"uri\"");
        }
        String resolution = optionalString(raw, "resolution", "image block");
        if (resolution != null) {
            if (!IMAGE_RESOLUTIONS.contains(resolution)) {
                throw DecisionJson.invalid(
                        "image resolution must be low, medium, high, or ultra_high (got \"" + resolution + "\")");
            }
            out.put("resolution", resolution);
        }
        return out;
    }

    private ObjectNode encodeCrossDialectImageBlock(Map<?, ?> raw, String type) {
        if ("input_image".equals(type)
                && raw.get("image_url") == null
                && (raw.get("data") != null || raw.get("uri") != null)) {
            return encodeNativeImageBlock(raw);
        }
        Object imageUrlObj = raw.get("image_url");
        String url;
        String detail = optionalString(raw, "detail", type + " block");
        String resolution = optionalString(raw, "resolution", type + " block");
        if (imageUrlObj instanceof String s) {
            url = s;
        } else if (imageUrlObj instanceof Map<?, ?> urlMap) {
            url = requireString(urlMap, "url", type + " image_url");
            if (detail == null) {
                detail = optionalString(urlMap, "detail", type + " image_url");
            }
        } else {
            throw DecisionJson.invalid(type + " block must carry an \"image_url\" string or object");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("type", "image");
        if (url.startsWith("data:")) {
            StrippedMedia media = parseDataUrl(url, type + " block");
            String mime = resolveMediaMime(null, media.dataUrlMime(), true, media.bytes(), null);
            out.put("data", media.rawBase64());
            out.put("mime_type", mime);
        } else if (url.startsWith("https://") || url.startsWith("files/")) {
            validateGeminiUri(url, false);
            out.put("uri", url);
        } else {
            throw DecisionJson.invalid(type + " URL must be a base64 data URL, https:// URL, or files/ URI");
        }
        String mappedResolution = resolution != null ? resolution : mapDetailToResolution(detail);
        if (mappedResolution != null) {
            if (!IMAGE_RESOLUTIONS.contains(mappedResolution)) {
                throw DecisionJson.invalid("invalid image resolution \"" + mappedResolution + "\"");
            }
            out.put("resolution", mappedResolution);
        }
        return out;
    }

    private ObjectNode encodeNativeDocumentBlock(Map<?, ?> raw) {
        if (raw.get("resolution") != null) {
            throw DecisionJson.invalid("document block does not support \"resolution\"");
        }
        if (raw.get("data") != null && raw.get("uri") != null) {
            throw DecisionJson.invalid("document block must not specify both \"data\" and \"uri\"");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("type", "document");
        String explicitMime = normalizeDocumentMime(optionalString(raw, "mime_type", "document block"));
        if (raw.get("data") != null) {
            String rawData = requireString(raw, "data", "document block");
            StrippedMedia media = parseInlineBase64(rawData, "document block");
            String mime = resolveDocumentMime(explicitMime, media.dataUrlMime(), media.bytes(), null);
            out.put("data", media.rawBase64());
            out.put("mime_type", mime);
        } else if (raw.get("uri") != null) {
            String uri = requireString(raw, "uri", "document block");
            validateGeminiUri(uri, false);
            out.put("uri", uri);
            if (explicitMime != null) {
                out.put("mime_type", explicitMime);
            }
        } else {
            throw DecisionJson.invalid("document block must specify either \"data\" or \"uri\"");
        }
        return out;
    }

    private ObjectNode encodeCrossDialectFileBlock(Map<?, ?> raw) {
        Map<?, ?> source = raw.get("file") instanceof Map<?, ?> nested ? nested : raw;
        String filename = optionalString(source, "filename", "file block");
        String explicitMime = normalizeDocumentMime(optionalString(source, "mime_type", "file block"));
        Object fileDataObj = source.get("file_data") != null ? source.get("file_data") : source.get("data");
        Object fileUrlObj = source.get("file_url") != null ? source.get("file_url") : source.get("uri");
        if (fileDataObj != null && fileUrlObj != null) {
            throw DecisionJson.invalid("file block must not specify both file_data/data and file_url/uri");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("type", "document");
        if (fileDataObj != null) {
            if (!(fileDataObj instanceof String fileData) || fileData.isBlank()) {
                throw DecisionJson.invalid("file block file_data/data must be a non-blank string");
            }
            StrippedMedia media = parseInlineBase64(fileData, "file block");
            String mime = resolveDocumentMime(explicitMime, media.dataUrlMime(), media.bytes(), filename);
            out.put("data", media.rawBase64());
            out.put("mime_type", mime);
            return out;
        }
        if (fileUrlObj != null) {
            if (!(fileUrlObj instanceof String uri) || uri.isBlank()) {
                throw DecisionJson.invalid("file block file_url/uri must be a non-blank string");
            }
            validateGeminiUri(uri, false);
            out.put("uri", uri);
            if (explicitMime != null) {
                out.put("mime_type", explicitMime);
            } else {
                String inferred = inferDocumentMimeFromFilename(filename);
                if (inferred != null) {
                    out.put("mime_type", inferred);
                }
            }
            return out;
        }
        String fileId = optionalString(source, "file_id", "file block");
        if (fileId != null && fileId.startsWith("files/")) {
            validateGeminiUri(fileId, false);
            out.put("uri", fileId);
            if (explicitMime != null) {
                out.put("mime_type", explicitMime);
            }
            return out;
        }
        throw DecisionJson.invalid("file block requires inline file_data/data, https:// file_url, or files/ URI");
    }

    private ObjectNode encodeNativeAudioBlock(Map<?, ?> raw) {
        if (raw.get("data") != null && raw.get("uri") != null) {
            throw DecisionJson.invalid("audio block must not specify both \"data\" and \"uri\"");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("type", "audio");
        String explicitMime = normalizeAudioMime(optionalString(raw, "mime_type", "audio block"));
        if (raw.get("data") != null) {
            String rawData = requireString(raw, "data", "audio block");
            StrippedMedia media = parseInlineBase64(rawData, "audio block");
            String mime = resolveAudioMime(explicitMime, media.dataUrlMime());
            out.put("data", media.rawBase64());
            out.put("mime_type", mime);
        } else if (raw.get("uri") != null) {
            String uri = requireString(raw, "uri", "audio block");
            validateGeminiUri(uri, false);
            out.put("uri", uri);
            if (explicitMime != null) {
                out.put("mime_type", explicitMime);
            }
        } else {
            throw DecisionJson.invalid("audio block must specify either \"data\" or \"uri\"");
        }
        Integer channels = optionalPositiveInt(raw, "channels", "audio block");
        if (channels != null) {
            out.put("channels", channels);
        }
        Integer sampleRate = optionalPositiveInt(raw, "sample_rate", "audio block");
        if (sampleRate != null) {
            out.put("sample_rate", sampleRate);
        }
        return out;
    }

    private ObjectNode encodeCrossDialectAudioBlock(Map<?, ?> raw) {
        Map<?, ?> source = raw.get("input_audio") instanceof Map<?, ?> nested ? nested : raw;
        String rawData = requireString(source, "data", "input_audio block");
        StrippedMedia media = parseInlineBase64(rawData, "input_audio block");
        String format = optionalString(source, "format", "input_audio block");
        String mimeType = optionalString(source, "mime_type", "input_audio block");
        if (mimeType == null) {
            mimeType = optionalString(raw, "mime_type", "input_audio block");
        }
        String mappedFormat = format == null ? null : AUDIO_FORMAT_TO_MIME.get(format.toLowerCase(Locale.ROOT));
        if (format != null && mappedFormat == null) {
            mappedFormat = normalizeAudioMime(format);
        }
        String explicitMime = mimeType != null ? normalizeAudioMime(mimeType) : mappedFormat;
        String resolved = resolveAudioMime(explicitMime, media.dataUrlMime());
        ObjectNode out = mapper.createObjectNode();
        out.put("type", "audio");
        out.put("data", media.rawBase64());
        out.put("mime_type", resolved);
        Integer channels = optionalPositiveInt(source, "channels", "input_audio block");
        if (channels != null) {
            out.put("channels", channels);
        }
        Integer sampleRate = optionalPositiveInt(source, "sample_rate", "input_audio block");
        if (sampleRate != null) {
            out.put("sample_rate", sampleRate);
        }
        return out;
    }

    private ObjectNode encodeNativeVideoBlock(Map<?, ?> raw) {
        if (raw.get("data") != null && raw.get("uri") != null) {
            throw DecisionJson.invalid("video block must not specify both \"data\" and \"uri\"");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("type", "video");
        String explicitMime = normalizeVideoMime(optionalString(raw, "mime_type", "video block"));
        if (raw.get("data") != null) {
            String rawData = requireString(raw, "data", "video block");
            StrippedMedia media = parseInlineBase64(rawData, "video block");
            String mime = resolveVideoMime(explicitMime, media.dataUrlMime());
            out.put("data", media.rawBase64());
            out.put("mime_type", mime);
        } else if (raw.get("uri") != null) {
            String uri = requireString(raw, "uri", "video block");
            validateGeminiUri(uri, true);
            out.put("uri", uri);
            if (explicitMime != null) {
                out.put("mime_type", explicitMime);
            }
        } else {
            throw DecisionJson.invalid("video block must specify either \"data\" or \"uri\"");
        }
        applyVideoOptions(out, raw);
        return out;
    }

    private ObjectNode encodeCrossDialectVideoBlock(Map<?, ?> raw) {
        Object videoUrlObj = raw.get("video_url");
        String url;
        if (videoUrlObj instanceof String s) {
            url = s;
        } else if (videoUrlObj instanceof Map<?, ?> urlMap) {
            url = requireString(urlMap, "url", "video_url");
        } else {
            throw DecisionJson.invalid("video_url block must carry a \"video_url\" string or object");
        }
        String explicitMime = normalizeVideoMime(optionalString(raw, "mime_type", "video_url block"));
        ObjectNode out = mapper.createObjectNode();
        out.put("type", "video");
        if (url.startsWith("data:")) {
            StrippedMedia media = parseDataUrl(url, "video_url block");
            String mime = resolveVideoMime(explicitMime, media.dataUrlMime());
            out.put("data", media.rawBase64());
            out.put("mime_type", mime);
        } else if (url.startsWith("https://") || url.startsWith("files/")) {
            validateGeminiUri(url, true);
            out.put("uri", url);
            if (explicitMime != null) {
                out.put("mime_type", explicitMime);
            }
        } else {
            throw DecisionJson.invalid("video_url must be a base64 data URL, https:// URL, or files/ URI");
        }
        applyVideoOptions(out, raw);
        return out;
    }

    private void applyVideoOptions(ObjectNode out, Map<?, ?> raw) {
        String resolution = optionalString(raw, "resolution", "video block");
        if (resolution != null) {
            if (!VIDEO_RESOLUTIONS.contains(resolution)) {
                throw DecisionJson.invalid(
                        "video resolution must be low, medium, or high (got \"" + resolution + "\")");
            }
            out.put("resolution", resolution);
        }
        Object processing = raw.get("processing");
        if (processing != null) {
            if (processing instanceof String s) {
                if (!"static".equals(s) && !"agentic".equals(s)) {
                    throw DecisionJson.invalid(
                            "video processing must be \"static\" or \"agentic\" (got \"" + s + "\")");
                }
                out.put("processing", s);
            } else if (processing instanceof Map<?, ?> procMap) {
                out.set("processing", mapper.valueToTree(procMap));
            } else {
                throw DecisionJson.invalid("video processing must be a string or object");
            }
        }
        String name = optionalString(raw, "name", "video block");
        if (name != null) {
            out.put("name", name);
        }
    }

    private Object decodeInput(JsonNode input, List<String> imagesOut) {
        if (input == null || input.isNull()) {
            throw DecisionJson.invalid("input is required");
        }
        if (input.isString()) {
            String s = input.asString();
            if (s.isEmpty()) {
                throw DecisionJson.invalid("input string must not be empty");
            }
            return s;
        }
        List<Map<String, Object>> normalizedBlocks = new ArrayList<>();
        if (input.isObject()) {
            normalizedBlocks.add(decodeNativeBlock(input));
        } else if (input.isArray()) {
            if (input.isEmpty()) {
                throw DecisionJson.invalid("input array must not be empty");
            }
            for (JsonNode item : input) {
                normalizedBlocks.add(decodeNativeBlock(item));
            }
        } else {
            throw DecisionJson.invalid("input must be a string, Content object, or Content[] array");
        }
        // Only extract trailing plain inline images into canonical images[] when
        // every block is leading text + trailing inline base64 images (with no
        // uri/resolution metadata); pure text-block arrays and multi-format arrays
        // preserve their exact Gemini block list for symmetric re-encoding.
        if (canExtractSharedImages(normalizedBlocks)) {
            StringBuilder text = new StringBuilder();
            for (Map<String, Object> block : normalizedBlocks) {
                if ("text".equals(block.get("type"))) {
                    text.append((String) block.get("text"));
                } else {
                    imagesOut.add((String) block.get("data"));
                }
            }
            return text.toString();
        }
        return normalizedBlocks;
    }

    private static boolean canExtractSharedImages(List<Map<String, Object>> blocks) {
        boolean sawImage = false;
        for (Map<String, Object> block : blocks) {
            Object type = block.get("type");
            if ("text".equals(type)) {
                if (sawImage) {
                    return false;
                }
            } else if ("image".equals(type)) {
                if (!block.containsKey("data") || block.containsKey("uri") || block.containsKey("resolution")) {
                    return false;
                }
                sawImage = true;
            } else {
                return false;
            }
        }
        return sawImage;
    }

    private Map<String, Object> decodeNativeBlock(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw DecisionJson.invalid("each input block must be a JSON object");
        }
        String type = DecisionJson.stringField(node, "type", DecisionCodecException.TYPE_INVALID_REQUEST, "input");
        if (!NATIVE_BLOCK_TYPES.contains(type)) {
            throw DecisionJson.invalid("unsupported Gemini input block type \"" + type + "\"");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw =
                (Map<String, Object>) DecisionJson.toPlain(mapper, node, DecisionCodecException.TYPE_INVALID_REQUEST);
        ObjectNode validated = encodeContentBlock(raw);
        @SuppressWarnings("unchecked")
        Map<String, Object> plain = (Map<String, Object>)
                DecisionJson.toPlain(mapper, validated, DecisionCodecException.TYPE_INVALID_REQUEST);
        return plain;
    }

    private ObjectNode encodeQuestion(String name, DecisionQuestion question) {
        ObjectNode node = mapper.createObjectNode();
        String instructions = instructionsTextForEncode(name, question.instructions());
        switch (question) {
            case DecisionQuestion.NoulQuestion q -> {
                node.put("type", "noul");
                node.put("instructions", instructions);
                if (q.criteria() != null && !q.criteria().isEmpty()) {
                    ObjectNode criteria = node.putObject("criteria");
                    Object whenTrue = q.criteria().get("true");
                    Object whenFalse = q.criteria().get("false");
                    if (whenTrue != null) {
                        criteria.put("true_description", DecisionJson.textOf(mapper, whenTrue));
                    }
                    if (whenFalse != null) {
                        criteria.put("false_description", DecisionJson.textOf(mapper, whenFalse));
                    }
                }
            }
            case DecisionQuestion.ChoiceQuestion q -> {
                node.put("type", "choice");
                node.put("instructions", instructions);
                ObjectNode criteria = node.putObject("criteria");
                q.criteria().forEach((opt, desc) -> {
                    String text = DecisionJson.textOf(mapper, desc);
                    if (text == null) {
                        criteria.putNull(opt);
                    } else {
                        criteria.put(opt, text);
                    }
                });
            }
            case DecisionQuestion.ScoreQuestion q -> {
                node.put("type", "score");
                node.put("instructions", instructions);
                ArrayNode criteria = node.putArray("criteria");
                for (Object level : q.criteria()) {
                    criteria.add(DecisionJson.textOf(mapper, level));
                }
            }
            case DecisionQuestion.BoxQuestion ignored ->
                throw DecisionJson.invalid("box questions are TypeSafe-only; Gemini Decisions cannot represent them");
        }
        return node;
    }

    private DecisionQuestion decodeQuestion(String name, JsonNode node) {
        String what = "question \"" + name + "\"";
        String errorType = DecisionCodecException.TYPE_INVALID_REQUEST;
        if (node == null || !node.isObject()) {
            throw DecisionJson.invalid(what + " must be an object");
        }
        String type = DecisionJson.stringField(node, "type", errorType, what);
        String instructions = decodeInstructionsText(node.get("instructions"), what);
        return switch (type) {
            case "noul" -> {
                JsonNode criteria = node.get("criteria");
                Map<String, Object> sides = null;
                if (criteria != null && !criteria.isNull()) {
                    if (!criteria.isObject()) {
                        throw DecisionJson.invalid(what + ": noul criteria must be an object");
                    }
                    if (!criteria.isEmpty()) {
                        sides = new LinkedHashMap<>();
                        for (Map.Entry<String, JsonNode> e : criteria.properties()) {
                            String key = e.getKey();
                            JsonNode val = e.getValue();
                            if (!"true_description".equals(key) && !"false_description".equals(key)) {
                                throw DecisionJson.invalid(
                                        what + ": Gemini noul criteria keys must be \"true_description\" or "
                                                + "\"false_description\" (got \"" + key + "\")");
                            }
                            if (!val.isNull() && !val.isString()) {
                                throw DecisionJson.invalid(what + ": noul criteria values must be strings or null");
                            }
                            sides.put(
                                    "true_description".equals(key) ? "true" : "false",
                                    val.isNull() ? null : val.asString());
                        }
                    }
                }
                yield new DecisionQuestion.NoulQuestion(instructions, sides);
            }
            case "choice" -> {
                JsonNode criteria = node.get("criteria");
                if (criteria == null
                        || !criteria.isObject()
                        || criteria.size() < MIN_CHOICE_OPTIONS
                        || criteria.size() > MAX_CHOICE_OPTIONS) {
                    throw DecisionJson.invalid(what + ": choice criteria must have " + MIN_CHOICE_OPTIONS + "–"
                            + MAX_CHOICE_OPTIONS + " options");
                }
                Map<String, Object> options = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> e : criteria.properties()) {
                    validateChoiceKey(what, e.getKey());
                    JsonNode val = e.getValue();
                    if (!val.isNull() && !val.isString()) {
                        throw DecisionJson.invalid(
                                what + ": choice description for \"" + e.getKey() + "\" must be a string or null");
                    }
                    options.put(e.getKey(), val.isNull() ? null : val.asString());
                }
                yield new DecisionQuestion.ChoiceQuestion(instructions, options);
            }
            case "score" -> {
                JsonNode criteria = node.get("criteria");
                if (criteria == null
                        || !criteria.isArray()
                        || criteria.size() < MIN_SCORE_LEVELS
                        || criteria.size() > MAX_SCORE_LEVELS) {
                    throw DecisionJson.invalid(what + ": score criteria must list " + MIN_SCORE_LEVELS + "–"
                            + MAX_SCORE_LEVELS + " levels");
                }
                List<Object> levels = new ArrayList<>();
                for (JsonNode level : criteria) {
                    if (!level.isString() || level.asString().isBlank()) {
                        throw DecisionJson.invalid(what + ": each score level must be a non-empty string");
                    }
                    levels.add(level.asString());
                }
                yield new DecisionQuestion.ScoreQuestion(instructions, levels);
            }
            default ->
                throw DecisionJson.invalid(what + ": type must be choice, noul, or score (got \"" + type + "\")");
        };
    }

    private String decodeInstructionsText(JsonNode instructions, String what) {
        if (instructions == null || instructions.isNull()) {
            throw DecisionJson.invalid(what + ": instructions are required for Gemini Decisions");
        }
        if (instructions.isString()) {
            String text = instructions.asString();
            if (text.isBlank()) {
                throw DecisionJson.invalid(what + ": instructions must not be blank");
            }
            return text;
        }
        if (instructions.isObject()) {
            if (!"text".equals(instructions.path("type").asString(""))) {
                throw DecisionJson.invalid(what + ": Gemini instructions only accept text content blocks");
            }
            String text = DecisionJson.stringField(
                    instructions, "text", DecisionCodecException.TYPE_INVALID_REQUEST, what + " instructions");
            if (text.isBlank()) {
                throw DecisionJson.invalid(what + ": instructions must not be blank");
            }
            return text;
        }
        if (instructions.isArray()) {
            if (instructions.isEmpty()) {
                throw DecisionJson.invalid(what + ": instructions must not be empty");
            }
            StringBuilder out = new StringBuilder();
            for (JsonNode part : instructions) {
                if (!part.isObject() || !"text".equals(part.path("type").asString(""))) {
                    throw DecisionJson.invalid(what + ": Gemini instructions only accept text content blocks");
                }
                out.append(DecisionJson.stringField(
                        part, "text", DecisionCodecException.TYPE_INVALID_REQUEST, what + " instructions"));
            }
            String text = out.toString();
            if (text.isBlank()) {
                throw DecisionJson.invalid(what + ": instructions must not be blank");
            }
            return text;
        }
        throw DecisionJson.invalid(what + ": instructions must be a string or text content block(s)");
    }

    private String instructionsTextForEncode(String name, Object instructions) {
        String what = "question \"" + name + "\"";
        if (instructions == null) {
            throw DecisionJson.invalid(what + ": Gemini Decisions requires non-null instructions");
        }
        if (instructions instanceof String s) {
            if (s.isBlank()) {
                throw DecisionJson.invalid(what + ": Gemini Decisions requires non-blank instructions");
            }
            return s;
        }
        if (instructions instanceof Map<?, ?> map && map.get("type") instanceof String type) {
            if (MEDIA_BLOCK_TYPES.contains(type)) {
                throw DecisionJson.invalid(
                        what + ": Gemini Decisions instructions only accept text (no \"" + type + "\" media)");
            }
            if ("text".equals(type) || "input_text".equals(type)) {
                Object t = map.get("text");
                if (!(t instanceof String s) || s.isBlank()) {
                    throw DecisionJson.invalid(what + ": instructions text block must carry non-blank text");
                }
                return s;
            }
        }
        if (instructions instanceof List<?> list && !list.isEmpty()) {
            boolean allTextBlocks = true;
            StringBuilder joined = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && m.get("type") instanceof String type) {
                    if (MEDIA_BLOCK_TYPES.contains(type)) {
                        throw DecisionJson.invalid(
                                what + ": Gemini Decisions instructions only accept text (no \"" + type + "\" media)");
                    }
                    if ("text".equals(type) || "input_text".equals(type)) {
                        Object t = m.get("text");
                        if (!(t instanceof String s)) {
                            throw DecisionJson.invalid(what + ": instructions text block must carry string text");
                        }
                        joined.append(s);
                        continue;
                    }
                }
                allTextBlocks = false;
            }
            if (allTextBlocks) {
                String text = joined.toString();
                if (text.isBlank()) {
                    throw DecisionJson.invalid(what + ": Gemini Decisions requires non-blank instructions");
                }
                return text;
            }
        }
        String folded = DecisionJson.textOf(mapper, instructions);
        if (folded == null || folded.isBlank()) {
            throw DecisionJson.invalid(what + ": Gemini Decisions requires non-blank instructions");
        }
        return folded;
    }

    private void validateRequest(DecisionRequest request) {
        if (request == null || request.model() == null || request.model().isBlank() || request.state() == null) {
            throw DecisionJson.invalid("model and state are required");
        }
        DecisionJson.requireFiniteTree(request.state(), DecisionCodecException.TYPE_INVALID_REQUEST, "state");
        DecisionJson.requireFiniteTree(request.extras(), DecisionCodecException.TYPE_INVALID_REQUEST, "extras");
        if (request.hasAssets()) {
            throw DecisionJson.invalid("assets are TypeSafe-only; Gemini Decisions cannot represent them");
        }
        for (String extraKey : request.extras().keySet()) {
            if (!"assets".equals(extraKey)) {
                throw DecisionJson.invalid("Gemini Decisions does not support top-level field \"" + extraKey
                        + "\" (only model, input, and questions are allowed)");
            }
        }
        if (request.questions() == null || request.questions().isEmpty()) {
            throw DecisionJson.invalid("questions must be a non-empty object of named questions");
        }
        if (request.questions().size() > MAX_QUESTIONS) {
            throw DecisionJson.invalid("Gemini Decisions accepts at most " + MAX_QUESTIONS + " questions per request");
        }
        for (Map.Entry<String, DecisionQuestion> e : request.questions().entrySet()) {
            String key = e.getKey();
            validateQuestionKey(key);
            DecisionQuestion q = e.getValue();
            if (q == null) {
                throw DecisionJson.invalid("question \"" + key + "\" must not be null");
            }
            String what = "question \"" + key + "\"";
            instructionsTextForEncode(key, q.instructions());
            switch (q) {
                case DecisionQuestion.NoulQuestion n -> {
                    if (n.criteria() != null) {
                        for (String side : n.criteria().keySet()) {
                            if (!"true".equals(side) && !"false".equals(side)) {
                                throw DecisionJson.invalid(what
                                        + ": noul criteria keys must be \"true\" or \"false\" (got \"" + side + "\")");
                            }
                        }
                    }
                }
                case DecisionQuestion.ChoiceQuestion c -> {
                    if (c.criteria() == null
                            || c.criteria().size() < MIN_CHOICE_OPTIONS
                            || c.criteria().size() > MAX_CHOICE_OPTIONS) {
                        throw DecisionJson.invalid(what + ": Gemini Decisions choice options must number "
                                + MIN_CHOICE_OPTIONS + "–" + MAX_CHOICE_OPTIONS);
                    }
                    for (String opt : c.criteria().keySet()) {
                        validateChoiceKey(what, opt);
                    }
                }
                case DecisionQuestion.ScoreQuestion s -> {
                    if (s.criteria() == null
                            || s.criteria().size() < MIN_SCORE_LEVELS
                            || s.criteria().size() > MAX_SCORE_LEVELS) {
                        throw DecisionJson.invalid(what + ": Gemini Decisions score levels must number "
                                + MIN_SCORE_LEVELS + "–" + MAX_SCORE_LEVELS);
                    }
                    for (Object level : s.criteria()) {
                        String text = DecisionJson.textOf(mapper, level);
                        if (text == null || text.isBlank()) {
                            throw DecisionJson.invalid(what + ": score levels must be non-empty strings");
                        }
                    }
                }
                case DecisionQuestion.BoxQuestion ignored ->
                    throw DecisionJson.invalid(
                            "box questions are TypeSafe-only; Gemini Decisions cannot represent them");
            }
        }
        if (request.images() != null) {
            for (String image : request.images()) {
                if (image == null || image.isBlank()) {
                    throw DecisionJson.invalid("images must be non-blank base64 strings");
                }
                requireRawBase64(image, "images");
            }
        }
    }

    private static void validateQuestionKey(String name) {
        if (name == null || !QUESTION_KEY_PATTERN.matcher(name).matches()) {
            throw DecisionJson.invalid("question name must match ^[A-Za-z0-9_-]{1,64}$");
        }
    }

    private static void validateChoiceKey(String what, String key) {
        if (key == null
                || key.isEmpty()
                || key.length() > MAX_CHOICE_KEY_CHARS
                || key.getBytes(StandardCharsets.UTF_8).length > MAX_CHOICE_KEY_UTF8_BYTES) {
            throw DecisionJson.invalid(what + ": choice key must be 1–128 chars and <= 512 UTF-8 bytes");
        }
        if (!key.strip().equals(key)) {
            throw DecisionJson.invalid(what + ": choice key must not have leading or trailing whitespace");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw DecisionJson.invalid(what + ": choice key must not contain control characters");
            }
        }
    }

    // ------------------------------------------------------------------ response

    public DecisionResponse decodeResponse(String json) throws DecisionCodecException {
        JsonNode root = DecisionJson.readTree(mapper, json, DecisionCodecException.TYPE_API_ERROR, "response");
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
                null,
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
        ObjectNode answers = root.putObject("answers");
        if (canonical.answers() != null) {
            for (Map.Entry<String, DecisionAnswer> e : canonical.answers().entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                    throw DecisionJson.api("answers must map non-blank names to non-null answers");
                }
                answers.set(e.getKey(), encodeAnswer(e.getValue()));
            }
        }
        if (canonical.usage() != null) {
            ObjectNode usage = root.putObject("usage");
            Usage u = canonical.usage();
            usage.put("total_input_tokens", u.billedPromptTokens());
            usage.put("total_output_tokens", 0L);
            usage.put("total_tokens", u.totalTokens());
        }
        DecisionJson.mergeExtras(mapper, root, canonical.extras(), RESPONSE_KEYS);
        return DecisionJson.write(mapper, root);
    }

    private DecisionAnswer decodeAnswer(String name, JsonNode node) {
        String what = "answer \"" + name + "\"";
        String errorType = DecisionCodecException.TYPE_API_ERROR;
        if (node == null || !node.isObject()) {
            throw DecisionJson.api(what + " must be an object");
        }
        String type = DecisionJson.stringField(node, "type", errorType, what);
        return switch (type) {
            case "noul" -> {
                double noul = unitInterval(DecisionJson.numberField(node, "noul", errorType, what), what + ": noul");
                Double confidence = DecisionJson.optionalNumber(node, "confidence", errorType, what);
                if (confidence != null) {
                    unitInterval(confidence, what + ": confidence");
                }
                yield new DecisionAnswer.NoulAnswer(noul);
            }
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
            default -> throw DecisionJson.api(what + ": unknown answer type \"" + type + "\"");
        };
    }

    private ObjectNode encodeAnswer(DecisionAnswer answer) {
        ObjectNode node = mapper.createObjectNode();
        switch (answer) {
            case DecisionAnswer.NoulAnswer a -> {
                double noul = unitInterval(a.noul(), "noul answer");
                node.put("type", "noul");
                node.put("noul", noul);
                node.put("confidence", Math.max(noul, 1.0 - noul));
            }
            case DecisionAnswer.RefusalAnswer ignored -> node.put("type", "refusal");
            case DecisionAnswer.ChoiceAnswer a -> {
                if (a.probabilities() == null || a.probabilities().isEmpty()) {
                    throw DecisionJson.api("choice answer probabilities must be non-empty");
                }
                a.probabilities().values().forEach(v -> unitInterval(v, "choice probability"));
                if (a.confidence() != null) {
                    unitInterval(a.confidence(), "choice confidence");
                }
                node.put("type", "choice");
                node.put("choice", a.choice());
                ObjectNode probabilities = node.putObject("probabilities");
                a.probabilities().forEach(probabilities::put);
                if (a.confidence() != null) {
                    node.put("confidence", a.confidence());
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
                node.put("type", "score");
                node.put("score", a.score());
                if (a.legend() != null) {
                    ObjectNode legend = node.putObject("legend");
                    a.legend().forEach((k, v) -> legend.set(k, mapper.valueToTree(v)));
                }
                ObjectNode probabilities = node.putObject("probabilities");
                a.probabilities().forEach(probabilities::put);
                if (a.confidence() != null) {
                    node.put("confidence", a.confidence());
                }
            }
            case DecisionAnswer.BoxAnswer ignored ->
                throw DecisionJson.api("box answers are TypeSafe-only and cannot be encoded for Gemini Decisions");
        }
        return node;
    }

    private static Usage decodeUsage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        long input = DecisionJson.longField(node, "total_input_tokens", DecisionCodecException.TYPE_API_ERROR, "usage");
        Long output = DecisionJson.optionalLongField(
                node, "total_output_tokens", DecisionCodecException.TYPE_API_ERROR, "usage");
        Long wireTotal =
                DecisionJson.optionalLongField(node, "total_tokens", DecisionCodecException.TYPE_API_ERROR, "usage");
        long total;
        if (wireTotal != null) {
            total = wireTotal;
        } else {
            try {
                total = Math.addExact(input, output == null ? 0L : output);
            } catch (ArithmeticException e) {
                throw DecisionJson.api("usage token counts overflow the 64-bit range");
            }
        }
        return new Usage(input, 0L, total);
    }

    private static double unitInterval(double value, String what) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw DecisionJson.api(what + " must be a number in [0, 1] (got " + value + ")");
        }
        return value;
    }

    // ------------------------------------------------------------------ media helpers

    private record StrippedMedia(String rawBase64, String dataUrlMime, byte[] bytes) {}

    private static StrippedMedia parseInlineBase64(String raw, String what) {
        if (raw.startsWith("data:")) {
            return parseDataUrl(raw, what);
        }
        byte[] decoded = requireRawBase64(raw, what);
        return new StrippedMedia(raw, null, decoded);
    }

    private static StrippedMedia parseDataUrl(String url, String what) {
        int comma = url.indexOf(',');
        if (!url.startsWith("data:") || comma < 0) {
            throw DecisionJson.invalid(what + ": invalid data URL");
        }
        String header = url.substring("data:".length(), comma);
        String lowerHeader = header.toLowerCase(Locale.ROOT);
        int b64Idx = lowerHeader.indexOf(";base64");
        if (b64Idx < 0 || b64Idx + ";base64".length() != lowerHeader.length()) {
            throw DecisionJson.invalid(what + ": data URL must be base64-encoded");
        }
        String mimePart = header.substring(0, b64Idx);
        int semi = mimePart.indexOf(';');
        String mime =
                (semi >= 0 ? mimePart.substring(0, semi) : mimePart).trim().toLowerCase(Locale.ROOT);
        if (mime.isEmpty()) {
            throw DecisionJson.invalid(what + ": data URL missing media type");
        }
        String rawB64 = url.substring(comma + 1);
        byte[] decoded = requireRawBase64(rawB64, what);
        return new StrippedMedia(rawB64, mime, decoded);
    }

    private static byte[] requireRawBase64(String raw, String what) {
        if (raw == null
                || raw.isBlank()
                || raw.startsWith("data:")
                || raw.contains("://")
                || raw.startsWith("file:")
                || raw.length() % 4 != 0) {
            throw DecisionJson.invalid(what + ": must be raw base64, not a URL");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(raw);
            if (decoded.length == 0) {
                throw DecisionJson.invalid(what + ": base64 payload must not be empty");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw DecisionJson.invalid(what + ": invalid base64 payload");
        }
    }

    private static void validateGeminiUri(String uri, boolean isVideo) {
        if (uri == null || uri.isBlank()) {
            throw DecisionJson.invalid("media URI must not be blank");
        }
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c <= 0x20 || c == 0x7f) {
                throw DecisionJson.invalid("media URI must not contain whitespace or control characters");
            }
        }
        if (uri.startsWith("files/")) {
            String rest = uri.substring("files/".length());
            if (rest.isEmpty() || rest.contains("..") || rest.contains("?") || rest.contains("#")) {
                throw DecisionJson.invalid("invalid Gemini files/ URI: " + uri);
            }
            return;
        }
        URI parsed;
        try {
            parsed = URI.create(uri);
        } catch (IllegalArgumentException e) {
            throw DecisionJson.invalid("invalid media URI: " + uri);
        }
        String scheme = parsed.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            throw DecisionJson.invalid("Gemini media URI must use https:// or files/ (got \"" + uri + "\")");
        }
        if (parsed.isOpaque()
                || parsed.getHost() == null
                || parsed.getHost().isBlank()
                || parsed.getUserInfo() != null) {
            throw DecisionJson.invalid("invalid https:// media URI");
        }
        if (isVideo) {
            String host = parsed.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("youtube.com")
                    || host.endsWith(".youtube.com")
                    || host.equals("youtu.be")
                    || host.endsWith(".youtu.be")) {
                throw DecisionJson.invalid("Gemini Decisions video does not support YouTube URIs");
            }
        }
    }

    private static String normalizeImageMime(String mime) {
        if (mime == null) {
            return null;
        }
        String lower = mime.trim().toLowerCase(Locale.ROOT);
        String canonical = "image/jpg".equals(lower) ? "image/jpeg" : lower;
        if (!IMAGE_MIME_TYPES.contains(canonical)) {
            throw DecisionJson.invalid("unsupported Gemini image mime_type \"" + mime + "\"");
        }
        return canonical;
    }

    private static String normalizeDocumentMime(String mime) {
        if (mime == null) {
            return null;
        }
        String lower = mime.trim().toLowerCase(Locale.ROOT);
        if (!DOCUMENT_MIME_TYPES.contains(lower)) {
            throw DecisionJson.invalid("unsupported Gemini document mime_type \"" + mime + "\"");
        }
        return lower;
    }

    private static String normalizeAudioMime(String mime) {
        if (mime == null) {
            return null;
        }
        String lower = mime.trim().toLowerCase(Locale.ROOT);
        String canonical =
                switch (lower) {
                    case "audio/x-wav" -> "audio/wav";
                    case "audio/x-flac" -> "audio/flac";
                    case "audio/mp4" -> "audio/m4a";
                    default -> lower;
                };
        if (!AUDIO_MIME_TYPES.contains(canonical)) {
            throw DecisionJson.invalid("unsupported Gemini audio mime_type \"" + mime + "\"");
        }
        return canonical;
    }

    private static String normalizeVideoMime(String mime) {
        if (mime == null) {
            return null;
        }
        String lower = mime.trim().toLowerCase(Locale.ROOT);
        String canonical =
                switch (lower) {
                    case "video/quicktime" -> "video/mov";
                    case "video/x-msvideo" -> "video/avi";
                    case "video/x-ms-wmv" -> "video/wmv";
                    default -> lower;
                };
        if (!VIDEO_MIME_TYPES.contains(canonical)) {
            throw DecisionJson.invalid("unsupported Gemini video mime_type \"" + mime + "\"");
        }
        return canonical;
    }

    private static String resolveMediaMime(
            String explicitMime, String dataUrlMime, boolean image, byte[] bytes, String ignored) {
        String normalizedDataMime = image ? normalizeImageMime(dataUrlMime) : dataUrlMime;
        if (explicitMime != null && normalizedDataMime != null && !explicitMime.equals(normalizedDataMime)) {
            throw DecisionJson.invalid(
                    "block mime_type \"" + explicitMime + "\" disagrees with data URL \"" + normalizedDataMime + "\"");
        }
        if (explicitMime != null) {
            return explicitMime;
        }
        if (normalizedDataMime != null) {
            return normalizedDataMime;
        }
        return sniffImageMime(bytes);
    }

    private static String resolveDocumentMime(String explicitMime, String dataUrlMime, byte[] bytes, String filename) {
        String normalizedDataMime = normalizeDocumentMime(dataUrlMime);
        if (explicitMime != null && normalizedDataMime != null && !explicitMime.equals(normalizedDataMime)) {
            throw DecisionJson.invalid("document mime_type \"" + explicitMime + "\" disagrees with data URL \""
                    + normalizedDataMime + "\"");
        }
        if (explicitMime != null) {
            return explicitMime;
        }
        if (normalizedDataMime != null) {
            return normalizedDataMime;
        }
        if (bytes.length >= 5
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F'
                && bytes[4] == '-') {
            return "application/pdf";
        }
        String inferred = inferDocumentMimeFromFilename(filename);
        if (inferred != null) {
            return inferred;
        }
        throw DecisionJson.invalid("document block requires mime_type (or a data URL / recognized filename extension)");
    }

    private static String resolveAudioMime(String explicitMime, String dataUrlMime) {
        String normalizedDataMime = normalizeAudioMime(dataUrlMime);
        if (explicitMime != null && normalizedDataMime != null && !explicitMime.equals(normalizedDataMime)) {
            throw DecisionJson.invalid(
                    "audio mime_type \"" + explicitMime + "\" disagrees with data URL \"" + normalizedDataMime + "\"");
        }
        if (explicitMime != null) {
            return explicitMime;
        }
        if (normalizedDataMime != null) {
            return normalizedDataMime;
        }
        throw DecisionJson.invalid("audio block requires mime_type or format");
    }

    private static String resolveVideoMime(String explicitMime, String dataUrlMime) {
        String normalizedDataMime = normalizeVideoMime(dataUrlMime);
        if (explicitMime != null && normalizedDataMime != null && !explicitMime.equals(normalizedDataMime)) {
            throw DecisionJson.invalid(
                    "video mime_type \"" + explicitMime + "\" disagrees with data URL \"" + normalizedDataMime + "\"");
        }
        if (explicitMime != null) {
            return explicitMime;
        }
        if (normalizedDataMime != null) {
            return normalizedDataMime;
        }
        throw DecisionJson.invalid("video block requires mime_type");
    }

    private static String inferDocumentMimeFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String lower = filename.trim().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".xml")) return "text/xml";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".rtf")) return "application/rtf";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".odt")) return "application/vnd.oasis.opendocument.text";
        if (lower.endsWith(".ods")) return "application/vnd.oasis.opendocument.spreadsheet";
        if (lower.endsWith(".odp")) return "application/vnd.oasis.opendocument.presentation";
        return null;
    }

    private static String sniffImageMime(byte[] head) {
        if (head.length >= 8
                && head[0] == (byte) 0x89
                && head[1] == 'P'
                && head[2] == 'N'
                && head[3] == 'G'
                && head[4] == 0x0D
                && head[5] == 0x0A
                && head[6] == 0x1A
                && head[7] == 0x0A) {
            return "image/png";
        }
        if (head.length >= 2 && head[0] == (byte) 0xFF && head[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        if (head.length >= 12
                && head[0] == 'R'
                && head[1] == 'I'
                && head[2] == 'F'
                && head[3] == 'F'
                && head[8] == 'W'
                && head[9] == 'E'
                && head[10] == 'B'
                && head[11] == 'P') {
            return "image/webp";
        }
        if (head.length >= 6
                && head[0] == 'G'
                && head[1] == 'I'
                && head[2] == 'F'
                && head[3] == '8'
                && (head[4] == '7' || head[4] == '9')
                && head[5] == 'a') {
            return "image/gif";
        }
        if (head.length >= 2 && head[0] == 'B' && head[1] == 'M') {
            return "image/bmp";
        }
        if (head.length >= 4
                && ((head[0] == 'I' && head[1] == 'I' && head[2] == 42 && head[3] == 0)
                        || (head[0] == 'M' && head[1] == 'M' && head[2] == 0 && head[3] == 42))) {
            return "image/tiff";
        }
        return "image/png";
    }

    private static String mapDetailToResolution(String detail) {
        if (detail == null) {
            return null;
        }
        return switch (detail.toLowerCase(Locale.ROOT)) {
            case "low" -> "low";
            case "high" -> "high";
            case "auto" -> null;
            default -> detail;
        };
    }

    private static String requireString(Map<?, ?> map, String key, String what) {
        Object val = map.get(key);
        if (!(val instanceof String s) || s.isBlank()) {
            throw DecisionJson.invalid(what + ": \"" + key + "\" must be a non-blank string");
        }
        return s;
    }

    private static String optionalString(Map<?, ?> map, String key, String what) {
        Object val = map.get(key);
        if (val == null) {
            return null;
        }
        if (!(val instanceof String s) || s.isBlank()) {
            throw DecisionJson.invalid(what + ": \"" + key + "\" must be a non-blank string when present");
        }
        return s;
    }

    private static Integer optionalPositiveInt(Map<?, ?> map, String key, String what) {
        Object val = map.get(key);
        if (val == null) {
            return null;
        }
        if (!(val instanceof Number num)) {
            throw DecisionJson.invalid(what + ": \"" + key + "\" must be a positive integer");
        }
        double d = num.doubleValue();
        if (!Double.isFinite(d) || d != Math.rint(d) || d < 1 || d > Integer.MAX_VALUE) {
            throw DecisionJson.invalid(what + ": \"" + key + "\" must be a positive integer");
        }
        return (int) d;
    }
}
