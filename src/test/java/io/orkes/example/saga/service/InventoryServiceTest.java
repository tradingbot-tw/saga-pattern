package io.orkes.example.saga.service;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.FoodItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InventoryService} 的行為測試（Issue #50）。
 *
 * <p>service 把 DAO 連線字串寫死為 {@code jdbc:sqlite:food_delivery.db}，無法注入，
 * 因此沿用 {@link SagaTestDb#createAppSchema()} 準備的應用資料庫與種子餐廳（id 1..3）。
 * 本測試只讀不寫，避免與其他測試類別共享的資料庫互相干擾。
 */
class InventoryServiceTest {

    @BeforeAll
    static void prepareDatabase() {
        SagaTestDb.createAppSchema();
    }

    private static ArrayList<FoodItem> items(String name, int quantity) {
        FoodItem item = new FoodItem();
        item.setItem(name);
        item.setQuantity(quantity);
        return new ArrayList<>(Arrays.asList(item));
    }

    @Test
    @DisplayName("happy path：種子餐廳（id 1）存在 → checkAvailability 回傳 true")
    void checkAvailabilityReturnsTrueForSeededRestaurant() {
        assertTrue(InventoryService.checkAvailability(1, items("burger", 2)));
    }

    @Test
    @DisplayName("happy path：三個種子餐廳（id 1..3）都通過庫存檢查")
    void checkAvailabilityReturnsTrueForEverySeededRestaurant() {
        for (int restaurantId = 1; restaurantId <= 3; restaurantId++) {
            assertTrue(InventoryService.checkAvailability(restaurantId, items("burger", 1)),
                    "種子餐廳 id " + restaurantId + " 應存在");
        }
    }

    @Test
    @DisplayName("步驟失敗訊號：不存在的餐廳（id 999）→ false，worker 據此回 terminal error")
    void checkAvailabilityReturnsFalseForUnknownRestaurant() {
        assertFalse(InventoryService.checkAvailability(999, items("burger", 1)));
    }

    @Test
    @DisplayName("邊界：id 0 與負數都不存在 → false")
    void checkAvailabilityReturnsFalseForNonPositiveIds() {
        assertFalse(InventoryService.checkAvailability(0, items("burger", 1)));
        assertFalse(InventoryService.checkAvailability(-1, items("burger", 1)));
    }

    @Test
    @DisplayName("現有行為（空清單）：items 不參與判斷，存在的餐廳仍回傳 true")
    void emptyItemListDoesNotAffectAvailability() {
        assertTrue(InventoryService.checkAvailability(1, new ArrayList<>()),
                "目前實作只檢查餐廳是否存在，items 不影響結果");
    }

    @Test
    @DisplayName("現有行為（null 清單）：不拋 NullPointerException，存在的餐廳仍回傳 true")
    void nullItemListDoesNotAffectAvailability() {
        assertTrue(InventoryService.checkAvailability(1, null),
                "目前實作未讀取 items，null 與空清單行為相同");
    }

    @Test
    @DisplayName("現有行為：items 也不能讓不存在的餐廳變成可用 → false")
    void itemListDoesNotMakeUnknownRestaurantAvailable() {
        assertFalse(InventoryService.checkAvailability(999, items("burger", 1)));
    }

    @Test
    @Disabled("Issue #85：先在 01-test 交付紅燈案例，02-impl 修正判斷式後啟用")
    @DisplayName("邊界（Issue #85）：name 只有空白字元 → checkAvailability 回傳 false")
    void checkAvailabilityReturnsFalseForWhitespaceOnlyRestaurantName() throws Exception {
        int restaurantId = 900_000 + ThreadLocalRandom.current().nextInt(99_999);
        try (Connection conn = DriverManager.getConnection(SagaTestDb.APP_DB_URL);
             PreparedStatement insert = conn.prepareStatement(
                     "INSERT INTO restaurants(id, name, address, contact) VALUES(?, ?, ?, ?)")) {
            insert.setInt(1, restaurantId);
            insert.setString(2, "   ");
            insert.setString(3, "測試地址");
            insert.setString(4, "測試聯絡方式");
            assertEquals(1, insert.executeUpdate(), "前置條件：應可插入 name 只有空白的餐廳");
        }

        try {
            assertFalse(InventoryService.checkAvailability(restaurantId, items("burger", 1)),
                    "name 只有空白字元時，餐廳不可用");
        } finally {
            try (Connection conn = DriverManager.getConnection(SagaTestDb.APP_DB_URL);
                 PreparedStatement delete = conn.prepareStatement(
                         "DELETE FROM restaurants WHERE id = ?")) {
                delete.setInt(1, restaurantId);
                delete.executeUpdate();
            }
        }
    }

    @Test
    @Disabled("Issue #85：先在 01-test 交付紅燈案例，02-impl 修正判斷式後啟用")
    @DisplayName("判斷式（Issue #85）：NULL 與空白名稱都不可用，非空白名稱才可用")
    void isUsableRestaurantNameRejectsNullAndBlankNames() {
        // restaurants.name 的 DDL 是 text NOT NULL，無法真的插入 NULL 資料列，
        // 因此依 Issue #85 的驗證方式直接驗證判斷式本身。
        assertFalse(InventoryService.isUsableRestaurantName(null), "NULL 名稱不可用");
        assertFalse(InventoryService.isUsableRestaurantName(""), "空字串不可用");
        assertFalse(InventoryService.isUsableRestaurantName("   "), "只有空白不可用");
        assertFalse(InventoryService.isUsableRestaurantName("\t\n"), "只有控制空白不可用");
        assertTrue(InventoryService.isUsableRestaurantName("Mikes"), "非空白名稱可用");
        assertTrue(InventoryService.isUsableRestaurantName("  Mikes  "), "前後空白不影響可用性");
    }
}
