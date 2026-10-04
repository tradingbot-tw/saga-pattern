package io.orkes.example.saga.dao;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.Driver;
import io.orkes.example.saga.pojos.Shipment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShipmentDAO} 的行為測試（Issue #9）：外送單的狀態機
 * SCHEDULED → CONFIRMED，以及補償路徑 SCHEDULED/CONFIRMED → CANCELED。
 */
class ShipmentDAOTest {

    private static String url;
    private static ShipmentDAO dao;

    @BeforeAll
    static void createDatabase() throws IOException {
        url = SagaTestDb.tempUrl("shipments");
        SagaTestDb.createSchema(url);
        dao = new ShipmentDAO(url);
    }

    @AfterAll
    static void dropDatabase() {
        SagaTestDb.delete(url);
    }

    private static Shipment shipment(String orderId) {
        Shipment shipment = new Shipment();
        shipment.setOrderId(orderId);
        shipment.setDriverId(1);
        shipment.setDeliveryAddress("5331 Redford Court, Montgomery AL 36116");
        shipment.setDeliveryInstructions("放門口");
        return shipment;
    }

    private static String statusOf(String orderId) throws SQLException {
        return SagaTestDb.queryString(url,
                "SELECT status FROM shipments WHERE orderId = '" + orderId + "'");
    }

    @Test
    @DisplayName("happy path：新外送單以 SCHEDULED 進 DB（POJO 的 status 欄位不參與寫入）")
    void insertShipmentStartsAsScheduled() throws SQLException {
        String orderId = SagaTestDb.uniqueId("order");
        Shipment shipment = shipment(orderId);
        shipment.setStatus("SENT"); // 實作不讀取此欄位，寫入時一律用 SCHEDULED

        assertTrue(dao.insertShipment(shipment));
        assertEquals(Shipment.Status.SCHEDULED.name(), statusOf(orderId));
    }

    @Test
    @DisplayName("狀態機：confirmShipment 把 SCHEDULED 轉為 CONFIRMED")
    void confirmShipmentMovesToConfirmed() throws SQLException {
        String orderId = SagaTestDb.uniqueId("order");
        dao.insertShipment(shipment(orderId));

        dao.confirmShipment(orderId);

        assertEquals(Shipment.Status.CONFIRMED.name(), statusOf(orderId));
    }

    @Test
    @DisplayName("補償/回滾：cancelShipment 把已確認的外送單轉為 CANCELED")
    void cancelShipmentMovesConfirmedToCancelled() throws SQLException {
        String orderId = SagaTestDb.uniqueId("order");
        dao.insertShipment(shipment(orderId));
        dao.confirmShipment(orderId);

        dao.cancelShipment(orderId);

        assertEquals(Shipment.Status.CANCELED.name(), statusOf(orderId));
    }

    @Test
    @DisplayName("補償/回滾：cancelShipment 可撤銷尚未確認（SCHEDULED）的外送單")
    void cancelShipmentMovesScheduledToCancelled() throws SQLException {
        String orderId = SagaTestDb.uniqueId("order");
        dao.insertShipment(shipment(orderId));

        dao.cancelShipment(orderId);

        assertEquals(Shipment.Status.CANCELED.name(), statusOf(orderId));
    }

    @Test
    @DisplayName("步驟失敗訊號：送件地址缺失（NOT NULL）時 insertShipment 回傳 false")
    void insertShipmentFailsWhenAddressMissing() {
        Shipment shipment = shipment(SagaTestDb.uniqueId("order"));
        shipment.setDeliveryAddress(null);

        assertFalse(dao.insertShipment(shipment), "寫入失敗應讓 ShipmentService 回傳 0");
    }

    @Test
    @DisplayName("司機資料：種子司機可讀到名字，查無司機時維持空字串（service 據此判斷無人可送）")
    void readDriverDistinguishesKnownFromUnknown() {
        Driver known = new Driver();
        known.setName("");
        dao.readDriver(1, known);
        assertFalse(known.getName().isBlank(), "種子司機 1 應有名字");
        assertEquals(1, known.getId());

        Driver unknown = new Driver();
        unknown.setName("");
        dao.readDriver(999, unknown);
        assertEquals("", unknown.getName(), "查無司機時不應寫入名字，讓 isBlank() 判斷成立");
    }

    @Test
    @DisplayName("狀態機：外送狀態只有 SCHEDULED/CONFIRMED/DELIVERED/CANCELED 四種")
    void shipmentStatusEnumIsClosed() {
        assertEquals(4, Shipment.Status.values().length);
        for (Shipment.Status status : Shipment.Status.values()) {
            assertEquals(status, Shipment.Status.valueOf(status.name()));
        }
    }
}
