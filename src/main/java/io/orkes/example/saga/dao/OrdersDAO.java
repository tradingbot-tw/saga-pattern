package io.orkes.example.saga.dao;

import io.orkes.example.saga.pojos.Customer;
import io.orkes.example.saga.pojos.Order;

import java.sql.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.Date;

public class OrdersDAO extends BaseDAO {

    public OrdersDAO(String url) {
        super(url);
    }

    /**
     * 在單一連線、單一交易內寫入訂單與明細（Issue #86）。
     *
     * <p>兩個 {@code INSERT} 共用同一個連線並以 {@code setAutoCommit(false)} 包覆；
     * 任何一步失敗都先 {@code rollback} 再回傳非空錯誤訊息，資料庫不得留下半成品列——
     * 否則 {@code orders} 已提交、{@code orders_details} 失敗時，該 {@code PENDING}
     * 訂單不在任何補償流程的涵蓋範圍內，會永遠滯留。
     *
     * <p>回傳契約不變（呼叫端 {@code OrderService.createOrder} 依賴）：
     * 成功回傳空字串、失敗回傳非空錯誤訊息。
     */
    public String insertOrder(Order order) {
        Date date = new Date();
        Timestamp nowAsTS = new Timestamp(date.getTime());

        String itemsStr = String.join("", order.getOrderDetails().getItems().toString());

        String notesStr;

        if(!order.getOrderDetails().getNotes().isEmpty()) {
            notesStr = String.join("", order.getOrderDetails().getNotes().toString());
        } else {
            notesStr = "";
        }

        String sqlOrder = "INSERT INTO orders(orderId,customerId,restaurantId,deliveryAddress,createdAt,status) VALUES(?,?,?,?,?,?)";
        String sqlDetails = "INSERT INTO orders_details(orderId,items,notes) VALUES(?,?,?)";

        try (Connection conn = this.connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement orderStmt = conn.prepareStatement(sqlOrder);
                 PreparedStatement detailsStmt = conn.prepareStatement(sqlDetails)) {
                orderStmt.setString(1, order.getOrderId());
                orderStmt.setInt(2, order.getCustomer().getId());
                orderStmt.setInt(3, order.getRestaurantId());
                orderStmt.setString(4, order.getDeliveryAddress());
                orderStmt.setTimestamp(5, nowAsTS);
                orderStmt.setString(6, order.getStatus().name());
                orderStmt.executeUpdate();

                detailsStmt.setString(1, order.getOrderId());
                detailsStmt.setString(2, itemsStr);
                detailsStmt.setString(3, notesStr);
                detailsStmt.executeUpdate();

                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    System.out.println(rollbackError.getMessage());
                }
                System.out.println(e.getMessage());
                return errorMessage(e);
            }
        } catch (SQLException e) {
            System.out.println(e.getMessage());
            return errorMessage(e);
        }

        return "";
    }

    /**
     * 保證失敗回傳值「非空」：SQLException 的 message 可能為 null／空字串，
     * 後退為例外字串，維持呼叫端以 {@code isEmpty()} 判成敗的契約。
     */
    private static String errorMessage(SQLException e) {
        String message = e.getMessage();
        return (message == null || message.isEmpty()) ? e.toString() : message;
    }

    /**
     * 更新訂單，回傳受影響的列數（Issue #34：不再 silent no-op）。
     *
     * <p>回傳 {@code 0} 代表 {@code orderId} 不存在，呼叫端必須回報 terminal error，
     * 不得當成補償成功。
     */
    public int updateOrder(Order order) {
        String sql = "UPDATE orders SET restaurantId=?,deliveryAddress=?,status=? WHERE orderId=?";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, order.getRestaurantId());
            pstmt.setString(2, order.getDeliveryAddress());
            pstmt.setString(3, order.getStatus().name());
            pstmt.setString(4, order.getOrderId());
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to update order " + order.getOrderId(), e);
        }
    }

    /**
     * 只把訂單狀態改為 {@code CANCELLED}，回傳受影響的列數（Issue #34 的補償 primitive）。
     *
     * <p>與 {@link #updateOrder(Order)} 的差別是只動 status 欄位，不會用補償時不完整的
     * {@link Order} 覆寫 {@code restaurantId} / {@code deliveryAddress}。
     */
    public int cancelOrder(String orderId) {
        String sql = "UPDATE orders SET status=? WHERE orderId=?";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, Order.Status.CANCELLED.name());
            pstmt.setString(2, orderId);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to cancel order " + orderId, e);
        }
    }

    public void readOrder(String orderId, Order order) {
        String sql = "SELECT orderId, customerId, restaurantId, deliveryAddress, createdAt, status FROM orders WHERE orderId = ?";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, orderId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                order.setOrderId(rs.getString("orderId"));
                Customer customer = new Customer();
                customer.setId(rs.getInt("customerId"));
                order.setCustomer(customer);
                order.setRestaurantId(rs.getInt("restaurantId"));
                order.setDeliveryAddress(rs.getString("deliveryAddress"));
                order.setCreatedAt(rs.getLong("createdAt"));
                order.setStatus(Order.Status.valueOf(rs.getString("status")));
            }

        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
    }

    public int insertCustomer(Customer customer) {
        int id = 0;

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement("SELECT id FROM customers WHERE email = ?")) {
            pstmt.setString(1, customer.getEmail());
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                id = rs.getInt("id");
            }
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }

        if (id > 0) {
            return id;
        }

        String sql = "INSERT INTO customers(email,name,contact) VALUES(?,?,?)";

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, customer.getEmail());
            pstmt.setString(2, customer.getName());
            pstmt.setString(3, customer.getContact());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }

        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement("SELECT id FROM customers WHERE email = ?")) {
            pstmt.setString(1, customer.getEmail());
            ResultSet rs = pstmt.executeQuery();
            id = rs.getInt("id");
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
        return id;
    }
}
