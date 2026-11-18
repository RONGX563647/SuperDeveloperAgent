package top.rongx.agent.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SQLite → 目标库的逐表复制。
 *
 * <p>用两个 SQLite 文件模拟"源 → 目标"：复制逻辑与后端无关（列取交集、行 ON CONFLICT DO NOTHING），
 * 因此不需要真起 PostgreSQL 也能覆盖到公共列裁剪、幂等、缺表跳过等分支。
 */
class SqliteToTargetCopierTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        PlatformDb.closeAll();
    }

    @Test
    @DisplayName("公共列被完整复制，目标库多出的列不参与")
    void copiesCommonColumns() throws Exception {
        Path sourceFile = tempDir.resolve("source.db");
        Path targetFile = tempDir.resolve("target.db");
        try (Connection source = sqlite(sourceFile);
             Statement s = source.createStatement()) {
            s.execute("CREATE TABLE platform_meta (meta_key TEXT PRIMARY KEY,"
                    + " meta_value TEXT, legacy_only TEXT)");
            s.execute("INSERT INTO platform_meta VALUES ('k1','v1','ignored')");
        }
        try (Connection target = sqlite(targetFile);
             Statement t = target.createStatement()) {
            // 目标库没有 legacy_only，多一个 extra 列（可空，不参与复制）
            t.execute("CREATE TABLE platform_meta (meta_key TEXT PRIMARY KEY,"
                    + " meta_value TEXT, extra TEXT)");
        }

        try (Connection source = sqlite(sourceFile);
             Connection target = sqlite(targetFile)) {
            SqliteToTargetCopier.Report report = SqliteToTargetCopier.copy(source, target);

            assertEquals(1, report.copied());
            assertTrue(report.complete());
            assertEquals(1, report.tables().size());
            assertEquals("platform_meta", report.tables().get(0).table());
        }
        try (Connection target = sqlite(targetFile);
             Statement t = target.createStatement();
             var rs = t.executeQuery("SELECT meta_key, meta_value, extra FROM platform_meta")) {
            assertTrue(rs.next());
            assertEquals("k1", rs.getString(1));
            assertEquals("v1", rs.getString(2));
            assertEquals(null, rs.getString(3), "目标独有列应为空");
        }
    }

    @Test
    @DisplayName("重复执行幂等：已有行不重复插入")
    void copyIsIdempotent() throws Exception {
        Path sourceFile = tempDir.resolve("source.db");
        Path targetFile = tempDir.resolve("target.db");
        try (Connection source = sqlite(sourceFile);
             Statement s = source.createStatement()) {
            s.execute("CREATE TABLE projects (id TEXT PRIMARY KEY, name TEXT)");
            s.execute("INSERT INTO projects VALUES ('p1','项目一')");
        }
        try (Connection target = sqlite(targetFile);
             Statement t = target.createStatement()) {
            t.execute("CREATE TABLE projects (id TEXT PRIMARY KEY, name TEXT)");
        }

        try (Connection source = sqlite(sourceFile);
             Connection target = sqlite(targetFile)) {
            SqliteToTargetCopier.copy(source, target);
            SqliteToTargetCopier.Report second = SqliteToTargetCopier.copy(source, target);
            assertEquals(0, second.copied(), "已存在的行不应被重复计入");
        }
        try (Connection target = sqlite(targetFile);
             Statement t = target.createStatement();
             var rs = t.executeQuery("SELECT COUNT(*) FROM projects")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("目标库缺表时跳过并标记，不让整体失败")
    void missingTargetTableIsSkipped() throws Exception {
        Path sourceFile = tempDir.resolve("source.db");
        Path targetFile = tempDir.resolve("target.db");
        try (Connection source = sqlite(sourceFile);
             Statement s = source.createStatement()) {
            s.execute("CREATE TABLE only_in_source (id TEXT PRIMARY KEY)");
            s.execute("INSERT INTO only_in_source VALUES ('x')");
        }
        try (Connection target = sqlite(targetFile);
             Statement t = target.createStatement()) {
            t.execute("CREATE TABLE platform_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT)");
        }

        try (Connection source = sqlite(sourceFile);
             Connection target = sqlite(targetFile)) {
            SqliteToTargetCopier.Report report = SqliteToTargetCopier.copy(source, target);

            assertFalse(report.complete(), "有表被跳过时算部分完成");
            assertEquals(1, report.skippedTables());
            assertTrue(report.tables().get(0).skipped().contains("已跳过"));
        }
    }

    @Test
    @DisplayName("SQLite 内部表与 flyway 历史表不参与复制")
    void excludesInternalTables() throws Exception {
        Path sourceFile = tempDir.resolve("source.db");
        try (Connection source = sqlite(sourceFile);
             Statement s = source.createStatement()) {
            s.execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER PRIMARY KEY)");
            s.execute("CREATE TABLE real_table (id TEXT PRIMARY KEY)");
            assertTrue(SqliteToTargetCopier.tables(source).contains("real_table"));
            assertFalse(SqliteToTargetCopier.tables(source).contains("flyway_schema_history"));
        }
    }

    private static Connection sqlite(Path file) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + file);
    }
}
