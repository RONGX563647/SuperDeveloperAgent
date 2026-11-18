package top.rongx.agent.infra.persistence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.error.EnvironmentException;

/** 设置页写入的数据库后端配置：落盘格式、读取、注入优先级与清除。 */
class DbBackendConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("写入后能原样读回 url/用户名/密文，密码不落明文")
    void writesAndReadsBack() throws Exception {
        Path file = DbBackendConfig.file(tempDir.resolve("platform.db"));

        DbBackendConfig.write(file, "jdbc:postgresql://127.0.0.1:5432/agent",
                "agent_user", "cipher-blob", "iv-blob");

        Map<String, String> values = DbBackendConfig.read(file);
        assertEquals("jdbc:postgresql://127.0.0.1:5432/agent", values.get(DbBackendConfig.KEY_URL));
        assertEquals("agent_user", values.get(DbBackendConfig.KEY_USERNAME));
        assertEquals("cipher-blob", values.get(DbBackendConfig.KEY_PASSWORD_CIPHER));
        assertEquals("iv-blob", values.get(DbBackendConfig.KEY_PASSWORD_IV));
        String raw = Files.readString(file);
        assertTrue(raw.contains("db.password-cipher"), "应写入密文字段");
        assertFalse(raw.contains("db.password:"), "不得写入明文 password 字段");
    }

    @Test
    @DisplayName("含特殊字符的取值经引号转义后仍能读回")
    void roundTripsTrickyValues() {
        Path file = DbBackendConfig.file(tempDir.resolve("platform.db"));

        DbBackendConfig.write(file, "jdbc:postgresql://h/db", "user\"quoted",
                "cipher\\slash", "iv");

        Map<String, String> values = DbBackendConfig.read(file);
        assertEquals("user\"quoted", values.get(DbBackendConfig.KEY_USERNAME));
        assertEquals("cipher\\slash", values.get(DbBackendConfig.KEY_PASSWORD_CIPHER));
    }

    @Test
    @DisplayName("文件不存在时返回空表且不报错（回落到 application.yml）")
    void missingFileYieldsEmpty() {
        Path file = DbBackendConfig.file(tempDir.resolve("platform.db"));

        assertTrue(DbBackendConfig.read(file).isEmpty());
    }

    @Test
    @DisplayName("清除后配置消失，回到 SQLite")
    void clearRemovesFile() {
        Path file = DbBackendConfig.file(tempDir.resolve("platform.db"));
        DbBackendConfig.write(file, "jdbc:postgresql://h/db", "u", "c", "i");

        DbBackendConfig.clear(file);

        assertFalse(Files.exists(file));
        assertTrue(DbBackendConfig.read(file).isEmpty());
    }

    @Test
    @DisplayName("注入系统属性：已存在的键不被覆盖（环境变量/命令行优先）")
    void applyDoesNotOverrideExistingProperties() {
        Path file = DbBackendConfig.file(tempDir.resolve("platform.db"));
        DbBackendConfig.write(file, "jdbc:postgresql://from-file/db", "file_user", "c", "i");
        System.setProperty("AGENT_DB_URL", "jdbc:postgresql://from-env/db");
        try {
            DbBackendConfig.applyToSystemProperties(file, null);

            assertEquals("jdbc:postgresql://from-env/db", System.getProperty("AGENT_DB_URL"),
                    "已存在的键必须保留");
        } finally {
            System.clearProperty("AGENT_DB_URL");
            System.clearProperty("AGENT_DB_USERNAME");
        }
    }

    @Test
    @DisplayName("配置文件里的 url 留空时完全不注入（保持默认 SQLite）")
    void blankUrlInjectsNothing() {
        Path file = DbBackendConfig.file(tempDir.resolve("platform.db"));
        DbBackendConfig.write(file, "", "u", "c", "i");
        System.clearProperty("AGENT_DB_URL");

        DbBackendConfig.applyToSystemProperties(file, null);

        assertEquals(null, System.getProperty("AGENT_DB_URL"));
    }

    @Test
    @DisplayName("解密失败不抛异常：只跳过密码，url/用户名仍注入")
    void decryptFailureKeepsUrl() {
        Path file = DbBackendConfig.file(tempDir.resolve("platform.db"));
        DbBackendConfig.write(file, "jdbc:postgresql://h/db", "u", "c", "i");
        System.clearProperty("AGENT_DB_URL");
        System.clearProperty("AGENT_DB_PASSWORD");
        try {
            DbBackendConfig.applyToSystemProperties(file, (cipher, iv) -> {
                throw new EnvironmentException(
                    top.rongx.agent.domain.error.ErrorCode.ENVIRONMENT_INVALID_STATE, "坏了");
            });

            assertEquals("jdbc:postgresql://h/db", System.getProperty("AGENT_DB_URL"));
            assertEquals(null, System.getProperty("AGENT_DB_PASSWORD"));
        } finally {
            System.clearProperty("AGENT_DB_URL");
            System.clearProperty("AGENT_DB_USERNAME");
        }
    }

    @Test
    @DisplayName("配置文件名固定为 db-backend.yml，与 SQLite 同目录")
    void configFileFollowsSqliteDirectory() {
        Path sqlite = tempDir.resolve("nested/platform.db");

        assertEquals(tempDir.resolve("nested/db-backend.yml"),
                DbBackendConfig.file(sqlite));
    }
}
