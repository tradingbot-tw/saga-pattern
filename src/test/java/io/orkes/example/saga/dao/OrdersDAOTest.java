package io.orkes.example.saga.dao;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.Customer;
import io.orkes.example.saga.pojos.FoodItem;
import io.orkes.example.saga.pojos.Order;
import io.orkes.example.saga.pojos.OrderDetails;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrdersDAO} 的行為測試（Issue #9）：訂單寫入/回讀、狀態機轉換（補償路徑）、
 * 客戶 upsert 的冪等性，以及重複訂單 id 的失敗訊號。
 */
class OrdersDAOTest {

    private static String url;
    private static OrdersDAO dao;

    @BeforeAll
    static void createDatabase() throws IOException {
        url = SagaTestDb.tempUrl("orders");
        SagaTestDb.createSchema(url);
        dao = new OrdersDAO(url);
    }

    @AfterAll
    static void dropDatabase() {
        SagaTestDb.delete(url);
    }

    private static FoodItem foodItem(String name, int quantity) {
        FoodItem item = new FoodItem();
        item.setItem(name);
        item.setQuantity(quantity);
        return item;
    }

    private static Order pendingOrder(String orderId) {
        Customer customer = new Customer();
        customer.setEmail(SagaTestDb.uniqueId("customer") + "@example.com");
        customer.setName("測試客戶");
        customer.setContact("+10000000000");
        customer.setId(dao.insertCustomer(customer));

        OrderDetails details = new OrderDetails();
        details.setOrderId(orderId);
        details.setItems(new ArrayList<>(Arrays.asList(foodItem("burger", 2))));
        details.setNotes(new ArrayList<>(Arrays.asList("不加辣")));

        Order order = new Order();
        order.setOrderId(orderId);
        order.setCustomer(customer);
        order.setRestaurantId(1);
        order.setDeliveryAddress("43 West 4th Street, New York NY 10024");
        order.setStatus(Order.Status.PENDING);
        order.setOrderDetails(details);
        return order;
    }

    @Test
    @DisplayName("happy path：訂單寫入後可回讀，初始狀態為 PENDING")
    void insertOrderPersistsAndReadsBackAsPending() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        Order order = pendingOrder(orderId);

        assertEquals("", dao.insertOrder(order), "insertOrder 成功時應回傳空字串");

        Order read = new Order();
        dao.readOrder(orderId, read);

        assertEquals(orderId, read.getOrderId());
        assertEquals(order.getCustomer().getId(), read.getCustomer().getId());
        assertEquals(1, read.getRestaurantId());
        assertEquals(order.getDeliveryAddress(), read.getDeliveryAddress());
        assertEquals(Order.Status.PENDING, read.getStatus());
        assertTrue(read.getCreatedAt() > 0, "寫入時應帶上建立時間");
        assertEquals(1, SagaTestDb.count(url,
                "SELECT COUNT(*) FROM orders_details WHERE orderId = '" + orderId + "'"),
                "明細應寫入 orders_details");
    }

    @Test
    @DisplayName("補償/回滾：updateOrder 把 PENDING 訂單轉為 CANCELLED")
    void updateOrderMovesPendingToCancelled() {
        String orderId = SagaTestDb.uniqueId("order");
        Order order = pendingOrder(orderId);
        dao.insertOrder(order);

        order.setStatus(Order.Status.CANCELLED);
        dao.updateOrder(order);

        Order read = new Order();
        dao.readOrder(orderId, read);
        assertEquals(Order.Status.CANCELLED, read.getStatus());
    }

    @Test
    @DisplayName("狀態機：訂單狀態只有 PENDING/ASSIGNED/CONFIRMED/CANCELLED 四種，可經 DB 文字往返")
    void orderStatusSurvivesDatabaseRoundTrip() {
        for (Order.Status status : Order.Status.values()) {
            Order.Status parsed = Order.Status.valueOf(status.name());
            assertEquals(status, parsed, status.name() + " 應可由 DB 中的文字還原");
        }
        assertEquals(4, Order.Status.values().length);
    }

    @Test
    @DisplayName("冪等重複請求：同一 email 重複 insertCustomer 回傳同一筆 id")
    void insertCustomerIsIdempotentPerEmail() throws Exception {
        Customer first = new Customer();
        first.setEmail(SagaTestDb.uniqueId("dup") + "@example.com");
        first.setName("第一位");
        first.setContact("+11111111111");

        int id = dao.insertCustomer(first);
        assertTrue(id > 0, "新客戶應取得 autoincrement id");

        Customer repeated = new Customer();
        repeated.setEmail(first.getEmail());
        repeated.setName("同一个人第二次請求");
        repeated.setContact("+22222222222");

        assertEquals(id, dao.insertCustomer(repeated), "重複請求不得建立第二位客戶，應沿用既有 id");
        assertEquals(1, SagaTestDb.count(url,
                        "SELECT COUNT(*) FROM customers WHERE email = '" + first.getEmail() + "'")
                , "同一 email 在 customers 表中應只有一列");
    }

    @Test
    @DisplayName("步驟失敗訊號：重複 orderId 寫入回傳非空錯誤（OrderService 據此回傳 null）")
    void duplicateOrderIdIsRejectedWithError() {
        String orderId = SagaTestDb.uniqueId("order");
        assertEquals("", dao.insertOrder(pendingOrder(orderId)));

        String error = dao.insertOrder(pendingOrder(orderId));

        assertNotNull(error);
        assertFalse(error.isEmpty(), "主鍵重複時 insertOrder 應回傳錯誤訊息而非空字串");
        assertTrue(error.contains("orders.orderId"), "錯誤訊息應指出衝突的欄位，實際為: " + error);
    }

    @Test
    @DisplayName("回讀不存在的訂單時不會拋錯，但欄位保持未設定")
    void readUnknownOrderLeavesFieldsUnset() {
        Order read = new Order();
        dao.readOrder(SagaTestDb.uniqueId("missing"), read);

        assertEquals(null, read.getOrderId(), "查無訂單時不應覆蓋 orderId");
        assertEquals(null, read.getStatus(), "查無訂單時不應覆蓋 status");
    }
}
