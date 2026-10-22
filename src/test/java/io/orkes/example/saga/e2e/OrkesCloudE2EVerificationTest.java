package io.orkes.example.saga.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.common.config.ObjectMapperProvider;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import com.netflix.conductor.common.run.Workflow;
import io.orkes.conductor.client.ApiClient;
import io.orkes.conductor.client.http.ApiException;
import io.orkes.conductor.client.http.OrkesTaskClient;
import io.orkes.conductor.client.http.OrkesWorkflowClient;
import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.*;
import io.orkes.example.saga.workers.ConductorWorkers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真實 Orkes Cloud 端到端驗證（#136–#139 的補償 workflow）。
 *
 * <p>僅在本地存在 .env.local 憑證時執行；若無憑證則自動跳過，不影響 CI。
 * 每個測試用獨立的 task domain，由本測試扮演 worker：從 Orkes 領取 task、以 Conductor 的
 * ObjectMapper 把 task 的實際 {@code inputData} 綁定到 worker 參數（與 SDK 相同），在本地 SQLite 執行，再回報結果。
 * 因此也驗證了 server 上的定義確實傳出 {@code orderId = workflowId} 與 {@code allowMissing = true}。
 *
 * <p>缺陷 C（JOIN 競態）無法刻意觸發，不在此驗證；由 {@code SagaCompensationDefectTest} 以模擬紀錄覆蓋。
 */
class OrkesCloudE2EVerificationTest {

    private static final String APP_DB_URL = SagaTestDb.APP_DB_URL;
    private static final ObjectMapper MAPPER = new ObjectMapperProvider().getObjectMapper();
    private static String serverUrl;
    private static String keyId;
    private static String keySecret;
    private static boolean credentialsAvailable = false;

    private OrkesWorkflowClient workflowClient;
    private OrkesTaskClient taskClient;
    private ConductorWorkers workers;
    private String domain;
    private final List<String> triggers = new ArrayList<>();

