package io.orkes.example.saga.workers;

import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.sdk.workflow.task.WorkerTask;
import io.orkes.example.saga.dao.DaoWriteException;
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
     * {@code cancel_payment}：補償步驟，取消該訂單的<strong>全部</strong>付款（Issue #34 / G3）。
     *
     * <p>失敗語意依人類裁定（TB1 報告 §9 第 4 題，不採 best-effort）：成功 → {@code COMPLETED}；
     * 資料庫例外 → {@code FAILED}（可重試，由 Conductor retry policy 接管）；查無可取消的付款 →
     * {@code FAILED_WITH_TERMINAL_ERROR}（重試不會憑空生出資料）。失敗原因一律寫進
     * {@code reasonForIncompletion} 讓告警讀得到，補償失敗不會再被吞掉。
     */
    @WorkerTask(value = "cancel_payment", threadCount = 2, pollingInterval = 300)
    public TaskResult cancelPaymentTask(CancelRequest cancelRequest) {
        TaskResult result = new TaskResult();
        String orderId = cancelRequest.getOrderId();
        try {
            int cancelledPayments = PaymentService.cancelPayment(orderId);
            if (cancelledPayments > 0) {
                result.setStatus(TaskResult.Status.COMPLETED);
            } else {
                result.setReasonForIncompletion("No payment to cancel for order " + orderId);
                result.setStatus(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR);
            }
        } catch (DaoWriteException e) {
            result.setReasonForIncompletion("Database error while cancelling payment for order "
                    + orderId + ": " + e.getMessage());
            result.setStatus(TaskResult.Status.FAILED);
        }
        return result;
    }

    /**
     * {@code cancel_delivery}：補償步驟，取消該訂單的外送單（Issue #34 / G3）。
     *
     * <p>狀態語意同 {@link #cancelPaymentTask}：成功 {@code COMPLETED}、資料庫例外
     * {@code FAILED}（可重試）、查無可取消的外送單 {@code FAILED_WITH_TERMINAL_ERROR}，
     * 失敗原因寫進 {@code reasonForIncompletion}。
     */
    @WorkerTask(value = "cancel_delivery", threadCount = 2, pollingInterval = 300)
    public TaskResult cancelDeliveryTask(CancelRequest cancelRequest) {
        TaskResult result = new TaskResult();
        String orderId = cancelRequest.getOrderId();
        try {
            int cancelledShipments = ShipmentService.cancelDelivery(orderId);
            if (cancelledShipments > 0) {
                result.setStatus(TaskResult.Status.COMPLETED);
            } else {
                result.setReasonForIncompletion("No delivery to cancel for order " + orderId);
                result.setStatus(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR);
            }
        } catch (DaoWriteException e) {
            result.setReasonForIncompletion("Database error while cancelling delivery for order "
                    + orderId + ": " + e.getMessage());
            result.setStatus(TaskResult.Status.FAILED);
        }
        return result;
    }

    /**
     * {@code cancel_order}：補償步驟，把訂單轉為 CANCELLED（Issue #34 / G3）。
     *
     * <p>不存在的 {@code orderId} 不得回報成功：查無訂單（或更新 0 列）回
     * {@code FAILED_WITH_TERMINAL_ERROR}；資料庫例外回 {@code FAILED}（可重試）；
     * 成功才回 {@code COMPLETED}。「資料庫讀取失敗不等於訂單不存在」——兩者由
     * {@code DaoWriteException} 與查無列區分，不得混為一談。
     */
    @WorkerTask(value = "cancel_order", threadCount = 2, pollingInterval = 300)
    public TaskResult cancelOrderTask(CancelRequest cancelRequest) {
        TaskResult result = new TaskResult();
        String orderId = cancelRequest.getOrderId();
        try {
            Order order = OrderService.getOrder(orderId);
            if (order.getOrderId() == null) {
                result.setReasonForIncompletion("Order " + orderId + " does not exist");
                result.setStatus(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR);
            } else {
                int cancelled = OrderService.cancelOrder(order);
                if (cancelled > 0) {
                    result.setStatus(TaskResult.Status.COMPLETED);
                } else {
                    result.setReasonForIncompletion("Order " + orderId + " no longer exists to cancel");
                    result.setStatus(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR);
                }
            }
        } catch (DaoWriteException e) {
            result.setReasonForIncompletion("Database error while cancelling order "
                    + orderId + ": " + e.getMessage());
            result.setStatus(TaskResult.Status.FAILED);
        }
        return result;
    }

}
