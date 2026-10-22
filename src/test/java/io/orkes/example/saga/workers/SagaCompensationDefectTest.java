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
 * 重現 Quint 規格（specs/saga.qnt）在現行補償 workflow 找到的反例 A–D。
 *
 * <p>每個反例對應 specs/saga_test.qnt 中同名的 {@code defect_*} 情境：
 * 正向步驟呼叫真實的 worker 寫入 SQLite；需要的資料庫錯誤以 SQLite trigger 注入到真實程式碼路徑；
 * Conductor 的行為（重試、逾時、JOIN 競態）以 {@link Workflow}／{@link Task} 的執行紀錄表達；
 * 補償 workflow 由 {@link FailureWorkflowHarness} 照版控中的 JSON 執行。
 *
 * <p><b>defect 測試斷言的是正確的 Saga 一致性</b>（補償後不殘留生效中的訂單、付款、出貨單），
 * 因此在缺陷修正前會失敗；失敗訊息會附上補償 workflow 實際選到的 SWITCH 分支。
 * {@code control_*} 測試是對照組：同一套 harness 在現行 workflow 能正確處理的情境下應通過，
 * 用來確認紅燈來自缺陷，而不是 harness 本身。
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
    // 對照組：現行 workflow 能正確補償
    // ==================================================================

    @Test
    @DisplayName("對照組：notify_driver 失敗（JOIN 正常被取消）→ 取消出貨、付款、訂單")
    void control_notifyDriverFailureCompensatesEverything() throws Exception {
        Forward f = runForwardUntilShipped();
        JsonNode failed = failedWorkflow(Workflow.WorkflowStatus.FAILED, List.of(
                f.orderTask, f.inventoryTask, f.paymentTask, f.shipTask,
                task("notify_fork_ref", Task.Status.COMPLETED),
                task("notify_driver_ref", Task.Status.FAILED),
                task("notify_customer_ref", Task.Status.CANCELED),
                task("notify_join_ref", Task.Status.CANCELED)));

        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, f.email, CUSTOMER_NAME);

        assertEquals("notify_driver_ref", run.switchKey(), run.toString());
        assertNothingLeft(f.orderId, run);
    }

    @Test
    @DisplayName("對照組：ship_food 失敗（insertShipment 失敗）→ 取消付款、訂單")
    void control_shipFoodFailureCompensatesPaymentAndOrder() throws Exception {
        Forward f = runForwardUntilPaid();
        failNextShipmentInsert(f.orderId);
        Task shipTask = shipFood(f.orderId, TaskResult.Status.FAILED);

        JsonNode failed = failedWorkflow(Workflow.WorkflowStatus.FAILED, List.of(
                f.orderTask, f.inventoryTask, f.paymentTask, shipTask));
        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, f.email, CUSTOMER_NAME);

        assertEquals("ship_food_ref", run.switchKey(), run.toString());
        assertNothingLeft(f.orderId, run);
    }

    // ==================================================================
    // 反例 A：重試留下的失敗紀錄讓 SWITCH 選錯分支
    // ==================================================================

    @Test
    @DisplayName("缺陷 A：check_inventory 重試成功後 ship_food 失敗，補償應取消付款與訂單")
    void defectA_retriedCheckInventoryMakesSwitchPickWrongBranch() throws Exception {
        String email = SagaTestDb.uniqueId("defect-a") + "@example.com";
        Task orderTask = orderFood(email);
        String orderId = orderIdOf(orderTask);
        // 第一次嘗試因暫時性錯誤 FAILED（worker 例外 → SDK 回報 FAILED），Conductor 依 retryCount 3 重試
        Task inventoryAttempt1 = retriedFailure("check_inventory_ref");
        Task inventoryAttempt2 = checkInventory();
        Task paymentTask = makePayment(orderId);
        failNextShipmentInsert(orderId);
        Task shipTask = shipFood(orderId, TaskResult.Status.FAILED);

        JsonNode failed = failedWorkflow(Workflow.WorkflowStatus.FAILED, List.of(
                orderTask, inventoryAttempt1, inventoryAttempt2, paymentTask, shipTask));
        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, email, CUSTOMER_NAME);

        assertNothingLeft(orderId, run);
    }

    // ==================================================================
    // 反例 B：逾時後完全不補償
    // ==================================================================

    @Test
    @DisplayName("缺陷 B：make_payment 已扣款但回報逾時（TIMED_OUT），補償應取消付款與訂單")
    void defectB_timedOutTaskIsNotInFailedSetSoNothingIsCompensated() throws Exception {
        String email = SagaTestDb.uniqueId("defect-b") + "@example.com";
        Task orderTask = orderFood(email);
        String orderId = orderIdOf(orderTask);
        Task inventoryTask = checkInventory();
        // worker 已完成扣款，但結果沒有在 responseTimeoutSeconds 內回報 → Conductor 標成 TIMED_OUT
        makePayment(orderId);
        Task paymentTask = task("make_payment_ref", Task.Status.TIMED_OUT);

        JsonNode failed = failedWorkflow(Workflow.WorkflowStatus.TIMED_OUT,
                List.of(orderTask, inventoryTask, paymentTask));
        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, email, CUSTOMER_NAME);

        assertNothingLeft(orderId, run);
    }

    // ==================================================================
    // 反例 C：並行分支失敗時 JOIN 競態（依 conductor-oss 原始碼推論）
    // ==================================================================

    @Test
    @DisplayName("缺陷 C（推論）：notify_driver 失敗且 JOIN 也標成 FAILED，補償應取消出貨、付款、訂單")
    void defectC_failedJoinHidesTheFailedBranch() throws Exception {
        Forward f = runForwardUntilShipped();
        JsonNode failed = failedWorkflow(Workflow.WorkflowStatus.FAILED, List.of(
                f.orderTask, f.inventoryTask, f.paymentTask, f.shipTask,
                task("notify_fork_ref", Task.Status.COMPLETED),
                task("notify_driver_ref", Task.Status.FAILED),
                task("notify_customer_ref", Task.Status.COMPLETED),
                // 非同步 JOIN 看到失敗的分支後把自己標成 FAILED（Join.java L100-131），
                // 且在 decide 重新載入 workflow 之前已寫回
                task("notify_join_ref", Task.Status.FAILED)));

        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, f.email, CUSTOMER_NAME);

        assertNothingLeft(f.orderId, run);
    }

    // ==================================================================
    // 反例 D：SCHEDULED 出貨單殘留
    // ==================================================================

    @Test
    @DisplayName("缺陷 D：confirmShipment 資料庫錯誤使出貨單停在 SCHEDULED，補償應取消出貨單")
    void defectD_scheduledShipmentIsNotCanceled() throws Exception {
        Forward f = runForwardUntilPaid();
        failShipmentConfirmation(f.orderId);
        Task shipTask = shipFood(f.orderId, TaskResult.Status.FAILED);

        JsonNode failed = failedWorkflow(Workflow.WorkflowStatus.FAILED, List.of(
                f.orderTask, f.inventoryTask, f.paymentTask, shipTask));
        FailureWorkflowHarness.Run run = failureWorkflow.run(failed, f.email, CUSTOMER_NAME);

        assertNothingLeft(f.orderId, run);
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
                () -> assertEquals("CANCELLED", orderStatus, "訂單應被取消。" + run),
                () -> assertEquals(0, activePayments, "不得殘留 SUCCESSFUL 付款。" + run),
                () -> assertEquals(0, activeShipments, "不得殘留 SCHEDULED/CONFIRMED 出貨單。" + run));
    }

    // ==================================================================
    // 正向步驟：呼叫真實 worker
    // ==================================================================

    private record Forward(String email, String orderId, Task orderTask, Task inventoryTask,
                           Task paymentTask, Task shipTask) {
    }

    private Forward runForwardUntilPaid() throws SQLException {
        String email = SagaTestDb.uniqueId("buyer") + "@example.com";
        Task orderTask = orderFood(email);
        String orderId = orderIdOf(orderTask);
        return new Forward(email, orderId, orderTask, checkInventory(), makePayment(orderId), null);
    }

    private Forward runForwardUntilShipped() throws SQLException {
        Forward f = runForwardUntilPaid();
        return new Forward(f.email, f.orderId, f.orderTask, f.inventoryTask, f.paymentTask,
                shipFood(f.orderId, TaskResult.Status.COMPLETED));
    }

    private Task orderFood(String email) {
        OrderRequest request = new OrderRequest();
        request.setCustomerEmail(email);
        request.setCustomerName(CUSTOMER_NAME);
        request.setCustomerContact("+886912345678");
        request.setRestaurantId(1);
        request.setItems(new ArrayList<>(Arrays.asList(burger())));
        request.setNotes(new ArrayList<>());
        request.setDeliveryAddress("台北市信義區信義路五段7號");
        TaskResult result = workers.orderFoodTask(request);
        assertEquals(TaskResult.Status.COMPLETED, result.getStatus(), "前置條件：order_food 應成功");
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

    private static String orderIdOf(Task orderTask) {
        return String.valueOf(orderTask.getOutputData().get("orderId"));
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
