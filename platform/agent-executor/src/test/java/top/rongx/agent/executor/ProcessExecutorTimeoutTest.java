package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 超时与取消必须真的终止外部进程（含子进程树），而不是等它自己退出。 */
class ProcessExecutorTimeoutTest {

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Test
    @DisplayName("挂住的外部进程在预算内被杀，返回 E-302")
    void hungProcessIsKilled() {
        List<String> argv = windows()
                ? List.of("ping", "-n", "20", "127.0.0.1")
                : List.of("sleep", "20");
        StringBuilder sink = new StringBuilder();
        long start = System.nanoTime();

        ProcessExecutor.StepOutcome outcome = new ProcessExecutor()
                .exec(Path.of("."), argv, 600, sink, () -> false);
        long costMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals("E-302", outcome.errorCode(), "超时必须收敛，不能无限等待");
        assertEquals(124, outcome.exitCode());
        assertTrue(costMs < 8_000, "超时后等待过久（" + costMs + "ms），可能没杀干净子进程");
    }

    @Test
    @DisplayName("取消标志在启动后立即生效 → E-303")
    void cancellationStopsImmediately() {
        ProcessExecutor.StepOutcome outcome = new ProcessExecutor().exec(Path.of("."),
                List.of("java", "-version"), 10_000, new StringBuilder(), () -> true);
        assertEquals("E-303", outcome.errorCode());
        assertEquals(143, outcome.exitCode());
    }

    @Test
    @DisplayName("进度感知：静默超过阈值就判卡死，摘要写明「停滞」而不是笼统「超时」")
    void silentProcessIsReportedAsStalled() {
        List<String> argv = windows()
                ? List.of("ping", "-n", "20", "127.0.0.1")
                : List.of("ping", "-c", "20", "-i", "1", "127.0.0.1");
        // ping 每到 1s 才回一行，200ms 的静默阈值必然先触发
        ProcessExecutor.StepOutcome outcome = new ProcessExecutor().execUntilIdle(
                Path.of("."), argv, 200, 30_000, new StringBuilder(), () -> false, null);

        assertEquals(124, outcome.exitCode());
        assertEquals("E-302", outcome.errorCode());
        assertTrue(outcome.summary().contains("执行停滞"), outcome.summary());
    }

    @Test
    @DisplayName("进度感知：一直有输出就跑完，不被总时长以外的东西砍断")
    void outputKeepsRunnerAlive() {
        List<String> argv = windows()
                ? List.of("ping", "-n", "3", "127.0.0.1")
                : List.of("ping", "-c", "3", "-i", "1", "127.0.0.1");
        // 总上限 20s 但静默阈值 5s：每 1s 一行的输出足以让它自然跑完（约 2s）
        ProcessExecutor.StepOutcome outcome = new ProcessExecutor().execUntilIdle(
                Path.of("."), argv, 5_000, 20_000, new StringBuilder(), () -> false, null);

        assertEquals(null, outcome.errorCode(), "有持续进度时不该被判卡死");
    }
}
