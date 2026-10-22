package io.orkes.example.saga.pojos;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class PaymentDetails {
    private String number;
    private String expiry;
    private int cvv;

    /** 卡號只保留後四碼時用的符號。 */
    private static final String MASK = "****";

    /**
     * 可落庫的卡號摘要：只保留後四碼（Issue #83）。
     *
     * <p>完整卡號與 {@code cvv} 不得寫入資料庫或日誌。卡號為 {@code null}／空白、
     * 或長度不足以區分後四碼時，一律回傳 {@link #MASK}，不透露任何位數。
     */
    public String maskedNumber() {
        if (number == null) {
            return MASK;
        }
        String digits = number.replaceAll("\\s+", "");
        if (digits.length() < 4) {
            return MASK;
        }
        return MASK + digits.substring(digits.length() - 4);
    }
}