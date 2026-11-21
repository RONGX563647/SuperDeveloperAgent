package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import top.rongx.agent.domain.docker.ContainerState;
import top.rongx.agent.domain.retry.Retry;
import java.util.function.BooleanSupplier;
import top.rongx.agent.domain.process.BoundedCommand;

/**
 * Docker 运维操作的<b>唯一入口</b>。
 *
 * <p>为什么要收口：同一件事曾各自实现多份，且已经出现真实漂移——
 * <ul>
 *   <li>「镜像是否存在」7 份（{@code BuiltInPipelineEngine} / {@code DockerDeployer}(×2) /
 *       {@code DockerEnvProvisioner} / {@code DockerSandbox}(×2) / {@code ImageProvisioner}(×2) /
 *       {@code ServerDeployer}），超时从 5s 到 15s 不等；</li>
 *   <li>「容器是否在跑」5 份，判据从 {@code exitCode==0} 到 {@code "true".equalsIgnoreCase} 不等；</li>
 *   <li>「网络 ensure」2 份（{@code DockerNetwork} 与 {@code DockerEnvProvisioner.ensureNetwork}，
 *       后者是前者的复制品）。</li>
 * </ul>
 * 收口之后，超时、错误码、脱敏、输出裁剪只有一份定义；调用方只表达"我要什么事实"。
 *
 * <p>刻意<b>不做</b>的事：不接管 {@link DockerSandbox#wrap}（那是安全边界，隔离参数必须在
 * 沙箱类里可见、可被不变量测试断言），也不接管部署器的容器编排（那是业务语义）。
 * 本类只回答"docker 现在是什么状态"和"执行一条 docker 命令"。
 */
public final class DockerOps {

    /** 探测类命令（inspect / ps / port）的统一预算：本机守护进程正常时是毫秒级。 */
    private static final long PROBE_TIMEOUT_MS = 5_000L;
    /** 变更类命令（network create / rm -f）的统一预算。 */
    private static final long MUTATE_TIMEOUT_MS = 30_000L;

    /** 可注入的命令执行器：便于单测与需要自定义工作目录/环境的调用方。 */
    @FunctionalInterface
    public interface CommandRunner {
        BoundedCommand.Outcome run(List<String> argv, Path workdir, long timeoutMs);
    }

    /** 一条 {@code docker ps} 记录（只取排障真正需要的字段）。 */
    public record PsRow(String name, String state, String status) {

        /**
         * 是否在运行。
         *
         * <p>判据按优先级：{@code State} 列（新版本 docker 有，值为 running/exited/created…）；
         * 没有该列时退回 {@code Status} 文本（{@code Up 3 minutes} / {@code Exited (0) …}）。
         * 原实现散在 5 处、判据各不相同（有的只看退出码、有的只看 "true"），这里只留一份。
         */
        public boolean running() {
            return ContainerState.running(state, status);
        }
    }

    private final String dockerBinary;
    private final Path workdir;
    private final CommandRunner runner;

    public DockerOps(String dockerBinary) {
        this(dockerBinary, Path.of("."));
    }

    public DockerOps(String dockerBinary, Path workdir) {
        this(dockerBinary, workdir, BoundedCommand::run);
    }

    public DockerOps(String dockerBinary, Path workdir, CommandRunner runner) {
        this.dockerBinary = dockerBinary == null || dockerBinary.isBlank() ? "docker" : dockerBinary.trim();
        this.workdir = workdir == null ? Path.of(".") : workdir;
        this.runner = runner == null ? BoundedCommand::run : runner;
    }

    public String binary() {
        return dockerBinary;
    }

    /** 拼 argv（唯一允许拼 docker 子命令的地方）。 */
    public List<String> cmd(String... args) {
        List<String> argv = new ArrayList<>(args.length + 1);
        argv.add(dockerBinary);
        for (String arg : args) {
            argv.add(arg);
        }
        return List.copyOf(argv);
    }

    public BoundedCommand.Outcome run(List<String> argv) {
        return run(argv, PROBE_TIMEOUT_MS);
    }

    public BoundedCommand.Outcome run(List<String> argv, long timeoutMs) {
        return runner.run(argv, workdir, timeoutMs);
    }

    /** 守护进程是否可用（3s：启动路径上不允许被 CLI 拖住）。 */
    public boolean available() {
        return run(cmd("info", "--format", "{{.ServerVersion}}"), 3_000L).ok();
    }

