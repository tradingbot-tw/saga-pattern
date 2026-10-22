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
        return isUsableRestaurantName(restaurant.getName());
    }

    /**
     * 「餐廳可用」的名稱判斷式：名稱非 {@code null} 且 {@link String#isBlank()} 為 false 才可用。
     * 抽成獨立方法讓測試能直接驗證 NULL／空白邊界
     * （Issue #85：{@code restaurants.name} 是 {@code text NOT NULL}，無法以資料列製造 NULL）。
     */
    static boolean isUsableRestaurantName(String name) {
        return name != null && !name.isBlank();
    }
}
