package io.orkes.example.saga.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netflix.conductor.common.config.ObjectMapperProvider;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.run.Workflow;
import io.orkes.example.saga.pojos.CancelRequest;
import io.orkes.example.saga.pojos.CustomerNotificationRequest;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 在測試中執行 {@code conductor/workflows/FoodDeliveryFailureWorkflow.v2.json}，不連線 Conductor server。
 *
 * <p>對應 specs/saga.qnt 的補償模型，每一步都盡量使用與正式環境相同的元件：
 * <ol>
 *   <li><b>failedWorkflow</b>：以 Conductor 自己的 {@link Workflow}／{@link Task} 物件描述主 workflow
 *       結束時的狀態，並依 conductor-oss {@code WorkflowExecutorOps.terminateWorkflow}
 *       的規則填入 {@code failedReferenceTaskNames}（只收 {@code FAILED}／{@code FAILED_WITH_TERMINAL_ERROR}，
 *       包含已被重試的失敗嘗試；{@code TIMED_OUT} 不收）。這個欄位是 {@code HashSet}，
 *       以 Conductor 的 {@link ObjectMapperProvider} 序列化，因此陣列順序就是雜湊順序。</li>
 *   <li><b>JSON_JQ_TRANSFORM</b>：以 jackson-jq（Conductor 伺服器使用的 JQ 實作）執行定義檔中的
 *       {@code queryExpression}。</li>
 *   <li><b>SWITCH</b>：依 {@code decisionCases} 選分支，找不到 key 時走 {@code defaultCase}。</li>
 *   <li><b>SIMPLE task</b>：直接呼叫 {@link ConductorWorkers} 的 worker 方法，寫入真實的 SQLite。
 *       非 {@code COMPLETED} 時，補償 workflow 以 FAILED 結束（不模擬重試）。</li>
 * </ol>
 */
final class FailureWorkflowHarness {

    static final ObjectMapper CONDUCTOR_MAPPER = new ObjectMapperProvider().getObjectMapper();

    private final JsonNode definition;
    private final ConductorWorkers workers;

    private FailureWorkflowHarness(JsonNode definition, ConductorWorkers workers) {
        this.definition = definition;
        this.workers = workers;
    }

    static FailureWorkflowHarness load(ConductorWorkers workers) throws IOException {
        Path file = Paths.get("conductor/workflows/FoodDeliveryFailureWorkflow.v2.json");
        JsonNode root = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
        return new FailureWorkflowHarness(root, workers);
    }

    // ------------------------------------------------------------------
    // 主 workflow 的失敗狀態
    // ------------------------------------------------------------------

    /** 主 workflow 中一個 task 的執行紀錄。 */
    static Task task(String referenceTaskName, Task.Status status, Map<String, Object> outputData) {
        Task task = new Task();
        task.setTaskId(UUID.randomUUID().toString());
        task.setReferenceTaskName(referenceTaskName);
        task.setTaskDefName(referenceTaskName.replaceFirst("_ref$", ""));
        task.setStatus(status);
        task.setOutputData(outputData == null ? new HashMap<>() : new HashMap<>(outputData));
        return task;
    }

    static Task task(String referenceTaskName, Task.Status status) {
        return task(referenceTaskName, status, null);
    }

    /** 已被 Conductor 重試的失敗嘗試（之後會再排一個同 ref 的新 task）。 */
    static Task retriedFailure(String referenceTaskName) {
        Task task = task(referenceTaskName, Task.Status.FAILED);
        task.setRetried(true);
        return task;
    }

    /**
     * 主 workflow 以 {@code status} 終止時，Conductor 交給 failureWorkflow 的 {@code failedWorkflow}。
     * {@code failedReferenceTaskNames} 依 conductor-oss
     * {@code WorkflowExecutorOps.terminateWorkflow}（core/.../WorkflowExecutorOps.java L816-836）收集。
     */
    static JsonNode failedWorkflow(String workflowId, Workflow.WorkflowStatus status, List<Task> tasks) {
        WorkflowDef def = new WorkflowDef();
        def.setName("FoodDeliveryWorkflow");
        def.setVersion(2);

        Workflow workflow = new Workflow();
        workflow.setWorkflowId(workflowId);
        workflow.setWorkflowDefinition(def);
        workflow.setCreateTime(System.currentTimeMillis());
        workflow.setStatus(status);
        workflow.setTasks(new ArrayList<>(tasks));
        for (Task t : tasks) {
            if (t.getStatus() == Task.Status.FAILED || t.getStatus() == Task.Status.FAILED_WITH_TERMINAL_ERROR) {
                workflow.getFailedReferenceTaskNames().add(t.getReferenceTaskName());
            }
        }
        return CONDUCTOR_MAPPER.valueToTree(workflow);
    }

    // ------------------------------------------------------------------
    // 執行補償 workflow
    // ------------------------------------------------------------------

