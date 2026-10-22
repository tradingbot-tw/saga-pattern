package io.orkes.example.saga.dao;

import io.orkes.example.saga.pojos.Driver;
import io.orkes.example.saga.pojos.Shipment;

import java.sql.*;
import java.util.Date;

public class ShipmentDAO extends BaseDAO {

    public ShipmentDAO(String url) {
        super(url);
    }

    public boolean insertShipment(Shipment shipment) {
        Date date = new Date();
        Timestamp nowAsTS = new Timestamp(date.getTime());

        String sql = "INSERT INTO shipments(orderId,driverId,address,instructions,createdAt,status) VALUES(?,?,?,?,?,?)";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, shipment.getOrderId());
            pstmt.setInt(2, shipment.getDriverId());
            pstmt.setString(3, shipment.getDeliveryAddress());
            pstmt.setString(4, shipment.getDeliveryInstructions());
            pstmt.setTimestamp(5, nowAsTS);
            pstmt.setString(6, Shipment.Status.SCHEDULED.name());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println(e.getMessage());
            return false;
        }
        return true;
    }
    /**
     * 取消外送單，回傳受影響的列數（Issue #34：不再 silent no-op）。
     *
     * <p>回傳 {@code 0} 代表該 {@code orderId} 沒有可取消的外送單。
     */
    public int cancelShipment(String orderId) {
        String sql = "UPDATE shipments SET status=? WHERE orderId=?;";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, Shipment.Status.CANCELED.name());
            pstmt.setString(2, orderId);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to cancel shipment for order " + orderId, e);
        }
    }

    /**
     * 確認外送單，回傳受影響的列數（Issue #84：失敗不再被吞掉）。
     *
     * <p>與 {@link #cancelShipment} 一致的失敗語意：{@code SQLException} 包成
     * {@link DataAccessException} 往外拋，讓 service／worker 能把資料庫失敗回報成
     * 可重試的 task 失敗，而不是在外送單仍停在 {@code SCHEDULED} 時誤報成功。
     *
     * <p>回傳 {@code 0} 代表該 {@code orderId} 沒有可確認的外送單。
     */
    public int confirmShipment(String orderId) {
        String sql = "UPDATE shipments SET status=? WHERE orderId=?;";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, Shipment.Status.CONFIRMED.name());
            pstmt.setString(2, orderId);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to confirm shipment for order " + orderId, e);
        }
    }

    public void readDriver(int driverId, Driver driver) {
        String sql = "SELECT name, contact FROM drivers WHERE id = ?";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, driverId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                driver.setId(driverId);
                driver.setName(rs.getString("name"));
                driver.setContact(rs.getString("contact"));
            }

        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
    }
}
