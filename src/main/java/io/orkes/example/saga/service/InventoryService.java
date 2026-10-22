package io.orkes.example.saga.service;

import io.orkes.example.saga.dao.InventoryDAO;
import io.orkes.example.saga.pojos.FoodItem;
import io.orkes.example.saga.pojos.Restaurant;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Objects;

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
     * 「餐廳可用」的名稱判斷式。抽成獨立方法讓測試能直接驗證 NULL／空白邊界
     * （Issue #85：{@code restaurants.name} 是 {@code text NOT NULL}，無法以資料列製造 NULL）。
     * 本層僅為測試接縫，行為與原本的 {@code !Objects.equals(name, "")} 相同。
     */
    static boolean isUsableRestaurantName(String name) {
        return !Objects.equals(name, "");
    }
}
