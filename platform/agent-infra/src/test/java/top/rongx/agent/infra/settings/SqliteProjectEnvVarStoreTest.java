package top.rongx.agent.infra.settings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.settings.ProjectEnvVar;
import top.rongx.agent.infra.security.SecretBox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 项目环境变量：加密落库、敏感项掩码、部署侧可解析明文。 */
class SqliteProjectEnvVarStoreTest {

    @TempDir
    Path workspace;

    private SqliteProjectEnvVarStore store() {
        return new SqliteProjectEnvVarStore(workspace.toString(), new SecretBox(workspace.toString(), ""));
    }

    @Test
    @DisplayName("敏感值加密且列表只回掩码；非敏感值可核对；resolve 返回明文")
    void encryptsMasksAndResolves() throws Exception {
        SqliteProjectEnvVarStore store = store();
        store.save("RY", "test", "TOKEN_SECRET", "super-secret-value", true, "admin");
        store.save("RY", "test", "DB_HOST", "10.0.0.12", false, "admin");

        var list = store.findAll("RY", "test");
        assertEquals(2, list.size());
        ProjectEnvVar token = list.stream().filter(v -> v.key().equals("TOKEN_SECRET")).findFirst().orElseThrow();
        ProjectEnvVar host = list.stream().filter(v -> v.key().equals("DB_HOST")).findFirst().orElseThrow();
        assertTrue(token.secret());
        assertEquals("****alue", token.value(), "敏感项只显示末 4 位");
        assertFalse(token.toString().contains("super-secret-value"));
        assertFalse(host.secret());
        assertEquals("10.0.0.12", host.value());

        Map<String, String> resolved = store.resolve("RY", "test");
        assertEquals("super-secret-value", resolved.get("TOKEN_SECRET"));
        assertEquals("10.0.0.12", resolved.get("DB_HOST"));

        // 密文落库：数据库里不应出现明文
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + workspace.resolve("_platform/projects.db"));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value_enc FROM project_env_vars")) {
            while (rs.next()) {
                assertFalse(rs.getString(1).contains("super-secret-value"));
            }
        }
    }

    @Test
    @DisplayName("留空值 = 保持原值（只切换敏感标记）；新变量留空则拒绝")
    void blankValueKeepsExisting() {
        SqliteProjectEnvVarStore store = store();
        store.save("RY", "test", "DB_HOST", "10.0.0.12", true, "admin");
        ProjectEnvVar updated = store.save("RY", "test", "DB_HOST", "", false, "devops");

        assertFalse(updated.secret());
        assertEquals("10.0.0.12", updated.value());
        assertEquals("devops", updated.updatedBy());
        assertEquals("10.0.0.12", store.resolve("RY", "test").get("DB_HOST"));

        assertThrows(IllegalArgumentException.class,
                () -> store.save("RY", "test", "NEW_KEY", null, true, "admin"));
    }

    @Test
    @DisplayName("删除与合法性校验")
    void deleteAndValidation() {
        SqliteProjectEnvVarStore store = store();
        store.save("RY", "test", "A_B", "1", true, "admin");
        store.delete("RY", "test", "A_B");
        assertTrue(store.findAll("RY", "test").isEmpty());
        // 幂等删除
        store.delete("RY", "test", "A_B");

        assertThrows(IllegalArgumentException.class,
                () -> store.save("RY", "qa", "K", "v", true, "admin"));
        assertThrows(IllegalArgumentException.class,
                () -> store.save("RY", "test", "1BAD", "v", true, "admin"));
        assertThrows(IllegalArgumentException.class,
                () -> store.save("../x", "test", "K", "v", true, "admin"));
    }
}
