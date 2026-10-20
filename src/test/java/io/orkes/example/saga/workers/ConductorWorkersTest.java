package io.orkes.example.saga.workers;

import com.netflix.conductor.common.metadata.tasks.TaskResult;
import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.dao.PaymentsDAO;
import io.orkes.example.saga.dao.ShipmentDAO;
import io.orkes.example.saga.pojos.CancelRequest;
import io.orkes.example.saga.pojos.CheckInventoryRequest;
import io.orkes.example.saga.pojos.CustomerNotificationRequest;
import io.orkes.example.saga.pojos.DriverNotificationRequest;
import io.orkes.example.saga.pojos.FoodItem;
import io.orkes.example.saga.pojos.Order;
import io.orkes.example.saga.pojos.OrderRequest;
import io.orkes.example.saga.pojos.Payment;
import io.orkes.example.saga.pojos.PaymentDetails;
import io.orkes.example.saga.pojos.PaymentMethod;
import io.orkes.example.saga.pojos.PaymentRequest;
import io.orkes.example.saga.pojos.Shipment;
import io.orkes.example.saga.pojos.ShippingRequest;
import io.orkes.example.saga.service.OrderService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConductorWorkers} 的測試（Issue #9）：saga 各個步驟在 worker 端輸出的狀態，
 * 正是協調器決定「重試」或「觸發補償」的依據——
 * {@code FAILED} 表示可重試（由 Conductor 的 retry policy 接管），
 * {@code FAILED_WITH_TERMINAL_ERROR} 表示不可重試、直接轉入補償步驟。
 *
 * <p>真正的重試/逾時計時由 Conductor server 端的工作流定義執行（需外部服務，本 Issue 排除 E2E），
 * 這裡涵蓋的是 worker 提供給它的那一半契約。
 */
class ConductorWorkersTest {

    private static final String URL = SagaTestDb.APP_DB_URL;

    private ConductorWorkers workers;

    @BeforeAll
    static void prepareDatabase() {
        SagaTestDb.createAppSchema();
    }

    @BeforeEach
    void setUp() {
        workers = new ConductorWorkers();
    }

    private static FoodItem item(String name, int quantity) {
        FoodItem foodItem = new FoodItem();
        foodItem.setItem(name);
        foodItem.setQuantity(quantity);
        return foodItem;
    }

    /** 一筆合法的外送訂單請求。 */
    private static OrderRequest orderRequest() {
        OrderRequest request = new OrderRequest();
        request.setCustomerEmail(SagaTestDb.uniqueId("buyer") + "@example.com");
        request.setCustomerName("測試買家");
        request.setCustomerContact("+12126781345");
        request.setRestaurantId(1);
        request.setItems(new ArrayList<>(Arrays.asList(item("burger", 1))));
        request.setNotes(new ArrayList<>());
        request.setDeliveryAddress("1693 Alice Court, Annapolis MD 21401");
        return request;
    }

    private static PaymentRequest paymentRequest(String orderId, String expiry) {
        PaymentDetails details = new PaymentDetails();
        details.setNumber("4111111111111111");
        details.setExpiry(expiry);
        details.setCvv(123);

        PaymentMethod method = new PaymentMethod();
        method.setType("Credit Card");
        method.setDetails(details);

        PaymentRequest request = new PaymentRequest();
        request.setOrderId(orderId);
        request.setCustomerId(1);
        request.setAmount(25f);
        request.setMethod(method);
        return request;
    }

    private static String futureExpiry() {
        return YearMonth.now().plusYears(2).format(DateTimeFormatter.ofPattern("MM/yyyy"));
    }

    @Test
    @DisplayName("happy path：order_food 完成並把 orderId 交給後續步驟")
    void orderFoodCompletesAndPublishesOrderId() {
        TaskResult result = workers.orderFoodTask(orderRequest());

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus());
        String orderId = String.valueOf(result.getOutputData().get("orderId"));
        assertNotNull(orderId);
        assertTrue(orderId.length() > 8, "應輸出訂單 id，實際為: " + orderId);

