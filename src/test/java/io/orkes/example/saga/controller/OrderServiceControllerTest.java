package io.orkes.example.saga.controller;

import io.orkes.example.saga.pojos.FoodDeliveryRequest;
import io.orkes.example.saga.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link OrderServiceController} 對外端點 {@code POST /triggerFoodDeliveryFlow} 的契約
 * （Issue #60）。
 *
 * <p>這裡不啟動 Spring 容器、不連線 Conductor：以手寫替身取代
 * {@link WorkflowService}，直接呼叫 controller 方法，驗證端點只做轉發——
 * 請求物件原樣交給 service，service 的回傳 Map 原樣包成 200 OK 回給呼叫端。
 */
class OrderServiceControllerTest {

    /** 只記錄收到的請求、回傳固定結果的 {@link WorkflowService} 替身。 */
    private static class RecordingWorkflowService extends WorkflowService {
        private FoodDeliveryRequest received;
        private final Map<String, Object> result = new LinkedHashMap<>();

        RecordingWorkflowService() {
            // WorkflowService 的欄位在轉發測試中不會被使用
            super(null, null);
            result.put("workflowId", "workflow-42");
        }

        @Override
        public Map<String, Object> startFoodDeliveryWorkflow(FoodDeliveryRequest foodDeliveryRequest) {
            this.received = foodDeliveryRequest;
            return result;
        }
    }

    private RecordingWorkflowService workflowService;
    private OrderServiceController controller;

    @BeforeEach
    void setUp() {
        workflowService = new RecordingWorkflowService();
        controller = new OrderServiceController(workflowService);
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
    @DisplayName("請求轉發：controller 把同一個 FoodDeliveryRequest 原樣交給 WorkflowService")
    void passesRequestBodyThroughToWorkflowService() {
        FoodDeliveryRequest request = deliveryRequest();

        controller.triggerFoodDeliveryFlow(request);

        assertSame(request, workflowService.received,
                "controller 不應改寫或包裝請求，必須原樣轉交");
        assertEquals("jane@example.com", workflowService.received.getCustomerEmail());
        assertEquals(2, workflowService.received.getRestaurantId());
        assertEquals(35.5, workflowService.received.getPaymentAmount());
    }

    @Test
    @DisplayName("回應轉發：service 的回傳 Map 原樣包成 200 OK 回給呼叫端")
    void returnsWorkflowServiceResultAsOkBody() {
        ResponseEntity<Map<String, Object>> response =
                controller.triggerFoodDeliveryFlow(deliveryRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(workflowService.result, response.getBody(),
                "controller 不應改寫 service 的結果");
        assertEquals("workflow-42", response.getBody().get("workflowId"));
    }
}
