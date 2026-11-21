package top.rongx.agent.domain.process;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 有界外部命令（只读探测共用原语）。
 *
 * <p>平台里散落的 {@code git / docker} 只读探测曾经写成
 * {@code process.getInputStream().readAllBytes()} 再 {@code waitFor(timeout)}：
 * 读流会一直阻塞到子进程退出，超时参数形同虚设；子进程不退出（镜像拉取、
 * 远端 hang、CLI 等锁）时整条请求永久卡住，且没有任何错误输出。
 * 这正是「缺镜像静默卡 27 分钟」的同根问题。
 *
 * <p>本原语保证三件事：
 * <ol>
 *   <li><b>输出边读边收</b>：虚拟线程并行排空 stdout+stderr，管道写满不会死锁；</li>
 *   <li><b>超时是硬约束</b>：{@code waitFor(timeout)} 后强制终止进程树（含后代）并关流；</li>
 *   <li><b>失败可判定</b>：启动失败 / 超时 / 非零退出分别给出可读原因，绝不静默吞掉。</li>
 * </ol>
 *
 * <p>只用 {@code ProcessBuilder(List)}，不拼命令字符串（规约 §10.4）；纯 JDK
 * 实现，不依赖任何框架，允许 domain / infra / executor / gateway 共用。
 * 需要「取消 / 实时日志 / 分段步骤」的完整动作执行仍走执行面的
 * {@code ProcessExecutor}；本原语负责所有不需要逐行事件的有界命令
 * （只读探测、确定性构建/测试检查等）。若 argv 首元素本身是命令解释器，
 * 由调用方通过 {@link CommandShell} 按 OS 自适应决定，不在此处写死路径。
 */
public final class BoundedCommand {

    private BoundedCommand() {
    }

    /** 执行结果。 */
    public record Outcome(int exitCode, String output, boolean timedOut, String failure) {

        /** 是否成功（退出码 0 且没有启动/超时/中断故障）。 */
        public boolean ok() {
            return !timedOut && failure == null && exitCode == 0;
        }

        /** 面向人的失败原因；成功时返回空串。 */
        public String detail() {
            if (failure != null) {
                return failure;
            }
            return exitCode == 0 ? "" : "命令失败（exit " + exitCode + "）";
        }
    }

    /** 在平台工作目录下执行。 */
    public static Outcome run(List<String> argv, long timeoutMs) {
        return run(argv, null, timeoutMs);
    }

    /**
     * @param argv      完整命令（不经 shell）
     * @param workdir   工作目录；{@code null} 表示继承当前进程目录
     * @param timeoutMs 硬超时；到期即强杀进程树并返回 {@code timedOut=true}
     */
    public static Outcome run(List<String> argv, Path workdir, long timeoutMs) {
        return run(argv, workdir, timeoutMs, Map.of());
    }

    /**
     * 带额外环境变量的有界执行：git HTTPS 凭据等只经环境传递，不进 argv。
     *
     * @param env 追加/覆盖的环境变量；{@code null} 视为空
     */
    public static Outcome run(List<String> argv, Path workdir, long timeoutMs,
                              Map<String, String> env) {
        long budget = Math.max(1L, timeoutMs);
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        if (workdir != null) {
            pb.directory(workdir.toFile());
        }
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new Outcome(127, "", false,
                    "无法启动命令 " + argv.getFirst() + "：" + e.getMessage());
        } catch (RuntimeException e) {
            return new Outcome(127, "", false,
                    "无法启动命令 " + argv.getFirst() + "：" + e);
        }

        StringBuilder sink = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(
                () -> ProcessIo.pump(process.getInputStream(), sink, null));

        boolean finished;
        try {
            finished = process.waitFor(budget, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProcessIo.killTree(process);
            ProcessIo.joinQuietly(reader);
            return new Outcome(143, sink.toString(), false, "命令被中断：" + String.join(" ", argv));
        }

        if (!finished) {
            ProcessIo.killTree(process);
            ProcessIo.joinQuietly(reader);
            return new Outcome(124, sink.toString(), true,
                    "命令超时（" + (budget / 1000) + "s），已终止：" + String.join(" ", argv));
        }

        ProcessIo.joinQuietly(reader);
        int exit = process.exitValue();
        return new Outcome(exit, sink.toString(), false,
                exit == 0 ? null : "命令失败（exit " + exit + "）：" + String.join(" ", argv));
    }

}
