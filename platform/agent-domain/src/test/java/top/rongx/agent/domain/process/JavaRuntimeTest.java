package top.rongx.agent.domain.process;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 部署器必须用「当前 JVM 自己是谁」解析 java，而不是 PATH 上的另一个 JDK。 */
class JavaRuntimeTest {

    @Test
    @DisplayName("解析结果与当前 JVM / java.home 一致，仓库不绑定安装目录")
    void resolvesCurrentJvm() {
        String binary = JavaRuntime.javaBinary();
        assertFalse(binary.isBlank());
        String expected = ProcessHandle.current().info().command()
                .filter(command -> !command.isBlank())
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin",
                        CommandShell.isWindows() ? "java.exe" : "java").toString());
        assertEquals(expected, binary);
    }

    @Test
    @DisplayName("解析出的可执行文件确实能启动 JVM")
    void resolvedBinaryCanLaunch() {
        BoundedCommand.Outcome outcome = BoundedCommand.run(
                List.of(JavaRuntime.javaBinary(), "-version"), 15_000L);
        assertTrue(outcome.ok(), outcome.detail());
        assertTrue(outcome.output().contains("version"));
    }
}
