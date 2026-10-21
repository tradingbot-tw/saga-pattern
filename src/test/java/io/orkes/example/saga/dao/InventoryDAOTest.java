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
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InventoryDAO} 的查詢行為測試（Issue #50）。
 *
 * <p>每個測試使用 {@link SagaTestDb} 建立的獨立暫存資料庫（含 {@code restaurants}
 * 種子資料），與應用自身的 {@code food_delivery.db} 互不干擾。
 * 涵蓋：查得時回填 POJO、查無資料時不拋例外且保持 POJO 原樣（Issue #50 AC-4）。
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

    /** 建立「名字已被預先填入哨兵值」的 POJO，用來驗證 readRestaurant 何時會覆寫、何時不會。 */
    private static Restaurant pojoWithSentinelName() {
        Restaurant restaurant = new Restaurant();
        restaurant.setName("SENTINEL-UNCHANGED");
        return restaurant;
    }

    @Test
    @DisplayName("happy path：查得種子餐廳時 name 被回填為非空白")
    void readRestaurantPopulatesNameWhenFound() {
        Restaurant restaurant = new Restaurant();
        restaurant.setName("");

        assertDoesNotThrow(() -> dao.readRestaurant(1, restaurant));

        assertNotNull(restaurant.getName(), "查得時 name 應被ResultSet覆寫");
        assertFalse(restaurant.getName().isBlank(), "種子餐廳 1 的 name 欄應非空白");
    }

    @Test
    @DisplayName("查得種子餐廳時 address 與 contact 欄也一併回填（非 null；欄位內容交換為既有缺陷，見 Issue #9）")
    void readRestaurantPopulatesAddressAndContactWhenFound() {
        Restaurant restaurant = new Restaurant();

        assertDoesNotThrow(() -> dao.readRestaurant(1, restaurant));

        assertNotNull(restaurant.getAddress(), "查得時 address 欄應被回填（值與 contact 互換為既有缺陷，不在本工單斷言）");
        assertNotNull(restaurant.getContact(), "查得時 contact 欄應被回填");
    }

    @Test
    @DisplayName("AC-4：查無資料（id 999 不存在的餐廳）時不拋例外，且 POJO 保持原樣")
    void readRestaurantDoesNotThrowAndLeavesPojoUntouchedWhenNotFound() {
        Restaurant restaurant = pojoWithSentinelName();

        assertDoesNotThrow(() -> dao.readRestaurant(999, restaurant));

        assertEquals("SENTINEL-UNCHANGED", restaurant.getName(),
                "查無資料時不應覆寫呼叫端傳入的 name");
        assertNull(restaurant.getAddress(), "查無資料時 contact/address 不應被寫入");
        assertNull(restaurant.getContact(), "查無資料時 contact 不應被寫入");
    }

    @Test
    @DisplayName("AC-4：查無資料時即使 POJO 全為 null，readRestaurant 仍安靜返回（不拋例外、不寫入 null 覆寫）")
    void readRestaurantOnMissingRowWithPristinePojoDoesNotThrow() {
        Restaurant restaurant = new Restaurant();

        assertDoesNotThrow(() -> dao.readRestaurant(123456, restaurant));

        assertNull(restaurant.getName(), "查無資料時不應對 null POJO 寫入任何值");
        assertNull(restaurant.getAddress());
        assertNull(restaurant.getContact());
    }

    @Test
    @DisplayName("AC-4：負數 id 查無匹配列時同樣不拋例外、POJO 原樣")
    void readRestaurantWithNegativeIdDoesNotThrow() {
        Restaurant restaurant = pojoWithSentinelName();

        assertDoesNotThrow(() -> dao.readRestaurant(-5, restaurant));

        assertEquals("SENTINEL-UNCHANGED", restaurant.getName());
    }

    @Test
    @DisplayName("AC-4：表存在但完全無資料時查詢不拋例外（空結果集路徑）")
    void readRestaurantOnEmptyTableDoesNotThrow() throws Exception {
        String emptyUrl = SagaTestDb.tempUrl("inventory-empty");
        try {
            SagaTestDb.createSchema(emptyUrl);
            try (Connection conn = DriverManager.getConnection(emptyUrl);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM restaurants");
            }
            InventoryDAO emptyDao = new InventoryDAO(emptyUrl);
            Restaurant restaurant = pojoWithSentinelName();

            assertDoesNotThrow(() -> emptyDao.readRestaurant(1, restaurant));

            assertEquals("SENTINEL-UNCHANGED", restaurant.getName());
            assertEquals(0, SagaTestDb.count(emptyUrl, "SELECT COUNT(*) FROM restaurants"),
                    "前提確認：表已清空");
        } finally {
            SagaTestDb.delete(emptyUrl);
        }
    }

    @Test
    @DisplayName("重覆查詢同一 DAO 執行個體是安全的：每次呼叫各自開新連線、結果一致")
    void repeatedReadsOnSameDaoInstanceAreConsistent() throws SQLException {
        Restaurant first = new Restaurant();
        Restaurant second = new Restaurant();
        first.setName("");
        second.setName("");

        dao.readRestaurant(2, first);
        dao.readRestaurant(2, second);

        assertEquals(first.getName(), second.getName(),
                "同一 id 的兩次查詢應回填相同 name");
        assertFalse(first.getName().isBlank(), "種子餐廳 2 應查得到名字");
    }
}