    @BeforeAll
    static void loadCredentialsAndInitDb() {
        SagaTestDb.createAppSchema();

        File envFile = new File(".env.local");
        if (envFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(envFile)) {
                props.load(fis);
                serverUrl = props.getProperty("CONDUCTOR_SERVER_URL");
                keyId = props.getProperty("CONDUCTOR_SECURITY_CLIENT_KEY_ID");
                keySecret = props.getProperty("CONDUCTOR_SECURITY_CLIENT_SECRET");
                if (serverUrl != null && keyId != null && keySecret != null) {
                    credentialsAvailable = true;
                }
            } catch (Exception e) {
                System.err.println("Failed to read .env.local: " + e.getMessage());
            }
        }
    }

    @BeforeEach
    void connect() {
        Assumptions.assumeTrue(credentialsAvailable, "未偵測到 .env.local 憑證，跳過真實雲端 E2E 測試");
        ApiClient apiClient = new ApiClient(serverUrl, keyId, keySecret);
        workflowClient = new OrkesWorkflowClient(apiClient);
        taskClient = new OrkesTaskClient(apiClient);
        workers = new ConductorWorkers();
        domain = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void dropInjectedFaults() throws Exception {
        for (String trigger : triggers) {
            execute("DROP TRIGGER IF EXISTS \"" + trigger + "\"");
        }
        triggers.clear();
    }

    // ==================================================================
    // 情境
    // ==================================================================

    @Test
    @DisplayName("E2E：make_payment 被拒 → 補償反向取消（cancel_delivery 為 no-op），訂單與付款皆取消")
    void paymentDeclinedIsCompensated() throws Exception {
        String workflowId = startWorkflow(expiredCard());
        orderFood(workflowId);
        checkInventory();
        TaskResult payment = execute("make_payment", t -> workers.makePaymentTask(bind(t, PaymentRequest.class)));
        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, payment.getStatus());

        awaitMainTerminated(workflowId, Workflow.WorkflowStatus.FAILED, 60);
        runCompensation(workflowId);
        assertNothingLeft(workflowId);
    }

    @Test
    @DisplayName("E2E 缺陷 A（#136）：check_inventory 失敗一次後重試成功，ship_food 失敗 → 付款仍被取消")
    void retriedCheckInventoryDoesNotChangeCompensation() throws Exception {
        String workflowId = startWorkflow(validCard());
        orderFood(workflowId);
        // 第一次嘗試回報 FAILED（可重試），Conductor 在 retryDelaySeconds（10 秒）後重排
        execute("check_inventory", t -> {
            TaskResult r = new TaskResult();
            r.setStatus(TaskResult.Status.FAILED);
            r.setReasonForIncompletion("e2e: transient failure");
            return r;
        });
        checkInventory();
        TaskResult payment = execute("make_payment", t -> workers.makePaymentTask(bind(t, PaymentRequest.class)));
        assertEquals(TaskResult.Status.COMPLETED, payment.getStatus());
        failNextShipmentInsert(workflowId);
        TaskResult ship = execute("ship_food", t -> workers.shipFoodTask(bind(t, ShippingRequest.class)));
        assertEquals(TaskResult.Status.FAILED, ship.getStatus());

        Workflow main = awaitMainTerminated(workflowId, Workflow.WorkflowStatus.FAILED, 60);
        System.out.println(">>> [E2E A] failedReferenceTaskNames = " + main.getFailedReferenceTaskNames());
        assertTrue(main.getFailedReferenceTaskNames().contains("check_inventory_ref"),
                "前置條件：重試前的失敗嘗試應留在 failedReferenceTaskNames，實際為 " + main.getFailedReferenceTaskNames());
        runCompensation(workflowId);
        assertNothingLeft(workflowId);
    }

    @Test
    @DisplayName("E2E 缺陷 B（#137）：make_payment 已扣款但沒有回報、逾時 → 付款與訂單仍被取消")
    void timedOutPaymentIsCompensated() throws Exception {
        String workflowId = startWorkflow(validCard());
        orderFood(workflowId);
        checkInventory();
        // 領取並在本地完成扣款，但不回報 → responseTimeoutSeconds（60 秒）後 TIMED_OUT
        Task paymentTask = pollWithRetry("make_payment", 30);
        assertNotNull(paymentTask, "應領取到 make_payment");
        assertEquals(TaskResult.Status.COMPLETED,
                workers.makePaymentTask(bind(paymentTask, PaymentRequest.class)).getStatus());

        // make_payment 的 timeoutSeconds 是 300：task 逾時要等滿 300 秒才觸發 TIME_OUT_WF，
        // 所以等待時間必須大於它（實測首個版本約 304 秒轉為 TIMED_OUT）。
        awaitMainTerminated(workflowId, Workflow.WorkflowStatus.TIMED_OUT, 420);
        runCompensation(workflowId);
        assertNothingLeft(workflowId);
    }

    @Test
    @DisplayName("E2E 缺陷 D（#139）：confirmShipment 資料庫錯誤，出貨單停在 SCHEDULED → 補償取消出貨單")
    void scheduledShipmentIsCanceled() throws Exception {
        String workflowId = startWorkflow(validCard());
        orderFood(workflowId);
        checkInventory();
        assertEquals(TaskResult.Status.COMPLETED,
                execute("make_payment", t -> workers.makePaymentTask(bind(t, PaymentRequest.class))).getStatus());
        failShipmentConfirmation(workflowId);
        TaskResult ship = execute("ship_food", t -> workers.shipFoodTask(bind(t, ShippingRequest.class)));
        assertEquals(TaskResult.Status.FAILED, ship.getStatus());
        assertEquals(1, SagaTestDb.count(APP_DB_URL, "SELECT COUNT(*) FROM shipments WHERE orderId = '"
                + workflowId + "' AND status = 'SCHEDULED'"), "前置條件：出貨單應停在 SCHEDULED");

        awaitMainTerminated(workflowId, Workflow.WorkflowStatus.FAILED, 60);
        runCompensation(workflowId);
        assertNothingLeft(workflowId);
    }

    // ==================================================================
    // 正向步驟與補償
    // ==================================================================

    private String startWorkflow(Map<String, Object> paymentMethod) {
        StartWorkflowRequest startReq = new StartWorkflowRequest();
        startReq.setName("FoodDeliveryWorkflow");
        startReq.setVersion(2);
        startReq.setCorrelationId("e2e-test-" + domain);
        startReq.setTaskToDomain(Map.of("*", domain));

        Map<String, Object> input = new HashMap<>();
        input.put("customerEmail", "e2e-verify@example.com");
        input.put("customerName", "E2E實測買家");
        input.put("customerContact", "+886912345678");
        input.put("restaurantId", 1);
        input.put("foodItems", List.of(Map.of("item", "burger", "quantity", 1)));
        input.put("additionalNotes", List.of("請放門口"));
        input.put("address", "台北市信義區信義路五段7號");
        input.put("deliveryInstructions", "無接觸送餐");
        input.put("paymentAmount", 120.0);
        input.put("paymentMethod", paymentMethod);
        startReq.setInput(input);

        String workflowId = workflowClient.startWorkflow(startReq);
        assertNotNull(workflowId, "主流程啟動成功");
        System.out.println(">>> [E2E] 主流程已啟動，workflowId: " + workflowId + "，domain: " + domain);
        return workflowId;
    }

    private void orderFood(String workflowId) throws Exception {
        TaskResult result = execute("order_food", t -> {
            OrderRequest request = bind(t, OrderRequest.class);
            assertEquals(workflowId, request.getOrderId(), "order_food 的 orderId 應為 workflowId");
            return workers.orderFoodTask(request);
        });
        assertEquals(TaskResult.Status.COMPLETED, result.getStatus());
        assertEquals(workflowId, result.getOutputData().get("orderId"));
    }

    private void checkInventory() throws Exception {
        TaskResult result = execute("check_inventory",
                t -> workers.checkInventoryTask(bind(t, CheckInventoryRequest.class)));
        assertEquals(TaskResult.Status.COMPLETED, result.getStatus());
    }

    /** 補償 workflow：cancel_delivery → cancel_payment → cancel_order → notify_customer，且都以 workflowId 為 orderId。 */
    private void runCompensation(String workflowId) throws Exception {
        String failureWorkflowId = null;
        for (String cancel : List.of("cancel_delivery", "cancel_payment", "cancel_order")) {
            Task task = pollWithRetry(cancel, 60);
            assertNotNull(task, "Orkes 應派發 " + cancel);
            failureWorkflowId = task.getWorkflowInstanceId();
            CancelRequest request = bind(task, CancelRequest.class);
            assertEquals(workflowId, request.getOrderId(), cancel + " 的 orderId 應為 workflowId");
            assertTrue(request.isAllowMissing(), cancel + " 應帶 allowMissing=true");
            TaskResult result = switch (cancel) {
                case "cancel_delivery" -> workers.cancelDeliveryTask(request);
                case "cancel_payment" -> workers.cancelPaymentTask(request);
                default -> workers.cancelOrderTask(request);
            };
            assertEquals(TaskResult.Status.COMPLETED, result.getStatus(), cancel + " 應完成");
            report(task, result);
            System.out.println(">>> [E2E] " + cancel + " 完成");
        }
        Task notify = pollWithRetry("notify_customer", 60);
        assertNotNull(notify, "Orkes 應派發 notify_customer");
        workers.checkForCustomerNotifications(bind(notify, CustomerNotificationRequest.class));
        TaskResult done = new TaskResult();
        done.setStatus(TaskResult.Status.COMPLETED);
        report(notify, done);

        Workflow compensation = null;
        for (int i = 0; i < 30; i++) {
            compensation = workflowClient.getWorkflow(failureWorkflowId, false);
            if (compensation.getStatus().isTerminal()) {
                break;
            }
            Thread.sleep(1000);
        }
        assertEquals(Workflow.WorkflowStatus.COMPLETED, compensation.getStatus(), "補償 workflow 應完成");
    }

    /**
     * 等待主 workflow 進入終態。長時間輪詢時 Orkes client 偶爾會回 HTTP timeout／5xx，
     * 這不是產品行為，重試即可；只有狀態不符才讓測試失敗。
     */
    private Workflow awaitMainTerminated(String workflowId, Workflow.WorkflowStatus expected, int maxSeconds)
            throws Exception {
        Workflow main = null;
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                main = workflowClient.getWorkflow(workflowId, true);
                if (main.getStatus().isTerminal()) {
                    break;
                }
            } catch (ApiException e) {
                System.out.println(">>> [E2E] 輪詢暫時失敗，重試：" + e.getMessage());
            }
            Thread.sleep(3000);
        }
        assertNotNull(main, "未能取得主流程狀態");
        assertEquals(expected, main.getStatus(), "主流程狀態");
        return main;
    }

    private void assertNothingLeft(String orderId) throws Exception {
        assertAll(
                () -> assertEquals("CANCELLED", SagaTestDb.queryString(APP_DB_URL,
                        "SELECT status FROM orders WHERE orderId = '" + orderId + "'"), "訂單應取消"),
                () -> assertEquals(0, SagaTestDb.count(APP_DB_URL, "SELECT COUNT(*) FROM payments WHERE orderId = '"
                        + orderId + "' AND status = 'SUCCESSFUL'"), "不得殘留 SUCCESSFUL 付款"),
                () -> assertEquals(0, SagaTestDb.count(APP_DB_URL, "SELECT COUNT(*) FROM shipments WHERE orderId = '"
                        + orderId + "' AND status IN ('SCHEDULED', 'CONFIRMED')"), "不得殘留出貨單"));
        System.out.println(">>> [E2E] 最終一致性通過：" + orderId);
    }

    // ==================================================================
    // 基礎設施
    // ==================================================================

    private interface Handler {
        TaskResult handle(Task task) throws Exception;
    }

    /** 領取一個 task、處理並回報。 */
    private TaskResult execute(String taskType, Handler handler) throws Exception {
        Task task = pollWithRetry(taskType, 60);
        assertNotNull(task, "應從 Orkes Cloud 領取到 " + taskType);
        TaskResult result = handler.handle(task);
        report(task, result);
        System.out.println(">>> [E2E] " + taskType + " 回報 " + result.getStatus());
        return result;
    }

    private void report(Task task, TaskResult result) {
        result.setTaskId(task.getTaskId());
        result.setWorkflowInstanceId(task.getWorkflowInstanceId());
        taskClient.updateTask(result);
    }

    private static <T> T bind(Task task, Class<T> type) {
        return MAPPER.convertValue(task.getInputData(), type);
    }

    private Task pollWithRetry(String taskType, int maxSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Task task = taskClient.pollTask(taskType, "e2e-worker", domain);
                if (task != null && task.getTaskId() != null) {
                    return task;
                }
            } catch (ApiException e) {
                System.out.println(">>> [E2E] 領取 " + taskType + " 暫時失敗，重試：" + e.getMessage());
            }
            Thread.sleep(2000);
        }
        return null;
    }

    private static Map<String, Object> expiredCard() {
        return Map.of("type", "Credit Card",
                "details", Map.of("number", "4111111111111111", "expiry", "01/2020", "cvv", 123));
    }

    private static Map<String, Object> validCard() {
        String expiry = YearMonth.now().plusYears(2).format(DateTimeFormatter.ofPattern("MM/yyyy"));
        return Map.of("type", "Credit Card",
                "details", Map.of("number", "4111111111111111", "expiry", expiry, "cvv", 123));
    }

    private void failNextShipmentInsert(String orderId) throws Exception {
        String name = "e2e_insert_fail_" + orderId;
        execute("CREATE TRIGGER \"" + name + "\" BEFORE INSERT ON shipments WHEN NEW.orderId = '" + orderId
                + "' BEGIN SELECT RAISE(ABORT, 'injected: insert shipment failed'); END");
        triggers.add(name);
    }

    private void failShipmentConfirmation(String orderId) throws Exception {
        String name = "e2e_confirm_fail_" + orderId;
        execute("CREATE TRIGGER \"" + name + "\" BEFORE UPDATE ON shipments WHEN NEW.orderId = '" + orderId
                + "' AND NEW.status = 'CONFIRMED' BEGIN SELECT RAISE(ABORT, 'injected: confirm shipment failed'); END");
        triggers.add(name);
    }

    private static void execute(String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(APP_DB_URL); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }
}
