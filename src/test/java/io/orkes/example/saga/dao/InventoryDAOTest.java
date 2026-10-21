package io.orkes.example.saga.dao;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.Restaurant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link InventoryDAO} 的行為測試（Issue #50）。
 *
 * <p>DAO 的連線字串由建構子傳入，因此使用 {@link SagaTestDb#tempUrl(String)} 建立的獨立資料庫，
 * 不與其他測試共享狀態；{@link SagaTestDb#createSchema(String)} 會建表並帶入種子餐廳。
 */
class InventoryDAOTest {

    private static String url;
    private static InventoryDAO dao;

    @BeforeAll
    static void createDatabase() throws IOException {
        url = SagaTestDb.tempUrl("inventory");
        SagaTestDb.createSchema(url);
        dao = new InventoryDAO(url);
    }

    @AfterAll
    static void dropDatabase() {
        SagaTestDb.delete(url);
    }

    @Test
    @DisplayName("happy path：種子餐廳可回讀 name/address/contact 三個欄位")
    void readSeededRestaurantPopulatesColumns() {
        Restaurant restaurant = new Restaurant();

        dao.readRestaurant(1, restaurant);

        assertEquals("Mikes", restaurant.getName());
        assertNotNull(restaurant.getAddress(), "address 應被回填");
        assertFalse(restaurant.getAddress().isBlank(), "address 不應為空字串");
        assertNotNull(restaurant.getContact(), "contact 應被回填");
        assertFalse(restaurant.getContact().isBlank(), "contact 不應為空字串");
    }

    @Test
    @DisplayName("契約：readRestaurant 只回填查詢欄位，不覆寫呼叫端設定的 id")
    void readRestaurantLeavesCallerSuppliedIdUntouched() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(42);

        dao.readRestaurant(1, restaurant);

        assertEquals(42, restaurant.getId(), "readRestaurant 的 SQL 不含 id，不得改寫呼叫端的 id");
        assertEquals("Mikes", restaurant.getName(), "其餘欄位仍應正常回填");
    }

    @Test
    @DisplayName("查無資料：readRestaurant 不拋例外，且 name/address/contact 維持未設定（null）")
    void readUnknownRestaurantDoesNotThrowAndLeavesFieldsNull() {
        Restaurant restaurant = new Restaurant();

        assertDoesNotThrow(() -> dao.readRestaurant(999, restaurant));
        assertNull(restaurant.getName());
        assertNull(restaurant.getAddress());
        assertNull(restaurant.getContact());
    }

    @Test
    @DisplayName("查無資料：不覆寫呼叫端既有的預設值（service 靠 name 判斷餐廳存在與否）")
    void readUnknownRestaurantKeepsCallerDefaults() {
        Restaurant restaurant = new Restaurant();
        restaurant.setName("");
        restaurant.setAddress("未設定");
        restaurant.setContact("未設定");

        dao.readRestaurant(999, restaurant);

        assertEquals("", restaurant.getName());
        assertEquals("未設定", restaurant.getAddress());
        assertEquals("未設定", restaurant.getContact());
    }

    @Test
    @DisplayName("回讀即時資料：DB 更新後 readRestaurant 取得最新 name，不是快取")
    void readRestaurantReturnsLatestRowValues() throws Exception {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement update = conn.prepareStatement(
                     "UPDATE restaurants SET name = ? WHERE id = ?")) {
            update.setString(1, "Tamarind 二店");
            update.setInt(2, 2);
            assertEquals(1, update.executeUpdate(), "前置條件：種子餐廳 id 2 應存在");
        }

        Restaurant restaurant = new Restaurant();
        dao.readRestaurant(2, restaurant);

        assertEquals("Tamarind 二店", restaurant.getName());
    }
}
