package top.rongx.agent.gateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** .env 加载器：解析规则与优先级（真实环境变量 > .env）。 */
class DotEnvLoaderTest {

    @Test
    @DisplayName("解析 K=V 与注释行，注入系统属性")
    void parsesKeyValue(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        // 用不会与真实环境变量重名的键：真实环境变量优先是 .env 的既定语义，
        // 用 AGENT_MODEL_* 这类键会让本用例在"机器上确实配了模型变量"时失败。
        Files.writeString(file, """
                # 注释
                DOTENV_BASE_URL=https://api.deepseek.com
                DOTENV_MODEL_NAME=deepseek-chat

                QUOTED="hello world"
                """);
        // 清理可能存在的同名属性
        System.clearProperty("DOTENV_BASE_URL");
        System.clearProperty("DOTENV_MODEL_NAME");
        System.clearProperty("QUOTED");

        AgentApplication.loadDotEnv(file);

        assertEquals("https://api.deepseek.com", System.getProperty("DOTENV_BASE_URL"));
        assertEquals("deepseek-chat", System.getProperty("DOTENV_MODEL_NAME"));
        assertEquals("hello world", System.getProperty("QUOTED"));
    }

    @Test
    @DisplayName("非法键名与无等号行被跳过；文件不存在不抛错")
    void skipsInvalidLines(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, """
                no-equals-line
                1BAD=x
                VALID_OK=y
                """);
        System.clearProperty("VALID_OK");

        AgentApplication.loadDotEnv(file);

        assertNull(System.getProperty("1BAD"));
        assertEquals("y", System.getProperty("VALID_OK"));

        // 不存在的文件静默跳过
        AgentApplication.loadDotEnv(dir.resolve("nope.env"));
    }

    @Test
    @DisplayName("真实环境变量优先：.env 不覆盖已存在的系统属性")
    void doesNotOverrideExisting(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, "DOTENV_EXISTING=from-file\n");
        System.setProperty("DOTENV_EXISTING", "from-system");
        try {
            AgentApplication.loadDotEnv(file);
            assertEquals("from-system", System.getProperty("DOTENV_EXISTING"),
                    "已有值必须保留（真实环境变量 > .env）");
        } finally {
            System.clearProperty("DOTENV_EXISTING");
        }
    }

    @Test
    @DisplayName(".env 文件确实被 gitignore 忽略（防凭证入库）")
    void dotEnvIsIgnoredByGit() {
        // 读取 .gitignore 静态断言（不依赖 git 命令）
        Path gitignore = Path.of("..").resolve(".gitignore").toAbsolutePath().normalize();
        // platform/.gitignore 或根 .gitignore 至少一处含 .env
        Path platformGitignore = Path.of(".gitignore").toAbsolutePath().normalize();
        boolean found = false;
        try {
            String content = Files.readString(platformGitignore);
            found = content.lines().anyMatch(l -> l.trim().equals(".env"));
        } catch (IOException ignored) {
            // fall through
        }
        if (!found) {
            try {
                String content = Files.readString(gitignore);
                found = content.lines().anyMatch(l -> l.trim().equals(".env"));
            } catch (IOException ignored) {
                // fall through
            }
        }
        assertTrue(found, ".gitignore 必须包含 .env（凭证不入库）");
    }
}
