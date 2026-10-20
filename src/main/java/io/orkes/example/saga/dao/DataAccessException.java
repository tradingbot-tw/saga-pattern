package io.orkes.example.saga.dao;

/**
 * DAO 在資料庫存取失敗時拋出的可辨識例外（Issue #34）。
 *
 * <p>補償路徑需要區分「目標不存在」與「資料庫不可用」：前者是
 * {@code FAILED_WITH_TERMINAL_ERROR}，後者是可重試的 {@code FAILED}。
 * DAO 不再以 {@code System.out.println} 吞掉 {@link java.sql.SQLException}，
 * 而是包裝成本例外讓 service / worker 依此回報。
 */
public class DataAccessException extends RuntimeException {

    public DataAccessException(String message, Throwable cause) {
        super(cause == null || cause.getMessage() == null
                ? message
                : message + ": " + cause.getMessage(), cause);
    }
}
