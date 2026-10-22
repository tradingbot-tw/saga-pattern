package io.orkes.example.saga.service;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.dao.DataAccessException;
import io.orkes.example.saga.pojos.FoodItem;
import io.orkes.example.saga.pojos.Order;
import io.orkes.example.saga.pojos.OrderRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link OrderService} 的步驟行為測試（Issue #60）。
 *
 * <p>涵蓋訂單建立（成功落庫）、寫入失敗回傳 {@code null}（讓 order_food worker 轉 FAILED
 * 的契約），以及補償路徑 {@code cancelOrder} 的受影響列數語意（Issue #34 契約：
 * 既有訂單回傳 1、查無訂單回傳 0）。
 *
 * <p>service 層把 DAO 連線字串寫死為 {@code jdbc:sqlite:food_delivery.db}，
 * 因此本測試沿用應用的資料庫檔（.gitignore 已排除），並用唯一 email／orderId 隔開各情境。
 *
 * <p>Issue #86（REQ-1）另以 SQLite {@code TRIGGER} 注入 {@code orders_details} 的
 * {@code INSERT} 失敗，驗證訂單主檔與明細的寫入被交易包覆：明細失敗時不留下任何半成品列。
 *
 * <p>Issue #87（REQ-1）以 SQLite {@code TRIGGER} 注入 {@code customers} 的
 * {@code INSERT} 失敗，驗證客戶寫入失敗時 {@code createOrder} 讓例外往外拋，
 * 不得以 {@code customerId = 0} 建立指向不存在客戶的訂單列。
 */
class OrderServiceTest {

    private static final String URL = SagaTestDb.APP_DB_URL;

    @BeforeAll
    static void prepareDatabase() {
        SagaTestDb.createAppSchema();
    }

    private static FoodItem foodItem(String name, int quantity) {
        FoodItem item = new FoodItem();
        item.setItem(name);
        item.setQuantity(quantity);
        return item;
    }

    /**
     * 建立一筆可成功落庫的請求；{@code deliveryAddress} 為 null 時會違反 orders 表的
     * NOT NULL 限制，使訂單寫入失敗（AC-2 的故障注入，不需要修改 main 程式碼）。
     */
    private static OrderRequest request(String email, String deliveryAddress) {
        OrderRequest request = new OrderRequest();
        request.setCustomerEmail(email);
        request.setCustomerName("測試客戶");
        request.setCustomerContact("+10000000000");
        request.setRestaurantId(2);
        request.setItems(new ArrayList<>(Arrays.asList(foodItem("burger", 2))));
        request.setNotes(new ArrayList<>(Arrays.asList("不加辣")));
        request.setDeliveryAddress(deliveryAddress);
        return request;
    }

    private static String statusInDb(String orderId) throws Exception {
        return SagaTestDb.queryString(URL,
                "SELECT status FROM orders WHERE orderId = '" + orderId + "'");
    }

