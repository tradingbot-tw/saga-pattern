package io.orkes.example.saga.pojos;

import lombok.Data;

/**
 * {@code notify_customer} task 的輸入（Issue #24 / G2）。
 *
 * <p>只包含 workflow 起始輸入實際能提供的欄位：{@code orderId} 來自
 * {@code order_food.output.orderId}，{@code customerEmail}／{@code customerName}
 * 來自 {@link FoodDeliveryRequest}。原本使用的完整 {@link Order} 物件在 repo 內
 * 沒有任何生產者，Conductor workflow 定義無法綁定。
 */
@Data
public class CustomerNotificationRequest {
    private String orderId;
    private String customerEmail;
    private String customerName;
}
