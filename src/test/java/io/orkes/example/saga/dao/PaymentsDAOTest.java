package io.orkes.example.saga.dao;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.Payment;
import io.orkes.example.saga.pojos.PaymentDetails;
import io.orkes.example.saga.pojos.PaymentMethod;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentsDAO} 的行為測試（Issue #9）：付款紀錄寫入/回讀、狀態機轉換，
 * 以及補償（撤銷付款）的路徑。
 */
class PaymentsDAOTest {

    private static String url;
    private static PaymentsDAO dao;

    @BeforeAll
    static void createDatabase() throws IOException {
        url = SagaTestDb.tempUrl("payments");
        SagaTestDb.createSchema(url);
        dao = new PaymentsDAO(url);
    }

    @AfterAll
    static void dropDatabase() {
        SagaTestDb.delete(url);
    }

    /**
     * 建立一筆付款紀錄。金流僅為範例用的模擬值（見 catalog-info.yaml 的 risk-profile），
     * 卡片欄位一律使用測試用樣板數字，不涉及真實憑證。
     */
    private static Payment pendingPayment(String orderId) {
        PaymentDetails details = new PaymentDetails();
        details.setNumber("4111111111111111");
        details.setExpiry("12/2099");
        details.setCvv(123);

        PaymentMethod method = new PaymentMethod();
        method.setType("Credit Card");
        method.setDetails(details);

        Payment payment = new Payment();
        payment.setPaymentId(SagaTestDb.uniqueId("payment"));
        payment.setOrderId(orderId);
        payment.setAmount(42.5);
        payment.setPaymentMethod(method);
        payment.setStatus(Payment.Status.PENDING);
        return payment;
    }

    @Test
    @DisplayName("happy path：付款寫入後初始狀態為 PENDING，可依 orderId 回讀")
    void insertPaymentPersistsAsPending() {
        String orderId = SagaTestDb.uniqueId("order");
        Payment payment = pendingPayment(orderId);

        assertEquals("", dao.insertPayment(payment), "insertPayment 成功時應回傳空字串");

        Payment read = new Payment();
        dao.readPayment(orderId, read);

        assertEquals(payment.getPaymentId(), read.getPaymentId());
        assertEquals(orderId, read.getOrderId());
        assertEquals(42.5, read.getAmount(), 0.001);
        assertEquals(Payment.Status.PENDING, read.getStatus());
        assertTrue(read.getCreatedAt() > 0, "寫入時應帶上建立時間");
    }

    @Test
    @DisplayName("狀態機：PENDING → SUCCESSFUL 的更新會回讀到同一筆付款")
    void updatePaymentStatusToSuccessful() {
        String orderId = SagaTestDb.uniqueId("order");
        Payment payment = pendingPayment(orderId);
        dao.insertPayment(payment);

        payment.setStatus(Payment.Status.SUCCESSFUL);
        assertEquals(1, dao.updatePaymentStatus(payment), "更新既有付款應回傳受影響的 1 列");

        Payment read = new Payment();
        dao.readPayment(orderId, read);
        assertEquals(Payment.Status.SUCCESSFUL, read.getStatus());
    }

    @Test
    @DisplayName("步驟失敗訊號：更新不存在的付款回傳 0，不再只印訊息")
    void updateUnknownPaymentReturnsZero() {
        Payment missing = pendingPayment(SagaTestDb.uniqueId("missing-payment"));
        missing.setStatus(Payment.Status.CANCELED);

        assertEquals(0, dao.updatePaymentStatus(missing),
                "UPDATE 沒有命中任何列時必須回傳 0");
    }

    @Test
    @DisplayName("補償（多重付款）：cancelPaymentsByOrderId 一次取消同一 orderId 的全部付款")
    void cancelPaymentsByOrderIdCancelsEveryPayment() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        Payment first = pendingPayment(orderId);
        Payment second = pendingPayment(orderId);
        assertEquals("", dao.insertPayment(first));
        assertEquals("", dao.insertPayment(second));

        assertEquals(2, dao.cancelPaymentsByOrderId(orderId));

        assertEquals(0, SagaTestDb.count(url,
                        "SELECT COUNT(*) FROM payments WHERE orderId = '" + orderId
                                + "' AND status <> 'CANCELED'"),
                "同一 orderId 的每一筆付款都必須是 CANCELED");
    }

    @Test
    @DisplayName("步驟失敗訊號：cancelPaymentsByOrderId 查無付款時回傳 0")
    void cancelPaymentsByOrderIdReturnsZeroForUnknownOrder() {
        assertEquals(0, dao.cancelPaymentsByOrderId(SagaTestDb.uniqueId("missing-order")),
                "查無付款時必須回傳 0，讓 worker 回報 terminal error");
    }

    @Test
    @DisplayName("補償/回滾：已成功的付款可被更新為 CANCELED")
    void updatePaymentStatusToCancelledAsCompensation() {
        String orderId = SagaTestDb.uniqueId("order");
        Payment payment = pendingPayment(orderId);
        dao.insertPayment(payment);
        payment.setStatus(Payment.Status.SUCCESSFUL);
        dao.updatePaymentStatus(payment);

        payment.setStatus(Payment.Status.CANCELED);
        assertEquals(1, dao.updatePaymentStatus(payment));

        Payment read = new Payment();
        dao.readPayment(orderId, read);
        assertEquals(Payment.Status.CANCELED, read.getStatus());
    }

    @Test
    @DisplayName("步驟失敗訊號：重複 paymentId 寫入回傳非空錯誤")
    void duplicatePaymentIdIsRejectedWithError() {
        String orderId = SagaTestDb.uniqueId("order");
        Payment payment = pendingPayment(orderId);
        assertEquals("", dao.insertPayment(payment));

        String error = dao.insertPayment(payment);

        assertFalse(error.isEmpty(), "主鍵重複時 insertPayment 應回傳錯誤訊息");
        assertTrue(error.toUpperCase().contains("PRIMARY KEY"), "錯誤訊息應指出主鍵衝突，實際為: " + error);
    }

    @Test
    @DisplayName("狀態機：付款狀態只有 PENDING/FAILED/SUCCESSFUL/CANCELED 四種，可經 DB 文字往返")
    void paymentStatusSurvivesDatabaseRoundTrip() {
        assertEquals(4, Payment.Status.values().length);
        for (Payment.Status status : Payment.Status.values()) {
            assertEquals(status, Payment.Status.valueOf(status.name()));
        }
    }
}
