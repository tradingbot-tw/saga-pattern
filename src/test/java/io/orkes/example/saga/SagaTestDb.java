package io.orkes.example.saga;

import io.orkes.example.saga.dao.BaseDAO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * 測試用的 SQLite 資料庫工具（Issue #9）。
 *
 * <p>DAO 層的連線字串由建構子傳入，因此每個 DAO 測試使用獨立的暫存資料庫檔案，互不干擾。
 * service 與 worker 層則把 {@code jdbc:sqlite:food_delivery.db} 寫死在 static 欄位
 * （見 {@code OrderService}/{@code PaymentService} 等），無法注入，只能共用測試工作目錄下的
 * 同一個資料庫檔；該檔已在 .gitignore 中（{@code *.db}），且各測試用 UUID 避免資料相衝。
 */
public final class SagaTestDb {

    /** 與 {@code SagaApplication} 及各 service 寫死的連線字串一致。 */
    public static final String APP_DB_URL = "jdbc:sqlite:food_delivery.db";

    private SagaTestDb() {
    }

    /** 建立一個全新的暫存資料庫連線字串（檔案尚未建立，由 {@link #createSchema} 建表）。 */
    public static String tempUrl(String name) throws IOException {
        Path file = Paths.get(System.getProperty("java.io.tmpdir"),
                "saga-test-" + name + "-" + UUID.randomUUID() + ".db");
        Files.deleteIfExists(file);
        return "jdbc:sqlite:" + file;
    }

    /** 暫存資料庫路徑（去掉 jdbc 前綴）。 */
    public static String filePathOf(String url) {
        return url.substring("jdbc:sqlite:".length());
    }

    /** 刪除暫存資料庫檔。 */
    public static void delete(String url) {
        try {
            Files.deleteIfExists(Paths.get(filePathOf(url)));
        } catch (IOException e) {
            System.err.println("清理暫存資料庫失敗: " + e.getMessage());
        }
    }

    /**
     * 依 {@code SagaApplication.initDB()} 的順序建齊 7 張表並帶入種子資料
     * （{@code createTables} 內部有 {@code tableExists} 守衛，重複呼叫是安全的）。
     */
    public static BaseDAO createSchema(String url) {
        BaseDAO dao = new BaseDAO(url);
        dao.createTables("orders");
        dao.createTables("inventory");
        dao.createTables("payments");
        dao.createTables("shipments");
        return dao;
    }

    /** 為寫死路徑的 service/worker 測試確保資料庫與表存在。 */
    public static void createAppSchema() {
        createSchema(APP_DB_URL);
    }

    /** 取查詢結果第一列的第一欄（文字），無結果時回傳 null。 */
    public static String queryString(String url, String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** 取 {@code COUNT(*)} 型查詢的第一欄整數結果。 */
    public static int count(String url, String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** 產生測試用 id，確保跨測試不互相干擾。 */
    public static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
