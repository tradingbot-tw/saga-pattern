package io.orkes.example.saga.workers;

import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.sdk.workflow.task.WorkerTask;
import io.orkes.example.saga.dao.DataAccessException;
import io.orkes.example.saga.pojos.*;
import io.orkes.example.saga.service.InventoryService;
import io.orkes.example.saga.service.OrderService;
import io.orkes.example.saga.service.PaymentService;
import io.orkes.example.saga.service.ShipmentService;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@AllArgsConstructor
@Component
@ComponentScan(basePackages = {"io.orkes"})
public class ConductorWorkers {
    /**
     * Note: Using this setting, up to 5 tasks will run in parallel, with tasks being polled every 200ms
     */
    @WorkerTask(value = "order_food", threadCount = 3, pollingInterval = 300)
    public TaskResult orderFoodTask(OrderRequest orderRequest) {
        String orderId = OrderService.createOrder(orderRequest);

        TaskResult result = new TaskResult();
        Map<String, Object> output = new HashMap<>();

        if(orderId != null) {
            output.put("orderId", orderId);
            result.setOutputData(output);
            result.setStatus(TaskResult.Status.COMPLETED);
        } else {
            output.put("orderId", null);
            result.setStatus(TaskResult.Status.FAILED);
        }
        return result;
    }

    @WorkerTask(value = "check_inventory", threadCount = 2, pollingInterval = 300)
    public TaskResult checkInventoryTask(CheckInventoryRequest checkInventoryRequest) {
        int restaurantId = checkInventoryRequest.getRestaurantId();
        ArrayList<FoodItem> items = checkInventoryRequest.getItems();
        boolean availability = InventoryService.checkAvailability(restaurantId, items);

        TaskResult result = new TaskResult();

        if (availability) {
            result.setStatus(TaskResult.Status.COMPLETED);
        } else {
            result.setReasonForIncompletion("Restaurant is closed");
            result.setStatus(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR);
        }

        return result;
    }

    @WorkerTask(value = "make_payment", threadCount = 2, pollingInterval = 300)
    public TaskResult makePaymentTask(PaymentRequest paymentRequest) {
        TaskResult result = new TaskResult();

        Payment payment = PaymentService.createPayment(paymentRequest);
        Map<String, Object> output = new HashMap<>();
        output.put("orderId", payment.getOrderId());
        output.put("paymentId", payment.getPaymentId());
        output.put("paymentStatus", payment.getStatus().name());

        if(payment.getStatus() == Payment.Status.SUCCESSFUL) {
            result.setStatus(TaskResult.Status.COMPLETED);
        } else {
            output.put("error", payment.getErrorMsg());
            result.setStatus(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR);
        }

        result.setOutputData(output);

        return result;
    }

    /**
     * {@code ship_food}：指派司機並建立外送單（Issue #24 / G1）。
     *
     * <p>成功時 output 帶 {@code driverId}，讓下游 {@code notify_driver} 的
     * {@link DriverNotificationRequest#getDriverId()} 有生產者；失敗時不輸出
     * {@code driverId}，狀態維持 {@code FAILED} 由 Conductor retry policy 接管。
     */
    @WorkerTask(value = "ship_food", threadCount = 2, pollingInterval = 300)
    public TaskResult shipFoodTask(ShippingRequest shippingRequest) {
        TaskResult result = new TaskResult();
        Map<String, Object> output = new HashMap<>();
        int driverId = ShipmentService.createShipment(shippingRequest);
        if (driverId != 0) {
            output.put("driverId", driverId);
            result.setOutputData(output);
            result.setStatus(TaskResult.Status.COMPLETED);
        } else {
            result.setStatus(TaskResult.Status.FAILED);
        }
        return result;
    }

    @WorkerTask(value = "notify_driver", threadCount = 2, pollingInterval = 300)
    public Map<String, Object> checkForDriverNotifications(DriverNotificationRequest driverNotificationRequest) {
        Map<String, Object> result = new HashMap<>();
        return result;
    }

    /**
     * {@code notify_customer} 的 placeholder（Issue #24 / G2）。
     *
     * <p>輸入只要求 workflow 起始輸入實際提供的欄位（{@code orderId}、{@code customerEmail}、
     * {@code customerName}），不再需要 repo 內沒有生產者的完整 {@link Order}。本步驟
     * <strong>不實際寄送通知</strong>，output 以 {@code notified=false} 明確標示，避免
     * workflow 定義誤以為已通知。
     */
    @WorkerTask(value = "notify_customer", threadCount = 2, pollingInterval = 300)
    public Map<String, Object> checkForCustomerNotifications(CustomerNotificationRequest request) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", request.getOrderId());
        result.put("notified", false);
        return result;
    }
//
    /**
     * 補償步驟的成功結果（Issue #34）：三個 cancel_* 都以 {@link TaskResult} 回報，
     * 不再回傳 {@code Map} 讓 SDK 一律標成 {@code COMPLETED}。
     */
    private static TaskResult completed() {
        TaskResult result = new TaskResult();
        result.setStatus(TaskResult.Status.COMPLETED);
        return result;
    }

    /**
     * 補償步驟的失敗結果：{@code FAILED} 可重試（資料庫例外），
     * {@code FAILED_WITH_TERMINAL_ERROR} 不可重試（目標不存在）。
     */
    private static TaskResult failed(TaskResult.Status status, String reason) {
        TaskResult result = new TaskResult();
        result.setStatus(status);
        result.setReasonForIncompletion(reason);
        return result;
    }

    @WorkerTask(value = "cancel_payment", threadCount = 2, pollingInterval = 300)
    public TaskResult cancelPaymentTask(CancelRequest cancelRequest) {
        String orderId = cancelRequest.getOrderId();
        try {
            int canceled = PaymentService.cancelPayment(orderId);
            if (canceled == 0) {
                return failed(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR,
                        "No payment found for order " + orderId);
            }
            return completed();
        } catch (DataAccessException e) {
            return failed(TaskResult.Status.FAILED,
                    "Failed to cancel payment for order " + orderId + ": " + e.getMessage());
        }
    }

    @WorkerTask(value = "cancel_delivery", threadCount = 2, pollingInterval = 300)
    public TaskResult cancelDeliveryTask(CancelRequest cancelRequest) {
        String orderId = cancelRequest.getOrderId();
        try {
            int canceled = ShipmentService.cancelDelivery(orderId);
            if (canceled == 0) {
                return failed(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR,
                        "No shipment found for order " + orderId);
            }
            return completed();
        } catch (DataAccessException e) {
            return failed(TaskResult.Status.FAILED,
                    "Failed to cancel delivery for order " + orderId + ": " + e.getMessage());
        }
    }

    @WorkerTask(value = "cancel_order", threadCount = 2, pollingInterval = 300)
    public TaskResult cancelOrderTask(CancelRequest cancelRequest) {
        String orderId = cancelRequest.getOrderId();
        try {
            int canceled = OrderService.cancelOrder(orderId);
            if (canceled == 0) {
                return failed(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR,
                        "No order found for order " + orderId);
            }
            return completed();
        } catch (DataAccessException e) {
            return failed(TaskResult.Status.FAILED,
                    "Failed to cancel order " + orderId + ": " + e.getMessage());
        }
    }

}
