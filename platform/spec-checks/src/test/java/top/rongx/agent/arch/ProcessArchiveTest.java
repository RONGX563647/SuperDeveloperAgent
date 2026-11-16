package top.rongx.agent.arch;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 会话归档规约（docs/session-archive-spec-v1.md）。
 *
 * <p>校验逻辑只有一份：{@code tools/session-archive/gate.py}。这里只调用它，
 * 避免 Java / Python 双份规则漂移；hook 与 CI 走的是同一个入口。
 */
class ProcessArchiveTest {

    /** 仓库根：spec.root 指向 platform/，向上找到含 gate.py 的那一层。 */
    private static final Path ROOT = repoRoot();

    private static final List<List<String>> PYTHON_CANDIDATES = List.of(
            List.of("python"), List.of("python3"), List.of("py", "-3"));

    @Test
    @DisplayName("会话归档完整且哈希链未断")
    void archiveIsValid() {
        runGate("validate");
    }

    @Test
    @DisplayName("归档校验器自检通过")
    void gateSelfTestPasses() {
        runGate("self-test");
    }

    private static Path repoRoot() {
        Path start = Path.of(System.getProperty("spec.root", ".")).toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("tools/session-archive/gate.py"))) {
                return dir;
            }
        }
        return start;
    }

    private static void runGate(String subcommand) {
        List<String> command = new ArrayList<>(python());
        command.add(ROOT.resolve("tools/session-archive/gate.py").toString());
        command.add(subcommand);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(ROOT.toFile())
                .redirectErrorStream(true);
        String output;
        int exit;
        try {
            Process process = builder.start();
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            exit = process.waitFor();
        } catch (IOException e) {
            throw new UncheckedIOException("无法执行会话归档校验器", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("会话归档校验被中断", e);
        }
        assertEquals(0, exit,
                () -> "gate.py " + subcommand + " 失败（exit=" + exit + "）：\n" + output);
    }

    private static List<String> python() {
        for (List<String> candidate : PYTHON_CANDIDATES) {
            List<String> probe = new ArrayList<>(candidate);
            probe.add("--version");
            try {
                Process process = new ProcessBuilder(probe).redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                if (process.waitFor() == 0) {
                    return candidate;
                }
            } catch (IOException ignored) {
                // 该解释器不存在，继续尝试下一个
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assumptions.abort("未找到 python，无法校验会话归档；"
                + "请安装 Python 3 或执行 tools/session-archive/install-hooks.ps1");
        throw new IllegalStateException("unreachable");
    }
}