        Order stored = OrderService.getOrder(orderId);
        assertEquals(orderId, stored.getOrderId());
        assertEquals(Order.Status.PENDING, stored.getStatus(), "新訂單應以 PENDING 進入狀態機");
    }

    @Test
    @DisplayName("重試語義：order_food 寫入被拒（送件地址缺失）時回傳 FAILED，讓工作流依 policy 重試")
    void orderFoodFailsRetryablyWhenInsertIsRejected() {
        OrderRequest broken = orderRequest();
        broken.setDeliveryAddress(null);

        TaskResult result = workers.orderFoodTask(broken);

        assertEquals(TaskResult.Status.FAILED, result.getStatus(),
                "可修正的失敗應是 FAILED（可重試），而不是終端錯誤");
        assertTrue(result.getOutputData().isEmpty(), "失敗時不輸出 orderId");
    }

    @Test
    @DisplayName("happy path：check_inventory 認得種子餐廳")
    void checkInventoryCompletesForKnownRestaurant() {
        CheckInventoryRequest request = new CheckInventoryRequest();
        request.setRestaurantId(1);
        request.setItems(new ArrayList<>(Arrays.asList(item("burger", 1))));

        assertEquals(TaskResult.Status.COMPLETED, workers.checkInventoryTask(request).getStatus());
    }

    @Test
    @DisplayName("補償觸發：check_inventory 查無餐廳時回傳終端錯誤並給出原因，不再重試")
    void checkInventoryFailsWithTerminalErrorForUnknownRestaurant() {
        CheckInventoryRequest request = new CheckInventoryRequest();
        request.setRestaurantId(999);
        request.setItems(new ArrayList<>());

        TaskResult result = workers.checkInventoryTask(request);

        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, result.getStatus(),
                "餐廳不存在不該靠重試解決");
        assertEquals("Restaurant is closed", result.getReasonForIncompletion());
    }

    @Test
    @DisplayName("happy path：make_payment 成功時輸出 SUCCESSFUL 供工作流繼續下一步")
    void makePaymentCompletesForValidCard() {
        String orderId = SagaTestDb.uniqueId("order");

        TaskResult result = workers.makePaymentTask(paymentRequest(orderId, futureExpiry()));

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus());
        assertEquals(orderId, result.getOutputData().get("orderId"));
        assertEquals("SUCCESSFUL", result.getOutputData().get("paymentStatus"));
        assertNull(result.getOutputData().get("error"));
    }

    @Test
    @DisplayName("補償觸發：make_payment 因卡片逾期失敗時回傳終端錯誤，並把原因交給補償步驟")
    void makePaymentFailsTerminallyForExpiredCard() {
        String orderId = SagaTestDb.uniqueId("order");

        TaskResult result = workers.makePaymentTask(paymentRequest(orderId, "01/2020"));

        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, result.getStatus());
        assertEquals("FAILED", result.getOutputData().get("paymentStatus"));
        assertEquals("Expired payment method:01/2020", result.getOutputData().get("error"));
    }

    /** 一筆可成功指派司機的外送請求。 */
    private static ShippingRequest shippingRequest(String orderId) {
        ShippingRequest request = new ShippingRequest();
        request.setOrderId(orderId);
        request.setDeliveryAddress("1693 Alice Court, Annapolis MD 21401");
        request.setDeliveryInstructions("放門口");
        return request;
    }

    @Test
    @DisplayName("重試語義：ship_food 寫外送單失敗時回傳 FAILED（司機指派可再嘗試）")
    void shipFoodFailsRetryablyWhenShipmentInsertIsRejected() {
        ShippingRequest request = shippingRequest(SagaTestDb.uniqueId("order"));
        request.setDeliveryAddress(null);

        TaskResult result = workers.shipFoodTask(request);

        assertEquals(TaskResult.Status.FAILED, result.getStatus());
        assertFalse(result.getOutputData().containsKey("driverId"),
                "失敗時不得輸出 driverId，避免下游把未指派的司機當成有效值");
    }

    @Test
    @DisplayName("契約：ship_food 成功時把 createShipment 指派的 driverId 放進 output")
    void shipFoodPublishesDriverIdOnSuccess() throws Exception {
        ShippingRequest request = shippingRequest(SagaTestDb.uniqueId("order"));

        TaskResult result = workers.shipFoodTask(request);

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus());
        Object driverId = result.getOutputData().get("driverId");
        assertNotNull(driverId, "ship_food 成功時必須輸出 driverId 供 notify_driver 綁定");
        assertEquals(
                SagaTestDb.queryString(URL,
                        "SELECT driverId FROM shipments WHERE orderId = '" + request.getOrderId() + "'"),
                String.valueOf(driverId),
                "output 的 driverId 必須是 createShipment 實際指派並持久化的司機");
    }

    @Test
    @DisplayName("契約：notify_driver 可直接綁定 ship_food 輸出的 driverId")
    void shipFoodOutputFeedsDriverNotification() {
        ShippingRequest request = shippingRequest(SagaTestDb.uniqueId("order"));

        TaskResult shipResult = workers.shipFoodTask(request);
        assertEquals(TaskResult.Status.COMPLETED, shipResult.getStatus());
        Object driverId = shipResult.getOutputData().get("driverId");
        assertNotNull(driverId, "notify_driver 的 driverId 必須有生產者");

        DriverNotificationRequest notification = new DriverNotificationRequest();
        notification.setDriverId((Integer) driverId);
        notification.setOrderId(request.getOrderId());
        notification.setDropOff(request.getDeliveryAddress());
        notification.setPickUp("restaurant");

        assertNotNull(workers.checkForDriverNotifications(notification));
    }

    @Test
    @DisplayName("補償/回滾：cancel_order 成功時回 COMPLETED 並把訂單轉為 CANCELLED")
    void cancelOrderRollsBackCreatedOrder() {
        String orderId = String.valueOf(workers.orderFoodTask(orderRequest()).getOutputData().get("orderId"));
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        TaskResult result = workers.cancelOrderTask(cancelRequest);

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus(), "補償成功必須回報 COMPLETED");
        assertTrue(result.getOutputData().isEmpty(), "補償步驟成功時回傳空輸出，實際為: " + result.getOutputData());
        assertEquals(Order.Status.CANCELLED, OrderService.getOrder(orderId).getStatus());
    }

    @Test
    @DisplayName("補償/回滾：cancel_payment 成功時回 COMPLETED 並把付款改為 CANCELED")
    void cancelPaymentRollsBackSuccessfulPayment() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        workers.makePaymentTask(paymentRequest(orderId, futureExpiry()));
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        TaskResult result = workers.cancelPaymentTask(cancelRequest);

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus(), "補償成功必須回報 COMPLETED");
        assertEquals("CANCELED", SagaTestDb.queryString(URL,
                "SELECT status FROM payments WHERE orderId = '" + orderId + "'"));
    }

    @Test
    @DisplayName("補償/回滾：cancel_delivery 成功時回 COMPLETED 並把外送單改為 CANCELED")
    void cancelDeliveryRollsBackScheduledShipment() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        Shipment shipment = new Shipment();
        shipment.setOrderId(orderId);
        shipment.setDriverId(1);
        shipment.setDeliveryAddress("1693 Alice Court, Annapolis MD 21401");
        shipment.setDeliveryInstructions("放門口");
        assertTrue(new ShipmentDAO(URL).insertShipment(shipment));
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        TaskResult result = workers.cancelDeliveryTask(cancelRequest);

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus(), "補償成功必須回報 COMPLETED");
        assertEquals("CANCELED", SagaTestDb.queryString(URL,
                "SELECT status FROM shipments WHERE orderId = '" + orderId + "'"));
    }

    /**
     * Issue #34（G3）：補償失敗必須讓 task 失敗並能被告警（人類裁定，TB1 報告 §9 第 4 題），
     * 語意為——成功 → {@code COMPLETED}；資料庫例外 → {@code FAILED}（可重試）；
     * 目標不存在 → {@code FAILED_WITH_TERMINAL_ERROR}；失敗原因寫進 {@code reasonForIncompletion}。
     *
     * <p>以下測試重現既有缺陷（回傳 Map 一律 COMPLETED、DAO 吞掉 SQLException），
     * 在修復（02-impl）前為紅燈，於 01-test 層以 {@code @Disabled} 交付（該層單獨 CI 綠燈），
     * 本層修復完成後 un-skip，於本層 CI 驗證綠燈。
     */
    @Test
    @DisplayName("失敗語意：cancel_order 對不存在的 orderId 不得回報成功，應回終端錯誤並給原因")
    void cancelOrderFailsTerminallyForUnknownOrderId() {
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(SagaTestDb.uniqueId("ghost-order"));

        TaskResult result = workers.cancelOrderTask(cancelRequest);

        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, result.getStatus(),
                "查無訂單不是可重試的失敗，不得標成 COMPLETED");
        assertNotNull(result.getReasonForIncompletion(), "失敗原因必須寫進 reasonForIncompletion，供告警讀取");
        assertTrue(result.getReasonForIncompletion().contains(cancelRequest.getOrderId()),
                "原因應指出是哪一筆訂單找不到，實際為: " + result.getReasonForIncompletion());
    }

    @Test
    @DisplayName("失敗語意：cancel_payment 查無可取消的付款時應回終端錯誤並給原因")
    void cancelPaymentFailsTerminallyWhenNoPaymentExists() {
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(SagaTestDb.uniqueId("ghost-order"));

        TaskResult result = workers.cancelPaymentTask(cancelRequest);

        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, result.getStatus(),
                "沒有可取消的付款時不得標成 COMPLETED");
        assertNotNull(result.getReasonForIncompletion(), "失敗原因必須寫進 reasonForIncompletion，供告警讀取");
        assertTrue(result.getReasonForIncompletion().contains(cancelRequest.getOrderId()),
                "原因應指出是哪一筆訂單查無付款，實際為: " + result.getReasonForIncompletion());
    }

    @Test
    @DisplayName("失敗語意：cancel_delivery 查無可取消的外送單時應回終端錯誤並給原因")
    void cancelDeliveryFailsTerminallyWhenNoShipmentExists() {
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(SagaTestDb.uniqueId("ghost-order"));

        TaskResult result = workers.cancelDeliveryTask(cancelRequest);

        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, result.getStatus(),
                "沒有可取消的外送單時不得標成 COMPLETED");
        assertNotNull(result.getReasonForIncompletion(), "失敗原因必須寫進 reasonForIncompletion，供告警讀取");
        assertTrue(result.getReasonForIncompletion().contains(cancelRequest.getOrderId()),
                "原因應指出是哪一筆訂單查無外送單，實際為: " + result.getReasonForIncompletion());
    }

    @Test
    @DisplayName("失敗語意：cancel_payment 遭遇資料庫例外時回 FAILED（可重試）並帶原因")
    void cancelPaymentFailsRetryablyOnDatabaseError() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        workers.makePaymentTask(paymentRequest(orderId, futureExpiry()));

        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        TaskResult result = withAppDatabaseUnavailable(() -> workers.cancelPaymentTask(cancelRequest));

        assertEquals(TaskResult.Status.FAILED, result.getStatus(),
                "資料庫例外是可修正的失敗，應讓 Conductor retry policy 接管，而不是 terminal error 或 COMPLETED");
        assertNotNull(result.getReasonForIncompletion(), "失敗原因必須寫進 reasonForIncompletion，供告警讀取");
    }

    @Test
    @DisplayName("失敗語意：cancel_delivery 遭遇資料庫例外時回 FAILED（可重試）並帶原因")
    void cancelDeliveryFailsRetryablyOnDatabaseError() throws Exception {
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(SagaTestDb.uniqueId("order"));

        TaskResult result = withAppDatabaseUnavailable(() -> workers.cancelDeliveryTask(cancelRequest));

        assertEquals(TaskResult.Status.FAILED, result.getStatus(),
                "資料庫例外必須讓 task 失敗且可重試，不得被 DAO 吞掉後標成 COMPLETED");
        assertNotNull(result.getReasonForIncompletion(), "失敗原因必須寫進 reasonForIncompletion，供告警讀取");
    }

    @Test
    @DisplayName("失敗語意：cancel_order 遭遇資料庫例外時回 FAILED（可重試）而非查無訂單的終端錯誤")
    void cancelOrderFailsRetryablyOnDatabaseError() throws Exception {
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(SagaTestDb.uniqueId("order"));

        TaskResult result = withAppDatabaseUnavailable(() -> workers.cancelOrderTask(cancelRequest));

        assertEquals(TaskResult.Status.FAILED, result.getStatus(),
                "資料庫壞掉不等於訂單不存在，應回可重試的 FAILED，不得誤判為終端錯誤");
        assertNotNull(result.getReasonForIncompletion(), "失敗原因必須寫進 reasonForIncompletion，供告警讀取");
    }

    @Test
    @DisplayName("多筆付款：cancel_payment 對同一 orderId 的多筆付款要全部取消")
    void cancelPaymentCancelsEveryPaymentOfTheOrder() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        PaymentsDAO paymentsDAO = new PaymentsDAO(URL);
        assertTrue(paymentsDAO.insertPayment(recordedPayment(orderId)).isEmpty());
        assertTrue(paymentsDAO.insertPayment(recordedPayment(orderId)).isEmpty());
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        TaskResult result = workers.cancelPaymentTask(cancelRequest);

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus());
        assertEquals(2, SagaTestDb.count(URL, "SELECT COUNT(*) FROM payments WHERE orderId = '"
                + orderId + "' AND status = 'CANCELED'"), "同一訂單的所有付款都必須被補償取消");
        assertEquals(0, SagaTestDb.count(URL, "SELECT COUNT(*) FROM payments WHERE orderId = '"
                + orderId + "' AND status != 'CANCELED'"), "不得留下未取消的付款");
    }

    /** 一筆已成立（SUCCESSFUL）的付款紀錄，供直接寫入測試資料庫。 */
    private static Payment recordedPayment(String orderId) {
        PaymentDetails details = new PaymentDetails();
        details.setNumber("4111111111111111");
        details.setExpiry("12/2099");
        details.setCvv(123);

        PaymentMethod method = new PaymentMethod();
        method.setType("Credit Card");
        method.setDetails(details);

        Payment payment = new Payment();
        payment.setPaymentId(SagaTestDb.uniqueId("payment"));
        payment.setOrderId(orderId);
        payment.setAmount(10.0);
        payment.setPaymentMethod(method);
        payment.setStatus(Payment.Status.SUCCESSFUL);
        return payment;
    }

    /**
     * 暫時移開應用資料庫檔（{@code food_delivery.db}）以模擬「資料庫不可用」，
     * 讓 DAO 在連線/語句層碰到真實的 {@code SQLException}，結束後原樣還原。
     */
    private static TaskResult withAppDatabaseUnavailable(SqlStage stage) throws Exception {
        Path db = Paths.get(SagaTestDb.filePathOf(URL));
        Path backupDir = Paths.get("build", "test-db-backup");
        Files.createDirectories(backupDir);
        Path backup = backupDir.resolve("food_delivery-" + UUID.randomUUID() + ".db");
        boolean existed = Files.exists(db);
        if (existed) {
            Files.move(db, backup);
        }
        try {
            return stage.run();
        } finally {
            if (existed && Files.exists(backup)) {
                if (Files.exists(db)) {
                    Files.delete(db);
                }
                Files.move(backup, db);
            }
            SagaTestDb.createAppSchema();
        }
    }

    /** 資料庫不可用期間要執行的 worker 呼叫。 */
    private interface SqlStage {
        TaskResult run() throws Exception;
    }

    @Test
    @DisplayName("契約：notify_customer 接受 workflow 起始輸入可提供的最小物件，並標示未寄送")
    void notifyCustomerAcceptsMinimalCustomerInput() {
        CustomerNotificationRequest request = new CustomerNotificationRequest();
        request.setOrderId(SagaTestDb.uniqueId("order"));
        request.setCustomerEmail("buyer@example.com");
        request.setCustomerName("測試買家");

        Map<String, Object> output = workers.checkForCustomerNotifications(request);

        assertEquals(Boolean.FALSE, output.get("notified"),
                "notify_customer 是 placeholder，output 必須明確標示未寄送");
        assertEquals(request.getOrderId(), output.get("orderId"));
    }

    @Test
    @DisplayName("通知步驟不實際寄送：notify_driver 無輸出，notify_customer 標示 notified=false")
    void notificationStepsArePlaceholders() {
        assertTrue(workers.checkForDriverNotifications(new DriverNotificationRequest()).isEmpty());

        CustomerNotificationRequest request = new CustomerNotificationRequest();
        request.setOrderId(SagaTestDb.uniqueId("order"));
        request.setCustomerEmail("buyer@example.com");
        request.setCustomerName("測試買家");
        Map<String, Object> output = workers.checkForCustomerNotifications(request);
        assertEquals(Boolean.FALSE, output.get("notified"));
        assertEquals(request.getOrderId(), output.get("orderId"));
    }
}
