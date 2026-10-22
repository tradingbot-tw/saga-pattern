package io.orkes.example.saga.service;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.FoodItem;
import io.orkes.example.saga.pojos.Order;
import io.orkes.example.saga.pojos.OrderRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link OrderService} 的步驟行為測試（Issue #60）。
 *
 * <p>涵蓋訂單建立（成功落庫）、寫入失敗回傳 {@code null}（讓 order_food worker 轉 FAILED
 * 的契約），以及補償路徑 {@code cancelOrder} 的受影響列數語意（Issue #34 契約：
 * 既有訂單回傳 1、查無訂單回傳 0）。
 *
 * <p>service 層把 DAO 連線字串寫死為 {@code jdbc:sqlite:food_delivery.db}，
 * 因此本測試沿用應用的資料庫檔（.gitignore 已排除），並用唯一 email／orderId 隔開各情境。
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
    @Disabled("Issue #86：交易修復前紅燈；02-impl 移除 @Disabled")
    @DisplayName("AC-4 交易回滾：orders_details 寫入失敗 → createOrder 回傳 null 且不留下訂單列")
    void createOrderRollsBackOrderWhenOrderDetailsWriteFails() throws Exception {
        String email = SagaTestDb.uniqueId("rollback") + "@example.com";

        // 以 SQLite TRIGGER 讓 orders_details 的 INSERT 失敗，注入「第一筆成功、第二筆失敗」的情境。
        SagaTestDb.execute(URL, "DROP TRIGGER IF EXISTS fail_orders_details_insert");
        SagaTestDb.execute(URL,
                "CREATE TRIGGER fail_orders_details_insert BEFORE INSERT ON orders_details "
                        + "BEGIN SELECT RAISE(ABORT, 'simulated orders_details failure'); END");
        try {
            assertNull(OrderService.createOrder(
                            request(email, "43 West 4th Street, New York NY 10024")),
                    "明細寫入失敗時 createOrder 必須回傳 null");
        } finally {
            SagaTestDb.execute(URL, "DROP TRIGGER IF EXISTS fail_orders_details_insert");
        }

        // 客戶列在訂單交易之外，仍會寫入（Issue #86 不處理 customerId 落庫問題）。
        String customerId = SagaTestDb.queryString(URL,
                "SELECT id FROM customers WHERE email = '" + email + "'");
        assertNotNull(customerId, "交易只涵蓋 orders／orders_details，客戶列應已寫入");

        assertEquals(0, SagaTestDb.count(URL,
                        "SELECT COUNT(*) FROM orders WHERE customerId = " + customerId),
                "orders_details 失敗時 orders 列必須被回滾，不得留下半成品訂單");
        assertEquals(0, SagaTestDb.count(URL,
                        "SELECT COUNT(*) FROM orders_details WHERE orderId IN "
                                + "(SELECT orderId FROM orders WHERE customerId = " + customerId + ")"),
                "orders_details 不得留下任何屬於該訂單的列");
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
}
