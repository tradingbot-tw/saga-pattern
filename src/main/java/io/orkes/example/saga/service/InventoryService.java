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
        return hasUsableName(restaurant.getName());
    }

    /**
     * 「餐廳可用」判斷的測試縫隙（Issue #85）：從 {@link #checkAvailability}
     * 原樣抽出，本層方法體仍維持既有行為（只比對空字串），不做任何修正。
     *
     * <p>抽出是為了讓 NULL 名稱可以直接驗證判斷式：{@code restaurants} 的 DDL
     * 是 {@code name text NOT NULL}，無法插入 name 為 NULL 的餐廳列，
     * 透過 DAO／{@code checkAvailability} 端不到這個輸入（見 Issue #85 驗證方式）。
     */
    static boolean hasUsableName(String name) {
        return !Objects.equals(name, "");
    }
}
