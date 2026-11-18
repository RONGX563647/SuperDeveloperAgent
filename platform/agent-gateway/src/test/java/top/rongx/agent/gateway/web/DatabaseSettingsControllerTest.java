package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.infra.persistence.LegacySqliteImporter;
import top.rongx.agent.infra.persistence.PlatformDb;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;

/**
 * 设置页旧库导入接口：只读巡检、显式导入、权限与开关。
 *
 * <p>这段契约的重点是<b>启动不再改数据</b>：巡检接口必须零副作用，
 * 导入只能由 POST 显式触发，且受 {@code settings:write} 与
 * {@code agent.db.import-legacy} 双重约束。
 */
class DatabaseSettingsControllerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        PlatformDb.closeAll();
    }

    /** 权限替身：按构造参数决定是否放行，并记录被检查的权限码。 */
    private static final class StubUser extends CurrentUser {
        private final boolean allowed;
        private final List<String> checked = new ArrayList<>();

        StubUser(boolean allowed) {
            this.allowed = allowed;
        }

        @Override
        public void checkPermission(String permission) {
            checked.add(permission);
            if (!allowed) {
                throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                        "无权限：" + permission);
            }
        }
    }

    private AgentDbProperties props(boolean importLegacy) {
        return new AgentDbProperties("", "", "", tempDir.resolve("platform.db").toString(),
                2, importLegacy);
    }

    private DatabaseSettingsController controller(AgentDbProperties props, CurrentUser user) {
        PlatformDb db = PlatformDb.sqlite(tempDir.resolve("platform.db"));
        return new DatabaseSettingsController(props, null, null,
                new LegacySqliteImporter(db, props), user);
    }

    /** 造一个真实旧库文件（run_ledger 是已映射实体表，统一库里必然存在）。 */
    private void writeLegacy(String fileName) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve(fileName));
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE run_ledger (id TEXT PRIMARY KEY, kind TEXT NOT NULL,
                        project_id TEXT NOT NULL, subject_id TEXT NOT NULL, owner_user_id TEXT NOT NULL,
                        state TEXT NOT NULL, summary TEXT NOT NULL, error TEXT NOT NULL,
                        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
                    """);
            statement.execute("""
                    INSERT INTO run_ledger VALUES ('ctl-1','CHAT','p1','','u1','SUCCEEDED','接口导入','',1,2)
                    """);
        }
    }

    /** Map 里取数字：避免 assertEquals 在 Object 上的重载歧义。 */
    private static long num(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof Number number ? number.longValue() : -1L;
    }

    /** 统一库里某条旧库记录的行数（泛型推断会污染 assertEquals 重载，务必先落成 long）。 */
    private long rows(String id) {
        long count = PlatformDb.sqlite(tempDir.resolve("platform.db"))
                .read(em -> PlatformDb.count(em,
                        "SELECT COUNT(*) FROM run_ledger WHERE id = ?", id));
        return count;
    }

    @Test
    @DisplayName("GET /legacy 只读巡检：报告文件与 pending，且不导入数据")
    void legacyStatusIsReadOnly() throws Exception {
        writeLegacy("run_ledger.db");
        AgentDbProperties props = props(true);
        DatabaseSettingsController controller = controller(props, new StubUser(true));

        Map<String, Object> status = controller.legacyStatus();

        assertEquals("SQLITE", status.get("backend"));
        assertEquals(Boolean.TRUE, status.get("enabled"));
        assertEquals(Boolean.TRUE, status.get("pending"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) status.get("files");
        assertEquals(1, files.size());
        assertEquals("run_ledger.db", files.get(0).get("file"));
        assertEquals(Boolean.FALSE, files.get(0).get("imported"));
        assertEquals(0L, num(files.get(0), "importedRows"));

        long imported = rows("ctl-1");
        assertEquals(0L, imported, "巡检接口不得导入数据");
    }

    @Test
    @DisplayName("POST /legacy/import 显式触发导入并返回逐文件结果")
    void legacyImportWritesRows() throws Exception {
        writeLegacy("run_ledger.db");
        AgentDbProperties props = props(true);
        DatabaseSettingsController controller = controller(props, new StubUser(true));

        Map<String, Object> result = controller.legacyImport(Map.of("force", false));

        assertEquals(Boolean.TRUE, result.get("ok"));
        assertEquals(1L, num(result, "imported"));
        assertEquals(0L, num(result, "failedFiles"));
        long first = rows("ctl-1");
        assertEquals(1L, first);

        // 再触发一次：已标记 → 跳过，不重复导入
        Map<String, Object> second = controller.legacyImport(Map.of());
        assertEquals(0L, num(second, "imported"));
        assertEquals(1L, num(second, "skippedFiles"));
        long afterSecond = rows("ctl-1");
        assertEquals(1L, afterSecond);
    }

    @Test
    @DisplayName("force=true 重放：忽略标记但幂等，不产生重复行")
    void legacyImportForceReplays() throws Exception {
        writeLegacy("run_ledger.db");
        AgentDbProperties props = props(true);
        DatabaseSettingsController controller = controller(props, new StubUser(true));

        controller.legacyImport(Map.of());
        Map<String, Object> forced = controller.legacyImport(Map.of("force", true));

        assertEquals(Boolean.TRUE, forced.get("ok"));
        assertEquals(1L, num(forced, "imported"));
        assertEquals(0L, num(forced, "skippedFiles"));
        long rows = rows("ctl-1");
        assertEquals(1L, rows, "重放不得产生重复行");
    }

    @Test
    @DisplayName("配置关闭时导入接口明确拒绝，不做任何写入")
    void legacyImportRejectedWhenDisabled() throws Exception {
        writeLegacy("run_ledger.db");
        AgentDbProperties props = props(false);
        DatabaseSettingsController controller = controller(props, new StubUser(true));

        EnvironmentException error = assertThrows(EnvironmentException.class,
                () -> controller.legacyImport(Map.of()));
        assertTrue(error.getMessage().contains("import-legacy"));

        long rows = rows("ctl-1");
        assertEquals(0L, rows);
    }

    @Test
    @DisplayName("权限：读接口要 settings:read，导入要 settings:write")
    void permissionsAreEnforced() {
        AgentDbProperties props = props(true);

        StubUser reader = new StubUser(true);
        controller(props, reader).legacyStatus();
        assertEquals(List.of("settings:read"), reader.checked);

        StubUser writer = new StubUser(true);
        controller(props, writer).legacyImport(Map.of());
        assertEquals(List.of("settings:write"), writer.checked);

        DatabaseSettingsController denied = controller(props, new StubUser(false));
        assertThrows(EnvironmentException.class, denied::legacyStatus);
        assertThrows(EnvironmentException.class, () -> denied.legacyImport(Map.of()));
    }

    @Test
    @DisplayName("状态接口带出 legacyImportEnabled，前端据此禁用按钮")
    void statusExposesLegacySwitch() {
        Map<String, Object> enabled = controller(props(true), new StubUser(true)).status();
        Map<String, Object> disabled = controller(props(false), new StubUser(true)).status();

        assertEquals(Boolean.TRUE, enabled.get("legacyImportEnabled"));
        assertEquals(Boolean.FALSE, disabled.get("legacyImportEnabled"));
        assertFalse(String.valueOf(enabled.get("jdbcUrl")).isBlank());
    }

    @Test
    @DisplayName("无旧库文件时巡检返回空列表且不 pending")
    void emptyDirectoryHasNoPending() throws Exception {
        Files.createDirectories(tempDir);
        Map<String, Object> status = controller(props(true), new StubUser(true)).legacyStatus();

        assertEquals(Boolean.FALSE, status.get("pending"));
        assertTrue(((List<?>) status.get("files")).isEmpty());
    }
}
