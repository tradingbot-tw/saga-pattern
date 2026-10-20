package io.orkes.example.saga.pojos;

import lombok.Data;

/**
 * {@code notify_customer} 的最小輸入物件（Issue #24 G2）。
 *
 * <p>取代先前錯誤綁定的 {@link Order}：repo 內沒有任何 task 輸出 {@code Order}，
 * 工作流也無法提供它。本類別只涵蓋工作流實際能提供的欄位——
 * {@code customerEmail}／{@code customerName} 出自起始輸入（見
 * {@code WorkflowService#startFoodDeliveryWorkflow}），{@code orderId} 出自
 * {@code order_food} 的輸出。
 */
@Data
public class CustomerNotificationRequest {
    String orderId;
    String customerEmail;
    String customerName;
}
