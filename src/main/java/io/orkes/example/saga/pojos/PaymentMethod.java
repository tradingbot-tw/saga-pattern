package io.orkes.example.saga.pojos;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class PaymentMethod {
    private String type;
    private PaymentDetails details;

    /**
     * 可落庫的付款方式摘要（Issue #83）：只有類型與遮罩後的卡號，例如
     * {@code Credit Card ****1111}。
     *
     * <p>刻意不使用 Lombok 的 {@code toString()} 寫入資料庫：那會把完整卡號、到期日與
     * {@code cvv} 一起序列化進 {@code payments.method} 欄位。
     */
    public String toAuditString() {
        String kind = type == null || type.isBlank() ? "unknown" : type;
        return details == null ? kind : kind + " " + details.maskedNumber();
    }
}
