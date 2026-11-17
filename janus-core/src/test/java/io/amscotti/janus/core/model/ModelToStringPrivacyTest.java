package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Conversation content (prompts, completions, tool arguments, image payloads) is
 * excluded from log output by default: the string form of the canonical records
 * prints structure only. Operational detail (models, counts, stop reasons, token
 * usage presence) still prints so a log line remains diagnosable. With
 * {@link ContentLogging} explicitly enabled — the gateway's
 * {@code [janus.privacy] log-content} config — the full content prints, for local
 * debugging.
 */
class ModelToStringPrivacyTest {

    private static final String MARKER = "SECRET-PROMPT-MARKER-9f1c";

    @AfterEach
    void reset() {
        ContentLogging.disable();
    }

    @Test
    void contentIsExcludedFromLogOutputByDefault() {
        assertFalse(new UserMessage(MARKER).toString().contains(MARKER));
        assertFalse(new AssistantMessage(MARKER, null).toString().contains(MARKER));
        assertFalse(new SystemMessage(MARKER).toString().contains(MARKER));
        assertFalse(new DeveloperMessage(MARKER).toString().contains(MARKER));
        assertFalse(new ToolMessage("call_1", MARKER).toString().contains(MARKER));
        assertFalse(new Delta(null, MARKER, null).toString().contains(MARKER));
        assertFalse(new TextContent(MARKER).toString().contains(MARKER));
        assertFalse(new FunctionCall("f", MARKER).toString().contains(MARKER));
        assertFalse(
                new ImageUrlContent("https://x/" + MARKER + ".png").toString().contains(MARKER));
        assertTrue(new ImageUrlContent("https://x/y.png", "high").toString().contains("detail=high"));
        assertFalse(ImageSourceContent.base64("image/png", MARKER).toString().contains(MARKER));
        assertFalse(ImageSourceContent.url("https://x/" + MARKER).toString().contains(MARKER));
        // Hosted web-search invocation: the query is user conversation content.
        HostedToolCall.WebSearchCall search = new HostedToolCall.WebSearchCall(MARKER);
        assertFalse(search.toString().contains(MARKER));
        assertTrue(search.toString().contains("query not logged"));
        // Hosted web-search declaration: userLocation is a user-provided PII map.
        HostedToolDefinition.WebSearch webSearch = new HostedToolDefinition.WebSearch("high", Map.of("city", MARKER));
        assertFalse(webSearch.toString().contains(MARKER));
        assertTrue(webSearch.toString().contains("searchContextSize=high"));
        assertTrue(webSearch.toString().contains("userLocation not logged"));
        assertTrue(new DeveloperMessage(MARKER, "alice").toString().contains("name=alice"));
        assertFalse(new DeveloperMessage(MARKER, "alice").toString().contains(MARKER));

        // The default form is still structurally useful (operational fields print).
        assertTrue(new UserMessage(MARKER).toString().contains("content not logged"));
        assertTrue(new ToolMessage("call_1", MARKER).toString().contains("toolCallId=call_1"));
    }

    @Test
    void containersExcludeContentAndExtrasByDefault() {
        ChatRequest request = new ChatRequest(
                "m",
                List.of(new UserMessage(MARKER)),
                MARKER,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                Map.of("note", MARKER),
                null);
        assertFalse(request.toString().contains(MARKER), "request: messages, system, extras all excluded");

        ChatResponse response = new ChatResponse(
                "id",
                "chat.completion",
                1L,
                "m",
                List.of(new ChatChoice(0, new AssistantMessage(MARKER, null), "stop")),
                null,
                null,
                Map.of("note", MARKER),
                null);
        assertFalse(response.toString().contains(MARKER), "response: choices and extras excluded");

        StreamChunk chunk = new StreamChunk(
                "id",
                "chat.completion.chunk",
                1L,
                "m",
                List.of(new ChunkChoice(0, new Delta(null, MARKER, null), null)),
                null,
                Map.of("note", MARKER));
        assertFalse(chunk.toString().contains(MARKER), "chunk: deltas and extras excluded");
    }

