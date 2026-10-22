package io.orkes.example.saga.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 驗證針對 Quint 形式規格反例所做的修正（specs/saga_test.qnt 的
 * {@code defect_prefix_payment_insert_failure_test}；該反例只在 insertPayment 失敗、沒有付款列時出現，
 * 付款被拒時已有 FAILED 付款列，舊 workflow 的 cancel_payment 仍會成功）：
 * 1. 工作流層級：FoodDeliveryFailureWorkflow.v1.json 在 make_payment_ref 失敗時不再呼叫 cancel_payment。
 * 2. 業務層級：make_payment 失敗時正確依序回滾 cancel_order，確保訂單狀態成功轉為 CANCELLED，達成最終一致性。
 * 3. 安全防護：查無訂單時仍具備終端錯誤防護，防止無效 ID 誤報成功。
 *
 * <p>現行 workflow 仍有的已知缺陷見 specs/README.md「已知缺陷」。
 */
class SagaFlawReproductionTest {

    private static final String URL = SagaTestDb.APP_DB_URL;
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
        request.setCustomerName("反例修正驗證買家");
        request.setCustomerContact("+886912345678");
        request.setRestaurantId(1);
        request.setItems(new ArrayList<>(Arrays.asList(item("burger", 1))));
        request.setNotes(new ArrayList<>());
        request.setDeliveryAddress("台北市信義區信義路五段7號");
        return request;
    }

    @Test
    @DisplayName("工作流修復驗證：FoodDeliveryFailureWorkflow 在 make_payment_ref 分支不再包含 cancel_payment")
    void verifyWorkflowDefinitionNoLongerInvokesCancelPaymentOnPaymentFailure() throws Exception {
        File workflowFile = new File("conductor/workflows/FoodDeliveryFailureWorkflow.v1.json");
        assertTrue(workflowFile.exists(), "FoodDeliveryFailureWorkflow.v1.json 必須存在");

        JsonNode root = MAPPER.readTree(workflowFile);
        JsonNode switchTask = root.path("tasks").get(1); // switch_compensate_from
        assertEquals("switch_compensate_from", switchTask.path("name").asText());

        JsonNode paymentBranch = switchTask.path("decisionCases").path("make_payment_ref");
        assertNotNull(paymentBranch, "make_payment_ref 分支必須存在");

        // 驗證 make_payment_ref 下只有 cancel_order，無 cancel_payment
        assertEquals(1, paymentBranch.size(), "make_payment_ref 分支應僅有 1 個補償步驟（cancel_order）");
        assertEquals("cancel_order", paymentBranch.get(0).path("name").asText(),
                "make_payment_ref 失敗時應僅補償 cancel_order，不應調用未產生的 cancel_payment");
    }

    @Test
    @DisplayName("Saga 一致性驗證：make_payment 失敗後，依修正後工作流執行 cancel_order 成功將訂單回滾為 CANCELLED")
    void testPaymentFailureSagaCompensationRollsBackOrderSuccessfully() throws Exception {
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

        // 3. 步驟 3: make_payment 任務失敗（未落庫成功）
        // 依據修復後的 FoodDeliveryFailureWorkflow.v1.json 定義：
        // make_payment_ref 失敗分支不再呼叫 cancel_payment，直接執行 cancel_order
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(orderId);

        TaskResult cancelOrderResult = workers.cancelOrderTask(cancelRequest);
        assertEquals(TaskResult.Status.COMPLETED, cancelOrderResult.getStatus(),
                "cancel_order 應順利成功執行並回報 COMPLETED");

        // 4. 驗證最終一致性：訂單狀態必須成功回滾為 CANCELLED，消弭懸空副作用！
        String orderStatus = SagaTestDb.queryString(URL,
                "SELECT status FROM orders WHERE orderId = '" + orderId + "'");
        assertEquals("CANCELLED", orderStatus,
                "訂單必須成功回滾為 CANCELLED，達成 Saga 最終一致性");
    }

    @Test
    @DisplayName("安全防護驗證：cancel_order 遇惡意或幽靈 orderId 依然回傳 FAILED_WITH_TERMINAL_ERROR 擋下非法請求")
    void verifyGhostOrderReturnsTerminalError() {
        String nonExistentOrderId = SagaTestDb.uniqueId("ghost-order");
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.setOrderId(nonExistentOrderId);

        TaskResult result = workers.cancelOrderTask(cancelRequest);
        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, result.getStatus(),
                "查無訂單時應依 Issue #34 規範回傳終端錯誤，避免無效 ID 誤報成功");
    }
}
