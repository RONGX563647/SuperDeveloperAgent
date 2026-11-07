package top.rongx.agent.executor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.run.StageStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器执行的真实集成测试（需要运行中的 Docker 与本地镜像）。
 *
 * <p>无 Docker 或镜像不存在时**自动跳过**，因此在 CI 上不会红。
 * 用镜像环境变量 {@code -Ddocker.test.image=...} 覆盖，默认 {@code node:20-alpine}。
 */
class DockerActionRunnerTest {

    private static final String IMAGE = System.getProperty("docker.test.image", "node:20-alpine");

    private static DockerSandbox sandbox;

    @BeforeAll
    static void requireDocker() {
        sandbox = new DockerSandbox("docker", IMAGE, "none", "1g", "1", 256, "1000:1000");
        Assumptions.assumeTrue(sandbox.available(), "Docker 守护进程不可用，跳过容器测试");
        Assumptions.assumeTrue(imageExists(IMAGE), "本地镜像 " + IMAGE + " 不存在，跳过容器测试");
    }

    private static boolean imageExists(String image) {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", image)
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static ActionSpec spec(List<String> argv, List<String> produce) {
        return new ActionSpec(ActionId.BACKEND_BUILD, List.of(argv), produce,
                Duration.ofMinutes(2), ActionSource.builtin("test"));
    }

    @Test
    @DisplayName("容器内执行：成功并能读到版本输出")
    void runsInContainer(@TempDir Path dir) {
        ActionResult result = new DockerActionRunner(sandbox)
                .run(dir, spec(List.of("node", "-v"), List.of()));

        assertEquals(StageStatus.SUCCEEDED, result.result().status());
        assertTrue(result.logTail().contains("v20"), "应看到容器内 node 版本：" + result.logTail());
    }

    @Test
    @DisplayName("以非 root 运行（uid=1000）")
    void runsAsNonRoot(@TempDir Path dir) {
        ActionResult result = new DockerActionRunner(sandbox)
                .run(dir, spec(List.of("id", "-u"), List.of()));

        assertEquals(StageStatus.SUCCEEDED, result.result().status());
        assertEquals("1000", result.logTail().trim(), "必须是 uid 1000，不能是 root");
    }

    @Test
    @DisplayName("容器写出的文件落回宿主工作区，并被识别为产物")
    void artifactsFlowBackToHost(@TempDir Path dir) {
        String script = "const fs=require('fs');"
                + "fs.mkdirSync('target',{recursive:true});"
                + "fs.writeFileSync('target/out.jar','built-in-container');"
                + "console.log('wrote target/out.jar');";

        ActionResult result = new DockerActionRunner(sandbox)
                .run(dir, spec(List.of("node", "-e", script), List.of("target/*.jar")));

        assertEquals(StageStatus.SUCCEEDED, result.result().status());
        assertTrue(Files.exists(dir.resolve("target/out.jar")), "产物应出现在宿主目录");
        assertEquals(1, result.result().artifacts().size());
        assertEquals("target/out.jar", result.result().artifacts().get(0).ref());
        assertEquals(64, result.result().artifacts().get(0).digest().length());
    }

    @Test
    @DisplayName("网络被切断（--network none）")
    void networkIsBlocked(@TempDir Path dir) {
        String probe = "require('dns').lookup('example.com',e=>{"
                + "console.log(e?'NO-NETWORK':'HAS-NETWORK');process.exit(e?0:1)})";

        ActionResult result = new DockerActionRunner(sandbox)
                .run(dir, spec(List.of("node", "-e", probe), List.of()));

        assertEquals(StageStatus.SUCCEEDED, result.result().status(),
                "容器内不应能解析外网域名，日志：" + result.logTail());
        assertTrue(result.logTail().contains("NO-NETWORK"));
        assertFalse(result.logTail().contains("HAS-NETWORK"));
    }

    @Test
    @DisplayName("容器内命令失败 → E-101，日志可见")
    void failureInsideContainer(@TempDir Path dir) {
        ActionResult result = new DockerActionRunner(sandbox)
                .run(dir, spec(List.of("node", "-e", "console.error('boom');process.exit(3)"), List.of()));

        assertEquals(StageStatus.FAILED, result.result().status());
        assertEquals("E-101", result.result().errorCode());
        assertEquals(3, result.result().exitCode());
        assertTrue(result.logTail().contains("boom"));
    }
}
