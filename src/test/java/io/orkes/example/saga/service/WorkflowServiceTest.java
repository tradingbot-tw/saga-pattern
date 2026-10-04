package io.orkes.example.saga.service;

import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import io.orkes.conductor.client.ApiClient;
import io.orkes.conductor.client.http.OrkesWorkflowClient;
import io.orkes.example.saga.pojos.FoodDeliveryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkflowService}（saga 協調器的啟動入口）測試（Issue #9）。
 *
 * <p>以真實 REST 啟動工作流需要 Conductor server，屬 E2E 範圍（本 Issue 排除）。
 * 這裡改為攔截 {@code startWorkflow} 的呼叫，驗證協調器帶給工作流的**契約**：
 * 工作流程名稱/版本、輸入欄位對應，以及 task domain 的設定方式。
 */
class WorkflowServiceTest {

    /** 只記錄呼叫、不發出任何網路請求的 wf client。 */
    private static class RecordingWorkflowClient extends OrkesWorkflowClient {
        private StartWorkflowRequest captured;
        private String workflowIdToReturn = "workflow-42";
        private RuntimeException failureToThrow;

        RecordingWorkflowClient() {
            // 不帶憑證的 ApiClient 只做物件初始化，不會連線
            super(new ApiClient("http://localhost:0/api"));
        }

        @Override
        public String startWorkflow(StartWorkflowRequest request) {
            if (failureToThrow != null) {
                throw failureToThrow;
            }
            captured = request;
            return workflowIdToReturn;
        }
    }

    private RecordingWorkflowClient client;

    @BeforeEach
    void setUp() {
        client = new RecordingWorkflowClient();
    }

    private static StandardEnvironment environmentWithDomain(String domain) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> properties = new HashMap<>();
        if (domain != null) {
            properties.put("conductor.worker.all.domain", domain);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    private static FoodDeliveryRequest deliveryRequest() {
        FoodDeliveryRequest request = new FoodDeliveryRequest();
        request.setCustomerEmail("jane@example.com");
        request.setCustomerName("Jane Doe");
        request.setCustomerContact("+12126781345");
        request.setRestaurantId(2);
        request.setFoodItems(new ArrayList<>(Arrays.asList("burger", "fries")));
        request.setAdditionalNotes(new ArrayList<>(Arrays.asList("不要蔥")));
        request.setAddress("43 West 4th Street, New York NY 10024");
        request.setDeliveryInstructions("放門口");
        request.setPaymentAmount(35.5);
        request.setPaymentMethod("Credit Card");
        return request;
    }

    @Test
    @DisplayName("happy path：啟動 FoodDeliveryWorkflow v1 並把訂單資訊完整帶進工作流輸入")
    void startsWorkflowWithFullInput() {
        Map<String, Object> result = new WorkflowService(client, environmentWithDomain("saga"))
                .startFoodDeliveryWorkflow(deliveryRequest());

        assertEquals("workflow-42", result.get("workflowId"));
        assertNull(result.get("error"), "成功時不應夾帶錯誤欄位");

        StartWorkflowRequest sent = client.captured;
        assertEquals("FoodDeliveryWorkflow", sent.getName());
        assertEquals(Integer.valueOf(1), sent.getVersion());
        assertEquals("api-triggered", sent.getCorrelationId());

        Map<String, Object> input = sent.getInput();
        assertEquals("jane@example.com", input.get("customerEmail"));
        assertEquals("Jane Doe", input.get("customerName"));
        assertEquals("+12126781345", input.get("customerContact"));
        assertEquals(2, input.get("restaurantId"));
        assertEquals(Arrays.asList("burger", "fries"), input.get("foodItems"));
        assertEquals(Arrays.asList("不要蔥"), input.get("additionalNotes"));
        assertEquals("43 West 4th Street, New York NY 10024", input.get("address"));
        assertEquals("放門口", input.get("deliveryInstructions"));
        assertEquals(35.5, input.get("paymentAmount"));
        assertEquals("Credit Card", input.get("paymentMethod"));
        assertEquals(10, input.size(), "輸入應恰好對應協調流程用到的 10 個欄位，實際為: " + input.keySet());
    }

    @Test
    @DisplayName("worker 分派：設定 conductor.worker.all.domain 時，所有任務都路由到該 domain")
    void appliesTaskDomainWhenConfigured() {
        new WorkflowService(client, environmentWithDomain("saga"))
                .startFoodDeliveryWorkflow(deliveryRequest());

        assertEquals("saga", client.captured.getTaskToDomain().get("*"),
                "taskToDomain 應以 * 覆蓋全部任務");
    }

    @Test
    @DisplayName("worker 分派：未設定 domain 時不傳 taskToDomain，維持伺服器預設")
    void omitsTaskDomainWhenPropertyMissing() {
        new WorkflowService(client, environmentWithDomain(null))
                .startFoodDeliveryWorkflow(deliveryRequest());

        assertFalse(client.captured.getTaskToDomain().containsKey("*"),
                "不應出現 * 的 domain 覆蓋，實際為: " + client.captured.getTaskToDomain());
    }

    @Test
    @DisplayName("步驟失敗：啟動工作流失敗時回報訂單建立失敗，而不是丟出例外")
    void reportsErrorWhenWorkflowStartFails() {
        client.failureToThrow = new RuntimeException("conductor unavailable");

        Map<String, Object> result = new WorkflowService(client, environmentWithDomain("saga"))
                .startFoodDeliveryWorkflow(deliveryRequest());

        assertEquals("Order creation failure", result.get("error"));
        assertTrue(result.get("detail").toString().contains("conductor unavailable"),
                "detail 應保留原始錯誤，實際為: " + result.get("detail"));
        assertNull(result.get("workflowId"));
        assertNull(client.captured, "失敗時不該留下成功紀錄");
    }
}
