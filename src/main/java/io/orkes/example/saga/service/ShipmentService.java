package io.orkes.example.saga.service;

import io.orkes.example.saga.dao.DataAccessException;
import io.orkes.example.saga.dao.ShipmentDAO;
import io.orkes.example.saga.pojos.Driver;
import io.orkes.example.saga.pojos.Shipment;
import io.orkes.example.saga.pojos.ShippingRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.Random;

@Slf4j
public class ShipmentService {

    private static final ShipmentDAO shipmentDAO = new ShipmentDAO("jdbc:sqlite:food_delivery.db");

    public static int createShipment(ShippingRequest shippingRequest) {
        String orderId = shippingRequest.getOrderId();

        Shipment shipment = new Shipment();
        shipment.setOrderId(orderId);
        shipment.setDeliveryAddress(shippingRequest.getDeliveryAddress());
        shipment.setDeliveryInstructions(shippingRequest.getDeliveryInstructions());

        int driverId = findDriver();
        shipment.setDriverId(driverId);

        if (!shipmentDAO.insertShipment(shipment)) {
            log.error("Shipment creation for order {} failed.", orderId);
            return 0;
        }

        Driver driver = new Driver();
        driver.setName("");
        shipmentDAO.readDriver(driverId, driver);

        if (driver.getName().isBlank()) {
            log.error("Shipment creation for order {} failed as driver in the area is not available.", orderId);
            shipmentDAO.cancelShipment(orderId);
            return 0;
        }
        else {
            log.info("Assigned driver {} to order with id: {}", driverId, orderId);
            if (shipmentDAO.confirmShipment(orderId) == 0) {
                // Issue #84：確認沒有更新任何列也是失敗，不能讓呼叫端以為司機已指派成功。
                throw new DataAccessException(
                        "Failed to confirm shipment for order " + orderId + ": no rows updated", null);
            }
        }

        return driverId;
    }

    /**
     * 取消指定訂單的外送單，回傳受影響的列數（Issue #34）。
     *
     * <p>查無外送單時回傳 {@code 0}；資料庫失敗時丟出
     * {@link io.orkes.example.saga.dao.DataAccessException}，由 worker 轉成 task 失敗。
     */
    public static int cancelDelivery(String orderId) {
        return shipmentDAO.cancelShipment(orderId);
    }

    private static int findDriver() {
        Random random = new Random();
        int driverId = 0;
        int counter = 0;
        while (counter < 10) {
            driverId = random.nextInt(4);
            if(driverId !=0) break;
            counter += 1;
        }
        return driverId;
    }
}