    /** 補償 workflow 的執行結果。 */
    record Run(JsonNode extracted, String switchKey, List<String> executedRefs, String status) {
        String failedTaskName() {
            JsonNode n = extracted.get("failedTaskName");
            return n == null || n.isNull() ? null : n.asText();
        }

        @Override
        public String toString() {
            return "補償 workflow：extract=" + extracted + "，SWITCH key=" + switchKey
                    + "，執行=" + executedRefs + "，狀態=" + status;
        }
    }

    Run run(JsonNode failedWorkflow, String customerEmail, String customerName) throws Exception {
        List<String> executed = new ArrayList<>();
        JsonNode extracted = null;
        String switchKey = null;

        for (JsonNode task : definition.get("tasks")) {
            String type = task.get("type").asText();
            switch (type) {
                case "JSON_JQ_TRANSFORM" -> {
                    String query = task.get("inputParameters").get("queryExpression").asText();
                    ObjectNode input = CONDUCTOR_MAPPER.createObjectNode();
                    input.set("parent", failedWorkflow);   // "parent": "${workflow.input.failedWorkflow}"
                    extracted = jq(query, input);
                }
                case "SWITCH" -> {
                    JsonNode key = extracted.get("failedTaskName");
                    switchKey = key == null || key.isNull() ? null : key.asText();
                    JsonNode cases = task.get("decisionCases");
                    JsonNode branch = switchKey != null && cases.has(switchKey)
                            ? cases.get(switchKey) : task.get("defaultCase");
                    for (JsonNode inner : branch) {
                        if (!runSimple(inner, extracted, customerEmail, customerName, executed)) {
                            return new Run(extracted, switchKey, executed, "FAILED");
                        }
                    }
                }
                case "SIMPLE" -> {
                    if (!runSimple(task, extracted, customerEmail, customerName, executed)) {
                        return new Run(extracted, switchKey, executed, "FAILED");
                    }
                }
                default -> throw new IllegalStateException("harness 不支援的 task type: " + type);
            }
        }
        return new Run(extracted, switchKey, executed, "COMPLETED");
    }

    /**
     * 依定義檔的 {@code inputParameters} 組出 task 輸入（解析 {@code ${...}}），再以 Conductor 的
     * ObjectMapper 綁定到 worker 參數 POJO，與 SDK 的 AnnotatedWorker 相同。
     */
    private boolean runSimple(JsonNode task, JsonNode extracted, String customerEmail, String customerName,
                              List<String> executed) {
        String name = task.get("name").asText();
        String ref = task.get("taskReferenceName").asText();
        Map<String, Object> input = new HashMap<>();
        task.get("inputParameters").fields().forEachRemaining(e ->
                input.put(e.getKey(), resolve(e.getValue(), extracted, customerEmail, customerName)));

        TaskResult.Status status;
        switch (name) {
            case "cancel_order" -> status = workers.cancelOrderTask(bind(input, CancelRequest.class)).getStatus();
            case "cancel_payment" -> status = workers.cancelPaymentTask(bind(input, CancelRequest.class)).getStatus();
            case "cancel_delivery" -> status = workers.cancelDeliveryTask(bind(input, CancelRequest.class)).getStatus();
            case "notify_customer" -> {
                // 回傳 Map → SDK 標成 COMPLETED
                workers.checkForCustomerNotifications(bind(input, CustomerNotificationRequest.class));
                status = TaskResult.Status.COMPLETED;
            }
            default -> throw new IllegalStateException("harness 不支援的 worker: " + name);
        }
        executed.add(ref + "=" + status);
        return status == TaskResult.Status.COMPLETED;
    }

    private static Object resolve(JsonNode value, JsonNode extracted, String customerEmail, String customerName) {
        if (!value.isTextual()) {
            return CONDUCTOR_MAPPER.convertValue(value, Object.class);
        }
        String text = value.asText();
        String resultPrefix = "${extract_failure_context_ref.output.result.";
        if (text.startsWith(resultPrefix) && text.endsWith("}")) {
            JsonNode n = extracted.get(text.substring(resultPrefix.length(), text.length() - 1));
            return n == null || n.isNull() ? null : n.asText();
        }
        return switch (text) {
            case "${workflow.input.customerEmail}" -> customerEmail;
            case "${workflow.input.customerName}" -> customerName;
            default -> {
                if (text.contains("${")) {
                    throw new IllegalStateException("harness 無法解析的引用: " + text);
                }
                yield text;
            }
        };
    }

    private static <T> T bind(Map<String, Object> input, Class<T> type) {
        return CONDUCTOR_MAPPER.convertValue(input, type);
    }

    private static JsonNode jq(String query, JsonNode input) throws Exception {
        Scope root = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, root);
        Scope scope = Scope.newChildScope(root);
        List<JsonNode> out = new ArrayList<>();
        JsonQuery.compile(query, Versions.JQ_1_6).apply(scope, input, out::add);
        if (out.size() != 1) {
            throw new IllegalStateException("JQ 應產出 1 個結果，實際為 " + out);
        }
        return out.get(0);
    }
}
