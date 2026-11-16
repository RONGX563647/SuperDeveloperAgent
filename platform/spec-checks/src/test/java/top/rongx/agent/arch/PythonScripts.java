package top.rongx.agent.arch;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 调用仓库根下 Python 脚本的公共入口（会话归档、Handbook）。
 *
 * <p>校验/生成逻辑只有一份（tools/ 下的脚本），Java 侧只负责调用，
 * 避免规则在 Java / Python 两份实现里漂移。
 */
final class PythonScripts {

    private static final List<List<String>> PYTHON_CANDIDATES = List.of(
            List.of("python"), List.of("python3"), List.of("py", "-3"));

    private PythonScripts() {
    }

    /** 仓库根：spec.root 指向 platform/，向上找到含 tools/ 的那一层。 */
    static Path repoRoot() {
        Path start = Path.of(System.getProperty("spec.root", ".")).toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("tools"))) {
                return dir;
            }
        }
        return start;
    }

    /** 运行脚本子命令，退出码非 0 时带上脚本输出失败。 */
    static void run(String script, String subcommand, String label) {
        Path root = repoRoot();
        List<String> command = new ArrayList<>(python());
        command.add(root.resolve(script).toString());
        command.add(subcommand);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true);
        String output;
        int exit;
        try {
            Process process = builder.start();
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            exit = process.waitFor();
        } catch (IOException e) {
            throw new UncheckedIOException("无法执行" + label + "脚本：" + script, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(label + "校验被中断", e);
        }
        assertEquals(0, exit,
                () -> label + "校验失败（" + script + " " + subcommand + "，exit=" + exit + "）：\n" + output);
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
        Assumptions.abort("未找到 python，无法执行" + String.join("/", PYTHON_CANDIDATES.get(0))
                + " 校验；请安装 Python 3 或执行 tools/session-archive/install-hooks.ps1");
        throw new IllegalStateException("unreachable");
    }
}
