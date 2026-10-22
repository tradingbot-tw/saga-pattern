package io.orkes.example.saga.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import com.netflix.conductor.sdk.workflow.task.WorkerTask;
import io.orkes.conductor.client.ApiClient;
import io.orkes.conductor.client.http.OrkesWorkflowClient;
import io.orkes.example.saga.pojos.FoodDeliveryRequest;
import io.orkes.example.saga.service.WorkflowService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TB3（Issue #48）：worker 程式碼與 conductor 定義檔的契約測試。
 *
 * <p>TB2 重建的 {@code conductor/workflows/*.json} 與 {@code conductor/taskdefs/*.json}
 * 已註冊到 Orkes（見 {@code conductor/PROVENANCE.md}），但只要版控與定義再次分離就沒人會發現
 * （TB1 報告 §7.1 AC3、AC7）。本測試把兩邊綁在一起：
 * <ul>
 *   <li><b>JSON 端</b>：以 Jackson 直接讀版控檔（不連線外部 Conductor server）。</li>
 *   <li><b>程式碼端</b>：以反射讀 {@link ConductorWorkers} 的 {@code @WorkerTask} 與參數 POJO 欄位；
 *       以實際呼叫 {@link WorkflowService#startFoodDeliveryWorkflow} 並攔截
 *       {@code StartWorkflowRequest}（同 {@code WorkflowServiceTest} 模式，不發出網路請求）
 *       取得啟動名稱、版本與起始輸入 key。</li>
 * </ul>
 *
 * <p>worker 的輸出 key 依据 {@code conductor/PROVENANCE.md} §3 的對應關係，由
 * {@code ConductorWorkers.java} 原始檔中各 {@code @WorkerTask} 方法區塊內的
 * {@code output.put(..)}／{@code result.put(..)} 解析取得——這是版控內唯一可追溯的
 * 「worker 會輸出哪些欄位」依据；原始檔解析結果會與位元組碼上的 {@code @WorkerTask}
 * 名稱全集比對（見 {@link #setUp()}），解析器失靈時直接紅燈，不會沉默漏檢。
 *
 * <p>檢查項目對應 Issue #48「怎麼做」1–7，每項一個 {@code @Test}；另有兩個負面測試
 * 驗證檢查本身會指出違規的 task 名稱／輸出 key。
 */
class WorkflowDefinitionContractTest {

    private static final Pattern WORKER_ANN =
            Pattern.compile("@WorkerTask\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern OUTPUT_PUT =
            Pattern.compile("(?:output|result)\\.put\\(\\s*\"([^\"]+)\"");
    private static final Pattern DOLLAR_REF = Pattern.compile("\\$\\{([^}]*)\\}");
    private static final Pattern WORKFLOW_INPUT_REF =
            Pattern.compile("workflow\\.input\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern TASK_OUTPUT_REF =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\.output\\.([A-Za-z_][A-Za-z0-9_]*)(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern TASK_OUTPUT_WHOLE_REF =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\.output");
    /** Conductor 執行期提供的 workflow 屬性，不是起始輸入。 */
    private static final Set<String> WORKFLOW_BUILTINS = Set.of("workflow.workflowId");

    /** 由 {@link #findRepoRoot()} 載入的定義檔與程式碼端事实，{@link #setUp()} 一次建妥。 */
    private static Path repoRoot;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** workflow 名稱（JSON 的 name 欄位）→ 根節點。 */
    private static Map<String, JsonNode> workflows;
    /** task 名稱 → taskdefs/&lt;name&gt;.json 節點（缺檔的 worker 不會出現在這裡）。 */
    private static Map<String, JsonNode> taskDefs;
    /** @WorkerTask 名稱（位元組碼反射讀得）→ worker 方法名。 */
    private static Map<String, String> workerTasks;
    /** @WorkerTask 名稱 → 參數 POJO 的實例欄位名（參數不是恰好一個 POJO 時為 null）。 */
    private static Map<String, Set<String>> workerInputFields;
    /** @WorkerTask 名稱 → 原始檔解析出的輸出 key 全集。 */
    private static Map<String, Set<String>> workerOutputKeys;
    /** WorkflowService 實際发出的啟動請求（名稱、版本、起始輸入 key）。 */
    private static StartWorkflowRequest capturedStart;

    @BeforeAll
    static void setUp() throws IOException {
        repoRoot = findRepoRoot();
        workflows = loadWorkflows();
        taskDefs = loadTaskDefs();

        // ---- 程式碼端：反射讀 @WorkerTask 與參數 POJO 欄位 ----
        workerTasks = new LinkedHashMap<>();
        workerInputFields = new LinkedHashMap<>();
        for (Method m : ConductorWorkers.class.getDeclaredMethods()) {
            WorkerTask ann = m.getAnnotation(WorkerTask.class);
            if (ann == null) {
                continue;
            }
            String taskName = ann.value();
            workerTasks.put(taskName, m.getName());
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1) {
                workerInputFields.put(taskName, null);
                continue;
            }
            Set<String> fieldNames = new TreeSet<>();
            for (Field f : params[0].getDeclaredFields()) {
                if (!f.isSynthetic() && !Modifier.isStatic(f.getModifiers())) {
                    fieldNames.add(f.getName());
                }
            }
            workerInputFields.put(taskName, fieldNames);
        }

        // ---- 程式碼端：原始檔解析每個 worker 的輸出 key ----
        workerOutputKeys = parseWorkerOutputsFromSource(
                Files.readString(repoRoot.resolve(
                        "src/main/java/io/orkes/example/saga/workers/ConductorWorkers.java"),
                        StandardCharsets.UTF_8));
        assertEquals(workerTasks.keySet(), workerOutputKeys.keySet(),
                "ConductorWorkers.java 原始檔解析出的 @WorkerTask 與位元組碼不一致，"
                        + "解析器可能已跟不上程式碼寫法，需人工更新本測試");

        // ---- 程式碼端：實際啟動請求（記錄型 client，不連線）----
        RecordingWorkflowClient client = new RecordingWorkflowClient();
        new WorkflowService(client, new StandardEnvironment())
                .startFoodDeliveryWorkflow(aDeliveryRequest());
        capturedStart = client.captured;
        assertTrue(capturedStart != null && capturedStart.getInput() != null,
                "測試基礎設施故障：未能從 WorkflowService 攔到 StartWorkflowRequest");
    }

    /** 一筆合法的外送請求，值不影響欄位契約，僅供負面情境參考。 */
    private static FoodDeliveryRequest aDeliveryRequest() {
        FoodDeliveryRequest request = new FoodDeliveryRequest();
        request.setCustomerEmail("jane@example.com");
        request.setCustomerName("Jane Doe");
        return request;
    }

    /** 只記錄 startWorkflow 呼叫、不發出任何網路請求的 client（同 WorkflowServiceTest 模式）。 */
    private static class RecordingWorkflowClient extends OrkesWorkflowClient {
        private StartWorkflowRequest captured;

        RecordingWorkflowClient() {
            // 不帶憑證的 ApiClient 只做物件初始化，不會連線
            super(new ApiClient("http://localhost:0/api"));
        }

        @Override
        public String startWorkflow(StartWorkflowRequest request) {
            captured = request;
            return "contract-test-workflow";
        }
    }

    // ==================================================================
    // 檢查 1：每個 type 為 SIMPLE 的 task 名稱，都對應一個 @WorkerTask
    // ==================================================================

    @Test
    @DisplayName("契約 1：每個 workflow 中 SIMPLE task 的 name 都有對應的 @WorkerTask")
    void everySimpleTaskNameHasAWorker() {
        assertNoViolations(simpleTaskWorkerViolations(workflows));
    }

    private static List<String> simpleTaskWorkerViolations(Map<String, JsonNode> registry) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> wf : registry.entrySet()) {
            for (JsonNode task : collectTasks(wf.getValue().get("tasks"))) {
                if (!"SIMPLE".equals(typeOf(task))) {
                    continue;
                }
                String name = text(task, "name");
                if (!workerTasks.containsKey(name)) {
                    violations.add(wf.getKey() + ": SIMPLE task \"" + name
                            + "\"（taskReferenceName=" + text(task, "taskReferenceName")
                            + "）沒有對應的 @WorkerTask");
                }
            }
        }
        return violations;
    }

    // ==================================================================
    // 檢查 2：每個 @WorkerTask 至少被一個 workflow 使用，且 taskdefs 有同名檔
    // ==================================================================

    @Test
    @DisplayName("契約 2：每個 @WorkerTask 都被某個 workflow 使用，且 conductor/taskdefs 有同名檔案")
    void everyWorkerIsUsedAndRegistered() {
        assertNoViolations(unusedWorkerViolations());
    }

    private static List<String> unusedWorkerViolations() {
        List<String> violations = new ArrayList<>();
        Set<String> usedByWorkflows = new LinkedHashSet<>();
        for (JsonNode wf : workflows.values()) {
            for (JsonNode task : collectTasks(wf.get("tasks"))) {
                if ("SIMPLE".equals(typeOf(task))) {
                    usedByWorkflows.add(text(task, "name"));
                }
            }
        }
        for (String worker : workerTasks.keySet()) {
            if (!usedByWorkflows.contains(worker)) {
                violations.add("@WorkerTask \"" + worker + "\"（" + workerTasks.get(worker)
                        + "）未被任何 workflow 定義使用");
            }
            if (!taskDefs.containsKey(worker)) {
                violations.add("@WorkerTask \"" + worker
                        + "\" 在 conductor/taskdefs 沒有對應的同名檔案 " + worker + ".json");
            } else {
                String defName = text(taskDefs.get(worker), "name");
                if (!worker.equals(defName)) {
                    violations.add("conductor/taskdefs/" + worker + ".json 的 name 欄位為 \""
                            + defName + "\"，與檔名／worker 名稱 \"" + worker + "\" 不一致");
                }
            }
        }
        return violations;
    }

    // ==================================================================
    // 檢查 3：taskReferenceName 不重複（含 FORK 與 SWITCH 分支內的 task）
    // ==================================================================

    @Test
    @DisplayName("契約 3：taskReferenceName 在同一 workflow 內不重複（含 FORK／SWITCH 分支）")
    void taskReferenceNamesAreUnique() {
        assertNoViolations(duplicateRefViolations(workflows));
    }

    private static List<String> duplicateRefViolations(Map<String, JsonNode> registry) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> wf : registry.entrySet()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (JsonNode task : collectTasks(wf.getValue().get("tasks"))) {
                String ref = text(task, "taskReferenceName");
                if (ref != null) {
                    counts.merge(ref, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> c : counts.entrySet()) {
                if (c.getValue() > 1) {
                    violations.add(wf.getKey() + ": taskReferenceName \"" + c.getKey()
                            + "\" 重複出現 " + c.getValue() + " 次");
                }
            }
        }
        return violations;
    }

    // ==================================================================
    // 檢查 4：SIMPLE task 的 inputParameters key 都是該 worker 參數 POJO 的欄位
    // ==================================================================

    @Test
    @DisplayName("契約 4：每個 SIMPLE task 的 inputParameters key 都是 worker 參數 POJO 的欄位")
    void taskInputKeysMatchPojoFields() {
        assertNoViolations(taskInputViolations(workflows));
    }

    private static List<String> taskInputViolations(Map<String, JsonNode> registry) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> wf : registry.entrySet()) {
            for (JsonNode task : collectTasks(wf.getValue().get("tasks"))) {
                if (!"SIMPLE".equals(typeOf(task))) {
                    continue;
                }
                String name = text(task, "name");
                Set<String> fields = workerInputFields.get(name);
                JsonNode input = task.get("inputParameters");
                if (input == null || !input.isObject() || input.size() == 0) {
                    continue;
                }
                if (fields == null) {
                    if (workerTasks.containsKey(name)) {
                        violations.add(wf.getKey() + ": SIMPLE task \"" + name
                                + "（" + text(task, "taskReferenceName") + "）的參數不是單一 POJO，"
                                + "無法綁定 inputParameters");
                    }
                    continue;
                }
                for (Iterator<String> it = input.fieldNames(); it.hasNext(); ) {
                    String key = it.next();
                    if (!fields.contains(key)) {
                        violations.add(wf.getKey() + ": task \"" + name + "\"（"
                                + text(task, "taskReferenceName") + "）的 inputParameters key \""
                                + key + "\" 不是參數 POJO " + name + " 對應欄位，實際欄位為: " + fields);
                    }
                }
            }
        }
        return violations;
    }

    // ==================================================================
    // 檢查 5：每個 ${...} 都解析得到（workflow.input key、<ref>.output.<key>）
    // ==================================================================

    @Test
    @DisplayName("契約 5：workflow.input 引用在 WorkflowService 起始輸入中、<ref>.output.<key> 是 worker 實際輸出的欄位")
    void everyExpressionResolves() {
        assertNoViolations(expressionViolations(workflows));
    }

    private static List<String> expressionViolations(Map<String, JsonNode> registry) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : registry.entrySet()) {
            String wfName = entry.getKey();
            JsonNode root = entry.getValue();
            boolean launchedByService = wfName.equals(capturedStart.getName());
            Set<String> allowedInputKeys;
            if (launchedByService) {
                // 由 WorkflowService 啟動：能引用的 key 就是它實際放入的起始輸入
                allowedInputKeys = new TreeSet<>(capturedStart.getInput().keySet());
            } else {
                // 非 WorkflowService 啟動（failureWorkflow 由 Conductor 系統帶入）：
                // 以該定義檔自身宣告的 inputParameters 為準
                allowedInputKeys = declaredInputKeys(root);
            }
            if (launchedByService) {
                // JSON 頂層宣告也要程式真的放得進去
                for (String declared : declaredInputKeys(root)) {
                    if (!allowedInputKeys.contains(declared)) {
                        violations.add(wfName + ": 宣告的 inputParameters key \"" + declared
                                + "\" 不在 WorkflowService 實際放入的起始輸入 " + allowedInputKeys + " 中");
                    }
                }
            }
            Map<String, JsonNode> refs = refIndex(root);
            for (String expr : collectExpressions(root)) {
                if (WORKFLOW_BUILTINS.contains(expr)) {
                    // Conductor 內建的 workflow 屬性（例如 order_food 以 workflowId 當 orderId，#137）
                    continue;
                }
                Matcher input = WORKFLOW_INPUT_REF.matcher(expr);
                if (input.matches()) {
                    String key = input.group(1);
                    if (!allowedInputKeys.contains(key)) {
                        violations.add(wfName + ": ${" + expr + "} 引用不存在的 workflow.input key \""
                                + key + "\"，可用的 key 為: " + allowedInputKeys);
                    }
                    continue;
                }
                Matcher output = TASK_OUTPUT_REF.matcher(expr);
                if (output.matches()) {
                    String ref = output.group(1);
                    String key = output.group(2);
                    String rest = output.group(3);
                    JsonNode task = refs.get(ref);
                    if (task == null) {
                        violations.add(wfName + ": ${" + expr + "} 引用不存在的 taskReferenceName \""
                                + ref + "\"");
                        continue;
                    }
                    if ("SIMPLE".equals(typeOf(task)) && workerTasks.containsKey(text(task, "name"))) {
                        Set<String> outputs = workerOutputKeys.get(text(task, "name"));
                        if (!outputs.contains(key)) {
                            violations.add(wfName + ": ${" + expr + "} 引用的輸出 key \"" + key
                                    + "\" 不是 worker " + text(task, "name") + " 輸出的欄位，實際輸出為: " + outputs);
                        }
                    } else if ("JSON_JQ_TRANSFORM".equals(typeOf(task))) {
                        // JSON_JQ_TRANSFORM 的輸出固定包在 result 下
                        if (!"result".equals(key)) {
                            violations.add(wfName + ": ${" + expr + "} 對 JSON_JQ_TRANSFORM 任務的引用"
                                    + "應以 result 開頭，實際為 \"" + key + "\"");
                            continue;
                        }
                        if (rest != null && !rest.isEmpty()) {
                            String subKey = rest.substring(1).split("\\.")[0];
                            Set<String> jqKeys = jqTopLevelKeys(queryExpressionOf(task));
                            if (jqKeys.isEmpty()) {
                                // 解析不出欄位就無法驗證：回報而不是略過，避免「沒檢查」看起來像「通過」
                                violations.add(wfName + ": ${" + expr + "} 引用 " + ref
                                        + " 的 result key，但無法從其 queryExpression 解析出產出的欄位");
                            } else if (!jqKeys.contains(subKey)) {
                                violations.add(wfName + ": ${" + expr + "} 引用的 result key \"" + subKey
                                        + "\" 不在 queryExpression 產出的欄位 " + jqKeys + " 中");
                            }
                        }
                    }
                    // 其他系統任務（FORK_JOIN／JOIN／SWITCH）：只要求 ref 存在
                    continue;
                }
                if (TASK_OUTPUT_WHOLE_REF.matcher(expr).matches()) {
                    String ref = expr.substring(0, expr.length() - ".output".length());
                    if (!refs.containsKey(ref)) {
                        violations.add(wfName + ": ${" + expr + "} 引用不存在的 taskReferenceName \""
                                + ref + "\"");
                    }
                    continue;
                }
                violations.add(wfName + ": 無法解析的引用 ${" + expr + "}（不屬於 workflow.input.<key>"
                        + " 或 <taskReferenceName>.output.<key> 兩種已知形式）");
            }
        }
        return violations;
    }

    // ==================================================================
    // 檢查 6：補償 workflow 的 SWITCH 分支名稱都是主 workflow 的 taskReferenceName
    // ==================================================================

    @Test
    @DisplayName("契約 6：補償 workflow 的 SWITCH 分支名稱，都是主 workflow 真實存在的 taskReferenceName")
    void compensationBranchesMatchMainRefs() {
        assertNoViolations(compensationViolations());
    }

    private static List<String> compensationViolations() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : workflows.entrySet()) {
            String failureName = text(entry.getValue(), "failureWorkflow");
            if (failureName == null) {
                continue;
            }
            JsonNode compensation = workflows.get(failureName);
            if (compensation == null) {
                violations.add(entry.getKey() + ": failureWorkflow \"" + failureName
                        + "\" 在 conductor/workflows 沒有同名定義");
                continue;
            }
            Set<String> mainRefs = refIndex(entry.getValue()).keySet();
            for (JsonNode task : collectTasks(compensation.get("tasks"))) {
                if (!"SWITCH".equals(typeOf(task))) {
                    continue;
                }
                JsonNode cases = task.get("decisionCases");
                if (cases == null || !cases.isObject()) {
                    continue;
                }
                Iterator<String> branches = cases.fieldNames();
                while (branches.hasNext()) {
                    String branch = branches.next();
                    if (!mainRefs.contains(branch)) {
                        violations.add("補償 " + failureName + ": SWITCH 分支名稱 \"" + branch
                                + "\" 不是主 workflow " + entry.getKey() + " 的 taskReferenceName，實際為: " + mainRefs);
                    }
                }
            }
        }
        return violations;
    }

    // ==================================================================
    // 檢查 7：WorkflowService 啟動的名稱與版本和 JSON 一致
    // ==================================================================

    @Test
    @DisplayName("契約 7：WorkflowService 啟動的 workflow 名稱與版本，與 JSON 定義的 name、version 一致")
    void startNameAndVersionMatchDefinition() {
        assertNoViolations(startNameVersionViolations());
    }

    private static List<String> startNameVersionViolations() {
        List<String> violations = new ArrayList<>();
        String name = capturedStart.getName();
        JsonNode root = workflows.get(name);
        if (root == null) {
            violations.add("WorkflowService 啟動的 workflow \"" + name
                    + "\" 在 conductor/workflows 沒有對應定義（name 欄位找不到）");
            return violations;
        }
        int jsonVersion = root.get("version").asInt();
        if (!Integer.valueOf(jsonVersion).equals(capturedStart.getVersion())) {
            violations.add("WorkflowService 啟動版本為 " + capturedStart.getVersion()
                    + "，但 " + name + " 定義的 version 為 " + jsonVersion);
        }
        return violations;
    }

    // ==================================================================
    // 負面測試：檢查本身會指出違規的 task 名稱／輸出 key
    // ==================================================================

    @Test
    @DisplayName("負面：SIMPLE task 引用不存在的 worker 名稱時，檢查 1 失敗並指出該名稱")
    void check1FailsOnUnknownTaskName() {
        Map<String, JsonNode> mutated = new LinkedHashMap<>(workflows);
        JsonNode copy = workflows.get("FoodDeliveryWorkflow").deepCopy();
        JsonNode firstTask = collectTasks(copy.get("tasks")).get(0);
        String ref = text(firstTask, "taskReferenceName");
        ((ObjectNode) firstTask).put("name", "nonexistent_worker");
        mutated.put("FoodDeliveryWorkflow", copy);

        List<String> violations = simpleTaskWorkerViolations(mutated);
        assertEquals(1, violations.size(), "應恰好指出 1 項違規，實際為: " + violations);
        assertTrue(violations.get(0).contains("nonexistent_worker"),
                "違規訊息應指出不存在的 task 名稱，實際為: " + violations.get(0));
        assertTrue(violations.get(0).contains(ref),
                "違規訊息應指出發生問題的 taskReferenceName，實際為: " + violations.get(0));
    }

    @Test
    @DisplayName("負面：${...} 引用不存在的輸出 key 時，檢查 5 失敗並指出該 key 與引用")
    void check5FailsOnUnknownOutputKey() {
        Map<String, JsonNode> mutated = new LinkedHashMap<>(workflows);
        JsonNode copy = workflows.get("FoodDeliveryWorkflow").deepCopy();
        ((ObjectNode) copy.get("outputParameters"))
                .put("orderId", "${order_food_ref.output.this_key_does_not_exist}");
        mutated.put("FoodDeliveryWorkflow", copy);

        List<String> violations = expressionViolations(mutated);
        assertEquals(1, violations.size(), "應恰好指出 1 項違規，實際為: " + violations);
        assertTrue(violations.get(0).contains("this_key_does_not_exist"),
                "違規訊息應指出不存在的輸出 key，實際為: " + violations.get(0));
        assertTrue(violations.get(0).contains("order_food_ref"),
                "違規訊息應指出被引用的 taskReferenceName，實際為: " + violations.get(0));
    }

    @Test
    @DisplayName("負面：補償 workflow 引用 JQ 擷取結果中不存在的欄位時，檢查 5 失敗並指出該欄位")
    void check5FailsOnUnknownJqResultKey() {
        Map<String, JsonNode> mutated = new LinkedHashMap<>(workflows);
        JsonNode copy = workflows.get("FoodDeliveryFailureWorkflow").deepCopy();
        ((ObjectNode) copy.get("outputParameters"))
                .put("orderId", "${extract_failure_context_ref.output.result.order_id}");
        mutated.put("FoodDeliveryFailureWorkflow", copy);

        List<String> violations = expressionViolations(mutated);
        assertEquals(1, violations.size(), "應恰好指出 1 項違規，實際為: " + violations);
        assertTrue(violations.get(0).contains("order_id"),
                "違規訊息應指出不存在的 result 欄位，實際為: " + violations.get(0));
        assertTrue(violations.get(0).contains("orderId") && violations.get(0).contains("failedTaskName"),
                "違規訊息應列出 queryExpression 實際產出的欄位，實際為: " + violations.get(0));
    }

    @Test
    @DisplayName("負面：JQ 任務的 queryExpression 解析不出欄位時，檢查 5 回報而不是略過")
    void check5ReportsUnparseableJqExpression() {
        Map<String, JsonNode> mutated = new LinkedHashMap<>(workflows);
        JsonNode copy = workflows.get("FoodDeliveryFailureWorkflow").deepCopy();
        for (JsonNode task : collectTasks(copy.get("tasks"))) {
            if ("JSON_JQ_TRANSFORM".equals(typeOf(task))) {
                ((ObjectNode) task.get("inputParameters")).put("queryExpression", ".parent");
            }
        }
        mutated.put("FoodDeliveryFailureWorkflow", copy);

        List<String> violations = expressionViolations(mutated);
        assertFalse(violations.isEmpty(), "解析不出欄位時不得視為通過");
        assertTrue(violations.stream().allMatch(v -> v.contains("無法從其 queryExpression 解析出產出的欄位")),
                "每項違規都應說明無法解析，實際為: " + violations);
    }

    // ==================================================================
    // 基礎設施
    // ==================================================================

    private static void assertNoViolations(List<String> violations) {
        assertTrue(violations.isEmpty(), "定義與程式碼不一致:\n" + String.join("\n", violations));
    }

    private static Path findRepoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("conductor").resolve("workflows"))) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "找不到含 conductor/workflows 的專案根目錄（user.dir=" + dir + "）");
    }

    private static Map<String, JsonNode> loadWorkflows() throws IOException {
        Map<String, JsonNode> loaded = new LinkedHashMap<>();
        Path dir = repoRoot.resolve("conductor/workflows");
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted().forEach(files::add);
        }
        assertFalse(files.isEmpty(), "conductor/workflows 下沒有任何 JSON 定義檔");
        for (Path file : files) {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String name = text(root, "name");
            assertTrue(name != null, file + " 缺少 name 欄位");
            loaded.put(name, root);
        }
        return loaded;
    }

    private static Map<String, JsonNode> loadTaskDefs() throws IOException {
        Map<String, JsonNode> loaded = new LinkedHashMap<>();
        Path dir = repoRoot.resolve("conductor/taskdefs");
        if (!Files.isDirectory(dir)) {
            return loaded;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted().forEach(files::add);
        }
        for (Path file : files) {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            loaded.put(stripJson(file.getFileName().toString()), root);
        }
        return loaded;
    }

    /**
     * 解析 ConductorWorkers 原始檔：每個 {@code @WorkerTask} 標注到下一個標注之間的方法區塊，
     * 收集其中的 {@code output.put("k")}／{@code result.put("k")} 作為該 worker 的輸出欄位全集
     * （對應 PROVENANCE §3「task 的輸出 key ← ConductorWorkers 的 output.put／result.put」）。
     */
    private static Map<String, Set<String>> parseWorkerOutputsFromSource(String source) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        Matcher ann = WORKER_ANN.matcher(source);
        while (ann.find()) {
            names.add(ann.group(1));
            starts.add(ann.start());
            ends.add(ann.end());
        }
        for (int i = 0; i < names.size(); i++) {
            int blockStart = ends.get(i);
            int blockEnd = (i + 1 < names.size()) ? starts.get(i + 1) : source.length();
            Set<String> keys = new TreeSet<>();
            Matcher put = OUTPUT_PUT.matcher(source.substring(blockStart, blockEnd));
            while (put.find()) {
                keys.add(put.group(1));
            }
            result.put(names.get(i), keys);
        }
        return result;
    }

    /** 遞迴收集 workflow 內的任務節點，含 FORK 分支（陣列的陣列）與 SWITCH 分支。 */
    private static List<JsonNode> collectTasks(JsonNode container) {
        List<JsonNode> tasks = new ArrayList<>();
        addTasks(container, tasks);
        return tasks;
    }

    private static void addTasks(JsonNode container, List<JsonNode> out) {
        if (container == null || !container.isArray()) {
            return;
        }
        for (JsonNode node : container) {
            if (!node.isObject() || !node.has("type")) {
                continue;
            }
            out.add(node);
            JsonNode fork = node.get("forkTasks");
            if (fork != null && fork.isArray()) {
                for (JsonNode branch : fork) {
                    addTasks(branch, out);
                }
            }
            JsonNode cases = node.get("decisionCases");
            if (cases != null && cases.isObject()) {
                for (Iterator<JsonNode> it = cases.elements(); it.hasNext(); ) {
                    addTasks(it.next(), out);
                }
            }
            addTasks(node.get("defaultCase"), out);
            addTasks(node.get("tasks"), out);
        }
    }

    /** taskReferenceName → 任務節點（同名的第一個；重複由檢查 3 報出）。 */
    private static Map<String, JsonNode> refIndex(JsonNode workflowRoot) {
        Map<String, JsonNode> refs = new LinkedHashMap<>();
        for (JsonNode task : collectTasks(workflowRoot.get("tasks"))) {
            String ref = text(task, "taskReferenceName");
            if (ref != null) {
                refs.putIfAbsent(ref, task);
            }
        }
        return refs;
    }

    /** workflow 節點中所有字串值裡的 ${...} 引用表達式（含 inputParameters、outputParameters）。 */
    private static List<String> collectExpressions(JsonNode node) {
        List<String> expressions = new ArrayList<>();
        addExpressions(node, expressions);
        return expressions;
    }

    private static void addExpressions(JsonNode node, List<String> out) {
        if (node.isObject()) {
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                addExpressions(it.next(), out);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                addExpressions(child, out);
            }
        } else if (node.isTextual()) {
            Matcher m = DOLLAR_REF.matcher(node.asText());
            while (m.find()) {
                out.add(m.group(1));
            }
        }
    }

    private static Set<String> declaredInputKeys(JsonNode workflowRoot) {
        Set<String> keys = new TreeSet<>();
        JsonNode declared = workflowRoot.get("inputParameters");
        if (declared != null && declared.isArray()) {
            for (JsonNode k : declared) {
                if (k.isTextual()) {
                    keys.add(k.asText());
                }
            }
        }
        return keys;
    }

    /**
     * 解析 JSON_JQ_TRANSFORM 的 queryExpression：取最外層物件在深度 1 上的 key
     * （含裸 key 與字串 key），用於驗證 {@code <ref>.output.result.<key>} 的 sub-key。
     * 解析不出 key 時回傳空集合；呼叫端把它當成違規回報（不略過）。
     */
    private static Set<String> jqTopLevelKeys(String expression) {
        Set<String> keys = new TreeSet<>();
        if (expression == null) {
            return keys;
        }
        int depth = 0;
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c == '"') {
                int end = expression.indexOf('"', i + 1);
                if (end < 0) {
                    break;
                }
                String content = expression.substring(i + 1, end);
                int j = end + 1;
                while (j < expression.length() && Character.isWhitespace(expression.charAt(j))) {
                    j++;
                }
                if (depth == 1 && j < expression.length() && expression.charAt(j) == ':') {
                    keys.add(content);
                }
                i = end + 1;
                continue;
            }
            if (c == '{' || c == '(' || c == '[') {
                depth++;
            } else if (c == '}' || c == ')' || c == ']') {
                depth--;
            } else if (depth == 1 && (Character.isLetter(c) || c == '_')) {
                int start = i;
                while (i < expression.length()
                        && (Character.isLetterOrDigit(expression.charAt(i)) || expression.charAt(i) == '_')) {
                    i++;
                }
                int j = i;
                while (j < expression.length() && Character.isWhitespace(expression.charAt(j))) {
                    j++;
                }
                if (j < expression.length() && expression.charAt(j) == ':') {
                    keys.add(expression.substring(start, i));
                }
                continue;
            }
            i++;
        }
        return keys;
    }

    /**
     * JSON_JQ_TRANSFORM 的 queryExpression 依 Conductor 慣例放在 {@code inputParameters} 底下
     * （conductor/workflows 的定義即是如此）；相容寫在 task 頂層的舊寫法。
     */
    private static String queryExpressionOf(JsonNode task) {
        JsonNode inputs = task.get("inputParameters");
        String nested = inputs == null ? null : text(inputs, "queryExpression");
        return nested != null ? nested : text(task, "queryExpression");
    }

    private static String typeOf(JsonNode task) {
        return text(task, "type");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static String stripJson(String fileName) {
        return fileName.substring(0, fileName.length() - ".json".length());
    }
}
