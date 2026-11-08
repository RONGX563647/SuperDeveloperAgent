package top.rongx.agent.executor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 宿主进程执行模式。
 *
 * <p>只做预算、日志、产物，**不做隔离**。用于本地开发或没有 Docker 的环境；
 * 有 Docker 时由 {@code agent.sandbox.mode=auto} 自动切到 {@link DockerActionRunner}。
 *
 * <p>Windows 适配：mvn/pnpm/make/task 等工具链在 Windows 上是 .cmd/.bat 包装器，
 * {@code ProcessBuilder} 不会按 PATHEXT 解析，直接传 "mvn" 会 E-209。
 * 这里按 PATHEXT 顺序探测同名可执行文件，命中即替换为完整路径。
 */
public class ProcessActionRunner extends AbstractActionRunner {

    /** Windows 可执行扩展名的探测顺序（PATHEXT 的常用子集）。 */
    private static final List<String> WINDOWS_EXTS = List.of(".cmd", ".bat", ".exe", "");

    @Override
    protected List<String> effectiveArgv(Path workspace, List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return argv;
        }
        String head = argv.get(0);
        if (head == null || head.isBlank() || head.contains("/") || head.contains("\\")
                || head.contains(":")) {
            return argv; // 已是路径/绝对命令，原样
        }
        Path resolved = resolveExecutable(head);
        if (resolved == null) {
            return argv;
        }
        List<String> out = new java.util.ArrayList<>(argv);
        out.set(0, resolved.toString());
        return List.copyOf(out);
    }

    /** 在 PATH 各目录里按 PATHEXT 探测 head 的真实可执行文件；找不到返回 null。 */
    private Path resolveExecutable(String head) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        for (String dir : pathEnv.split(";", -1)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : WINDOWS_EXTS) {
                Path candidate = Path.of(dir.trim(), head + ext);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
