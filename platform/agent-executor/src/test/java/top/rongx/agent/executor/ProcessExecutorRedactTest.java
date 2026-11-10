package top.rongx.agent.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 日志脱敏：密钥要挡住，但公开的镜像/产物指纹必须保留。 */
class ProcessExecutorRedactTest {

    private static final String DIGEST =
            "9c2a6d0f5b8e4a1c7d3f6b9e2a5c8d1f4e7b0a3c6d9f2e5b8c1a4d7f0e3b6a9c";

    @Test
    @DisplayName("sha256:<hex> 指纹保留（前端产物/日志里可读）")
    void keepsImageDigest() {
        String text = "[image] sha256:" + DIGEST;

        String out = ProcessExecutor.redact(text);

        assertTrue(out.contains(DIGEST), out);
        assertTrue(out.contains("sha256:"), out);
    }

    @Test
    @DisplayName("裸 64 位十六进制（JWT/令牌形态）仍然脱敏")
    void redactsBareHexSecret() {
        String out = ProcessExecutor.redact("token=" + DIGEST + " next-line");

        assertFalse(out.contains(DIGEST), out);
        assertTrue(out.contains("***"), out);
    }

    @Test
    @DisplayName("Bearer / password 赋值仍然脱敏")
    void redactsCredentialAssignments() {
        String bearer = ProcessExecutor.redact("Authorization: Bearer abcdefghijklmnopqrstuvwxyz");
        String password = ProcessExecutor.redact("password=supersecret123");

        assertFalse(bearer.contains("abcdefghijklmnopqrstuvwxyz"), bearer);
        assertFalse(password.contains("supersecret123"), password);
    }
}
