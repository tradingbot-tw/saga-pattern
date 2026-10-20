package io.orkes.example.saga.workers;

import com.netflix.conductor.common.metadata.tasks.TaskResult;
import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.dao.ShipmentDAO;
import io.orkes.example.saga.pojos.CancelRequest;
import io.orkes.example.saga.pojos.CheckInventoryRequest;
import io.orkes.example.saga.pojos.CustomerNotificationRequest;
import io.orkes.example.saga.pojos.DriverNotificationRequest;
import io.orkes.example.saga.pojos.FoodItem;
import io.orkes.example.saga.pojos.Order;
import io.orkes.example.saga.pojos.OrderRequest;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

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
    @DisplayName("補償/回滾：cancel_order 把已建立的訂單轉為 CANCELLED 並回報 COMPLETED")
    void cancelOrderRollsBackCreatedOrder() {
        String orderId = String.valueOf(workers.orderFoodTask(orderRequest()).getOutputData().get("orderId"));
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        TaskResult result = workers.cancelOrderTask(cancelRequest);

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus());
        assertEquals(Order.Status.CANCELLED, OrderService.getOrder(orderId).getStatus());
    }

    @Test
    @DisplayName("補償失敗（terminal）：cancel_order 查無訂單時不得回報成功")
    @Disabled("Issue #34 紅燈測試：02-impl 讓 cancel_order 對不存在的 orderId 回 FAILED_WITH_TERMINAL_ERROR")
    void cancelOrderFailsTerminallyWhenOrderDoesNotExist() {
        String missingOrderId = SagaTestDb.uniqueId("missing-order");
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(missingOrderId);

        TaskResult result = workers.cancelOrderTask(cancelRequest);

        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, result.getStatus(),
                "查無訂單不是可重試的失敗，應是 terminal error");
        assertNotNull(result.getReasonForIncompletion(), "失敗原因必須寫進 reasonForIncompletion");
        assertTrue(result.getReasonForIncompletion().contains(missingOrderId),
                "原因應指出查無的 orderId，實際為: " + result.getReasonForIncompletion());
    }

    @Test
    @DisplayName("補償/回滾：cancel_payment 把已成立的付款改為 CANCELED 並回報 COMPLETED")
    void cancelPaymentRollsBackSuccessfulPayment() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        workers.makePaymentTask(paymentRequest(orderId, futureExpiry()));
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        TaskResult result = workers.cancelPaymentTask(cancelRequest);

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus());
        assertEquals("CANCELED", SagaTestDb.queryString(URL,
                "SELECT status FROM payments WHERE orderId = '" + orderId + "'"));
    }

    @Test
    @DisplayName("補償失敗（terminal）：cancel_payment 查無付款時必須回 FAILED_WITH_TERMINAL_ERROR")
    @Disabled("Issue #34 紅燈測試：02-impl 讓 cancel_payment 對查無付款回 terminal error")
    void cancelPaymentFailsTerminallyWhenNoPaymentExists() {
        String missingOrderId = SagaTestDb.uniqueId("missing-payment");
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(missingOrderId);

        TaskResult result = workers.cancelPaymentTask(cancelRequest);

        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, result.getStatus());
        assertNotNull(result.getReasonForIncompletion());
        assertTrue(result.getReasonForIncompletion().contains(missingOrderId),
                "原因應指出查無付款的 orderId，實際為: " + result.getReasonForIncompletion());
    }

    @Test
    @DisplayName("補償（多重付款）：cancel_payment 取消同一 orderId 的所有付款")
    @Disabled("Issue #34 紅燈測試：現行 readPayment 只留最後一筆，02-impl 改為整批取消")
    void cancelPaymentCancelsEveryPaymentForOrder() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        workers.makePaymentTask(paymentRequest(orderId, futureExpiry()));
        workers.makePaymentTask(paymentRequest(orderId, futureExpiry()));
        assertEquals(2, SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM payments WHERE orderId = '" + orderId + "'"),
                "前置條件：同一 orderId 應有兩筆付款");

        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);
        TaskResult result = workers.cancelPaymentTask(cancelRequest);

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus());
        assertEquals(0, SagaTestDb.count(URL,
                        "SELECT COUNT(*) FROM payments WHERE orderId = '" + orderId
                                + "' AND status <> 'CANCELED'"),
                "同一 orderId 的每一筆付款都必須被取消");
    }

    @Test
    @DisplayName("補償失敗（可重試）：cancel_payment 遇資料庫寫入失敗時回 FAILED 並帶原因")
    @Disabled("Issue #34 紅燈測試：02-impl 不再吞掉 SQLException，讓 worker 回 FAILED")
    void cancelPaymentFailsRetryablyWhenDatabaseWriteFails() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        workers.makePaymentTask(paymentRequest(orderId, futureExpiry()));
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TRIGGER fail_cancel_payment BEFORE UPDATE ON payments "
                    + "WHEN NEW.status = 'CANCELED' BEGIN SELECT RAISE(ABORT, 'simulated payment db failure'); END");
            try {
                TaskResult result = workers.cancelPaymentTask(cancelRequest);

                assertEquals(TaskResult.Status.FAILED, result.getStatus(),
                        "資料庫例外是可重試的失敗，不是 terminal error");
                assertNotNull(result.getReasonForIncompletion());
                assertTrue(result.getReasonForIncompletion().contains("simulated payment db failure"),
                        "原因應保留資料庫錯誤訊息，實際為: " + result.getReasonForIncompletion());
            } finally {
                stmt.execute("DROP TRIGGER IF EXISTS fail_cancel_payment");
            }
        }
    }

    @Test
    @DisplayName("補償/回滾：cancel_delivery 把已排定的外送單改為 CANCELED 並回報 COMPLETED")
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

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus());
        assertEquals("CANCELED", SagaTestDb.queryString(URL,
                "SELECT status FROM shipments WHERE orderId = '" + orderId + "'"));
    }

    @Test
    @DisplayName("補償失敗（terminal）：cancel_delivery 查無外送單時必須回 FAILED_WITH_TERMINAL_ERROR")
    @Disabled("Issue #34 紅燈測試：02-impl 讓 cancel_delivery 對查無外送單回 terminal error")
    void cancelDeliveryFailsTerminallyWhenNoShipmentExists() {
        String missingOrderId = SagaTestDb.uniqueId("missing-shipment");
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(missingOrderId);

        TaskResult result = workers.cancelDeliveryTask(cancelRequest);

        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, result.getStatus());
        assertNotNull(result.getReasonForIncompletion());
        assertTrue(result.getReasonForIncompletion().contains(missingOrderId),
                "原因應指出查無外送單的 orderId，實際為: " + result.getReasonForIncompletion());
    }

    @Test
    @DisplayName("補償失敗（可重試）：cancel_delivery 遇資料庫寫入失敗時回 FAILED 並帶原因")
    @Disabled("Issue #34 紅燈測試：02-impl 不再吞掉 SQLException，讓 worker 回 FAILED")
    void cancelDeliveryFailsRetryablyWhenDatabaseWriteFails() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        Shipment shipment = new Shipment();
        shipment.setOrderId(orderId);
        shipment.setDriverId(1);
        shipment.setDeliveryAddress("1693 Alice Court, Annapolis MD 21401");
        shipment.setDeliveryInstructions("放門口");
        assertTrue(new ShipmentDAO(URL).insertShipment(shipment));
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TRIGGER fail_cancel_delivery BEFORE UPDATE ON shipments "
                    + "WHEN NEW.status = 'CANCELED' BEGIN SELECT RAISE(ABORT, 'simulated shipment db failure'); END");
            try {
                TaskResult result = workers.cancelDeliveryTask(cancelRequest);

                assertEquals(TaskResult.Status.FAILED, result.getStatus());
                assertNotNull(result.getReasonForIncompletion());
                assertTrue(result.getReasonForIncompletion().contains("simulated shipment db failure"),
                        "原因應保留資料庫錯誤訊息，實際為: " + result.getReasonForIncompletion());
            } finally {
                stmt.execute("DROP TRIGGER IF EXISTS fail_cancel_delivery");
            }
        }
    }

    @Test
    @DisplayName("補償失敗（可重試）：cancel_order 遇資料庫寫入失敗時回 FAILED 並帶原因")
    @Disabled("Issue #34 紅燈測試：02-impl 不再吞掉 SQLException，讓 worker 回 FAILED")
    void cancelOrderFailsRetryablyWhenDatabaseWriteFails() throws Exception {
        String orderId = String.valueOf(workers.orderFoodTask(orderRequest()).getOutputData().get("orderId"));
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TRIGGER fail_cancel_order BEFORE UPDATE ON orders "
                    + "WHEN NEW.status = 'CANCELLED' BEGIN SELECT RAISE(ABORT, 'simulated order db failure'); END");
            try {
                TaskResult result = workers.cancelOrderTask(cancelRequest);

                assertEquals(TaskResult.Status.FAILED, result.getStatus());
                assertNotNull(result.getReasonForIncompletion());
                assertTrue(result.getReasonForIncompletion().contains("simulated order db failure"),
                        "原因應保留資料庫錯誤訊息，實際為: " + result.getReasonForIncompletion());
            } finally {
                stmt.execute("DROP TRIGGER IF EXISTS fail_cancel_order");
            }
        }
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
