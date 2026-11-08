package top.rongx.agent.executor;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSch SSH 的失败路径：不依赖真实服务器，验证错误信息可读且不误导。
 */
class JschSshTest {

    @TempDir
    Path dir;

    private JschSsh ssh() {
        return new JschSsh(dir.resolve("known_hosts"));
    }

    @Test
    @DisplayName("端口未监听 → 明确提示连接被拒绝")
    void refusedPort() {
        JschSsh ssh = ssh();
        JschSsh.Result result = ssh.run("127.0.0.1", 1, "nobody", "PASSWORD", null, "pwd", "echo ok");
        assertFalse(result.ok());
        assertTrue(result.stderr().contains("连接"), result.stderr());
    }

    @Test
    @DisplayName("密码为空 → 直接给出可读原因（不发连接）")
    void blankPassword() {
        JschSsh.Result result = ssh().run("127.0.0.1", 1, "nobody", "PASSWORD", null, "", "echo ok");
        assertFalse(result.ok());
        assertTrue(result.stderr().contains("密码"), result.stderr());
    }

    @Test
    @DisplayName("密钥模式缺路径 → 提示配置私钥（不静默失败）")
    void missingKeyPath() {
        JschSsh.Result result = ssh().run("127.0.0.1", 1, "nobody", "KEY", "", null, "echo ok");
        assertFalse(result.ok());
        assertTrue(result.stderr().contains("私钥"), result.stderr());
    }

    @Test
    @DisplayName("known_hosts 文件会被创建（TOFU 指纹落盘的前提）")
    void knownHostsCreated() throws Exception {
        JschSsh ssh = ssh();
        ssh.run("127.0.0.1", 1, "nobody", "PASSWORD", null, "pwd", "echo ok");
        assertTrue(Files.isRegularFile(dir.resolve("known_hosts")));
    }
}
