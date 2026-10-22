package io.orkes.example.saga.pojos;

import lombok.Data;

import java.util.ArrayList;

@Data
public class OrderRequest {
    /**
     * 由 workflow 決定的訂單 id（{@code ${workflow.workflowId}}，#137）。
     *
     * <p>讓補償 workflow 不必依賴 {@code order_food} 的輸出也知道要取消哪一筆訂單：
     * 即使 {@code order_food} 逾時、結果沒回報，訂單也可能已建立。未提供時由 {@code createOrder} 產生 UUID。
     */
    private String orderId;
    private String customerEmail;
    private String customerName;
    private String customerContact;
    private int restaurantId;
    private ArrayList<FoodItem> items;
    private ArrayList<String> notes;
    private String deliveryAddress;
    private String deliveryInstructions;
}
