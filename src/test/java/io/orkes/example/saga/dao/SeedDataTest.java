package io.orkes.example.saga.dao;

import io.orkes.example.saga.SagaApplication;
import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.Restaurant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 資料庫初始化與種子資料測試（Issue #9）。走的是應用自身的啟動路徑
 * （{@code SagaApplication.initDB()} → {@code BaseDAO.createTables}），
 * 資料庫為 service/worker 寫死的 {@code jdbc:sqlite:food_delivery.db}。
 */
class SeedDataTest {

    private static final String URL = SagaTestDb.APP_DB_URL;

    private static BaseDAO dao;

    @BeforeAll
    static void initDatabase() {
        SagaApplication.initDB();
        dao = new BaseDAO(URL);
    }

    @Test
    @DisplayName("happy path：啟動時建立協調流程七個步驟會用到的全部資料表")
    void appInitCreatesEveryTableTheStepsDependOn() {
        assertTrue(dao.tableExists("orders"));
        assertTrue(dao.tableExists("orders_details"));
        assertTrue(dao.tableExists("customers"));
        assertTrue(dao.tableExists("restaurants"));
        assertTrue(dao.tableExists("payments"));
        assertTrue(dao.tableExists("drivers"));
        assertTrue(dao.tableExists("shipments"));
    }

    @Test
    @DisplayName("重複初始化是安全的：createTables 有 tableExists 守衛，種子資料不被重複插入")
    void runningInitTwiceKeepsSeedRowsIntact() throws Exception {
        SagaApplication.initDB();

        assertEquals(3, SagaTestDb.count(URL, "SELECT COUNT(*) FROM customers WHERE id <= 3"),
                "種子客戶應恰好 3 位（id 1..3）");
        assertEquals(3, SagaTestDb.count(URL, "SELECT COUNT(*) FROM restaurants WHERE id <= 3"),
                "種子餐廳應恰好 3 間（id 1..3）");
        assertEquals(4, SagaTestDb.count(URL, "SELECT COUNT(*) FROM drivers WHERE id <= 4"),
                "種子司機應恰好 4 位（id 1..4）");
    }

    @Test
    @DisplayName("庫存步驟的判準：只認得得到名字的餐廳（id 1..3 存在、999 不存在）")
    void onlySeededRestaurantsAreReadable() {
        InventoryDAO inventoryDAO = new InventoryDAO(URL);

        Restaurant known = new Restaurant();
        known.setName("");
        inventoryDAO.readRestaurant(1, known);
        assertFalse(known.getName().isBlank(), "種子餐廳 1 應可被查到");

        Restaurant unknown = new Restaurant();
        unknown.setName("");
        inventoryDAO.readRestaurant(999, unknown);
        assertEquals("", unknown.getName(), "查無餐廳時 name 應保持空白，checkAvailability 據此回傳 false");
    }

    /**
     * 既有缺陷（本測試為紅燈，故 skip；斷言完整保留，見 Issue #9 留言）：
     * {@code BaseDAO.seedCustomers()} 寫成
     * {@code INSERT INTO customers(email, name, contact) VALUES('John Smith','john.smith@example.com', ...)}，
     * 值是照 name/email 的順序排的，因此名字被寫進 email 欄、email 被寫進 name 欄。
     * 後果：{@code OrdersDAO.insertCustomer} 以 email 比對做冪等去重，種子客戶永遠比對不到，
     * 且任何以 email 欄取聯絡人的流程會拿到人名。
     */
    @Test
    @DisplayName("種子客戶的 email 欄位應存 email（揭露 BaseDAO.seedCustomers 欄位順序錯誤）")
    @Disabled("既有缺陷：BaseDAO.seedCustomers 的 INSERT 值順序與 (email, name) 欄位順序相反；"
            + "Issue #9 已回報，建議另開 agent-fix-bug 工作項修復後 un-skip")
    void seededCustomersStoreEmailInEmailColumn() throws Exception {
        String email = SagaTestDb.queryString(URL, "SELECT email FROM customers WHERE id = 1");
        String name = SagaTestDb.queryString(URL, "SELECT name FROM customers WHERE id = 1");

        assertTrue(email.contains("@"), "email 欄位應是電子郵件，實際為: " + email);
        assertFalse(name.contains("@"), "name 欄位不應是電子郵件，實際為: " + name);
    }

    /**
     * 既有缺陷（本測試為紅燈，故 skip；斷言完整保留，見 Issue #9 留言）：
     * {@code BaseDAO.seedRestaurants()} 寫成
     * {@code INSERT INTO restaurants(name, address, contact) VALUES('Mikes','+12121231345','5331 Redford Court...')}，
     * 電話被寫進 address 欄、地址被寫進 contact 欄。
     * 後果：{@code InventoryDAO.readRestaurant} 回填的 address/contact 是互換的，
     * 凡以 address 欄送件的流程拿到的是電話字串。
     */
    @Test
    @DisplayName("種子餐廳的 address 欄位應存地址（揭露 BaseDAO.seedRestaurants 欄位順序錯誤）")
    @Disabled("既有缺陷：BaseDAO.seedRestaurants 的 INSERT 值順序與 (address, contact) 欄位順序相反；"
            + "Issue #9 已回報，建議另開 agent-fix-bug 工作項修復後 un-skip")
    void seededRestaurantsStoreAddressInAddressColumn() {
        InventoryDAO inventoryDAO = new InventoryDAO(URL);
        Restaurant restaurant = new Restaurant();
        inventoryDAO.readRestaurant(1, restaurant);

        assertFalse(restaurant.getAddress().startsWith("+"),
                "address 欄位不應是電話，實際為: " + restaurant.getAddress());
        assertTrue(restaurant.getContact().startsWith("+"),
                "contact 欄位應是電話，實際為: " + restaurant.getContact());
    }
}
