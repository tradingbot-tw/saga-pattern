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

    @Test
    @DisplayName("重試語義：ship_food 寫外送單失敗時回傳 FAILED（司機指派可再嘗試）")
    void shipFoodFailsRetryablyWhenShipmentInsertIsRejected() {
        ShippingRequest request = new ShippingRequest();
        request.setOrderId(SagaTestDb.uniqueId("order"));
        request.setDeliveryAddress(null);
        request.setDeliveryInstructions("放門口");

        TaskResult result = workers.shipFoodTask(request);

        assertEquals(TaskResult.Status.FAILED, result.getStatus());
    }

    @Test
    @DisplayName("契約 G1：ship_food 成功時 output 帶出 driverId，且等於 createShipment 的回傳值")
    @Disabled("修復前紅燈（01-test 層）：shipFoodTask 從未 setOutputData，output 不含 driverId。02-impl 修復後啟用——Issue #24 G1")
    void shipFoodPublishesDriverIdOnSuccess() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        ShippingRequest request = new ShippingRequest();
        request.setOrderId(orderId);
        request.setDeliveryAddress("1693 Alice Court, Annapolis MD 21401");
        request.setDeliveryInstructions("放門口");

        TaskResult result = workers.shipFoodTask(request);

        assertEquals(TaskResult.Status.COMPLETED, result.getStatus());
        Object driverId = result.getOutputData().get("driverId");
        assertNotNull(driverId, "成功時 output 應帶出 driverId（notify_driver 需要它），實際 output: "
                + result.getOutputData());
        // 成功路径下 ShipmentService.createShipment 的回傳值即寫入 shipments.driverId 的司機 id，
        // 以持久化值核對 output，等價於「output.driverId == createShipment 回傳值」。
        String persistedDriverId = SagaTestDb.queryString(URL,
                "SELECT driverId FROM shipments WHERE orderId = '" + orderId + "'");
        assertEquals(persistedDriverId, String.valueOf(driverId),
                "output 的 driverId 必須等於 createShipment 的回傳值");
        // createShipment 只有在成功指派司機後才回傳非 0 driverId，此時外送單應為 CONFIRMED。
        assertEquals("CONFIRMED", SagaTestDb.queryString(URL,
                "SELECT status FROM shipments WHERE orderId = '" + orderId + "'"));
    }

    @Test
    @DisplayName("契約 G1：ship_food 失敗時維持 FAILED，且不輸出 driverId")
    void shipFoodOmitsDriverIdWhenShipmentFails() {
        ShippingRequest request = new ShippingRequest();
        request.setOrderId(SagaTestDb.uniqueId("order"));
        request.setDeliveryAddress(null);
        request.setDeliveryInstructions("放門口");

        TaskResult result = workers.shipFoodTask(request);

        assertEquals(TaskResult.Status.FAILED, result.getStatus());
        assertFalse(result.getOutputData().containsKey("driverId"),
                "失敗時 output 不得帶 driverId（無效指派不得餵給 notify_driver），實際 output: "
                        + result.getOutputData());
        assertNull(result.getOutputData().get("driverId"));
    }

    @Test
    @DisplayName("補償/回滾：cancel_order 把已建立的訂單轉為 CANCELLED")
    void cancelOrderRollsBackCreatedOrder() {
        String orderId = String.valueOf(workers.orderFoodTask(orderRequest()).getOutputData().get("orderId"));
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        Map<String, Object> result = workers.cancelOrderTask(cancelRequest);

        assertTrue(result.isEmpty(), "補償步驟成功時回傳空輸出，實際為: " + result);
        assertEquals(Order.Status.CANCELLED, OrderService.getOrder(orderId).getStatus());
    }

    @Test
    @DisplayName("補償/回滾：cancel_payment 把已成立的付款改為 CANCELED")
    void cancelPaymentRollsBackSuccessfulPayment() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        workers.makePaymentTask(paymentRequest(orderId, futureExpiry()));
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        workers.cancelPaymentTask(cancelRequest);

        assertEquals("CANCELED", SagaTestDb.queryString(URL,
                "SELECT status FROM payments WHERE orderId = '" + orderId + "'"));
    }

    @Test
    @DisplayName("補償/回滾：cancel_delivery 把已排定的外送單改為 CANCELED")
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

        workers.cancelDeliveryTask(cancelRequest);

        assertEquals("CANCELED", SagaTestDb.queryString(URL,
                "SELECT status FROM shipments WHERE orderId = '" + orderId + "'"));
    }

    @Test
    @DisplayName("通知步驟目前是不帶輸出的佔實作（notify_driver／notify_customer）")
    void notificationStepsArePlaceholders() {
        assertTrue(workers.checkForDriverNotifications(new DriverNotificationRequest()).isEmpty());
        // Issue #24 G2：notify_customer 的輸入由 Order 改為工作流實際能提供的最小輸入物件；
        // 本斷言只驗證「尚未寄送＝無輸出」的佔實作語意，占位輸出欄位（notified=false）由下方契约測試驗證。
        assertTrue(workers.checkForCustomerNotifications(new CustomerNotificationRequest()).isEmpty());
    }

    @Test
    @DisplayName("契約 G2：notify_customer 接受最小輸入物件並成功回傳，output 明確標示未寄送（notified=false）")
    @Disabled("修復前紅燈（01-test 層）：placeholder 回傳空 map，output 沒有 notified 標示。02-impl 修復後啟用——Issue #24 G2")
    void notifyCustomerAcceptsMinimalInputAndMarksNotNotified() {
        CustomerNotificationRequest request = new CustomerNotificationRequest();
        request.setOrderId(SagaTestDb.uniqueId("order"));
        request.setCustomerEmail("buyer@example.com");
        request.setCustomerName("測試買家");

        Map<String, Object> output = workers.checkForCustomerNotifications(request);

        // 「成功」＝以新契約輸入呼叫不拋異常、workflow 可繼續；placeholder 語意必須寫進 output，
        // 不得讓工作流定義誤以為已寄送通知（Issue #24 要求 3）。
        assertEquals(Boolean.FALSE, output.get("notified"),
                "output 應明確標示 notified=false（未實際寄送），實際 output: " + output);
        assertFalse(output.isEmpty(), "notify_customer 的 output 應帶出 placeholder 標示，不再是靜默空 map");
    }
}
