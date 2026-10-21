package io.orkes.example.saga.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netflix.conductor.sdk.workflow.task.WorkerTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * conductor/workflows/*.json、conductor/taskdefs/*.json 與 worker 程式碼之間的契約測試（Issue #48）。
 *
 * <p>TB2 重建的定義已入庫，但沒有任何測試讀它們；定義與程式碼再次分離時不會有人發現
 * （TB1 報告 §7.1 AC3、AC7）。本測試把兩邊鎖在一起：
 * <ul>
 *   <li>task 名稱 ↔ {@code @WorkerTask}（反射）；</li>
 *   <li>task 的 inputParameters key ↔ worker 參數 POJO 欄位（反射）；</li>
 *   <li>{@code ${...}}} 參照 ↔ workflow 起始輸入、taskReferenceName 與 worker 實際輸出；</li>
 *   <li>補償 SWITCH 分支 ↔ 主 workflow 的 taskReferenceName；</li>
 *   <li>WorkflowService 啟動的名稱／版本 ↔ 主 workflow JSON。</li>
 * </ul>
 *
 * <p>worker 的輸出 key 由 {@link ConductorWorkers} 原始碼中該 worker method 的
 * {@code .put("key", ...)} 取得（Java 反射看不到 Map 的 key），因此程式改輸出欄位、
 * 或 JSON 改引用欄位，任一側漂移都會讓本測試紅燈。此測試只讀檔，不連線 Conductor server。
 */
class WorkflowDefinitionContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern PUT_KEY = Pattern.compile("\\.put\\(\\s*\"([^\"]+)\"");
    private static final Path ROOT = locateProjectRoot();
    private static final Path WORKFLOW_DIR = ROOT.resolve("conductor/workflows");
    private static final Path TASKDEF_DIR = ROOT.resolve("conductor/taskdefs");
    private static final Path WORKERS_SOURCE =
            ROOT.resolve("src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java");
    private static final Path SERVICE_SOURCE =
            ROOT.resolve("src/main/java/io/orkes/example/saga/service/WorkflowService.java");

    private static Map<String, JsonNode> workflows;
    private static Map<String, WorkerContract> workers;
    private static String serviceWorkflowName;
    private static int serviceWorkflowVersion;
    private static Set<String> serviceStartInputs;

    /** 一個 {@code @WorkerTask} 在程式碼端的契約。 */
    private record WorkerContract(String taskName, String methodName, Class<?> requestType,
                                  Set<String> inputFields, Set<String> outputKeys) {
    }

    @BeforeAll
    static void loadDefinitions() throws IOException {
        workflows = new TreeMap<>();
        try (var paths = Files.list(WORKFLOW_DIR)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".json"))
                    .sorted().collect(Collectors.toList())) {
                JsonNode workflow = MAPPER.readTree(file.toFile());
                workflows.put(workflow.path("name").asText(), workflow);
            }
        }
        workers = loadWorkerContracts();
        loadServiceContract();
    }

    @Test
    @DisplayName("契約 1：每個 SIMPLE task 名稱都對應一個 @WorkerTask")
    void everySimpleTaskNameHasWorker() {
        List<String> errors = new ArrayList<>();
        for (JsonNode workflow : workflows.values()) {
            errors.addAll(checkSimpleTasksHaveWorkers(workflow));
        }
        assertNoViolations("SIMPLE task 與 @WorkerTask 不一致", errors);
    }

    @Test
    @DisplayName("契約 2：每個 @WorkerTask 都被 workflow 使用，且 taskdefs 有同名檔案")
    void everyWorkerTaskIsUsedAndHasTaskDefinition() throws IOException {
        assertNoViolations("worker 與 taskdef 不一致", checkWorkersUsedAndDefined());
    }

    @Test
    @DisplayName("契約 3：taskReferenceName 在單一 workflow 內不重複（含 FORK／SWITCH 分支）")
    void taskReferenceNamesAreUnique() {
        List<String> errors = new ArrayList<>();
        for (JsonNode workflow : workflows.values()) {
            errors.addAll(checkUniqueTaskReferences(workflow));
        }
        assertNoViolations("taskReferenceName 重複", errors);
    }

    @Test
    @DisplayName("契約 4：SIMPLE task 的 inputParameters key 都是 worker 參數 POJO 的欄位")
    void inputParameterKeysAreWorkerPojoFields() {
        List<String> errors = new ArrayList<>();
        for (JsonNode workflow : workflows.values()) {
            errors.addAll(checkInputKeysArePojoFields(workflow));
        }
        assertNoViolations("inputParameters 與 worker POJO 不一致", errors);
    }

    @Test
    @DisplayName("契約 5：每個 ${...} 都解析到起始輸入、存在的 task 與該 task 的輸出欄位")
    void everyExpressionResolves() {
        List<String> errors = new ArrayList<>();
        for (JsonNode workflow : workflows.values()) {
            errors.addAll(checkExpressions(workflow));
        }
        assertNoViolations("${...} 無法解析", errors);
    }

    @Test
    @DisplayName("契約 6：補償 workflow 的 SWITCH 分支名稱都是主 workflow 的 taskReferenceName")
    void compensationSwitchCasesAreRealTaskReferences() {
        JsonNode main = workflows.get(serviceWorkflowName);
        assertNotNull(main, "找不到 WorkflowService 啟動的 workflow：" + serviceWorkflowName);
        JsonNode failure = workflows.get(main.path("failureWorkflow").asText());
        assertNotNull(failure, "找不到 failureWorkflow：" + main.path("failureWorkflow").asText());

        assertNoViolations("補償 SWITCH 分支指向不存在的 task",
                checkCompensationSwitchCases(failure, main));
    }

    @Test
    @DisplayName("契約 7：WorkflowService 啟動的名稱、版本與起始輸入和主 workflow JSON 一致")
    void workflowServiceStartsDefinitionInJson() {
        assertNoViolations("WorkflowService 與 workflow JSON 不一致", checkServiceContract());
    }

    @Test
    @DisplayName("負面測試：SIMPLE task 名稱不存在時，檢查失敗並指出該名稱")
    void validationFailsAndNamesUnknownTask() {
        JsonNode broken = workflows.get(serviceWorkflowName).deepCopy();
        JsonNode simpleTask = allTasks(broken).stream()
                .filter(task -> "SIMPLE".equals(task.path("type").asText()))
                .findFirst().orElseThrow();
        String missingName = "task_without_worker";
        ((ObjectNode) simpleTask).put("name", missingName);

        List<String> errors = checkSimpleTasksHaveWorkers(broken);

        assertFalse(errors.isEmpty(), "不存在的 task 名稱必須讓檢查失敗");
        assertTrue(errors.stream().anyMatch(error -> error.contains(missingName)),
                "錯誤訊息必須指出是哪一個 task，實際為: " + errors);
    }

    @Test
    @DisplayName("負面測試：${...} 引用不存在的輸出 key 時，檢查失敗並指出該運算式")
    void validationFailsAndNamesUnknownOutputKey() {
        JsonNode broken = workflows.get(serviceWorkflowName).deepCopy();
        JsonNode notifyDriver = tasksByReference(broken).get("notify_driver_ref");
        assertNotNull(notifyDriver, "前置條件：主 workflow 應有 notify_driver_ref");
        String badExpression = "${ship_food_ref.output.driverIdTypo}";
        ((ObjectNode) notifyDriver.get("inputParameters")).put("driverId", badExpression);

        List<String> errors = checkExpressions(broken);

        assertFalse(errors.isEmpty(), "引用不存在的輸出 key 必須讓檢查失敗");
        assertTrue(errors.stream().anyMatch(error -> error.contains(badExpression)),
                "錯誤訊息必須指出是哪一個運算式，實際為: " + errors);
        assertTrue(errors.stream().anyMatch(error -> error.contains("ship_food_ref")),
                "錯誤訊息必須指出是哪一個 task，實際為: " + errors);
    }

    // ---------------------------------------------------------------- checks

    private static List<String> checkSimpleTasksHaveWorkers(JsonNode workflow) {
        List<String> errors = new ArrayList<>();
        for (JsonNode task : allTasks(workflow)) {
            if (!"SIMPLE".equals(task.path("type").asText())) {
                continue;
            }
            String name = task.path("name").asText();
            if (!workers.containsKey(name)) {
                errors.add(where(workflow, task) + "：SIMPLE task '" + name
                        + "' 沒有對應的 @WorkerTask（已註冊: " + workers.keySet() + "）");
            }
        }
        return errors;
    }

    private static List<String> checkWorkersUsedAndDefined() throws IOException {
        List<String> errors = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (JsonNode workflow : workflows.values()) {
            for (JsonNode task : allTasks(workflow)) {
                if ("SIMPLE".equals(task.path("type").asText())) {
                    used.add(task.path("name").asText());
                }
            }
        }
        for (WorkerContract worker : workers.values()) {
            if (!used.contains(worker.taskName())) {
                errors.add("@WorkerTask '" + worker.taskName() + "'（" + worker.methodName()
                        + "）沒有被任何 workflow 使用");
            }
            Path taskdef = TASKDEF_DIR.resolve(worker.taskName() + ".json");
            if (!Files.isRegularFile(taskdef)) {
                errors.add("@WorkerTask '" + worker.taskName() + "' 缺少 taskdef："
                        + ROOT.relativize(taskdef));
                continue;
            }
            JsonNode definition = MAPPER.readTree(taskdef.toFile());
            if (!worker.taskName().equals(definition.path("name").asText())) {
                errors.add(taskdef.getFileName() + " 的 name 是 '" + definition.path("name").asText()
                        + "'，不是 '" + worker.taskName() + "'");
            }
        }
        return errors;
    }

    private static List<String> checkUniqueTaskReferences(JsonNode workflow) {
        List<String> errors = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (JsonNode task : allTasks(workflow)) {
            String reference = task.path("taskReferenceName").asText();
            if (reference.isBlank()) {
                errors.add(where(workflow, task) + "：缺少 taskReferenceName");
                continue;
            }
            counts.merge(reference, 1, Integer::sum);
        }
        counts.forEach((reference, count) -> {
            if (count > 1) {
                errors.add(workflow.path("name").asText() + "：taskReferenceName '" + reference
                        + "' 重複 " + count + " 次");
            }
        });
        return errors;
    }

    private static List<String> checkInputKeysArePojoFields(JsonNode workflow) {
        List<String> errors = new ArrayList<>();
        for (JsonNode task : allTasks(workflow)) {
            if (!"SIMPLE".equals(task.path("type").asText())) {
                continue;
            }
            WorkerContract worker = workers.get(task.path("name").asText());
            if (worker == null) {
                continue; // 由契約 1 回報
            }
            task.path("inputParameters").fieldNames().forEachRemaining(key -> {
                if (!worker.inputFields().contains(key)) {
                    errors.add(where(workflow, task) + "：inputParameters key '" + key
                            + "' 不是 " + worker.requestType().getSimpleName() + " 的欄位（實際: "
                            + worker.inputFields() + "）");
                }
            });
        }
        return errors;
    }

    private static List<String> checkExpressions(JsonNode workflow) {
        List<String> errors = new ArrayList<>();
        for (JsonNode task : allTasks(workflow)) {
            collectExpressionErrors(workflow, task.path("inputParameters"),
                    task.path("taskReferenceName").asText(), errors);
        }
        collectExpressionErrors(workflow, workflow.path("outputParameters"),
                "workflow.outputParameters", errors);
        return errors;
    }

    private static void collectExpressionErrors(JsonNode workflow, JsonNode node, String context,
                                                List<String> errors) {
        if (node.isTextual()) {
            Matcher matcher = EXPRESSION.matcher(node.asText());
            while (matcher.find()) {
                errors.addAll(resolveExpression(workflow, context, matcher.group()));
            }
        } else if (node.isContainerNode()) {
            node.elements().forEachRemaining(child ->
                    collectExpressionErrors(workflow, child, context, errors));
        }
    }

    private static List<String> resolveExpression(JsonNode workflow, String context,
                                                   String expression) {
        List<String> errors = new ArrayList<>();
        String body = expression.substring(2, expression.length() - 1).trim();
        String location = workflow.path("name").asText() + " / " + context + " / " + expression;

        if (body.startsWith("workflow.input.")) {
            String key = body.substring("workflow.input.".length());
            if (!declaredInputs(workflow).contains(key)) {
                errors.add(location + "：'" + key + "' 不在 workflow 的 inputParameters");
            } else if (workflow.path("name").asText().equals(serviceWorkflowName)
                    && !serviceStartInputs.contains(key)) {
                errors.add(location + "：'" + key + "' 不在 WorkflowService 實際放入的起始輸入");
            }
            return errors;
        }

        int outputIndex = body.indexOf(".output.");
        if (outputIndex < 0) {
            errors.add(location + "：無法解析（只接受 workflow.input.* 或 <ref>.output.*）");
            return errors;
        }
        String reference = body.substring(0, outputIndex);
        String rest = body.substring(outputIndex + ".output.".length());
        String outputKey = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;

        JsonNode referencedTask = tasksByReference(workflow).get(reference);
        if (referencedTask == null) {
            errors.add(location + "：taskReferenceName '" + reference + "' 不存在");
            return errors;
        }
        Set<String> outputs = outputsOf(referencedTask);
        if (!outputs.contains(outputKey)) {
            errors.add(location + "：'" + outputKey + "' 不是 task '" + reference + "'（"
                    + referencedTask.path("type").asText() + "，name="
                    + referencedTask.path("name").asText() + "）的輸出欄位，實際輸出: " + outputs);
        }
        return errors;
    }

    /** worker 的輸出 key 來自原始碼；非 SIMPLE 的 task 只有 JSON_JQ_TRANSFORM 有已知的 result。 */
    private static Set<String> outputsOf(JsonNode task) {
        String type = task.path("type").asText();
        if ("SIMPLE".equals(type)) {
            WorkerContract worker = workers.get(task.path("name").asText());
            return worker == null ? Set.of() : worker.outputKeys();
        }
        if ("JSON_JQ_TRANSFORM".equals(type)) {
            return Set.of("result");
        }
        return Set.of();
    }

    private static List<String> checkCompensationSwitchCases(JsonNode failure, JsonNode main) {
        List<String> errors = new ArrayList<>();
        Set<String> mainReferences = tasksByReference(main).keySet();
        for (JsonNode task : allTasks(failure)) {
            if (!"SWITCH".equals(task.path("type").asText())) {
                continue;
            }
            task.path("decisionCases").fieldNames().forEachRemaining(branch -> {
                if (!mainReferences.contains(branch)) {
                    errors.add(failure.path("name").asText() + " / "
                            + task.path("taskReferenceName").asText() + "：SWITCH 分支 '" + branch
                            + "' 不是主 workflow 的 taskReferenceName（實際: " + mainReferences + "）");
                }
            });
        }
        return errors;
    }

    private static List<String> checkServiceContract() {
        List<String> errors = new ArrayList<>();
        JsonNode main = workflows.get(serviceWorkflowName);
        if (main == null) {
            errors.add("WorkflowService 啟動的 '" + serviceWorkflowName
                    + "' 在 conductor/workflows/*.json 找不到（實際: " + workflows.keySet() + "）");
            return errors;
        }
        if (main.path("version").asInt() != serviceWorkflowVersion) {
            errors.add("WorkflowService 啟動版本 " + serviceWorkflowVersion + "，但 JSON version 是 "
                    + main.path("version").asInt());
        }
        Set<String> declared = declaredInputs(main);
        if (!declared.equals(serviceStartInputs)) {
            Set<String> onlyJson = new TreeSet<>(declared);
            onlyJson.removeAll(serviceStartInputs);
            Set<String> onlyService = new TreeSet<>(serviceStartInputs);
            onlyService.removeAll(declared);
            errors.add("workflow inputParameters 與 WorkflowService inputData 不一致：只在 JSON="
                    + onlyJson + "，只在 WorkflowService=" + onlyService);
        }
        return errors;
    }

    // --------------------------------------------------------------- helpers

    private static Map<String, WorkerContract> loadWorkerContracts() throws IOException {
        String source = Files.readString(WORKERS_SOURCE);
        Map<String, WorkerContract> contracts = new TreeMap<>();
        for (Method method : ConductorWorkers.class.getDeclaredMethods()) {
            WorkerTask annotation = method.getAnnotation(WorkerTask.class);
            if (annotation == null) {
                continue;
            }
            Class<?> requestType = method.getParameterCount() == 1
                    ? method.getParameterTypes()[0] : null;
            Set<String> fields = requestType == null ? Set.of()
                    : java.util.Arrays.stream(requestType.getDeclaredFields())
                            .filter(field -> !Modifier.isStatic(field.getModifiers()))
                            .map(Field::getName)
                            .collect(Collectors.toCollection(TreeSet::new));
            contracts.put(annotation.value(), new WorkerContract(annotation.value(), method.getName(),
                    requestType, fields, outputKeysFromSource(source, method.getName())));
        }
        assertFalse(contracts.isEmpty(), "ConductorWorkers 上找不到任何 @WorkerTask（反射失敗）");
        return contracts;
    }

    /** 以原始碼掃描該 worker method body 內的 {@code .put("key", ...)}；反射看不到 Map 的 key。 */
    private static Set<String> outputKeysFromSource(String source, String methodName) {
        Matcher signature = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(")
                .matcher(source);
        if (!signature.find()) {
            throw new IllegalStateException("找不到 worker method 原始碼：" + methodName);
        }
        int start = source.indexOf('{', signature.end());
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int end = start;
        for (; end < source.length(); end++) {
            char current = source.charAt(end);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
            } else if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                break;
            }
        }
        Set<String> keys = new TreeSet<>();
        Matcher put = PUT_KEY.matcher(source.substring(start, end + 1));
        while (put.find()) {
            keys.add(put.group(1));
        }
        return keys;
    }

    private static void loadServiceContract() throws IOException {
        String source = Files.readString(SERVICE_SOURCE);
        Matcher name = Pattern.compile("setName\\(\\s*\"([^\"]+)\"\\s*\\)").matcher(source);
        Matcher version = Pattern.compile("setVersion\\(\\s*(\\d+)\\s*\\)").matcher(source);
        Matcher inputs = Pattern.compile("inputData\\.put\\(\\s*\"([^\"]+)\"").matcher(source);
        assertTrue(name.find(), "WorkflowService 找不到 setName(...)");
        assertTrue(version.find(), "WorkflowService 找不到 setVersion(...)");
        serviceWorkflowName = name.group(1);
        serviceWorkflowVersion = Integer.parseInt(version.group(1));
        serviceStartInputs = new LinkedHashSet<>();
        while (inputs.find()) {
            serviceStartInputs.add(inputs.group(1));
        }
        assertFalse(serviceStartInputs.isEmpty(), "WorkflowService 找不到 inputData.put(...)");
    }

    private static List<JsonNode> allTasks(JsonNode workflow) {
        List<JsonNode> tasks = new ArrayList<>();
        for (JsonNode task : workflow.path("tasks")) {
            collectTasks(task, tasks);
        }
        return tasks;
    }

    private static void collectTasks(JsonNode task, List<JsonNode> collected) {
        collected.add(task);
        for (JsonNode branch : task.path("forkTasks")) {
            for (JsonNode nested : branch) {
                collectTasks(nested, collected);
            }
        }
        task.path("decisionCases").fields().forEachRemaining(entry -> {
            for (JsonNode nested : entry.getValue()) {
                collectTasks(nested, collected);
            }
        });
        for (JsonNode nested : task.path("defaultCase")) {
            collectTasks(nested, collected);
        }
    }

    private static Map<String, JsonNode> tasksByReference(JsonNode workflow) {
        Map<String, JsonNode> byReference = new HashMap<>();
        for (JsonNode task : allTasks(workflow)) {
            byReference.put(task.path("taskReferenceName").asText(), task);
        }
        return byReference;
    }

    private static Set<String> declaredInputs(JsonNode workflow) {
        Set<String> declared = new LinkedHashSet<>();
        workflow.path("inputParameters").forEach(node -> declared.add(node.asText()));
        return declared;
    }

    private static String where(JsonNode workflow, JsonNode task) {
        return workflow.path("name").asText() + " / " + task.path("taskReferenceName").asText();
    }

    private static void assertNoViolations(String what, Collection<String> errors) {
        assertTrue(errors.isEmpty(), () -> what + ":\n - " + String.join("\n - ", errors));
    }

    private static Path locateProjectRoot() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("conductor/workflows"))) {
            directory = directory.getParent();
        }
        return directory == null ? Paths.get("").toAbsolutePath() : directory;
    }
}
