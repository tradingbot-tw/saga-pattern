package io.orkes.example.saga.service;

import io.orkes.example.saga.dao.OrdersDAO;
import io.orkes.example.saga.pojos.Customer;
import io.orkes.example.saga.pojos.Order;
import io.orkes.example.saga.pojos.OrderDetails;
import io.orkes.example.saga.pojos.OrderRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@AllArgsConstructor
@Service
public class OrderService {

    private static final OrdersDAO ORDERS_DAO = new OrdersDAO("jdbc:sqlite:food_delivery.db");

    public static String createOrder(OrderRequest orderRequest) {
        UUID uuid = UUID.randomUUID();
        String uuidAsString = uuid.toString();

        Order order = new Order();
        order.setOrderId(uuidAsString);

        Customer customer = new Customer();
        customer.setEmail(orderRequest.getCustomerEmail());
        customer.setName(orderRequest.getCustomerName());
        customer.setContact(orderRequest.getCustomerContact());
        customer.setId(ORDERS_DAO.insertCustomer(customer));

        log.info("Upsert customer record in DB with id: {}", customer.getId());

        order.setCustomer(customer);
        order.setRestaurantId(orderRequest.getRestaurantId());
        order.setDeliveryAddress(orderRequest.getDeliveryAddress());
        order.setStatus(Order.Status.PENDING);

        OrderDetails orderDetails = new OrderDetails();
        orderDetails.setOrderId(uuidAsString);
        orderDetails.setItems(orderRequest.getItems());
        orderDetails.setNotes(orderRequest.getNotes());

        order.setOrderDetails(orderDetails);

        String error = ORDERS_DAO.insertOrder(order);

        if (error.isEmpty()) {
            log.info("Created order with id: {}", order.getOrderId());
        }
        else {
            log.error("Order creation failure: {}", error);
            return null;
        }

        return uuidAsString;
    }

    public static Order getOrder(String orderId) {
        Order order = new Order();
        ORDERS_DAO.readOrder(orderId, order);
        return order;
    }

    /**
     * 取消訂單，回傳受影響的列數（Issue #34）。
     *
     * <p>查無 {@code orderId} 時回傳 {@code 0}；資料庫失敗時丟出
     * {@link io.orkes.example.saga.dao.DataAccessException}，由 worker 轉成 task 失敗。
     */
    public static int cancelOrder(String orderId) {
        log.info("Cancelling order {}", orderId);
        return ORDERS_DAO.cancelOrder(orderId);
    }
}
