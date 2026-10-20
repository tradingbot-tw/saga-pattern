package io.orkes.example.saga.dao;

import io.orkes.example.saga.pojos.Payment;

import java.sql.*;
import java.util.Date;

public class PaymentsDAO extends BaseDAO {

    public PaymentsDAO(String url) {
        super(url);
    }

    public String insertPayment(Payment payment) {
        Date date = new Date();
        Timestamp nowAsTS = new Timestamp(date.getTime());

        String sql = "INSERT INTO payments(paymentId, orderId, amount, method, createdAt, status) VALUES(?,?,?,?,?,?);";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, payment.getPaymentId());
            pstmt.setString(2, payment.getOrderId());
            pstmt.setDouble(3, payment.getAmount());
            pstmt.setString(4, payment.getPaymentMethod().toString());
            pstmt.setTimestamp(5, nowAsTS);
            pstmt.setString(6, payment.getStatus().name());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println(e.getMessage());
            return e.getMessage();
        }
        return "";
    }

    public int updatePaymentStatus(Payment payment) {
        String sql = "UPDATE payments SET status=? WHERE paymentId=?;";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, payment.getStatus().name());
            pstmt.setString(2, payment.getPaymentId());
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DaoWriteException("Failed to update payment " + payment.getPaymentId(), e);
        }
    }

    /**
     * 取消某訂單的<strong>全部</strong>付款（Issue #34：同一 orderId 可能有多筆付款，
     * 補償要能全數取消），回傳受影響列數；0 代表查無可取消的付款。
     */
    public int cancelPaymentsByOrderId(String orderId) {
        String sql = "UPDATE payments SET status=? WHERE orderId=?;";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, Payment.Status.CANCELED.name());
            pstmt.setString(2, orderId);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DaoWriteException("Failed to cancel payments for order " + orderId, e);
        }
    }

    public void readPayment(String orderId, Payment payment) {
        String sql = "SELECT paymentId, orderId, amount, method, createdAt, status FROM payments WHERE orderId = ?";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, orderId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                payment.setPaymentId(rs.getString("paymentId"));
                payment.setOrderId(rs.getString("orderId"));
                payment.setAmount(rs.getDouble("amount"));
                payment.setCreatedAt(rs.getLong("createdAt"));
                payment.setStatus(Payment.Status.valueOf(rs.getString("status")));
            }

        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
    }
}
