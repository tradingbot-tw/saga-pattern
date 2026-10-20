package io.orkes.example.saga.dao;

/**
 * DAO 資料庫失敗的可辨識例外（Issue #34 / TB1 報告 §7.2 G3）。
 *
 * <p>補償 worker 必須能區分兩種失敗：
 * <ul>
 *   <li><strong>資料庫例外</strong>（連線失敗、SQL 錯誤）→ 本例外，對應
 *       {@code FAILED}（可重試，由 Conductor retry policy 接管）；</li>
 *   <li><strong>目標列不存在</strong> → 更新回傳 0 列，對應
 *       {@code FAILED_WITH_TERMINAL_ERROR}（重試不會憑空生出資料）。</li>
 * </ul>
 * 既有實作把 {@code SQLException} 印出後吞掉，使補償失敗對工作流不可見，本例外終止這種語意。
 */
public class DaoWriteException extends RuntimeException {

    public DaoWriteException(String message) {
        super(message);
    }

    public DaoWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