    @Test
    void nullCollectionsDoNotThrowInDefaultForm() {
        // Construct-and-fail-later: messages/choices may be null before codec
        // validation, and a usage-only chunk carries null choices — toString
        // must not NPE on the log path.
        new ChatRequest(
                        "m", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        false, null, null, null, null, null, null)
                .toString();
        new ChatResponse("id", "chat.completion", 1L, "m", null, null, null, null, null).toString();
        new StreamChunk("id", "chat.completion.chunk", 1L, "m", null, null, null).toString();
    }

    @Test
    void explicitlyEnabledContentLoggingPrintsContent() {
        ContentLogging.enable();
        assertTrue(new UserMessage(MARKER).toString().contains(MARKER));
        assertTrue(new ImageUrlContent("https://x/" + MARKER, "high").toString().contains(MARKER));
        assertTrue(new ImageUrlContent("https://x/" + MARKER).toString().contains(MARKER));
        assertTrue(ImageSourceContent.base64("image/png", MARKER).toString().contains(MARKER));
        assertTrue(ImageSourceContent.url("https://x/" + MARKER).toString().contains(MARKER));
        assertTrue(new DeveloperMessage(MARKER, "alice").toString().contains(MARKER));
        assertTrue(new AssistantMessage(MARKER, null).toString().contains(MARKER));
        assertTrue(new SystemMessage(MARKER).toString().contains(MARKER));
        assertTrue(new ToolMessage("call_1", MARKER).toString().contains(MARKER));
        assertTrue(new Delta(null, MARKER, null).toString().contains(MARKER));
        assertTrue(new FunctionCall("f", MARKER).toString().contains(MARKER));
        assertTrue(new TextContent(MARKER).toString().contains(MARKER));
        assertTrue(new HostedToolCall.WebSearchCall(MARKER).toString().contains(MARKER));
        assertTrue(new HostedToolDefinition.WebSearch("high", Map.of("city", MARKER))
                .toString()
                .contains(MARKER));

        ChatRequest request = new ChatRequest(
                "m",
                List.of(new UserMessage(MARKER)),
                MARKER,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                Map.of("note", MARKER),
                null);
        assertTrue(request.toString().contains(MARKER));
    }

