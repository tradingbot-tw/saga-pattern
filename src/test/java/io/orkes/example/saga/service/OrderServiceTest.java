package io.orkes.example.saga.service;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.dao.OrdersDAO;
import io.orkes.example.saga.pojos.Customer;
import io.orkes.example.saga.pojos.FoodItem;
import io.orkes.example.saga.pojos.Order;
import io.orkes.example.saga.pojos.OrderDetails;
import io.orkes.example.saga.pojos.OrderRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link OrderService} 對 saga 步驟 {@code order_food} 提供的契約（Issue #60）。
 *
 * <p>worker 只依賴這兩個結果：{@link OrderService#createOrder(OrderRequest)} 成功時回傳
 * 非 null 的 orderId（讓工作流繼續），失敗時回傳 {@code null}（讓 {@code order_food}
 * 轉成 FAILED）；{@link OrderService#cancelOrder(String)} 回傳受影響列數，補償步驟才能
 * 區分「已取消」與「查無訂單」。service 把 DAO 連線字串寫死為
 * {@code jdbc:sqlite:food_delivery.db}，因此沿用 {@link SagaTestDb} 的應用資料庫，
 * 並以唯一 UUID 隔開各情境。
 */
class OrderServiceTest {

    private static final String URL = SagaTestDb.APP_DB_URL;

    private static OrdersDAO ordersDAO;

    @BeforeAll
    static void prepareDatabase() {
        SagaTestDb.createAppSchema();
        ordersDAO = new OrdersDAO(URL);
    }

    private static FoodItem foodItem(String name, int quantity) {
        FoodItem item = new FoodItem();
        item.setItem(name);
        item.setQuantity(quantity);
        return item;
    }

    /** 一筆合法的外送訂單請求；email 唯一，避免跨測試／跨 run 共用客戶資料。 */
    private static OrderRequest orderRequest() {
        OrderRequest request = new OrderRequest();
        request.setCustomerEmail(SagaTestDb.uniqueId("buyer") + "@example.com");
        request.setCustomerName("測試買家");
        request.setCustomerContact("+12126781345");
        request.setRestaurantId(1);
        request.setItems(new ArrayList<>(Arrays.asList(foodItem("burger", 2))));
        request.setNotes(new ArrayList<>(Arrays.asList("不要蔥")));
        request.setDeliveryAddress("1693 Alice Court, Annapolis MD 21401");
        request.setDeliveryInstructions("放門口");
        return request;
    }

    /** 直接以 DAO 種一筆 PENDING 訂單，讓 cancelOrder 的測試不依賴 createOrder。 */
    private static String seedPendingOrder() {
        String orderId = SagaTestDb.uniqueId("order");

        Customer customer = new Customer();
        customer.setEmail(SagaTestDb.uniqueId("cancel-buyer") + "@example.com");
        customer.setName("取消測試買家");
        customer.setContact("+12126781345");
        customer.setId(ordersDAO.insertCustomer(customer));

        OrderDetails details = new OrderDetails();
        details.setOrderId(orderId);
        details.setItems(new ArrayList<>(Arrays.asList(foodItem("burger", 1))));
        details.setNotes(new ArrayList<>());

        Order order = new Order();
        order.setOrderId(orderId);
        order.setCustomer(customer);
        order.setRestaurantId(1);
        order.setDeliveryAddress("1693 Alice Court, Annapolis MD 21401");
        order.setStatus(Order.Status.PENDING);
        order.setOrderDetails(details);

        assertEquals("", ordersDAO.insertOrder(order), "測試前置：PENDING 訂單應寫入成功");
        return orderId;
    }

    private static String orderStatusInDb(String orderId) throws Exception {
        return SagaTestDb.queryString(URL,
                "SELECT status FROM orders WHERE orderId = '" + orderId + "'");
    }

    @Test
    @DisplayName("createOrder 成功：回傳 orderId，DB 訂單為 PENDING 且客戶資料已寫入")
    void createOrderPersistsPendingOrderAndCustomer() throws Exception {
        OrderRequest request = orderRequest();

        String orderId = OrderService.createOrder(request);

        assertNotNull(orderId, "成功建立訂單時必須回傳 orderId");
        assertFalse(orderId.isBlank(), "orderId 不應為空白");

        Order stored = OrderService.getOrder(orderId);
        assertEquals(orderId, stored.getOrderId());
        assertEquals(Order.Status.PENDING, stored.getStatus(), "新訂單應以 PENDING 進入狀態機");
        assertEquals(request.getRestaurantId(), stored.getRestaurantId());
        assertEquals(request.getDeliveryAddress(), stored.getDeliveryAddress());

        assertNotNull(stored.getCustomer(), "讀回的訂單應帶著 customerId");
        String customerRow = SagaTestDb.queryString(URL,
                "SELECT email || '|' || name || '|' || contact FROM customers WHERE id = "
                        + stored.getCustomer().getId());
        assertEquals(request.getCustomerEmail() + "|" + request.getCustomerName() + "|"
                        + request.getCustomerContact(),
                customerRow, "客戶資料應原樣寫入 customers 表");
    }

    @Test
    @DisplayName("createOrder 訂單寫入失敗：回傳 null，讓 order_food 轉成 FAILED")
    void createOrderReturnsNullWhenOrderWriteFails() throws Exception {
        OrderRequest request = orderRequest();

        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TRIGGER IF EXISTS fail_order_insert");
            stmt.execute("CREATE TRIGGER fail_order_insert BEFORE INSERT ON orders "
                    + "BEGIN SELECT RAISE(ABORT, 'simulated order db failure'); END");
            try {
                assertNull(OrderService.createOrder(request),
                        "訂單寫入失敗時不得回傳 id，否則 order_food 會誤判成功");
            } finally {
                stmt.execute("DROP TRIGGER IF EXISTS fail_order_insert");
            }
        }

        int persistedOrders = SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM orders o JOIN customers c ON o.customerId = c.id "
                        + "WHERE c.email = '" + request.getCustomerEmail() + "'");
        assertEquals(0, persistedOrders, "寫入失敗時不應留下訂單列");
    }

    @Test
    @DisplayName("cancelOrder 既有訂單：回傳 1 並把狀態轉為 CANCELLED")
    void cancelOrderMarksExistingOrderCancelled() throws Exception {
        String orderId = seedPendingOrder();
        assertEquals(Order.Status.PENDING.name(), orderStatusInDb(orderId), "測試前置：訂單應為 PENDING");

        int affected = OrderService.cancelOrder(orderId);

        assertEquals(1, affected, "既有訂單應回報 1 列受影響");
        assertEquals(Order.Status.CANCELLED.name(), orderStatusInDb(orderId),
                "補償成功後 DB 狀態必須是 CANCELLED");
    }

    @Test
    @DisplayName("cancelOrder 查無訂單：回傳 0，讓 worker 判為 terminal error")
    void cancelOrderReturnsZeroForMissingOrder() {
        String missingOrderId = SagaTestDb.uniqueId("missing-order");

        assertEquals(0, OrderService.cancelOrder(missingOrderId),
                "查無訂單時不得回報成功列數");
    }
}
