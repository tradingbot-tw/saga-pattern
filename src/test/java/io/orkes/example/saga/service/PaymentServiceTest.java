package io.orkes.example.saga.service;

import io.orkes.example.saga.SagaTestDb;
import io.orkes.example.saga.pojos.Payment;
import io.orkes.example.saga.pojos.PaymentDetails;
import io.orkes.example.saga.pojos.PaymentMethod;
import io.orkes.example.saga.pojos.PaymentRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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

        assertEquals(1, PaymentService.cancelPayment(orderId),
                "既有付款應被取消一列");

        assertEquals(Payment.Status.CANCELED.name(), statusInDb(orderId));
    }

    @Test
    @DisplayName("補償的邊界：查無付款時 cancelPayment 回傳 0、不拋錯，也不留下任何付款紀錄")
    void cancelPaymentOnUnknownOrderIsHarmless() throws Exception {
        String orderId = SagaTestDb.uniqueId("ghost");

        assertEquals(0, PaymentService.cancelPayment(orderId),
                "查無付款時 service 回傳 0，由 worker 決定 terminal error");

        assertEquals(0, SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM payments WHERE orderId = '" + orderId + "'"));
    }

    /**
     * 以持久化 trigger 模擬「付款紀錄寫入失敗」（Issue #61）。
     *
     * <p>{@code PaymentsDAO.insertPayment} 每次自開連線，故 trigger 必須建在主庫
     * （TEMP trigger 只对建立連線可見）。RAISE(ABORT) 讓 INSERT 回傳非空錯誤訊息，
     * 走 {@code PaymentService.createPayment} 的寫入失敗分支。
     */
    private static void armInsertFailure() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TRIGGER IF EXISTS fail_payment_insert");
            stmt.execute("CREATE TRIGGER fail_payment_insert BEFORE INSERT ON payments "
                    + "BEGIN SELECT RAISE(ABORT, 'simulated payment insert failure'); END");
        }
    }

    private static void disarmInsertFailure() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TRIGGER IF EXISTS fail_payment_insert");
        }
    }

    @Test
    @DisplayName("寫入失敗：INSERT 被資料庫拒絕 → FAILED + \"Payment creation failure\"，且 DB 沒有該 orderId 紀錄，補償時 cancel_payment 回 0 列、worker 據此回 FAILED_WITH_TERMINAL_ERROR")
    void paymentRecordWriteFailureLeavesNoRowSoCompensationHitsTerminalError() throws Exception {
        String orderId = SagaTestDb.uniqueId("order");
        armInsertFailure();
        try {
            Payment payment = PaymentService.createPayment(request(orderId, "Credit Card", futureExpiry()));

            assertEquals(Payment.Status.FAILED, payment.getStatus(),
                    "付款紀錄寫入失敗時回傳的 Payment 必須是 FAILED");
            assertEquals("Payment creation failure", payment.getErrorMsg(),
                    "寫入失敗的固定錯誤訊息（非 makePayment 失敗訊息）");

            // 沒有落庫：cancel_payment 查無付款 → service 回 0 → worker 轉 FAILED_WITH_TERMINAL_ERROR
            // （0 列的語意已由 ConductorWorkersTest 的 terminal 測試涵蓋，這裡固化「查無」的前提）。
            assertEquals(0, SagaTestDb.count(URL,
                    "SELECT COUNT(*) FROM payments WHERE orderId = '" + orderId + "'"),
                    "寫入失敗代表 DB 中完全沒有這筆付款紀錄");
            assertEquals(0, PaymentService.cancelPayment(orderId),
                    "無紀錄可取消 → 回 0 列，補償 worker 會把它視為 FAILED_WITH_TERMINAL_ERROR");
        } finally {
            disarmInsertFailure();
        }
    }

    @Test
    @DisplayName("寫入失敗（NOT NULL 違例）：orderId 缺失導致 INSERT 被拒 → FAILED + \"Payment creation failure\"，DB 中既無該 orderId 也無該 paymentId 的半筆紀錄")
    void nullOrderIdWriteFailureLeavesNoPartialRecord() throws Exception {
        String orderId = null; // payments.orderId 為 NOT NULL，INSERT 必失敗（Issue #61 情境 1 的另一條路徑）

        Payment payment = PaymentService.createPayment(request(orderId, "Credit Card", futureExpiry()));

        assertEquals(Payment.Status.FAILED, payment.getStatus());
        assertEquals("Payment creation failure", payment.getErrorMsg());
        assertNotNull(payment.getPaymentId(), "paymentId 在寫入前已生成，但只存在於回傳物件裡");
        assertEquals(0, SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM payments WHERE orderId IS NULL"),
                "NOT NULL 違例不得留下任何 orderId 缺失的付款列");
        assertEquals(0, SagaTestDb.count(URL,
                "SELECT COUNT(*) FROM payments WHERE paymentId = '" + payment.getPaymentId() + "'"),
                "該 paymentId 也不應留下半筆紀錄（後續 updatePaymentStatus 是 0 列 no-op）");
    }
}