    @Test
    void toolDefinitionAndDecisionModelsExcludeContentByDefaultAndPrintWhenEnabled() {
        ToolDefinition tool = new ToolDefinition(
                "function",
                "lookup_user",
                "secret description " + MARKER,
                "{\"type\":\"object\",\"properties\":{\"" + MARKER + "\":{\"type\":\"string\"}}}",
                true,
                Map.of("type", MARKER));
        assertFalse(tool.toString().contains(MARKER), tool.toString());
        assertTrue(tool.toString().contains("name=lookup_user"), tool.toString());

        DecisionRequest decisionRequest = new DecisionRequest(
                "decision-1",
                "secret state " + MARKER,
                List.of("base64-" + MARKER),
                Map.of("q", new DecisionQuestion.NoulQuestion("secret question " + MARKER, Map.of("true", MARKER))),
                Map.of("extra", MARKER),
                Map.of("internal", MARKER));
        assertFalse(decisionRequest.toString().contains(MARKER), decisionRequest.toString());
        assertTrue(decisionRequest.toString().contains("model=decision-1"), decisionRequest.toString());

        DecisionAnswer.ChoiceAnswer choiceAnswer =
                new DecisionAnswer.ChoiceAnswer("opt-" + MARKER, Map.of("opt-" + MARKER, 0.918273), 0.827364);
        DecisionAnswer.NoulAnswer noulAnswer = new DecisionAnswer.NoulAnswer(0.736455);
        DecisionAnswer.ScoreAnswer scoreAnswer =
                new DecisionAnswer.ScoreAnswer(0.645544, Map.of("0", MARKER), Map.of("0", 0.645544), 0.554433);
        DecisionAnswer.BoxAnswer boxAnswer =
                new DecisionAnswer.BoxAnswer(0.112233, 0.445566, 0.778899, 0.991122, 0.887766);
        DecisionResponse decisionResponse = new DecisionResponse(
                "decision-1",
                Map.of(
                        "c", choiceAnswer,
                        "n", noulAnswer,
                        "s", scoreAnswer,
                        "b", boxAnswer,
                        "r", new DecisionAnswer.RefusalAnswer()),
                new Usage(10, 0, 10),
                Map.of("extra", MARKER),
                Map.of("internal", MARKER));

        assertFalse(choiceAnswer.toString().contains(MARKER), choiceAnswer.toString());
        assertFalse(noulAnswer.toString().contains("0.736455"), noulAnswer.toString());
        assertFalse(scoreAnswer.toString().contains(MARKER), scoreAnswer.toString());
        assertFalse(scoreAnswer.toString().contains("0.645544"), scoreAnswer.toString());
        assertFalse(boxAnswer.toString().contains("0.112233"), boxAnswer.toString());
        assertFalse(boxAnswer.toString().contains("0.887766"), boxAnswer.toString());
        assertFalse(decisionResponse.toString().contains(MARKER), decisionResponse.toString());

        ContentLogging.enable();
        assertTrue(tool.toString().contains(MARKER), tool.toString());
        assertTrue(decisionRequest.toString().contains(MARKER), decisionRequest.toString());
        assertTrue(choiceAnswer.toString().contains(MARKER), choiceAnswer.toString());
        assertTrue(noulAnswer.toString().contains("0.736455"), noulAnswer.toString());
        assertTrue(scoreAnswer.toString().contains(MARKER), scoreAnswer.toString());
        assertTrue(boxAnswer.toString().contains("0.112233"), boxAnswer.toString());
        assertTrue(decisionResponse.toString().contains(MARKER), decisionResponse.toString());
    }

    @Test
    void everyPublicModelClassExcludesContentCanariesWhenLoggingDisabled() throws Exception {
        ContentLogging.disable();
        Set<Class<?>> nonContentAllowlist = Set.of(Usage.class, ChatRole.class, ContentLogging.class);
        Set<String> operationalMetadataParams = Set.of(
                "model",
                "id",
                "object",
                "created",
                "name",
                "toolCallId",
                "type",
                "detail",
                "searchContextSize",
                "stopReason",
                "finishReason",
                "index",
                "stream",
                "strict",
                "mediaType",
                "role");

        List<Class<?>> modelClasses = discoverModelClasses();
        assertFalse(modelClasses.isEmpty(), "expected core.model classes on classpath");

        int[] seq = new int[] {1};
        for (Class<?> clazz : modelClasses) {
            if (!Modifier.isPublic(clazz.getModifiers())
                    || clazz.isInterface()
                    || Modifier.isAbstract(clazz.getModifiers())
                    || clazz.isEnum()
                    || nonContentAllowlist.contains(clazz)) {
                continue;
            }
            Set<String> canaries = new LinkedHashSet<>();
            Object instance = buildCanaryInstance(clazz, canaries, seq, operationalMetadataParams);
            String rendered = instance.toString();
            for (String canary : canaries) {
                assertFalse(
                        rendered.contains(canary),
                        clazz.getName() + ".toString() leaked canary '" + canary + "': " + rendered);
            }
        }
    }

