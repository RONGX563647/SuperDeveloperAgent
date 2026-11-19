package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
import top.rongx.agent.infra.persistence.PlatformDb;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;

/**
 * 设置页数据库接口：只读状态、权限、旧库导入已移除、热切换字段。
 *
 * <p>不含真实 PG：热切换的成功路径需要可连的 PostgreSQL，交由手工联调覆盖；
 * 这里锁定的是"接口面契约"与"失败时不破坏现状"。
 */
class DatabaseSettingsControllerTest {

    @TempDir
    Path tempDir;

    /** 本测试创建的 PlatformDb：用完必须显式关，否则 Windows 上会锁住临时目录。 */
    private final List<PlatformDb> opened = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (PlatformDb db : opened) {
            try {
                db.close();
            } catch (RuntimeException ignored) {
                // 已关闭则忽略
            }
        }
        opened.clear();
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

    private AgentDbProperties props() {
        return new AgentDbProperties("", "", "", tempDir.resolve("platform.db").toString(), 2);
    }

    /**
     * 只测状态与权限时不需要真实热切换器，用它撑住构造器。
     *
     * <p>用<b>内存库</b>而不是临时文件：本组用例只关心 {@code isPostgres()} 的返回值，
     * 落一个 Windows 上带 -wal/-shm 的 SQLite 文件反而会和临时目录清理抢锁。
     */
    private DatabaseSettingsController statusOnlyController(CurrentUser user) {
        AgentDbProperties props = props();
        PlatformDb db = PlatformDb.create("jdbc:sqlite::memory:", "", "");
        opened.add(db);
        return new DatabaseSettingsController(props, null, null, null, db, user);
    }

    /** Map 里取数字：避免 assertEquals 在 Object 上的重载歧义。 */
    private static long num(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof Number number ? number.longValue() : -1L;
    }

    @Test
    @DisplayName("状态接口报告实际后端、可热切换标记，且不再暴露旧库导入")
    void statusReportsLiveBackend() {
        Map<String, Object> status = statusOnlyController(new StubUser(true)).status();

        assertEquals("SQLITE", status.get("backend"), "backend 取自连接池");
        assertEquals("SQLITE", status.get("configuredBackend"));
        assertEquals(Boolean.TRUE, status.get("hotReload"), "热切换能力必须对前端可见");
        assertEquals(Boolean.TRUE, status.get("migratable"));
        assertFalse(String.valueOf(status.get("jdbcUrl")).isBlank());

        // 旧库导入需求已取消：状态里不得再出现相关字段
        assertFalse(status.containsKey("legacyImportEnabled"), "旧库导入字段应已删除");
        assertFalse(status.containsKey("legacy"), "旧库巡检字段应已删除");
    }

    @Test
    @DisplayName("权限：状态接口要 settings:read，写操作要 settings:write")
    void permissionsAreEnforced() {
        StubUser reader = new StubUser(true);
        statusOnlyController(reader).status();
        assertEquals(List.of("settings:read"), reader.checked);

        DatabaseSettingsController denied = statusOnlyController(new StubUser(false));
        assertThrows(EnvironmentException.class, denied::status);
        assertThrows(EnvironmentException.class,
                () -> denied.switchToPostgres(Map.of("url", "jdbc:postgresql://x/y")));
        assertThrows(EnvironmentException.class, denied::switchToSqlite);
    }

    @Test
    @DisplayName("目标 URL 非 PostgreSQL 时明确拒绝，不触碰现有后端")
    void rejectsNonPostgresTarget() {
        DatabaseSettingsController controller = statusOnlyController(new StubUser(true));

        assertThrows(EnvironmentException.class,
                () -> controller.test(Map.of("url", "jdbc:sqlite:/tmp/x.db")));
        assertThrows(EnvironmentException.class,
                () -> controller.switchToPostgres(Map.of("url", "jdbc:mysql://host/db")));
        assertThrows(EnvironmentException.class,
                () -> controller.test(Map.of()));
    }

    @Test
    @DisplayName("连通性失败返回可读原因而不是异常（用户在填表阶段就要看到）")
    void testReportsFailureReadably() {
        DatabaseSettingsController controller = statusOnlyController(new StubUser(true));

        // 指向一个必然连不上的端口：要回 ok=false + message，而不是抛 500
        Map<String, Object> out = controller.test(Map.of(
                "url", "jdbc:postgresql://127.0.0.1:1/none",
                "username", "nobody",
                "password", "wrong"));

        assertEquals(Boolean.FALSE, out.get("ok"));
        assertTrue(String.valueOf(out.get("message")).contains("连接失败"));
        assertTrue(num(out, "latencyMs") >= 0);
    }
}
