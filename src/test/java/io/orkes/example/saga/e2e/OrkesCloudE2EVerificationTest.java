package io.orkes.example.saga.e2e;

import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import com.netflix.conductor.common.run.Workflow;
import io.orkes.conductor.client.ApiClient;
import io.orkes.conductor.client.http.OrkesTaskClient;
import io.orkes.conductor.client.http.OrkesWorkflowClient;
import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.*;
import io.orkes.example.saga.workers.ConductorWorkers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真實 Orkes Cloud 端到端整合驗證測試：
 * 僅在本地存在 .env.local 憑證時執行；若無憑證則自動跳過，不影響 CI。
 */
class OrkesCloudE2EVerificationTest {

    private static final String APP_DB_URL = SagaTestDb.APP_DB_URL;
    private static String serverUrl;
    private static String keyId;
    private static String keySecret;
    private static boolean credentialsAvailable = false;

    @BeforeAll
    static void loadCredentialsAndInitDb() {
        SagaTestDb.createAppSchema();

        File envFile = new File(".env.local");
        if (envFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(envFile)) {
                props.load(fis);
                serverUrl = props.getProperty("CONDUCTOR_SERVER_URL");
                keyId = props.getProperty("CONDUCTOR_SECURITY_CLIENT_KEY_ID");
                keySecret = props.getProperty("CONDUCTOR_SECURITY_CLIENT_SECRET");
                if (serverUrl != null && keyId != null && keySecret != null) {
                    credentialsAvailable = true;
                }
            } catch (Exception e) {
                System.err.println("Failed to read .env.local: " + e.getMessage());
            }
        }
    }

    private static FoodItem item(String name, int quantity) {
        FoodItem foodItem = new FoodItem();
        foodItem.setItem(name);
        foodItem.setQuantity(quantity);
        return foodItem;
    }

    @Test
    @DisplayName("真實 Orkes Cloud E2E：驗證 make_payment 失敗後，雲端自動觸發補償並直接執行 cancel_order，達成最終一致性")
    void testRealOrkesCloudE2ECompensationFix() throws Exception {
        Assumptions.assumeTrue(credentialsAvailable, "未偵測到 .env.local 憑證，跳過真實雲端 E2E 測試");

        ApiClient apiClient = new ApiClient(serverUrl, keyId, keySecret);
        OrkesWorkflowClient workflowClient = new OrkesWorkflowClient(apiClient);
        OrkesTaskClient taskClient = new OrkesTaskClient(apiClient);

        ConductorWorkers workers = new ConductorWorkers();
        String domain = "e2e-" + System.currentTimeMillis();

        // 1. 發起真實主流程 FoodDeliveryWorkflow
        StartWorkflowRequest startReq = new StartWorkflowRequest();
        startReq.setName("FoodDeliveryWorkflow");
        startReq.setVersion(1);
        startReq.setCorrelationId("e2e-test-" + domain);
        startReq.setTaskToDomain(Map.of("*", domain));

        Map<String, Object> input = new HashMap<>();
        input.put("customerEmail", "e2e-verify@example.com");
        input.put("customerName", "E2E實測買家");
        input.put("customerContact", "+886912345678");
        input.put("restaurantId", 1);
        input.put("foodItems", List.of(Map.of("item", "burger", "quantity", 1)));
        input.put("additionalNotes", List.of("請放門口"));
        input.put("address", "台北市信義區信義路五段7號");
        input.put("deliveryInstructions", "無接觸送餐");
        input.put("paymentAmount", 120.0);
        // 使用過期卡片確保 make_payment 必然失敗
        input.put("paymentMethod", Map.of(
                "type", "Credit Card",
                "details", Map.of(
                        "number", "4111111111111111",
                        "expiry", "01/2020",
                        "cvv", 123
                )
        ));
        startReq.setInput(input);

        String mainWorkflowId = workflowClient.startWorkflow(startReq);
        assertNotNull(mainWorkflowId, "主流程啟動成功，獲得 workflowId");
        System.out.println(">>> [E2E] 主流程已於 Orkes Cloud 啟動，workflowId: " + mainWorkflowId);

        String orderId = null;

        // 2. 模擬 Worker 依序認領並執行正向步驟
        // Task 1: order_food
        Task orderFoodTask = pollWithRetry(taskClient, "order_food", domain, 30);
        assertNotNull(orderFoodTask, "成功從 Orkes Cloud 領取到 order_food 任務");
        OrderRequest orderReq = new OrderRequest();
        orderReq.setCustomerEmail("e2e-verify@example.com");
        orderReq.setCustomerName("E2E實測買家");
        orderReq.setCustomerContact("+886912345678");
        orderReq.setRestaurantId(1);
        orderReq.setItems(new ArrayList<>(List.of(item("burger", 1))));
        orderReq.setNotes(new ArrayList<>(List.of("請放門口")));
        orderReq.setDeliveryAddress("台北市信義區信義路五段7號");

        TaskResult orderRes = workers.orderFoodTask(orderReq);
        assertEquals(TaskResult.Status.COMPLETED, orderRes.getStatus());
        orderId = (String) orderRes.getOutputData().get("orderId");
        assertNotNull(orderId, "成功建立本地 SQLite 訂單");
        orderRes.setTaskId(orderFoodTask.getTaskId());
        orderRes.setWorkflowInstanceId(mainWorkflowId);
        taskClient.updateTask(orderRes);
        System.out.println(">>> [E2E] order_food 已完成，建立訂單: " + orderId);

        // Task 2: check_inventory
        Task checkInventoryTask = pollWithRetry(taskClient, "check_inventory", domain, 30);
        assertNotNull(checkInventoryTask, "成功從 Orkes Cloud 領取到 check_inventory 任務");
        CheckInventoryRequest inventoryReq = new CheckInventoryRequest();
        inventoryReq.setRestaurantId(1);
        inventoryReq.setItems(new ArrayList<>(List.of(item("burger", 1))));
        TaskResult inventoryRes = workers.checkInventoryTask(inventoryReq);
        assertEquals(TaskResult.Status.COMPLETED, inventoryRes.getStatus());
        inventoryRes.setTaskId(checkInventoryTask.getTaskId());
        inventoryRes.setWorkflowInstanceId(mainWorkflowId);
        taskClient.updateTask(inventoryRes);
        System.out.println(">>> [E2E] check_inventory 已完成");

        // Task 3: make_payment（執行扣款，卡片過期失敗）
        Task makePaymentTask = pollWithRetry(taskClient, "make_payment", domain, 30);
        assertNotNull(makePaymentTask, "成功從 Orkes Cloud 領取到 make_payment 任務");
        PaymentRequest paymentReq = new PaymentRequest();
        paymentReq.setOrderId(orderId);
        paymentReq.setAmount(120.0f);
        PaymentMethod method = new PaymentMethod();
        method.setType("Credit Card");
        PaymentDetails details = new PaymentDetails();
        details.setNumber("4111111111111111");
        details.setExpiry("01/2020");
        details.setCvv(123);
        method.setDetails(details);
        paymentReq.setMethod(method);

        TaskResult paymentRes = workers.makePaymentTask(paymentReq);
        assertEquals(TaskResult.Status.FAILED_WITH_TERMINAL_ERROR, paymentRes.getStatus());
        paymentRes.setTaskId(makePaymentTask.getTaskId());
        paymentRes.setWorkflowInstanceId(mainWorkflowId);
        taskClient.updateTask(paymentRes);
        System.out.println(">>> [E2E] make_payment 已回報 FAILED_WITH_TERMINAL_ERROR");

        // 3. 等待 Orkes Cloud 自動觸發 FoodDeliveryFailureWorkflow
        System.out.println(">>> [E2E] 等待 Orkes Cloud 觸發 failureWorkflow...");
        Workflow mainWorkflow = null;
        for (int i = 0; i < 30; i++) {
            mainWorkflow = workflowClient.getWorkflow(mainWorkflowId, false);
            if (mainWorkflow.getStatus() == Workflow.WorkflowStatus.FAILED) {
                break;
            }
            Thread.sleep(1000);
        }
        assertEquals(Workflow.WorkflowStatus.FAILED, mainWorkflow.getStatus(), "主流程應為 FAILED 狀態");

        // 4. 關鍵驗證：雲端應走入修正後的分支，直接派發 cancel_order，絕對不可派發 cancel_payment！
        System.out.println(">>> [E2E] 領取補償任務，驗證雲端是否直接派發 cancel_order...");
        Task compensationTask = pollWithRetry(taskClient, "cancel_order", domain, 30);
        assertNotNull(compensationTask, "【關鍵驗證成功】Orkes Cloud 直接派發了 cancel_order！");
        assertEquals("cancel_order", compensationTask.getTaskType());

        // 執行本地 cancel_order
        CancelRequest cancelReq = new CancelRequest();
        cancelReq.setOrderId(orderId);
        TaskResult cancelOrderRes = workers.cancelOrderTask(cancelReq);
        assertEquals(TaskResult.Status.COMPLETED, cancelOrderRes.getStatus());
        cancelOrderRes.setTaskId(compensationTask.getTaskId());
        cancelOrderRes.setWorkflowInstanceId(compensationTask.getWorkflowInstanceId());
        taskClient.updateTask(cancelOrderRes);
        System.out.println(">>> [E2E] cancel_order 本地回滾成功並已回傳 Orkes Cloud");

        // 5. 補償流程最後一步：notify_customer
        Task notifyTask = pollWithRetry(taskClient, "notify_customer", domain, 30);
        if (notifyTask != null) {
            TaskResult notifyRes = new TaskResult(notifyTask);
            notifyRes.setStatus(TaskResult.Status.COMPLETED);
            taskClient.updateTask(notifyRes);
            System.out.println(">>> [E2E] notify_customer 完成");
        }

        // 6. 驗證資料庫最終一致性：訂單必須成功回滾為 CANCELLED
        String finalOrderStatus = SagaTestDb.queryString(APP_DB_URL,
                "SELECT status FROM orders WHERE orderId = '" + orderId + "'");
        assertEquals("CANCELLED", finalOrderStatus,
                "【最終一致性達成】資料庫訂單已成功回滾為 CANCELLED！");

        System.out.println(">>> [E2E] 全流程通過！真實 Orkes Cloud 成功跳過無效付款取消，完成訂單回滾！");
    }

    private Task pollWithRetry(OrkesTaskClient taskClient, String taskType, String domain, int maxSeconds) throws Exception {
        for (int i = 0; i < maxSeconds; i++) {
            Task task = taskClient.pollTask(taskType, "e2e-worker", domain);
            if (task != null && task.getTaskId() != null) {
                return task;
            }
            Thread.sleep(1000);
        }
        return null;
    }
}
