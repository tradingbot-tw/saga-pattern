package io.orkes.example.saga.controller;

import io.orkes.example.saga.pojos.FoodDeliveryRequest;
import io.orkes.example.saga.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link OrderServiceController} 的端點契約測試（Issue #60）。
 *
 * <p>以手寫替身取代 {@link WorkflowService}（不啟動 Spring 容器、不連線 Conductor），
 * 直接呼叫 controller 方法，驗證：
 * <ul>
 *   <li>請求內容原樣交給 WorkflowService（同一物件、未被改寫）；</li>
 *   <li>回傳值原樣回給呼叫端——成功與失敗（error map）都是 HTTP 200 + 服務層的回傳內容，
 *       controller 不做任何攔截或改寫。</li>
 * </ul>
 */
class OrderServiceControllerTest {

    /** 只記錄呼叫、回傳預設值的替身；不觸碰 WorkflowClient／Environment。 */
    private static class StubWorkflowService extends WorkflowService {
        private FoodDeliveryRequest received;
        private int callCount;
        private Map<String, Object> responseToReturn = Map.of("workflowId", "wf-42");

        StubWorkflowService() {
            super(null, null);
        }

        @Override
        public Map<String, Object> startFoodDeliveryWorkflow(FoodDeliveryRequest foodDeliveryRequest) {
            callCount++;
            received = foodDeliveryRequest;
            return responseToReturn;
        }
    }

    private StubWorkflowService stub;
    private OrderServiceController controller;

    @BeforeEach
    void setUp() {
        stub = new StubWorkflowService();
        controller = new OrderServiceController(stub);
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
    @DisplayName("AC-4 請求透傳：triggerFoodDeliveryFlow 原樣（同物件）交給 WorkflowService")
    void endpointForwardsRequestUnalteredToWorkflowService() {
        FoodDeliveryRequest request = deliveryRequest();

        controller.triggerFoodDeliveryFlow(request);

        assertEquals(1, stub.callCount, "/triggerFoodDeliveryFlow 應恰好觸發一次工作流啟動");
        assertSame(request, stub.received, "controller 不得複製／改寫請求物件");
        // 欄位在交還給測試前保持原樣（防禦性確認）
        assertEquals("jane@example.com", stub.received.getCustomerEmail());
        assertEquals(2, stub.received.getRestaurantId());
        assertEquals(35.5, stub.received.getPaymentAmount());
    }

    @Test
    @DisplayName("AC-4 成功回傳：WorkflowService 的結果原樣回給呼叫端（HTTP 200 + 同一 map）")
    void endpointReturnsWorkflowServiceResponseVerbatim() {
        Map<String, Object> serviceResult = Map.of("workflowId", "wf-777");
        stub.responseToReturn = serviceResult;

        ResponseEntity<Map<String, Object>> response =
                controller.triggerFoodDeliveryFlow(deliveryRequest());

        assertEquals(200, response.getStatusCode().value());
        assertSame(serviceResult, response.getBody(), "回傳內容應原樣透出，不經包装或改寫");
        assertEquals(Map.of("workflowId", "wf-777"), response.getBody());
    }

    @Test
    @DisplayName("AC-4 失敗回傳：服務層錯誤 map 仍原樣回給呼叫端（controller 不攔截、不改狀態碼）")
    void endpointPassesThroughFailureResponse() {
        Map<String, Object> failure =
                Map.of("error", "Order creation failure", "detail", "java.lang.RuntimeException: boom");
        stub.responseToReturn = failure;

        ResponseEntity<Map<String, Object>> response =
                controller.triggerFoodDeliveryFlow(deliveryRequest());

        assertEquals(200, response.getStatusCode().value(),
                "controller 不做錯誤處理，失敗情境的狀態碼由服務層回傳決定");
        assertSame(failure, response.getBody());
    }
}
