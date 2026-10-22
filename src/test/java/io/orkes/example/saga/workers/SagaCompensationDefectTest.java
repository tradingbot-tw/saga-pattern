package io.orkes.example.saga.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.common.run.Workflow;
import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.CheckInventoryRequest;
import io.orkes.example.saga.pojos.FoodItem;
import io.orkes.example.saga.pojos.OrderRequest;
import io.orkes.example.saga.pojos.PaymentDetails;
import io.orkes.example.saga.pojos.PaymentMethod;
import io.orkes.example.saga.pojos.PaymentRequest;
import io.orkes.example.saga.pojos.ShippingRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.orkes.example.saga.workers.FailureWorkflowHarness.failedWorkflow;
import static io.orkes.example.saga.workers.FailureWorkflowHarness.retriedFailure;
import static io.orkes.example.saga.workers.FailureWorkflowHarness.task;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 以 Quint 規格（specs/saga.qnt）找到的反例 A–D（#136–#139）驗證補償 workflow。
 *
 * <p>每個情境對應 specs/saga_test.qnt 中的 {@code current_*}（修正後）與 {@code defect_*}（SWITCH 版反例）：
 * 正向步驟呼叫真實的 worker 寫入 SQLite；需要的資料庫錯誤以 SQLite trigger 注入到真實程式碼路徑；
 * Conductor 的行為（重試、逾時、JOIN 競態）以 {@link Workflow}／{@link Task} 的執行紀錄表達；
 * 補償 workflow 由 {@link FailureWorkflowHarness} 照版控中的 JSON 執行。
 *
 * <p>每個測試都斷言正確的 Saga 一致性（補償後不殘留生效中的訂單、付款、出貨單）。
 * 在 SWITCH 版補償 workflow 下，{@code defect*} 這 4 個測試都會失敗；
 * 現行補償 workflow 不看失敗點、一律反向取消（{@code allowMissing=true}），全部通過。
 *
 * <p>orderId 與正式環境相同：主 workflow 把 {@code ${workflow.workflowId}} 傳給 {@code order_food} 當訂單 id。
 */
class SagaCompensationDefectTest {

    private static final String URL = SagaTestDb.APP_DB_URL;
    private static final String CUSTOMER_NAME = "反例重現買家";

    private ConductorWorkers workers;
    private FailureWorkflowHarness failureWorkflow;
    private final List<String> triggers = new ArrayList<>();

    @BeforeAll
    static void prepareDatabase() {
        SagaTestDb.createAppSchema();
    }

    @BeforeEach
    void setUp() throws Exception {
        workers = new ConductorWorkers();
        failureWorkflow = FailureWorkflowHarness.load(workers);
    }

    @AfterEach
    void dropInjectedFaults() throws SQLException {
        for (String trigger : triggers) {
            execute("DROP TRIGGER IF EXISTS \"" + trigger + "\"");
        }
        triggers.clear();
    }

    // ==================================================================
    // 一般失敗路徑
    // ==================================================================

    @Test
    @DisplayName("一般：notify_driver 失敗（JOIN 正常被取消）→ 取消出貨、付款、訂單")
    void control_notifyDriverFailureCompensatesEverything() throws Exception {
        Forward f = runForwardUntilShipped();
        JsonNode failed = failedWorkflow(f.workflowId, Workflow.WorkflowStatus.FAILED, List.of(
                f.orderTask, f.inventoryTask, f.paymentTask, f.shipTask,
                task("notify_fork_ref", Task.Status.COMPLETED),
                task("notify_driver_ref", Task.Status.FAILED),
                task("notify_customer_ref", Task.Status.CANCELED),
                task("notify_join_ref", Task.Status.CANCELED)));

        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, f.email, CUSTOMER_NAME);

