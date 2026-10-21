package io.orkes.example.saga.service;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.dao.ShipmentDAO;
import io.orkes.example.saga.pojos.Shipment;
import io.orkes.example.saga.pojos.ShippingRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link ShipmentService} 的步驟行為測試（Issue #54）。
 *
 * <p>補償 workflow 依賴「{@code ship_food} 失敗時 {@code ShipmentService} 已自行取消出貨」
 * 這個前提（conductor/PROVENANCE.md §4 補償對照表：{@code ship_food} 失敗時補償流程
 * 只走 {@code cancel_payment → cancel_order}，<b>不重複取消出貨</b>），
 * 而這個前提此前只有 DAO 層測試，service 層沒有保護。
 *
 * <p>service 層把 DAO 連線字串寫死為 {@code jdbc:sqlite:food_delivery.db}
 * （見 {@link SagaTestDb} 文件），因此本測試沿用應用的資料庫檔
 * （.gitignore 已排除），並用唯一 orderId 隔開各情境；需要控制「找不到司機」時
 * 以現有資料設定（drivers 表內容）達成，不修改 {@code findDriver()} 實作，
 * 並保證在 {@code finally} 中還原種子司機資料，不影響其他測試類別。
 */
class ShipmentServiceTest {

    private static final String URL = SagaTestDb.APP_DB_URL;

    private static final String ADDRESS = "5331 Redford Court, Montgomery AL 36116";

    @BeforeAll
    static void prepareDatabase() {
        SagaTestDb.createAppSchema();
    }

    private static ShippingRequest request(String orderId) {
        ShippingRequest request = new ShippingRequest();
        request.setOrderId(orderId);
        request.setDeliveryAddress(ADDRESS);
        request.setDeliveryInstructions("放門口");
        return request;
    }

    /** 一筆可直接入庫的出貨資料（繞過 findDriver() 的隨機性，用於鋪測試資料）。 */
    private static Shipment shipment(String orderId) {
        Shipment shipment = new Shipment();
        shipment.setOrderId(orderId);
        shipment.setDriverId(1);
        shipment.setDeliveryAddress(ADDRESS);
        shipment.setDeliveryInstructions("放門口");
        return shipment;
    }

    private static String statusOf(String orderId) throws SQLException {
        return SagaTestDb.queryString(URL,
                "SELECT status FROM shipments WHERE orderId = '" + orderId + "'");
    }

    private static int shipmentCount(String orderId) throws SQLException {
        return SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM shipments WHERE orderId = '" + orderId + "'");
    }

    /** 備份 drivers 表全部列（id、name、contact），供「找不到司機」情境還原用。 */
    private static List<String[]> snapshotDrivers() throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, contact FROM drivers ORDER BY id")) {
            while (rs.next()) {
                rows.add(new String[] {
                        String.valueOf(rs.getInt("id")),
                        rs.getString("name"),
                        rs.getString("contact")
                });
            }
        }
        return rows;
    }

    /** 以顯式 id 回插備份資料，讓種子司機（id 1..4）恢復原樣。 */
    private static void restoreDrivers(List<String[]> rows) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL); Statement stmt = conn.createStatement()) {
            for (String[] row : rows) {
                String contact = row[2] == null ? "NULL" : "'" + row[2].replace("'", "''") + "'";
                stmt.executeUpdate("INSERT INTO drivers(id, name, contact) VALUES("
                        + row[0] + ", '" + row[1].replace("'", "''") + "', " + contact + ")");
            }
        }
    }

    @Test
    @DisplayName("happy path：有司機可派 → 回傳非 0 的 driverId，DB 出貨狀態為 CONFIRMED")
    void assignedDriverConfirmsShipment() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");

        int driverId = ShipmentService.createShipment(request(orderId));

        // findDriver() 擲 0..3（十次都擲到 0 的機率 (1/4)^10 ≈ 1e-6），
        // 種子司機 id 1..4 都有名字，故此路徑在既有實作上必然成立。
        assertNotEquals(0, driverId, "找到司機時應回傳配對到的 driverId");
        assertEquals(Shipment.Status.CONFIRMED.name(), statusOf(orderId),
                "正向步驟完成後出貨應停留在 CONFIRMED");
        assertEquals(driverId, SagaTestDb.count(URL,
                        "SELECT driverId FROM shipments WHERE orderId = '" + orderId + "'"),
                "入庫的 driverId 必須等於回傳值——worker 會把它輸出給 notify_driver");
    }

    @Test
    @DisplayName("找不到司機：createShipment 自行補償 → 回傳 0 且 DB 出貨狀態轉為 CANCELED")
    void missingDriverSelfCancelsShipment() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        List<String[]> backup = snapshotDrivers();
        try (Connection conn = DriverManager.getConnection(URL); Statement stmt = conn.createStatement()) {
            // 用現有資料設定控制情境：findDriver() 只可能選 0..3，清空司機後
            // 任何可能結果都查不到有名字的司機 → 必然走「找不到司機」的自行補償分支。
            stmt.executeUpdate("DELETE FROM drivers");

            int driverId = ShipmentService.createShipment(request(orderId));

            assertEquals(0, driverId, "找不到司機時 service 必須回傳 0，讓 worker 轉 terminal failure");
            assertEquals(Shipment.Status.CANCELED.name(), statusOf(orderId),
                    "PROVENANCE.md §4 補償對照表的前提：ship_food 失敗時已自行取消出貨，"
                            + "補償流程才不需要（也不該）再呼叫 cancel_delivery");
            assertEquals(1, shipmentCount(orderId), "自行補償是把出貨改為 CANCELED，不是刪掉紀錄");
        } finally {
            restoreDrivers(backup);
        }
    }

    @Test
    @DisplayName("寫入出貨失敗（送件地址缺失）→ 回傳 0，不留下任何出貨紀錄")
    void insertFailureLeavesNoShipmentRecord() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        ShippingRequest request = request(orderId);
        request.setDeliveryAddress(null); // shipments.address 為 NOT NULL → insertShipment 回傳 false

        int driverId = ShipmentService.createShipment(request);

        assertEquals(0, driverId, "插入失敗時 service 必須回傳 0");
        assertEquals(0, shipmentCount(orderId), "插入失敗後不得留下半成品紀錄");
    }

    @Test
    @DisplayName("補償/回滾：cancelDelivery 取消既有出貨 → 回傳受影響列數 1，DB 狀態轉 CANCELED")
    void cancelDeliveryCancelsExistingShipment() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        new ShipmentDAO(URL).insertShipment(shipment(orderId)); // 直接鋪資料，避開 findDriver() 隨機性

        assertEquals(1, ShipmentService.cancelDelivery(orderId), "既有出貨應被取消一列");
        assertEquals(Shipment.Status.CANCELED.name(), statusOf(orderId));
    }

    @Test
    @DisplayName("補償的邊界：查無出貨時 cancelDelivery 回傳 0、不拋錯，也不留下紀錄")
    void cancelDeliveryOnUnknownOrderIsHarmless() throws Exception {
        String orderId = SagaTestDb.uniqueId("ghost");

        assertEquals(0, ShipmentService.cancelDelivery(orderId),
                "查無出貨時回傳 0（Issue #34：不再 silent no-op），由 worker 決定 task 結果");
        assertEquals(0, shipmentCount(orderId), "取消不存在的出貨不應無中生有");
    }
}
