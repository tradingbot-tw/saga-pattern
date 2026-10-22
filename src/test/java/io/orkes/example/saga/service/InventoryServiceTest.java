package io.orkes.example.saga.service;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.FoodItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InventoryService} 的行為測試（Issue #50）。
 *
 * <p>service 把 DAO 連線字串寫死為 {@code jdbc:sqlite:food_delivery.db}，無法注入，
 * 因此沿用 {@link SagaTestDb#createAppSchema()} 準備的應用資料庫與種子餐廳（id 1..3）。
 * 本測試原則上只讀不寫；唯一例外是空白名稱邊界測試，會暫插入一筆自訂餐廳，
 * 斷言後立刻在 {@code finally} 刪除，避免與其他測試類別共享的資料庫互相干擾。
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

    // ===================================================================
    // Issue #85：「餐廳可用」判斷的空白／NULL 邊界。
    //
    // 修前的 `!Objects.equals(name, "")` 只比對空字串：name 為空白字串或
    // NULL 時仍回 true（可用），與 docs/modules/inventory.md §2.1 原本的
    // 描述一致。本測試層（01-test）以 @Disabled 提交紅燈斷言，02-impl
    // 判斷式改為 `name != null && !name.isBlank()` 後啟用，两者皆應綠燈。
    // ===================================================================

    @Test
    @DisplayName("邊界（Issue #85）：name 為空白字串的餐廳 → checkAvailability 回傳 false")
    void checkAvailabilityReturnsFalseForBlankNameRestaurant() throws SQLException {
        int restaurantId = insertRestaurant("   ");
        try {
            assertFalse(InventoryService.checkAvailability(restaurantId, items("burger", 1)),
                    "名稱只有空白的餐廳不具備可用名稱，應回傳 false");
        } finally {
            deleteRestaurant(restaurantId);
        }
    }

    @Test
    @DisplayName("邊界（Issue #85）：name 為 NULL → 判斷式回傳 false（NOT NULL 限制無法插入，直接驗證 hasUsableName）")
    void nullNameIsNotUsable() {
        assertFalse(InventoryService.hasUsableName(null),
                "NULL 名稱不具備可用名稱，應回傳 false");
    }

    /** 在應用資料庫插入一筆自訂名稱的餐廳，回傳其 id（僅供 Issue #85 邊界測試）。 */
    private static int insertRestaurant(String name) throws SQLException {
        try (Connection conn = DriverManager.getConnection(SagaTestDb.APP_DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO restaurants(name, address, contact) VALUES(?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, name);
            pstmt.setString(2, "Issue85 temporary row");
            pstmt.setString(3, "+1000000000");
            pstmt.executeUpdate();
            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                assertTrue(keys.next(), "插入餐廳後應取得自動產生的 id");
                return keys.getInt(1);
            }
        }
    }

    /** 刪除 {@link #insertRestaurant(String)} 插入的暫列，避免干擾其他測試。 */
    private static void deleteRestaurant(int restaurantId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(SagaTestDb.APP_DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(
                     "DELETE FROM restaurants WHERE id = ?")) {
            pstmt.setInt(1, restaurantId);
            pstmt.executeUpdate();
        }
    }
}