    private static List<Class<?>> discoverModelClasses() throws Exception {
        Path root = Path.of(ContentLogging.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path pkgDir = root.resolve("io/amscotti/janus/core/model");
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(pkgDir)) {
            for (Path path : walk.sorted().toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String fileName = path.getFileName().toString();
                if (!fileName.endsWith(".class") || fileName.equals("package-info.class")) {
                    continue;
                }
                String rel =
                        pkgDir.relativize(path).toString().replace('/', '.').replace('\\', '.');
                String fqcn = "io.amscotti.janus.core.model." + rel.substring(0, rel.length() - ".class".length());
                classes.add(Class.forName(fqcn));
            }
        }
        return classes;
    }

    private static Object buildCanaryInstance(
            Class<?> clazz, Set<String> canaries, int[] seq, Set<String> operationalParams) throws Exception {
        if (clazz.isInterface() && clazz.isSealed()) {
            Class<?>[] permitted = clazz.getPermittedSubclasses();
            return buildCanaryInstance(permitted[0], canaries, seq, operationalParams);
        }
        if (clazz == Usage.class) {
            return new Usage(10, 5, 15);
        }
        if (clazz == ChatRole.class) {
            return ChatRole.USER;
        }
        Constructor<?> ctor = List.of(clazz.getConstructors()).stream()
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow(() -> new AssertionError("no public constructor on " + clazz.getName()));
        RecordComponent[] components = clazz.isRecord() ? clazz.getRecordComponents() : null;
        Type[] genericTypes = ctor.getGenericParameterTypes();
        Class<?>[] paramTypes = ctor.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            String paramName =
                    components != null && components.length == paramTypes.length ? components[i].getName() : "arg" + i;
            boolean operational = operationalParams.contains(paramName)
                    || (clazz == FunctionCall.class && "description".equals(paramName));
            args[i] = canaryValueFor(
                    clazz.getSimpleName() + "_" + paramName,
                    genericTypes[i],
                    paramTypes[i],
                    operational,
                    canaries,
                    seq,
                    operationalParams);
        }
        return ctor.newInstance(args);
    }

    private static Object canaryValueFor(
            String label,
            Type genericType,
            Class<?> rawType,
            boolean operational,
            Set<String> canaries,
            int[] seq,
            Set<String> operationalParams)
            throws Exception {
        int id = seq[0]++;
        if (rawType == String.class || rawType == Object.class) {
            if (operational) {
                return "op_" + label;
            }
            String canary = "CANARY_" + label + "_" + id;
            canaries.add(canary);
            return canary;
        }
        if (rawType == double.class || rawType == Double.class) {
            double val = 0.719384 + id * 0.000137;
            if (!operational) {
                canaries.add(Double.toString(val));
            }
            return val;
        }
        if (rawType == int.class || rawType == Integer.class) {
            int val = 7300 + id;
            if (!operational) {
                canaries.add(Integer.toString(val));
            }
            return val;
        }
        if (rawType == long.class || rawType == Long.class) {
            long val = 918273000L + id;
            if (!operational) {
                canaries.add(Long.toString(val));
            }
            return val;
        }
        if (rawType == boolean.class || rawType == Boolean.class) {
            return Boolean.FALSE;
        }
        if (rawType == List.class) {
            Type elemType = genericType instanceof ParameterizedType pt ? pt.getActualTypeArguments()[0] : Object.class;
            Class<?> elemRaw = rawClassOf(elemType);
            return List.of(canaryValueFor(label + "_elem", elemType, elemRaw, false, canaries, seq, operationalParams));
        }
        if (rawType == Map.class) {
            Type valType = genericType instanceof ParameterizedType pt ? pt.getActualTypeArguments()[1] : Object.class;
            Class<?> valRaw = rawClassOf(valType);
            String keyCanary = "CANARY_KEY_" + label + "_" + id;
            canaries.add(keyCanary);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(
                    keyCanary,
                    canaryValueFor(label + "_val", valType, valRaw, false, canaries, seq, operationalParams));
            return map;
        }
        return buildCanaryInstance(rawType, canaries, seq, operationalParams);
    }

    private static Class<?> rawClassOf(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        return Object.class;
    }
}
