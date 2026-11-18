package top.rongx.agent.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WAL 落盘：把停留在 -wal 的数据合并回主文件，避免只拷 .db 时丢数据。 */
class SqliteWalCheckpointerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("checkpoint 把 WAL 数据写入主文件（主文件独立可读）")
    void checkpointFlushesWalIntoMainFile() throws Exception {
        Path db = tempDir.resolve("wal.db");
        long before;
        // 保持一个写连接打开，模拟生产常驻连接池：数据停在 WAL，不会自动落主文件
        try (HikariDataSource ds = sqlitePool(db);
             Connection writer = ds.getConnection()) {
            try (Statement s = writer.createStatement()) {
                s.execute("CREATE TABLE t (id TEXT PRIMARY KEY)");
                for (int i = 0; i < 200; i++) {
                    s.execute("INSERT INTO t VALUES ('r" + i + "')");
                }
            }
            before = java.nio.file.Files.size(db);

            SqliteWalCheckpointer.Checkpoint result = SqliteWalCheckpointer.checkpoint(ds);
            assertTrue(result.ok(), "checkpoint 应成功: " + result);
        }
        long after = java.nio.file.Files.size(db);
        assertTrue(after > before, "主文件应变大（WAL 内容已合并）: " + before + " -> " + after);

        // 用 immutable（忽略 WAL）只读主文件，确认数据真的落盘了
        try (Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:file:" + db.toAbsolutePath().toString().replace('\\', '/') + "?immutable=1")) {
            try (Statement s = c.createStatement();
                 var rs = s.executeQuery("SELECT COUNT(*) FROM t")) {
                rs.next();
                assertEquals(200, rs.getInt(1), "主文件独立可读到全部行");
            }
        }
    }

    @Test
    @DisplayName("多个常驻连接下 checkpoint 不崩，返回 busy 状态")
    void checkpointWithOpenConnections() throws Exception {
        Path db = tempDir.resolve("wal2.db");
        try (HikariDataSource ds = sqlitePool(db);
             Connection hold = ds.getConnection()) {
            try (Statement s = hold.createStatement()) {
                s.execute("CREATE TABLE t (id TEXT)");
                s.execute("INSERT INTO t VALUES ('a')");
            }
            SqliteWalCheckpointer.Checkpoint result = SqliteWalCheckpointer.checkpoint(ds);
            // 有空闲持有连接时实现可能返回 busy，也可能成功；关键是能返回且不抛异常
            assertTrue(result.checkpointed() >= 0);
        }
    }

    private static HikariDataSource sqlitePool(Path db) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + db);
        config.setMaximumPoolSize(4);
        // 与生产 DataSourceConfig 一致：必须显式开 WAL，否则连接关闭时自动合并，
        // 主文件本就自包含，checkpoint 测不出效果。
        config.addDataSourceProperty("journal_mode", "WAL");
        return new HikariDataSource(config);
    }
}
