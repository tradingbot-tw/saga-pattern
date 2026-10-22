package io.orkes.example.saga.dao;

import io.orkes.example.saga.pojos.Payment;
import io.orkes.example.saga.pojos.PaymentDetails;
import io.orkes.example.saga.pojos.PaymentMethod;

import java.sql.*;
import java.util.Date;

public class PaymentsDAO extends BaseDAO {

    public PaymentsDAO(String url) {
        super(url);
    }

    /**
     * 產生寫入 {@code payments.method} 的稽核字串（Issue #83）。
     *
     * <p>只保留付款方式 {@code type} 與卡號後四碼，例如 {@code "Credit Card ****1111"}；
     * 沒有卡號時只回傳 {@code type}。完整卡號、{@code cvv} 與 {@code expiry} 一律不寫入。
     */
    static String toAuditMethodString(PaymentMethod method) {
        if (method == null) {
            return "";
        }

        String type = method.getType() == null ? "" : method.getType().trim();
        PaymentDetails details = method.getDetails();
        String number = details == null ? null : details.getNumber();
        if (number == null) {
            return type;
        }

        String digits = number.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return type;
        }

        String suffix = "****" + digits.substring(digits.length() - 4);
        return type.isEmpty() ? suffix : type + " " + suffix;
    }

    public String insertPayment(Payment payment) {
        Date date = new Date();
        Timestamp nowAsTS = new Timestamp(date.getTime());

        String sql = "INSERT INTO payments(paymentId, orderId, amount, method, createdAt, status) VALUES(?,?,?,?,?,?);";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, payment.getPaymentId());
            pstmt.setString(2, payment.getOrderId());
            pstmt.setDouble(3, payment.getAmount());
            pstmt.setString(4, toAuditMethodString(payment.getPaymentMethod()));
            pstmt.setTimestamp(5, nowAsTS);
            pstmt.setString(6, payment.getStatus().name());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println(e.getMessage());
            return e.getMessage();
        }
        return "";
    }

    /**
     * 更新付款狀態，回傳受影響的列數（Issue #34：不再 silent no-op）。
     *
     * <p>回傳 {@code 0} 代表 {@code paymentId} 不存在。
     */
    public int updatePaymentStatus(Payment payment) {
        String sql = "UPDATE payments SET status=? WHERE paymentId=?;";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, payment.getStatus().name());
            pstmt.setString(2, payment.getPaymentId());
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to update payment " + payment.getPaymentId(), e);
        }
    }

    /**
     * 把同一 {@code orderId} 的所有付款一次改為 {@code CANCELED}，回傳受影響的列數
     * （Issue #34：多重付款不可只取消最後一筆）。
     */
    public int cancelPaymentsByOrderId(String orderId) {
        String sql = "UPDATE payments SET status=? WHERE orderId=?;";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, Payment.Status.CANCELED.name());
            pstmt.setString(2, orderId);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to cancel payments for order " + orderId, e);
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
