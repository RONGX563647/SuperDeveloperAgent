package top.rongx.agent.executor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.run.StageStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 准备阶段失败必须变成可见的结构化失败，而不是让运行静默停在 RUNNING。 */
class DockerActionRunnerPrepareTest {

    private static ActionSpec spec() {
        return new ActionSpec(ActionId.BACKEND_BUILD,
                List.of(List.of("mvn", "-B", "-q", "-DskipTests", "package")), List.of(),
                Duration.ofMinutes(10), ActionSource.builtin("test"));
    }

    private static DockerSandbox sandbox(DockerSandbox.CommandExecutor executor) {
        return new DockerSandbox("docker", "maven:3.9-eclipse-temurin-21", "bridge",
                "2g", "2", 512, "1000:1000", "agent-cache", executor);
    }

    @Test
    @DisplayName("缓存卷初始化超时 → 秒级失败、错误码与原因进日志")
    void prepareTimeoutIsVisible(@TempDir Path dir) {
        DockerSandbox sandbox = sandbox((cmd, timeout, cancelled, live) ->
                cmd.contains("inspect")
                        ? ProcessExecutor.StepOutcome.ok()
                        : ProcessExecutor.StepOutcome.failed(124, "E-302", "执行超时（30s），已终止"));
        StringBuilder live = new StringBuilder();
        long start = System.nanoTime();

        ActionResult result = new DockerActionRunner(sandbox)
                .run(dir, spec(), () -> false, live::append);
        long costMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(StageStatus.FAILED, result.result().status());
        assertEquals("E-302", result.result().errorCode());
        assertTrue(result.logTail().contains("缓存卷初始化失败"), result.logTail());
        assertTrue(live.toString().contains("[sandbox] ❌"), live.toString());
        assertTrue(costMs < 5_000, "准备失败不能拖着运行不放，实际 " + costMs + "ms");
    }

    @Test
    @DisplayName("镜像缺失 → E-209 并提示 pull / 换镜像")
    void missingImageIsActionable(@TempDir Path dir) {
        DockerSandbox sandbox = sandbox((cmd, timeout, cancelled, live) ->
                ProcessExecutor.StepOutcome.failed(1, "E-101", "命令失败（exit 1）"));

        ActionResult result = new DockerActionRunner(sandbox).run(dir, spec(), () -> false, null);

        assertEquals(StageStatus.FAILED, result.result().status());
        assertEquals("E-209", result.result().errorCode());
        assertTrue(result.logTail().contains("构建镜像未就绪"), result.logTail());
        assertTrue(result.logTail().contains("docker pull"), result.logTail());
    }

    @Test
    @DisplayName("取消信号能中断准备阶段 → E-303")
    void cancellationInterruptsPrepare(@TempDir Path dir) {
        DockerSandbox sandbox = sandbox((cmd, timeout, cancelled, live) ->
                cmd.contains("inspect")
                        ? ProcessExecutor.StepOutcome.ok()
                        : ProcessExecutor.StepOutcome.failed(143, "E-303", "执行被取消"));

        ActionResult result = new DockerActionRunner(sandbox).run(dir, spec(), () -> true, null);

        assertEquals(StageStatus.FAILED, result.result().status());
        assertEquals("E-303", result.result().errorCode());
    }

    @Test
    @DisplayName("准备阶段吃光预算 → 明确的「准备阶段耗尽预算」，而不是命令 0s 超时")
    void prepareOverBudgetFailsWithReason(@TempDir Path dir) {
        DockerSandbox sandbox = sandbox((cmd, timeout, cancelled, live) -> {
            if (cmd.contains("image") && cmd.contains("inspect")) {
                return ProcessExecutor.StepOutcome.ok();
            }
            if (cmd.contains("test")) {
                return ProcessExecutor.StepOutcome.failed(1, null, "not found"); // 空卷
            }
            // 模拟 chown -R 在大缓存卷上跑得比动作预算还久
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ProcessExecutor.StepOutcome.ok();
        });
        ActionSpec tiny = new ActionSpec(ActionId.BACKEND_BUILD,
                List.of(List.of("mvn", "-B", "-q", "-DskipTests", "package")), List.of(),
                Duration.ofMillis(100), ActionSource.builtin("test"));
        StringBuilder live = new StringBuilder();

        ActionResult result = new DockerActionRunner(sandbox).run(dir, tiny, () -> false, live::append);

        assertEquals(StageStatus.FAILED, result.result().status());
        assertEquals("E-302", result.result().errorCode());
        assertTrue(result.result().errorSummary().contains("准备阶段"),
                "失败原因必须指向准备阶段，否则用户与自愈都只能看到一条 0s 超时："
                        + result.result().errorSummary());
        assertTrue(live.toString().contains("[sandbox] ❌"), live.toString());
    }
}
