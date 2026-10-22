package io.orkes.example.saga.pojos;

import lombok.Data;

/**
 * {@code cancel_order}／{@code cancel_payment}／{@code cancel_delivery} 的輸入。
 */
@Data
public class CancelRequest {
    private String orderId;

    /**
     * 查無可取消的資料時是否視為成功（#136–#139）。
     *
     * <p>預設 {@code false}：查無資料回 {@code FAILED_WITH_TERMINAL_ERROR}，擋下無效 ID（Issue #34）。
     * 補償 workflow 不看失敗點、一律反向取消每個步驟，因此傳 {@code true}：
     * 該步驟從未寫入資料時，取消就是 no-op。
     */
    private boolean allowMissing;
}
