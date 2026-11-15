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
}