    private static int orderCountForCustomer(String email) throws Exception {
        return SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM orders WHERE customerId = "
                        + "(SELECT id FROM customers WHERE email = '" + email + "')");
    }

    /** 在應用資料庫上執行一條 DDL（Issue #86 以 TRIGGER 注入明細寫入失敗）。 */
    private static void exec(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    @Test
    @DisplayName("AC-1 happy path：createOrder 成功回傳 orderId，DB 訂單狀態為 PENDING、客戶資料已寫入")
    void createOrderSuccessPersistsPendingOrderAndCustomer() throws Exception {
        String email = SagaTestDb.uniqueId("buyer") + "@example.com";

        String orderId = OrderService.createOrder(request(email, "43 West 4th Street, New York NY 10024"));

        assertNotNull(orderId, "寫入成功應回傳 orderId");
        assertEquals(Order.Status.PENDING.name(), statusInDb(orderId), "訂單應以 PENDING 落庫");

        // 客戶資料已寫入 customers 表
        assertEquals("測試客戶", SagaTestDb.queryString(URL,
                "SELECT name FROM customers WHERE email = '" + email + "'"));
        assertEquals("+10000000000", SagaTestDb.queryString(URL,
                "SELECT contact FROM customers WHERE email = '" + email + "'"));

        // 訂單列指向剛寫入的客戶
        String customerIdInOrder = SagaTestDb.queryString(URL,
                "SELECT customerId FROM orders WHERE orderId = '" + orderId + "'");
        assertEquals(SagaTestDb.queryString(URL,
                        "SELECT id FROM customers WHERE email = '" + email + "'"),
                customerIdInOrder, "訂單的 customerId 應對應寫入的客戶列");

        // 明細也同步落庫（items／notes 寫入 orders_details）
        assertEquals(1, SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM orders_details WHERE orderId = '" + orderId + "'"));

        // service 層的 read-back 契約：getOrder 讀回的狀態一致
        assertEquals(Order.Status.PENDING, OrderService.getOrder(orderId).getStatus());
    }

    @Test
    @DisplayName("AC-2 步驟失敗：訂單寫入失敗 → createOrder 回傳 null（order_food worker 據此轉 FAILED）")
    void createOrderReturnsNullWhenOrderWriteFails() throws Exception {
        String email = SagaTestDb.uniqueId("failbuy") + "@example.com";

        // deliveryAddress 為 null，違反 orders.deliveryAddress NOT NULL → insertOrder 回傳錯誤訊息
        String orderId = OrderService.createOrder(request(email, null));

        assertNull(orderId, "寫入失敗必須回傳 null，讓協調器把 order_food 轉成 FAILED");
        assertEquals(0, orderCountForCustomer(email), "寫入失敗不得留下任何訂單列");
    }

    @Test
    @DisplayName("AC-3 補償：cancelOrder 對既有訂單回傳 1 並轉為 CANCELLED")
    void cancelOrderMarksExistingOrderCancelled() throws Exception {
        String email = SagaTestDb.uniqueId("canceler") + "@example.com";
        String orderId = OrderService.createOrder(request(email, "1693 Alice Court, Annapolis MD 21401"));
        assertNotNull(orderId);

        assertEquals(1, OrderService.cancelOrder(orderId), "既有訂單應被取消一列");

        assertEquals(Order.Status.CANCELLED.name(), statusInDb(orderId),
                "補償後 DB 狀態應為 CANCELLED");
    }

    @Test
    @DisplayName("AC-3 補償邊界：查無訂單時 cancelOrder 回傳 0（由 worker 決定 terminal error）")
    void cancelOrderOnUnknownOrderReturnsZero() throws Exception {
        String ghostOrderId = SagaTestDb.uniqueId("ghost-order");

        assertEquals(0, OrderService.cancelOrder(ghostOrderId),
                "查無訂單時 service 回傳 0、不拋錯");

        assertNull(statusInDb(ghostOrderId), "不存在的訂單不應被寫入任何狀態");
    }

    @Test
    @DisplayName("REQ-1 交易回滾：orders_details 的 INSERT 失敗時，createOrder 回傳 null 且 orders 與 orders_details 都不得留下該訂單的列")
    void createOrderLeavesNoPartialRowsWhenDetailsInsertFails() throws Exception {
        String email = SagaTestDb.uniqueId("rollbuy") + "@example.com";

        // 故障注入（REQ-1 的驗證方式）：BEFORE INSERT TRIGGER 讓 orders_details 的寫入
        // 一律以 RAISE(ABORT) 失敗，不需要修改 main 程式碼或表結構。
        exec("CREATE TRIGGER issue86_fail_details BEFORE INSERT ON orders_details "
                + "BEGIN SELECT RAISE(ABORT, 'Issue #86: simulated orders_details INSERT failure'); END");

        String orderId;
        try {
            orderId = OrderService.createOrder(
                    request(email, "5331 Redford Court, Montgomery AL 36116"));
        } finally {
            exec("DROP TRIGGER issue86_fail_details");
        }

        // 回傳契約不變（做法 3）：明細失敗時 createOrder 仍回傳 null，order_food 據此轉 FAILED。
        assertNull(orderId, "明細寫入失敗時 createOrder 必須回傳 null");

        // orderId 由 service 內部生成、失敗時不回傳，故以本次唯一 email 對應的 customerId
        // 鎖定「該訂單」；createOrder 在 insertOrder 之前必定先 upsert customers，
        // 該列不在交易範圍內（另有工單），保留它才能定位本輪嘗試建立的訂單。
        String customerFilter = "customerId = (SELECT id FROM customers WHERE email = '" + email + "')";

        assertEquals(0, SagaTestDb.count(URL, "SELECT COUNT(*) FROM orders WHERE " + customerFilter),
                "orders_details 失敗時 orders 必須被回滾，不得留下半成品列");

        assertEquals(0, SagaTestDb.count(URL,
                        "SELECT COUNT(*) FROM orders_details WHERE orderId IN "
                                + "(SELECT orderId FROM orders WHERE " + customerFilter + ")"),
                "orders_details 亦不得殘留任何列");
    }

    /** orders 表中指向不存在的客戶（customerId = 0）的列數。 */
    private static int customerIdZeroOrderCount() throws Exception {
        return SagaTestDb.count(URL, "SELECT COUNT(*) FROM orders WHERE customerId = 0");
    }

    @Test
    @DisplayName("REQ-1 客戶寫入失敗：createOrder 拋出 DataAccessException，不得以 customerId = 0 留下訂單列")
    void createOrderFailsWithoutCustomerIdZeroWhenCustomersInsertFails() throws Exception {
        String email = SagaTestDb.uniqueId("nocust") + "@example.com";

        // 故障注入（REQ-1 的驗證方式）：BEFORE INSERT TRIGGER 讓 customers 的寫入一律以
        // RAISE(ABORT) 失敗；email 為本次唯一，步驟 1 的查找必定 miss，必然走到 INSERT 失敗路徑。
        exec("CREATE TRIGGER issue87_fail_customer BEFORE INSERT ON customers "
                + "BEGIN SELECT RAISE(ABORT, 'Issue #87: simulated customers INSERT failure'); END");

        int zeroBefore = customerIdZeroOrderCount();
        try {
            assertThrows(DataAccessException.class,
                    () -> OrderService.createOrder(
                            request(email, "43 West 4th Street, New York NY 10024")),
                    "客戶寫入失敗時 createOrder 必須讓例外往外拋，而不是以 customerId = 0 建立訂單");
        } finally {
            exec("DROP TRIGGER issue87_fail_customer");
        }

        assertEquals(0, SagaTestDb.count(URL,
                        "SELECT COUNT(*) FROM customers WHERE email = '" + email + "'"),
                "失敗的 INSERT 不得留下客戶列");
        assertEquals(zeroBefore, customerIdZeroOrderCount(),
                "createOrder 不得新增以 customerId = 0 指向不存在客戶的訂單列");
    }
}
