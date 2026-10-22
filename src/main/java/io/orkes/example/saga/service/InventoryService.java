package io.orkes.example.saga.service;

import io.orkes.example.saga.dao.InventoryDAO;
import io.orkes.example.saga.pojos.FoodItem;
import io.orkes.example.saga.pojos.Restaurant;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;

@Slf4j
public class InventoryService {

    private static final InventoryDAO inventoryDAO = new InventoryDAO("jdbc:sqlite:food_delivery.db");

    public static boolean checkAvailability(int restaurantId, ArrayList<FoodItem> items) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(restaurantId);
        restaurant.setName("");
        inventoryDAO.readRestaurant(restaurantId, restaurant);
        return hasUsableName(restaurant.getName());
    }

    /**
     * 「餐廳可用」判斷（Issue #85）：名稱非 {@code null} 且去除空白後非空才算可用。
     *
     * <p>修前只比對空字串（{@code !Objects.equals(name, "")}），名稱是空白字串
     * 或 NULL 時都會被誤判為可用。保留「查無資料時 {@code name} 維持呼叫端
     * 預設值」的既有行為：{@code InventoryDAO.readRestaurant} 查無資料不覆寫，
     * service 的 {@code ""} 哨兵值經此判斷仍回 {@code false}。
     *
     * <p>此方法同時是測試縫隙：{@code restaurants.name} 的 DDL 是
     * {@code text NOT NULL}，無法插入 name 為 NULL 的餐廳列，NULL 輸入只能
     * 直接驗證判斷式（見 Issue #85 驗證方式與 {@code InventoryServiceTest}）。
     */
    static boolean hasUsableName(String name) {
        return name != null && !name.isBlank();
    }
}
