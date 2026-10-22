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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(1, dao.updateOrder(order), "更新既有訂單應回傳受影響的 1 列");

        Order read = new Order();
        dao.readOrder(orderId, read);
        assertEquals(Order.Status.CANCELLED, read.getStatus());
    }

    @Test
    @DisplayName("步驟失敗訊號：更新不存在的訂單回傳 0（cancel_order 據此回 terminal error）")
    void updateUnknownOrderReturnsZero() {
        Order missing = pendingOrder(SagaTestDb.uniqueId("missing-order"));

        assertEquals(0, dao.updateOrder(missing),
                "UPDATE 沒有命中任何列時必須回傳 0，不能 silent no-op");
    }

    @Test
    @DisplayName("補償 primitive：cancelOrder 只改 status，查無訂單時回傳 0")
    void cancelOrderOnlyUpdatesStatusAndReportsMissingRow() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        Order order = pendingOrder(orderId);
        dao.insertOrder(order);

        assertEquals(1, dao.cancelOrder(orderId));

        Order read = new Order();
        dao.readOrder(orderId, read);
        assertEquals(Order.Status.CANCELLED, read.getStatus());
        assertEquals(1, read.getRestaurantId(), "補償不得把 restaurantId 覆寫成 0");
        assertEquals(order.getDeliveryAddress(), read.getDeliveryAddress(),
                "補償不得把 deliveryAddress 覆寫成 null");

        assertEquals(0, dao.cancelOrder(SagaTestDb.uniqueId("missing-order")),
                "查無 orderId 時必須回傳 0，讓 worker 回報 terminal error");
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

    /** 在暫存資料庫上執行一條 DDL（Issue #87 以 TRIGGER 注入客戶寫入失敗）。 */
    private static void exec(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    @Test
    @DisplayName("REQ-2 資料庫錯誤：customers 的 INSERT 失敗時 insertCustomer 拋出 DataAccessException，不得回傳 0")
    void insertCustomerThrowsDataAccessExceptionWhenInsertFails() throws Exception {
        Customer customer = new Customer();
        customer.setEmail(SagaTestDb.uniqueId("uninsertable") + "@example.com");
        customer.setName("無法寫入");
        customer.setContact("+10000000000");

        // 故障注入：BEFORE INSERT TRIGGER 讓 customers 的寫入一律失敗（該 email 不存在，
        // 因此 insertCustomer 一定會走到 INSERT 分支）。
        exec("CREATE TRIGGER issue87_fail_customer_insert BEFORE INSERT ON customers "
                + "BEGIN SELECT RAISE(ABORT, 'Issue #87: simulated customers INSERT failure'); END");
        try {
            assertThrows(DataAccessException.class, () -> dao.insertCustomer(customer),
                    "INSERT 失敗必須拋出 DataAccessException，不能把 0 當成有效 id 回傳");
        } finally {
            exec("DROP TRIGGER issue87_fail_customer_insert");
        }

        assertEquals(0, SagaTestDb.count(url,
                        "SELECT COUNT(*) FROM customers WHERE email = '" + customer.getEmail() + "'"),
                "寫入失敗時不得留下客戶列");
    }

    @Test
    @DisplayName("REQ-2 資料庫錯誤：查詢 customers 失敗時 insertCustomer 拋出 DataAccessException，不得吞掉例外回傳 0")
    void insertCustomerThrowsDataAccessExceptionWhenLookupFails() throws Exception {
        // 沒有 customers 表的資料庫：第一個 SELECT 就會以 SQLException 失敗。
        String schemaLessUrl = SagaTestDb.tempUrl("orders-noschema");
        OrdersDAO schemaLessDao = new OrdersDAO(schemaLessUrl);

        Customer customer = new Customer();
        customer.setEmail(SagaTestDb.uniqueId("nolookup") + "@example.com");
        customer.setName("查不到");
        customer.setContact("+10000000000");

        try {
            assertThrows(DataAccessException.class, () -> schemaLessDao.insertCustomer(customer),
                    "查詢失敗必須拋出 DataAccessException，不能吞掉例外後回傳 0");
        } finally {
            SagaTestDb.delete(schemaLessUrl);
        }
    }

    @Test
    @DisplayName("REQ-2 資料庫錯誤：INSERT 後查不回客戶 id 時拋出 DataAccessException，不得回傳 0")
    void insertCustomerThrowsWhenInsertedRowCannotBeReadBack() throws Exception {
        Customer customer = new Customer();
        customer.setEmail(SagaTestDb.uniqueId("vanishing") + "@example.com");
        customer.setName("寫入即消失");
        customer.setContact("+10000000000");

        // 故障注入：INSERT 成功後立刻刪除該列，模擬「寫入後查不回 id」的情境。
        exec("CREATE TRIGGER issue87_drop_inserted_customer AFTER INSERT ON customers "
                + "BEGIN DELETE FROM customers WHERE id = NEW.id; END");
        try {
            assertThrows(DataAccessException.class, () -> dao.insertCustomer(customer),
                    "查不回客戶 id 時必須拋出 DataAccessException，不能把 0 當成有效 id 回傳");
        } finally {
            exec("DROP TRIGGER issue87_drop_inserted_customer");
        }
    }
}
