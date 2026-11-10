package top.rongx.agent.executor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开发环境本机托管契约测试：目录命名、缺 jar 的 fail-closed、停止幂等。
 *
 * <p>不启动真实进程（CI 机器不一定有 Java/Node 可执行环境），只验证状态机与文件名约定。
 */
class LocalDevHostDeployerTest {

    @Test
    @DisplayName("dev 分支只由环境/流水线决定：无法用 deployTarget 覆盖成 Docker")
    void devBranchIsNotOverridableByDeployTarget() {
        assertTrue(BuiltInPipelineEngine.localDevBranch("dev", "release", true));
        assertTrue(BuiltInPipelineEngine.localDevBranch("DEV", "release", true));
        // 检查构建只打包，不部署
        assertFalse(BuiltInPipelineEngine.localDevBranch("dev", "verify", true));
        // 非 dev 环境仍按各自策略
        assertFalse(BuiltInPipelineEngine.localDevBranch("test", "release", true));
        assertFalse(BuiltInPipelineEngine.localDevBranch("prod", "release", true));
        // 未装配本机托管能力时不误判
        assertFalse(BuiltInPipelineEngine.localDevBranch("dev", "release", false));
    }

    @Test
    @DisplayName("状态目录按 项目-环境 隔离，避免 dev/test 互相覆盖 pid")
    void stateDirIsScopedByProjectAndEnv(@TempDir Path repo) {
        Path dev = LocalDevHostDeployer.stateDir(repo, "RUOYIWNZ", "dev");
        Path test = LocalDevHostDeployer.stateDir(repo, "RUOYIWNZ", "test");
        assertTrue(dev.endsWith(Path.of(".agent", "deploy", "ruoyiwnz-dev")), dev.toString());
        assertTrue(test.endsWith(Path.of(".agent", "deploy", "ruoyiwnz-test")), test.toString());
        assertFalse(dev.equals(test));
    }

    @Test
    @DisplayName("没有可运行 jar → E-601（部署前就失败，不起任何进程）")
    void deployFailsWithoutJar(@TempDir Path repo) {
        LocalDevHostDeployer deployer = new LocalDevHostDeployer();
        LocalDevHostDeployer.Result result = deployer.deploy(repo, "RY", "dev",
                repo.resolve("missing.jar"), null, 18688, 0, Map.of());
        assertFalse(result.ok());
        assertEquals("E-601", result.errorCode());
        assertFalse(deployer.isRunning(repo, "RY", "dev"));
    }

    @Test
    @DisplayName("停止幂等：没有 pid 文件时返回 false，不抛异常")
    void stopIsIdempotent(@TempDir Path repo) throws Exception {
        LocalDevHostDeployer deployer = new LocalDevHostDeployer();
        assertFalse(deployer.stop(repo, "RY", "dev"));
        Files.createDirectories(LocalDevHostDeployer.stateDir(repo, "RY", "dev"));
        assertFalse(deployer.stop(repo, "RY", "dev"));
    }

    @Test
    @DisplayName("坏 pid 文件视为未运行（不让页面误报「运行中」）")
    void brokenPidCountsAsStopped(@TempDir Path repo) throws Exception {
        LocalDevHostDeployer deployer = new LocalDevHostDeployer();
        Path dir = LocalDevHostDeployer.stateDir(repo, "RY", "dev");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("backend.pid"), "not-a-number");
        assertFalse(deployer.isRunning(repo, "RY", "dev"));
        assertFalse(deployer.stop(repo, "RY", "dev"));
    }

    @Test
    @DisplayName("静态服务器脚本：反代 /prod-api 与 SPA 回退都在")
    void staticServerScriptHasProxyAndFallback() {
        String script = StaticServerScript.text();
        assertTrue(script.contains("/prod-api"));
        assertTrue(script.contains("/dev-api"));
        assertTrue(script.contains("sendIndex"));
        assertTrue(script.contains("distDir"));
    }
}