        assertNothingLeft(f.workflowId, run);
    }

    @Test
    @DisplayName("一般：ship_food 失敗（insertShipment 失敗，沒有出貨單）→ 取消付款、訂單，cancel_delivery 為 no-op")
    void control_shipFoodFailureCompensatesPaymentAndOrder() throws Exception {
        Forward f = runForwardUntilPaid();
        failNextShipmentInsert(f.workflowId);
        Task shipTask = shipFood(f.workflowId, TaskResult.Status.FAILED);

        JsonNode failed = failedWorkflow(f.workflowId, Workflow.WorkflowStatus.FAILED, List.of(
                f.orderTask, f.inventoryTask, f.paymentTask, shipTask));
        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, f.email, CUSTOMER_NAME);

        assertNothingLeft(f.workflowId, run);
    }

    @Test
    @DisplayName("一般：check_inventory 失敗 → 取消訂單，其餘 cancel 為 no-op")
    void control_inventoryFailureCancelsOrder() throws Exception {
        String workflowId = SagaTestDb.uniqueId("wf");
        String email = workflowId + "@example.com";
        Task orderTask = orderFood(workflowId, email);
        Task inventoryTask = task("check_inventory_ref", Task.Status.FAILED_WITH_TERMINAL_ERROR);

        JsonNode failed = failedWorkflow(workflowId, Workflow.WorkflowStatus.FAILED,
                List.of(orderTask, inventoryTask));
        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, email, CUSTOMER_NAME);

        assertNothingLeft(workflowId, run);
    }

    // ==================================================================
    // 反例 A（#136）：重試留下的失敗紀錄讓 SWITCH 選錯分支
    // ==================================================================

    @Test
    @DisplayName("缺陷 A（#136）：check_inventory 重試成功後 ship_food 失敗，補償應取消付款與訂單")
    void defectA_retriedCheckInventoryMakesSwitchPickWrongBranch() throws Exception {
        String workflowId = SagaTestDb.uniqueId("wf-a");
        String email = workflowId + "@example.com";
        Task orderTask = orderFood(workflowId, email);
        // 第一次嘗試因暫時性錯誤 FAILED（worker 例外 → SDK 回報 FAILED），Conductor 依 retryCount 3 重試
        Task inventoryAttempt1 = retriedFailure("check_inventory_ref");
        Task inventoryAttempt2 = checkInventory();
        Task paymentTask = makePayment(workflowId);
        failNextShipmentInsert(workflowId);
        Task shipTask = shipFood(workflowId, TaskResult.Status.FAILED);

        JsonNode failed = failedWorkflow(workflowId, Workflow.WorkflowStatus.FAILED, List.of(
                orderTask, inventoryAttempt1, inventoryAttempt2, paymentTask, shipTask));
        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, email, CUSTOMER_NAME);

        assertNothingLeft(workflowId, run);
    }

    // ==================================================================
    // 反例 B（#137）：逾時後完全不補償
    // ==================================================================

    @Test
    @DisplayName("缺陷 B（#137）：make_payment 已扣款但回報逾時（TIMED_OUT），補償應取消付款與訂單")
    void defectB_timedOutTaskIsNotInFailedSetSoNothingIsCompensated() throws Exception {
        String workflowId = SagaTestDb.uniqueId("wf-b");
        String email = workflowId + "@example.com";
        Task orderTask = orderFood(workflowId, email);
        Task inventoryTask = checkInventory();
        // worker 已完成扣款，但結果沒有在 responseTimeoutSeconds 內回報 → Conductor 標成 TIMED_OUT
        makePayment(workflowId);
        Task paymentTask = task("make_payment_ref", Task.Status.TIMED_OUT);

        JsonNode failed = failedWorkflow(workflowId, Workflow.WorkflowStatus.TIMED_OUT,
                List.of(orderTask, inventoryTask, paymentTask));
        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, email, CUSTOMER_NAME);

        assertNothingLeft(workflowId, run);
    }

    @Test
    @DisplayName("缺陷 B（#137）：order_food 已建立訂單但回報逾時，補償以 workflowId 取消訂單")
    void defectB_timedOutOrderFoodIsCompensatedByWorkflowId() throws Exception {
        String workflowId = SagaTestDb.uniqueId("wf-b-order");
        String email = workflowId + "@example.com";
        // worker 已建立訂單，但結果沒有回報 → TIMED_OUT 的 task 沒有 outputData.orderId
        orderFood(workflowId, email);
        Task orderTask = task("order_food_ref", Task.Status.TIMED_OUT);

        JsonNode failed = failedWorkflow(workflowId, Workflow.WorkflowStatus.TIMED_OUT, List.of(orderTask));
        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, email, CUSTOMER_NAME);

        assertNothingLeft(workflowId, run);
    }

    // ==================================================================
    // 反例 C（#138）：並行分支失敗時 JOIN 競態（依 conductor-oss 原始碼推論）
    // ==================================================================

    @Test
    @DisplayName("缺陷 C（#138，推論）：notify_driver 失敗且 JOIN 也標成 FAILED，補償應取消出貨、付款、訂單")
    void defectC_failedJoinHidesTheFailedBranch() throws Exception {
        Forward f = runForwardUntilShipped();
        JsonNode failed = failedWorkflow(f.workflowId, Workflow.WorkflowStatus.FAILED, List.of(
                f.orderTask, f.inventoryTask, f.paymentTask, f.shipTask,
                task("notify_fork_ref", Task.Status.COMPLETED),
                task("notify_driver_ref", Task.Status.FAILED),
                task("notify_customer_ref", Task.Status.COMPLETED),
                // 非同步 JOIN 看到失敗的分支後把自己標成 FAILED（Join.java L100-131），
                // 且在 decide 重新載入 workflow 之前已寫回
                task("notify_join_ref", Task.Status.FAILED)));

        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, f.email, CUSTOMER_NAME);

        assertNothingLeft(f.workflowId, run);
    }

    // ==================================================================
    // 反例 D（#139）：SCHEDULED 出貨單殘留
    // ==================================================================

    @Test
    @DisplayName("缺陷 D（#139）：confirmShipment 資料庫錯誤使出貨單停在 SCHEDULED，補償應取消出貨單")
    void defectD_scheduledShipmentIsNotCanceled() throws Exception {
        Forward f = runForwardUntilPaid();
        failShipmentConfirmation(f.workflowId);
        Task shipTask = shipFood(f.workflowId, TaskResult.Status.FAILED);

        JsonNode failed = failedWorkflow(f.workflowId, Workflow.WorkflowStatus.FAILED, List.of(
                f.orderTask, f.inventoryTask, f.paymentTask, shipTask));
        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, f.email, CUSTOMER_NAME);

        assertNothingLeft(f.workflowId, run);
    }

    // ==================================================================
    // 斷言：Saga 補償後不得殘留生效中的副作用（Quint inv_no_dangling_effects）
    // ==================================================================

    private static void assertNothingLeft(String orderId, FailureWorkflowHarness.Run run) throws SQLException {
        String orderStatus = SagaTestDb.queryString(URL,
                "SELECT status FROM orders WHERE orderId = '" + orderId + "'");
        int activePayments = SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM payments WHERE orderId = '" + orderId + "' AND status = 'SUCCESSFUL'");
        int activeShipments = SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM shipments WHERE orderId = '" + orderId
                        + "' AND status IN ('SCHEDULED', 'CONFIRMED')");
        assertAll(run.toString(),
                () -> assertEquals("COMPLETED", run.status(), "補償 workflow 應完成。" + run),
                () -> assertEquals(orderId, run.extracted().path("orderId").asText(),
                        "補償 workflow 應取消這筆訂單。" + run),
                () -> assertEquals("CANCELLED", orderStatus, "訂單應被取消。" + run),
                () -> assertEquals(0, activePayments, "不得殘留 SUCCESSFUL 付款。" + run),
                () -> assertEquals(0, activeShipments, "不得殘留 SCHEDULED/CONFIRMED 出貨單。" + run));
    }

    // ==================================================================
    // 正向步驟：呼叫真實 worker
    // ==================================================================

    private record Forward(String workflowId, String email, Task orderTask, Task inventoryTask,
                           Task paymentTask, Task shipTask) {
    }

    private Forward runForwardUntilPaid() {
        String workflowId = SagaTestDb.uniqueId("wf");
        String email = workflowId + "@example.com";
        Task orderTask = orderFood(workflowId, email);
        return new Forward(workflowId, email, orderTask, checkInventory(), makePayment(workflowId), null);
    }

    private Forward runForwardUntilShipped() {
        Forward f = runForwardUntilPaid();
        return new Forward(f.workflowId, f.email, f.orderTask, f.inventoryTask, f.paymentTask,
                shipFood(f.workflowId, TaskResult.Status.COMPLETED));
    }

    /** order_food 的輸入 orderId 來自 {@code ${workflow.workflowId}}。 */
    private Task orderFood(String workflowId, String email) {
        OrderRequest request = new OrderRequest();
        request.setOrderId(workflowId);
        request.setCustomerEmail(email);
        request.setCustomerName(CUSTOMER_NAME);
        request.setCustomerContact("+886912345678");
        request.setRestaurantId(1);
        request.setItems(new ArrayList<>(Arrays.asList(burger())));
        request.setNotes(new ArrayList<>());
        request.setDeliveryAddress("台北市信義區信義路五段7號");
        TaskResult result = workers.orderFoodTask(request);
        assertEquals(TaskResult.Status.COMPLETED, result.getStatus(), "前置條件：order_food 應成功");
        assertEquals(workflowId, result.getOutputData().get("orderId"), "前置條件：訂單 id 應等於 workflowId");
        return task("order_food_ref", Task.Status.COMPLETED, result.getOutputData());
    }

    private Task checkInventory() {
        CheckInventoryRequest request = new CheckInventoryRequest();
        request.setRestaurantId(1);
        request.setItems(new ArrayList<>(Arrays.asList(burger())));
        TaskResult result = workers.checkInventoryTask(request);
        assertEquals(TaskResult.Status.COMPLETED, result.getStatus(), "前置條件：check_inventory 應成功");
        return task("check_inventory_ref", Task.Status.COMPLETED);
    }

    private Task makePayment(String orderId) {
        PaymentDetails details = new PaymentDetails();
        details.setNumber("4111111111111111");
        details.setExpiry(YearMonth.now().plusYears(2).format(DateTimeFormatter.ofPattern("MM/yyyy")));
        details.setCvv(123);
        PaymentMethod method = new PaymentMethod();
        method.setType("Credit Card");
        method.setDetails(details);
        PaymentRequest request = new PaymentRequest();
        request.setOrderId(orderId);
        request.setCustomerId(1);
        request.setAmount(25f);
        request.setMethod(method);
        TaskResult result = workers.makePaymentTask(request);
        assertEquals(TaskResult.Status.COMPLETED, result.getStatus(), "前置條件：make_payment 應成功");
        return task("make_payment_ref", Task.Status.COMPLETED, result.getOutputData());
    }

    private Task shipFood(String orderId, TaskResult.Status expected) {
        ShippingRequest request = new ShippingRequest();
        request.setOrderId(orderId);
        request.setDeliveryAddress("台北市信義區信義路五段7號");
        request.setDeliveryInstructions("放門口");
        TaskResult result = workers.shipFoodTask(request);
        assertEquals(expected, result.getStatus(), "前置條件：ship_food 應回報 " + expected);
        Task.Status status = Task.Status.valueOf(result.getStatus().name());
        return task("ship_food_ref", status, result.getOutputData());
    }

    private static FoodItem burger() {
        FoodItem item = new FoodItem();
        item.setItem("burger");
        item.setQuantity(1);
        return item;
    }

    // ==================================================================
    // 以 SQLite trigger 在真實程式碼路徑注入資料庫錯誤（只影響指定 orderId）
    // ==================================================================

    /** insertShipment 拋出 SQLException → createShipment 回傳 0 → ship_food FAILED，沒有出貨單。 */
    private void failNextShipmentInsert(String orderId) throws SQLException {
        String name = "inject_insert_fail_" + orderId;
        execute("CREATE TRIGGER \"" + name + "\" BEFORE INSERT ON shipments WHEN NEW.orderId = '" + orderId
                + "' BEGIN SELECT RAISE(ABORT, 'injected: insert shipment failed'); END");
        triggers.add(name);
    }

    /** confirmShipment 拋出 SQLException → DataAccessException → ship_food FAILED，出貨單停在 SCHEDULED。 */
    private void failShipmentConfirmation(String orderId) throws SQLException {
        String name = "inject_confirm_fail_" + orderId;
        execute("CREATE TRIGGER \"" + name + "\" BEFORE UPDATE ON shipments WHEN NEW.orderId = '" + orderId
                + "' AND NEW.status = 'CONFIRMED' BEGIN SELECT RAISE(ABORT, 'injected: confirm shipment failed'); END");
        triggers.add(name);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }
}
