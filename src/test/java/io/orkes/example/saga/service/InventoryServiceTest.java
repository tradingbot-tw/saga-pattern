package io.orkes.example.saga.service;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.FoodItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InventoryService#checkAvailability(int, ArrayList)} 的行為測試（Issue #50）。
 *
 * <p>service 層把 DAO 連線字串寫死為 {@code jdbc:sqlite:food_delivery.db}（static 欄位，
 * 無法注入），因此沿用 {@link SagaTestDb#createAppSchema()} 確保應用資料庫與種子資料存在，
 * 與既有 {@code PaymentServiceTest}/{@code SeedDataTest} 的做法一致。
 *
 * <p>注意現行實作只以「餐厅 id 查得到非空白 name」作為可用與否的判準，
 * <b>完全不檢視 items 參數</b>；品項清單為空或 null 時的測試（AC-3）
 * 依目前程式行為撰寫，測試名稱中說明之。
 */
class InventoryServiceTest {

    private static final String URL = SagaTestDb.APP_DB_URL;

    @BeforeAll
    static void prepareDatabase() {
        SagaTestDb.createAppSchema();
    }

    private static FoodItem item(String name, int quantity) {
        FoodItem foodItem = new FoodItem();
        foodItem.setItem(name);
        foodItem.setQuantity(quantity);
        return foodItem;
    }

    /** AC-1：種子餐廳（id 1，initDB 固定種子）→ checkAvailability 回傳 true。 */
    @Test
    @DisplayName("AC-1：種子中存在的餐廳 id 1 → checkAvailability 回傳 true")
    void checkAvailabilityReturnsTrueForSeededRestaurant() {
        ArrayList<FoodItem> items =
                new ArrayList<>(Arrays.asList(item("burger", 1), item("fries", 2)));

        assertTrue(InventoryService.checkAvailability(1, items),
                "種子餐廳 1 存在於 restaurants 表，應判定為可用");
    }

    /** AC-2：不存在的餐廳 id → 回傳 false。 */
    @Test
    @DisplayName("AC-2：不存在的餐廳 id 999 → checkAvailability 回傳 false")
    void checkAvailabilityReturnsFalseForUnknownRestaurant() {
        ArrayList<FoodItem> items = new ArrayList<>(Arrays.asList(item("burger", 1)));

        assertFalse(InventoryService.checkAvailability(999, items),
                "查無餐廳時 name 保持空白，checkAvailability 應判定為不可用");
    }

    /** AC-3：品項清單為「空清單」時——目前程式行為是忽略 items，結果只取決於餐廳是否存在。 */
    @Test
    @DisplayName("AC-3（以目前程式行為為準）：空品項清單不影響判斷——存在餐廳仍 true（現行實作完全不檢視 items）")
    void checkAvailabilityIgnoresEmptyItemListUnderCurrentImplementation() {
        assertTrue(InventoryService.checkAvailability(1, new ArrayList<>()),
                "現行實作不使用 items 參數：空清單對存在餐廳仍回傳 true");
        assertFalse(InventoryService.checkAvailability(999, new ArrayList<>()),
                "空清單搭配不存在餐廳仍回傳 false（判斷只看餐廳存在與否）");
    }

    /** AC-3：品項清單為「null」時——目前程式行為是忽略 items，null 不會拋例外。 */
    @Test
    @DisplayName("AC-3（以目前程式行為為準）：null 品項清單不拋例外、不影響判斷——存在餐廳仍 true（現行實作完全不檢視 items）")
    void checkAvailabilityIgnoresNullItemListUnderCurrentImplementation() {
        assertTrue(InventoryService.checkAvailability(1, null),
                "現行實作不使用 items 參數：null 清單對存在餐廳仍回傳 true（不拋 NullPointerException）");
        assertFalse(InventoryService.checkAvailability(999, null),
                "null 清單搭配不存在餐廳仍回傳 false");
    }

    /** 種子之外的餐廳：新插入、名字非空白的餐廳立即可被判定為可用（以獨立 id 隔離其他測試）。 */
    @Test
    @DisplayName("新插入的非空白名字餐廳立即可被 checkAvailability 認得（判斷取自当下資料庫現值，非僅種子）")
    void checkAvailabilitySeesFreshlyInsertedRestaurant() throws SQLException {
        String uniqueName = "Saga-Test-Restaurant-" + SagaTestDb.uniqueId("x");
        int restaurantId;

        try (Connection conn = java.sql.DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO restaurants(name, address, contact) VALUES('"
                    + uniqueName + "','1 Test Lane','+10000000000')");
            try (ResultSet keys = stmt.executeQuery("SELECT last_insert_rowid()")) {
                assertTrue(keys.next(), "取得剛插入餐廳的自動生成 id");
                restaurantId = keys.getInt(1);
            }
        }

        try {
            assertTrue(InventoryService.checkAvailability(restaurantId, new ArrayList<>()),
                    "查得到的非空白 name 餐廳應判定為可用");
        } finally {
            try (Connection conn = java.sql.DriverManager.getConnection(URL);
                 Statement cleanup = conn.createStatement()) {
                cleanup.executeUpdate("DELETE FROM restaurants WHERE id = " + restaurantId);
            }
        }
    }

    /** AC-2 延伸：種子範圍邊界——種子只含 id 1..3，id 4 不應存在。 */
    @Test
    @DisplayName("AC-2：種子只有 id 1..3，id 4（超出種子範圍）回傳 false")
    void checkAvailabilityReturnsFalseForIdBeyondSeedRange() {
        assertFalse(InventoryService.checkAvailability(4, new ArrayList<>(Arrays.asList(item("soup", 1)))),
                "id 4 不在種子餐廳內，應判定為不可用");
    }
}
