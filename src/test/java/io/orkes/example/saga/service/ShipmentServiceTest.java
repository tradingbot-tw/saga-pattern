package io.orkes.example.saga.service;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.dao.DataAccessException;
import io.orkes.example.saga.dao.ShipmentDAO;
import io.orkes.example.saga.pojos.Shipment;
import io.orkes.example.saga.pojos.ShippingRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShipmentService} 的 service 層測試（Issue #54）。
 *
 * <p>涵蓋 {@code createShipment} 的四條路徑與 {@code cancelDelivery} 的既有／查無兩種結果。
 * 其中最重要的是**找不到司機時的自我補償**：補償 workflow 依賴
 * 「{@code ship_food} 失敗時 {@code ShipmentService} 已自行取消出貨」，
 * 因此 {@code ship_food} 失敗後不再呼叫 {@code cancel_delivery}
 * （見 {@code conductor/PROVENANCE.md} 的補償對照表）。
 *
 * <p>{@code ShipmentService} 把 DAO 連線字串寫死為 {@code jdbc:sqlite:food_delivery.db}，
 * 無法注入，因此本測試沿用應用的資料庫檔（.gitignore 已排除）並以唯一 orderId 隔離各情境。
 * 「找不到司機」以既有資料操作達成：暫時移除 {@code findDriver()} 可能抽到的司機列
 * （id 1–3；id 0 本來就不存在），不動 {@code findDriver()} 的實作，結束後於 finally 還原。
 */
class ShipmentServiceTest {

    private static final String URL = SagaTestDb.APP_DB_URL;

    /** {@code ShipmentService.findDriver()} 只會回傳 0–3。 */
    private static final String DRIVER_IDS_REACHABLE_BY_FIND_DRIVER = "id BETWEEN 1 AND 3";

    @BeforeAll
    static void prepareDatabase() {
        SagaTestDb.createAppSchema();
    }

    private static ShippingRequest shippingRequest(String orderId) {
        ShippingRequest request = new ShippingRequest();
        request.setOrderId(orderId);
        request.setDeliveryAddress("1693 Alice Court, Annapolis MD 21401");
        request.setDeliveryInstructions("放門口");
        return request;
    }

    private static String statusOf(String orderId) throws SQLException {
        return SagaTestDb.queryString(URL,
                "SELECT status FROM shipments WHERE orderId = '" + orderId + "'");
    }

    private static int shipmentCount(String orderId) throws SQLException {
        return SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM shipments WHERE orderId = '" + orderId + "'");
    }

    /** 可直接丟例外的測試動作。 */
    @FunctionalInterface
    private interface JdbcAction {
        void run() throws SQLException;
    }

    /**
     * 暫時移除 {@code findDriver()} 可能抽到的司機列，讓每一次隨機挑選都落空。
     *
     * <p>先備份再刪除，{@code finally} 一律還原，避免影響共用同一個資料庫檔的其他測試。
     */
    private static void withoutAvailableDrivers(JdbcAction action) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {

            List<String[]> backup = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, name, contact FROM drivers WHERE " + DRIVER_IDS_REACHABLE_BY_FIND_DRIVER)) {
                while (rs.next()) {
                    backup.add(new String[]{rs.getString("id"), rs.getString("name"), rs.getString("contact")});
                }
            }

