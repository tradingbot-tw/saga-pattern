package io.orkes.example.saga.workers;

import com.netflix.conductor.common.metadata.tasks.TaskResult;
import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 形式驗證反例重現測試：
 * 重現 Quint 形式規格所發現的 Saga 補償缺陷（FoodDeliveryFailureWorkflow 遇 make_payment 失敗時）。
 */
class SagaFlawReproductionTest {

    private static final String URL = SagaTestDb.APP_DB_URL;
    private ConductorWorkers workers;

    @BeforeAll
    static void prepareDatabase() {
        SagaTestDb.createAppSchema();
    }

    @BeforeEach
    void setUp() {
        workers = new ConductorWorkers();
    }

    private static FoodItem item(String name, int quantity) {
        FoodItem foodItem = new FoodItem();
        foodItem.setItem(name);
        foodItem.setQuantity(quantity);
        return foodItem;
    }

    private static OrderRequest orderRequest() {
        OrderRequest request = new OrderRequest();
        request.setCustomerEmail(SagaTestDb.uniqueId("buyer") + "@example.com");
        request.setCustomerName("反例驗證買家");
        request.setCustomerContact("+886912345678");
        request.setRestaurantId(1);
        request.setItems(new ArrayList<>(Arrays.asList(item("burger", 1))));
        request.setNotes(new ArrayList<>());
        request.setDeliveryAddress("台北市信義區信義路五段7號");
        return request;
    }

    private static PaymentRequest expiredPaymentRequest(String orderId) {
        PaymentDetails details = new PaymentDetails();
        details.setNumber("4111111111111111");
        details.setExpiry("01/2020"); // 已過期卡片，確保 makePayment 失敗
        details.setCvv(123);

        PaymentMethod method = new PaymentMethod();
        method.setType("Credit Card");
        method.setDetails(details);

        PaymentRequest request = new PaymentRequest();
        request.setOrderId(orderId);
        request.setCustomerId(1);
        request.setAmount(100f);
        request.setMethod(method);
        return request;
    }

    @Test
    @DisplayName("形式驗證反例 1：當 make_payment 未成功寫入付款紀錄即失敗時，Failure Workflow 觸發 cancel_payment 會因查無付款回傳 FAILED_WITH_TERMINAL_ERROR，中斷補償鏈導致訂單殘留未取消")
    void reproducePaymentAbsentCompensationFailureFlaw() throws Exception {
        // 1. 正向步驟 1: order_food 建立訂單成功
        TaskResult orderResult = workers.orderFoodTask(orderRequest());
        assertEquals(TaskResult.Status.COMPLETED, orderResult.getStatus());
        String orderId = String.valueOf(orderResult.getOutputData().get("orderId"));
        assertNotNull(orderId);

        // 2. 正向步驟 2: check_inventory 檢查庫存成功
        CheckInventoryRequest inventoryRequest = new CheckInventoryRequest();
        inventoryRequest.setRestaurantId(1);
        inventoryRequest.setItems(new ArrayList<>(Arrays.asList(item("burger", 1))));
        TaskResult inventoryResult = workers.checkInventoryTask(inventoryRequest);
        assertEquals(TaskResult.Status.COMPLETED, inventoryResult.getStatus());

        // 3. 步驟 3: make_payment 任務在尚未建立付款前即失敗
        // （例如 Conductor 端任務逾時、參數解析異常、或資料庫在 insertPayment 時失敗未留下紀錄）
        // 按照 FoodDeliveryFailureWorkflow.v1.json 之設計，失敗任務為 make_payment_ref 時，
        // Conductor switch-case 會強制依序排程：
        //   [1] cancel_payment
        //   [2] cancel_order
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        // 4. 執行補償步驟 1: cancel_payment
        TaskResult cancelPaymentResult = workers.cancelPaymentTask(cancelRequest);

        // 依據 Saga 最終一致性與冪等性規範：
        // 當付款根本未產生時，cancel_payment 應視為安全無操作（COMPLETED），不可拋出終端失敗中斷後續補償！
        assertEquals(TaskResult.Status.COMPLETED, cancelPaymentResult.getStatus(),
                "【Saga規範要求】查無付款時 cancel_payment 應具備冪等性回傳 COMPLETED，避免中斷補償鏈");

        // 5. 執行補償步驟 2: cancel_order
        TaskResult cancelOrderResult = workers.cancelOrderTask(cancelRequest);
        assertEquals(TaskResult.Status.COMPLETED, cancelOrderResult.getStatus(),
                "【Saga規範要求】訂單必須成功執行取消");

        // 6. 檢查資料庫狀態：訂單必須最終回滾為 CANCELLED！
        String orderStatus = SagaTestDb.queryString(URL,
                "SELECT status FROM orders WHERE orderId = '" + orderId + "'");
        assertEquals("CANCELLED", orderStatus,
                "【Saga規範要求】訂單必須成功回滾為 CANCELLED，不應殘留為 PENDING");
    }

    @Test
    @DisplayName("Saga 規範驗證 2：cancel_order 在重複補償或查無訂單時，應保持冪等性回傳 COMPLETED")
    void reproduceCancelOrderNonExistentFlaw() {
        String nonExistentOrderId = SagaTestDb.uniqueId("ghost-order");
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(nonExistentOrderId);

        TaskResult result = workers.cancelOrderTask(cancelRequest);
        // 依據 Saga 冪等性規範，補償操作應可安全重複執行
        assertEquals(TaskResult.Status.COMPLETED, result.getStatus(),
                "【Saga規範要求】cancel_order 應具備冪等性，查無訂單時應回傳 COMPLETED 而非終端失敗");
    }
}