    /** 守护进程版本（{@code docker version --format {{.Server.Version}}}）；不可用返回空。 */
    public Optional<String> daemonVersion() {
        BoundedCommand.Outcome out = run(cmd("version", "--format", "{{.Server.Version}}"),
                PROBE_TIMEOUT_MS);
        String text = out.ok() ? out.output().trim() : "";
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /** 镜像详情（JSON）；不存在或守护进程不可用返回空。 */
    public Optional<String> imageInspect(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        BoundedCommand.Outcome out = run(cmd("image", "inspect", "--format", "{{json .}}", ref));
        String text = out.ok() ? out.output().trim() : "";
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /** 镜像 ID（{@code sha256:...}）；不存在返回空。 */
    public Optional<String> imageId(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        BoundedCommand.Outcome out = run(cmd("image", "inspect", "--format", "{{.Id}}", ref));
        String text = out.ok() ? out.output().trim() : "";
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    public boolean imageExists(String ref) {
        return imageId(ref).isPresent();
    }

    /** 容器运行态：{@code empty} = 容器不存在或守护进程不可用（调用方要区分时用 {@link #ps}）。 */
    public Optional<Boolean> containerRunning(String container) {
        if (container == null || container.isBlank()) {
            return Optional.empty();
        }
        BoundedCommand.Outcome out = run(cmd("inspect", "--format", "{{.State.Running}}", container));
        if (!out.ok()) {
            return Optional.empty();
        }
        String text = out.output().trim();
        // docker 真跑起来时输出 true/false；空的或无法解析都当作"未知"
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("true".equalsIgnoreCase(text));
    }

    /** 按名称前缀/标签列容器。 */
    public List<PsRow> ps(List<String> filters) {
        List<String> argv = new ArrayList<>(cmd("ps", "-a", "--format",
                "{{.Names}}|{{.State}}|{{.Status}}"));
        if (filters != null) {
            for (String filter : filters) {
                argv.add("--filter");
                argv.add(filter);
            }
        }
        BoundedCommand.Outcome out = run(argv, PROBE_TIMEOUT_MS);
        return out.ok() ? parsePs(out.output()) : List.of();
    }

    /** 纯函数，便于单测：解析 {@code {{.Names}}|{{.State}}|{{.Status}}} 输出。 */
    static List<PsRow> parsePs(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<PsRow> rows = new ArrayList<>();
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|", 3);
            String name = parts[0].trim();
            if (name.isEmpty()) {
                continue;
            }
            rows.add(new PsRow(name,
                    parts.length > 1 ? parts[1].trim() : "",
                    parts.length > 2 ? parts[2].trim() : ""));
        }
        return List.copyOf(rows);
    }

    /**
     * 网络就绪：不存在则创建（幂等）。
     *
     * <p>内置网络（bridge/host/none）直接视为就绪：{@code docker network create bridge}
     * 会失败，而且它们永远存在。
     *
     * @return 失败原因；{@code null} = 已就绪
     */
    public String ensureNetwork(String network) {
        if (network == null || network.isBlank()
                || List.of("bridge", "host", "none").contains(network.toLowerCase(java.util.Locale.ROOT))) {
            return null;
        }
        if (run(cmd("network", "inspect", network)).ok()) {
            return null;
        }
        BoundedCommand.Outcome created = run(cmd("network", "create", network), MUTATE_TIMEOUT_MS);
        return created.ok() ? null : "网络创建失败 " + network + "：" + created.detail();
    }

    /**
     * 查询容器映射到宿主的端口（{@code -p ip::containerPort} 之后必有答案）。
     *
     * <p>这是<b>替代端口扫描</b>的关键：原实现用 {@code ServerSocket} 试绑再释放，
     * 在"释放"到"docker 真正绑定"之间存在 TOCTOU 窗口。让 docker 自己分配、
     * 再问它要结果，窗口消失，也不需要 200 次循环探测。
     *
     * @return 宿主端口；未映射或查询失败返回 {@code -1}
     */
    public int publishedPort(String container, int containerPort) {
        if (container == null || container.isBlank() || containerPort <= 0) {
            return -1;
        }
        BoundedCommand.Outcome out = run(cmd("port", container, containerPort + "/tcp"));
        if (!out.ok()) {
            return -1;
        }
        return parsePublishedPort(out.output());
    }

    /** 纯函数：解析 {@code 127.0.0.1:61487} / {@code 0.0.0.0:8080} / {@code [::]:8080}。 */
    static int parsePublishedPort(String output) {
        if (output == null || output.isBlank()) {
            return -1;
        }
        // 多行输出（同时绑 v4/v6）时取第一条能解析的
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            int colon = line.lastIndexOf(':');
            if (colon < 0 || colon == line.length() - 1) {
                continue;
            }
            try {
                int port = Integer.parseInt(line.substring(colon + 1).trim());
                if (port > 0 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException ignored) {
                // 继续看下一行
            }
        }
        return -1;
    }

    /** 删除容器（不存在不视为失败：部署是"先删后起"的幂等语义）。 */
    public boolean removeContainer(String container) {
        return run(cmd("rm", "-f", container), MUTATE_TIMEOUT_MS).ok();
    }

    /** 等待容器进入 running（{@code --wait} 不可用时的兜底轮询）。 */
    public boolean awaitRunning(String container, long timeoutMs, long pollMs,
                                BooleanSupplier cancelled) {
        Retry.Result result = Retry.until(timeoutMs, pollMs, cancelled,
                () -> containerRunning(container).orElse(false));
        // 超时边界上再补一次，避免「刚好在最后一次睡眠里就绪」被误判为失败
        return result == Retry.Result.SUCCESS
                || (result == Retry.Result.TIMEOUT && containerRunning(container).orElse(false));
    }
}