            stmt.executeUpdate("DELETE FROM drivers WHERE " + DRIVER_IDS_REACHABLE_BY_FIND_DRIVER);
            try {
                action.run();
            } finally {
                try (PreparedStatement restore = conn.prepareStatement(
                        "INSERT INTO drivers(id, name, contact) VALUES(?, ?, ?)")) {
                    for (String[] driver : backup) {
                        restore.setInt(1, Integer.parseInt(driver[0]));
                        restore.setString(2, driver[1]);
                        restore.setString(3, driver[2]);
                        restore.addBatch();
                    }
                    restore.executeBatch();
                }
            }
        }
    }

    @Test
    @DisplayName("happy path：有司機時 createShipment 回傳非 0 的 driverId，DB 狀態為 CONFIRMED")
    void createShipmentAssignsDriverAndConfirmsShipment() throws SQLException {
        String orderId = SagaTestDb.uniqueId("order");

        int driverId = ShipmentService.createShipment(shippingRequest(orderId));

        assertNotEquals(0, driverId, "有可指派司機時應回傳實際 driverId");
        assertEquals(String.valueOf(driverId),
                SagaTestDb.queryString(URL,
                        "SELECT driverId FROM shipments WHERE orderId = '" + orderId + "'"),
                "回傳的 driverId 必須與落庫的指派結果一致");
        assertEquals(Shipment.Status.CONFIRMED.name(), statusOf(orderId),
                "找到司機後外送單應由 SCHEDULED 轉為 CONFIRMED");
    }

    @Test
    @DisplayName("補償路徑：找不到司機時 createShipment 回傳 0，並自行把出貨狀態改為 CANCELED")
    void createShipmentCancelsItselfWhenNoDriverIsAvailable() throws SQLException {
        String orderId = SagaTestDb.uniqueId("order");

        withoutAvailableDrivers(() ->
                assertEquals(0, ShipmentService.createShipment(shippingRequest(orderId)),
                        "assign 不到司機時應以 0 回報失敗"));

        assertEquals(1, shipmentCount(orderId), "補償是先寫入再取消，仍應留下同一筆外送紀錄");
        assertEquals(Shipment.Status.CANCELED.name(), statusOf(orderId),
                "找不到司機時 service 必須自行取消出貨，讓 workflow 不必再呼叫 cancel_delivery");
    }

    @Test
    @DisplayName("步驟失敗：送件地址缺失導致寫入失敗時回傳 0，且不留下任何出貨紀錄")
    void createShipmentReturnsZeroWithoutPersistingWhenInsertFails() throws SQLException {
        String orderId = SagaTestDb.uniqueId("order");
        ShippingRequest request = shippingRequest(orderId);
        request.setDeliveryAddress(null);

        assertEquals(0, ShipmentService.createShipment(request),
                "寫入失敗時應回傳 0，讓 worker 回報可重試的 FAILED");

        assertEquals(0, shipmentCount(orderId), "寫入失敗不應留下部分寫入的外送紀錄");
        assertNull(statusOf(orderId), "沒有外送紀錄就沒有狀態");
    }

    @Test
    @Disabled("Issue #84：修復前 confirmShipment 吞掉 SQLException，02-impl 會 un-skip")
    @DisplayName("確認失敗：confirmShipment 的 UPDATE 失敗時 createShipment 拋出 DataAccessException，且 DB 不得停在 CONFIRMED")
    void createShipmentPropagatesWhenConfirmFails() throws SQLException {
        String orderId = SagaTestDb.uniqueId("order");

        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TRIGGER fail_confirm_shipment BEFORE UPDATE ON shipments "
                    + "WHEN NEW.status = 'CONFIRMED' BEGIN SELECT RAISE(ABORT, 'simulated confirm failure'); END");
            try {
                DataAccessException thrown = assertThrows(DataAccessException.class,
                        () -> ShipmentService.createShipment(shippingRequest(orderId)),
                        "確認失敗時必須往外拋，不得回傳 driverId 假裝成功");
                assertTrue(thrown.getMessage().contains("simulated confirm failure"),
                        "例外訊息應保留資料庫錯誤，實際為: " + thrown.getMessage());
            } finally {
                stmt.execute("DROP TRIGGER IF EXISTS fail_confirm_shipment");
            }
        }

        assertNotEquals(Shipment.Status.CONFIRMED.name(), statusOf(orderId),
                "確認失敗時外送單不得停在 CONFIRMED");
    }

    @Test
    @Disabled("Issue #84：修復前 confirmShipment 忽略受影響列數，02-impl 會 un-skip")
    @DisplayName("確認失敗：confirmShipment 未更新任何列時 createShipment 仍須拋出 DataAccessException")
    void createShipmentPropagatesWhenConfirmUpdatesNoRows() throws SQLException {
        String orderId = SagaTestDb.uniqueId("order");

        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TRIGGER skip_confirm_shipment BEFORE UPDATE ON shipments "
                    + "WHEN NEW.status = 'CONFIRMED' BEGIN SELECT RAISE(IGNORE); END");
            try {
                assertThrows(DataAccessException.class,
                        () -> ShipmentService.createShipment(shippingRequest(orderId)),
                        "UPDATE 沒有命中任何列也是確認失敗，不得回傳 driverId");
            } finally {
                stmt.execute("DROP TRIGGER IF EXISTS skip_confirm_shipment");
            }
        }

        assertNotEquals(Shipment.Status.CONFIRMED.name(), statusOf(orderId),
                "確認失敗時外送單不得停在 CONFIRMED");
    }

    @Test
    @DisplayName("補償：cancelDelivery 取消既有外送單回傳 1，DB 狀態為 CANCELED")
    void cancelDeliveryCancelsExistingShipment() throws SQLException {
        String orderId = SagaTestDb.uniqueId("order");
        Shipment shipment = new Shipment();
        shipment.setOrderId(orderId);
        shipment.setDriverId(1);
        shipment.setDeliveryAddress("1693 Alice Court, Annapolis MD 21401");
        shipment.setDeliveryInstructions("放門口");
        assertTrue(new ShipmentDAO(URL).insertShipment(shipment), "前置條件：應成功寫入外送單");

        assertEquals(1, ShipmentService.cancelDelivery(orderId),
                "既有外送單應被取消一列");

        assertEquals(Shipment.Status.CANCELED.name(), statusOf(orderId));
    }

    @Test
    @DisplayName("補償的邊界：cancelDelivery 查無外送單時回傳 0，不拋錯也不留下紀錄")
    void cancelDeliveryOnUnknownOrderReturnsZero() throws SQLException {
        String orderId = SagaTestDb.uniqueId("ghost-order");

        assertEquals(0, ShipmentService.cancelDelivery(orderId),
                "查無外送單時 service 回傳 0，由 worker 決定 terminal error");

        assertEquals(0, shipmentCount(orderId));
    }
}
