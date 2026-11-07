package top.rongx.agent.executor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
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

/** 执行器行为：成功 / 用户失败 / 命令不存在 / 产物识别 / 超时。 */
class ProcessActionRunnerTest {

    private final ProcessActionRunner runner = new ProcessActionRunner();

    private static ActionSpec spec(ActionId action, List<String> argv, List<String> produce, Duration timeout) {
        return new ActionSpec(action, List.of(argv), produce, timeout, ActionSource.builtin("test"));
    }

    @Test
    @DisplayName("成功执行并捕获输出")
    void success(@TempDir Path dir) {
        ActionResult result = runner.run(dir, spec(ActionId.BACKEND_BUILD,
                List.of("java", "-version"), List.of(), Duration.ofMinutes(2)));

        assertEquals(StageStatus.SUCCEEDED, result.result().status());
        assertEquals(0, result.result().exitCode());
        assertFalse(result.logTail().isBlank(), "应捕获到输出（java -version 打在 stderr）");
        assertTrue(result.result().durationMs() >= 0);
    }

    @Test
    @DisplayName("用户代码失败 → E-101，且不重试")
    void userFailure(@TempDir Path dir) {
        ActionResult result = runner.run(dir, spec(ActionId.BACKEND_BUILD,
                List.of("java", "-XX:+ThisFlagDoesNotExist"), List.of(), Duration.ofMinutes(2)));

        assertEquals(StageStatus.FAILED, result.result().status());
        assertEquals("E-101", result.result().errorCode());
        assertTrue(result.result().isUserFailure(), "E-1xx 应被识别为用户失败");
        assertFalse(result.result().isPlatformFailure());
    }

    @Test
    @DisplayName("命令不存在 → E-209（平台级，非用户代码问题）")
    void missingExecutable(@TempDir Path dir) {
        ActionResult result = runner.run(dir, spec(ActionId.BACKEND_BUILD,
                List.of("definitely-not-a-command-xyz"), List.of(), Duration.ofMinutes(1)));

        assertEquals(StageStatus.FAILED, result.result().status());
        assertEquals("E-209", result.result().errorCode());
        assertFalse(result.result().isUserFailure());
    }

    @Test
    @DisplayName("产物按 glob 识别并计算 sha256")
    void artifacts(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("app.jar"), "fake-jar-content");
        Files.writeString(target.resolve("app.jar.sha1"), "ignore-me");

        ActionResult result = runner.run(dir, spec(ActionId.BACKEND_BUILD,
                List.of("java", "-version"), List.of("target/*.jar"), Duration.ofMinutes(2)));

        assertEquals(StageStatus.SUCCEEDED, result.result().status());
        assertEquals(1, result.result().artifacts().size(), "只应命中 *.jar");
        assertEquals("target/app.jar", result.result().artifacts().get(0).ref());
        assertEquals(64, result.result().artifacts().get(0).digest().length());
    }

    @Test
    @DisplayName("超时 → E-302，进程被终止")
    void timeout() {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"),
                "本用例用 ping 制造阻塞，仅在 Windows 执行");

        Path dir = Path.of(System.getProperty("java.io.tmpdir"));
        long started = System.currentTimeMillis();
        ActionResult result = runner.run(dir, spec(ActionId.BACKEND_BUILD,
                List.of("ping", "-n", "10", "127.0.0.1"), List.of(), Duration.ofMillis(800)));
        long cost = System.currentTimeMillis() - started;

        assertEquals(StageStatus.FAILED, result.result().status());
        assertEquals("E-302", result.result().errorCode());
        assertTrue(cost < 8_000, "应在超时后迅速终止，实际耗时 " + cost + "ms");
    }
}
