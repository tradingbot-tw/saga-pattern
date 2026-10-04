package io.orkes.example.saga.service;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.Payment;
import io.orkes.example.saga.pojos.PaymentDetails;
import io.orkes.example.saga.pojos.PaymentMethod;
import io.orkes.example.saga.pojos.PaymentRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentService} 的步驟行為測試（Issue #9）。
 *
 * <p>service 層把 DAO 連線字串寫死為 {@code jdbc:sqlite:food_delivery.db}，
 * 因此本測試沿用應用的資料庫檔（.gitignore 已排除），並用唯一 orderId 隔開各情境。
 */
class PaymentServiceTest {

    private static final String URL = SagaTestDb.APP_DB_URL;

    @BeforeAll
    static void prepareDatabase() {
        SagaTestDb.createAppSchema();
    }

    private static PaymentRequest request(String orderId, String type, String expiry) {
        PaymentDetails details = new PaymentDetails();
        details.setNumber("4111111111111111");
        details.setExpiry(expiry);
        details.setCvv(123);

        PaymentMethod method = new PaymentMethod();
        method.setType(type);
        method.setDetails(details);

        PaymentRequest request = new PaymentRequest();
        request.setOrderId(orderId);
        request.setCustomerId(1);
        request.setAmount(19.75f);
        request.setMethod(method);
        return request;
    }

    /** 信用卡到期日欄位為 MM/yyyy，取兩年後的同月作為「尚未到期」。 */
    private static String futureExpiry() {
        return YearMonth.now().plusYears(2).format(DateTimeFormatter.ofPattern("MM/yyyy"));
    }

    private static String statusInDb(String orderId) throws Exception {
        return SagaTestDb.queryString(URL,
                "SELECT status FROM payments WHERE orderId = '" + orderId + "'");
    }

    @Test
    @DisplayName("happy path：有效信用卡付款成功，DB 紀錄為 SUCCESSFUL")
    void successfulCardPaymentIsRecordedAsSuccessful() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");

        Payment payment = PaymentService.createPayment(request(orderId, "Credit Card", futureExpiry()));

        assertNotNull(payment.getPaymentId());
        assertEquals(Payment.Status.SUCCESSFUL, payment.getStatus());
        assertNull(payment.getErrorMsg());
        assertEquals(Payment.Status.SUCCESSFUL.name(), statusInDb(orderId));
    }

    @Test
    @DisplayName("步驟失敗：卡號已逾期（01/2020）→ FAILED 並帶出可讓補償接管的錯誤原因")
    void expiredCardFailsWithReason() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");

        Payment payment = PaymentService.createPayment(request(orderId, "Credit Card", "01/2020"));

        assertEquals(Payment.Status.FAILED, payment.getStatus());
        assertEquals("Expired payment method:01/2020", payment.getErrorMsg());
        assertEquals(Payment.Status.FAILED.name(), statusInDb(orderId), "失敗狀態也要落庫，協調器才看得到");
    }

    @Test
    @DisplayName("步驟失敗：到期日格式錯誤 → FAILED，錯誤原因指出無法解析的日期")
    void unparsableExpiryFailsWithReason() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");

        Payment payment = PaymentService.createPayment(request(orderId, "Credit Card", "not-a-date"));

        assertEquals(Payment.Status.FAILED, payment.getStatus());
        assertTrue(payment.getErrorMsg().startsWith("Invalid expiry date:"),
                "應回報格式錯誤，實際為: " + payment.getErrorMsg());
        assertEquals(Payment.Status.FAILED.name(), statusInDb(orderId));
    }

    @Test
    @DisplayName("現有驗證範圍：只有 Credit Card 會檢查到期日，其他付款方式直接核准")
    void nonCardMethodSkipsExpiryCheck() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");

        Payment payment = PaymentService.createPayment(request(orderId, "Cash", "01/2020"));

        assertEquals(Payment.Status.SUCCESSFUL, payment.getStatus());
        assertEquals(Payment.Status.SUCCESSFUL.name(), statusInDb(orderId));
    }

    @Test
    @DisplayName("補償/回滾：cancelPayment 把既有付款改為 CANCELED（協調器的取消步驟）")
    void cancelPaymentMarksExistingPaymentCancelled() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        PaymentService.createPayment(request(orderId, "Credit Card", futureExpiry()));

        PaymentService.cancelPayment(orderId);

        assertEquals(Payment.Status.CANCELED.name(), statusInDb(orderId));
    }

    @Test
    @DisplayName("補償的邊界：查無付款時 cancelPayment 不拋錯，也不留下任何付款紀錄")
    void cancelPaymentOnUnknownOrderIsHarmless() throws Exception {
        String orderId = SagaTestDb.uniqueId("ghost");

        PaymentService.cancelPayment(orderId);

        assertEquals(0, SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM payments WHERE orderId = '" + orderId + "'"));
    }
}
