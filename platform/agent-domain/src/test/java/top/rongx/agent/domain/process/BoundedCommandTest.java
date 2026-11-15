package top.rongx.agent.domain.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 有界外部命令：三类失败（启动 / 超时 / 非零退出）都必须可判定，且超时是硬约束。
 *
 * <p>用当前 JVM 的 java 可执行文件跑 JDK 单文件源码程序，避免依赖特定 OS 的
 * sleep/timeout 命令，测试本身也跨平台。
 */
class BoundedCommandTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("成功：退出码 0 且 stdout/stderr 都被收集")
    void successReturnsZeroAndOutput() {
        BoundedCommand.Outcome outcome = BoundedCommand.run(
                List.of(JavaRuntime.javaBinary(), "-version"), 15_000L);
        assertTrue(outcome.ok(), outcome.detail());
        assertEquals(0, outcome.exitCode());
        assertTrue(outcome.output().contains("version") || !outcome.output().isBlank());
    }

    @Test
    @DisplayName("非零退出：给出可读原因，不静默吞掉")
    void nonZeroExitIsExplicitFailure() {
        BoundedCommand.Outcome outcome = BoundedCommand.run(
                List.of(JavaRuntime.javaBinary(), "--definitely-not-an-option"), 15_000L);
        assertFalse(outcome.ok());
        assertFalse(outcome.timedOut());
        assertNotEquals(0, outcome.exitCode());
        assertTrue(outcome.detail().contains("exit"), outcome.detail());
    }

    @Test
    @DisplayName("启动失败：立即返回原因，不等待超时")
    void missingBinaryFailsFast() {
        BoundedCommand.Outcome outcome = BoundedCommand.run(
                List.of("agent-no-such-binary-xyz"), 2_000L);
        assertFalse(outcome.ok());
        assertFalse(outcome.timedOut());
        assertTrue(outcome.failure().contains("无法启动命令"), outcome.failure());
    }

    @Test
    @DisplayName("环境变量重载：git 凭据类注入只经环境传递")
    void envOverrideReachesChild() throws IOException {
        Path source = temp.resolve("EnvProbe.java");
        Files.writeString(source, """
                public class EnvProbe {
                  public static void main(String[] args) {
                    System.out.println(System.getenv("AGENT_BOUNDED_TEST"));
                  }
                }
                """);
        BoundedCommand.Outcome outcome = BoundedCommand.run(
                List.of(JavaRuntime.javaBinary(), source.toString()), temp, 20_000L,
                Map.of("AGENT_BOUNDED_TEST", "injected"));
        assertTrue(outcome.ok(), outcome.detail());
        assertTrue(outcome.output().contains("injected"), outcome.output());
    }

    @Test
    @DisplayName("硬超时：挂住的子进程在预算内被杀掉，不会拖到 readAllBytes 的 EOF")
    void timeoutIsHardBound() throws IOException {
        Path source = temp.resolve("Hang.java");
        Files.writeString(source, """
                public class Hang {
                  public static void main(String[] args) throws Exception {
                    Thread.sleep(60_000L);
                  }
                }
                """);
        long started = System.nanoTime();
        BoundedCommand.Outcome outcome = BoundedCommand.run(
                List.of(JavaRuntime.javaBinary(), source.toString()), temp, 1_500L);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(outcome.timedOut(), outcome.detail());
        assertEquals(124, outcome.exitCode());
        assertTrue(outcome.failure().contains("超时"), outcome.failure());
        assertTrue(elapsedMs < 15_000L,
                "超时必须在预算附近收敛，实际耗时 " + elapsedMs + "ms");
    }
}
