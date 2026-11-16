package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 确定性修复：Docker 守护进程未运行（环境类故障）→ 尝试启动 → 平台复核。
 *
 * <p>这是最容易被误诊的故障：守护进程没起时 `docker image inspect` 也失败，
 * 流水线会报「构建镜像未就绪」，看起来像缺镜像，实际是 Docker Desktop 没启动。
 *
 * <p>安全边界：
 * <ul>
 *   <li>只做"启动本机 Docker"，不删容器、不改镜像；</li>
 *   <li>先只读探测：守护进程已正常时直接交回后续诊断，不乱启动；</li>
 *   <li>成功与否由平台自己执行 {@code docker version} 复核，不采信启动命令的退出码。</li>
 * </ul>
 */
final class DockerDaemonRepair implements FailureRepair {

    /** 守护进程不可用的典型输出（docker CLI 与 Docker Desktop 都覆盖）。 */
    private static final Pattern DAEMON_DOWN = Pattern.compile(
            "Cannot connect to the Docker daemon|error during connect"
                    + "|Is the docker daemon running|docker daemon is not running",
            Pattern.CASE_INSENSITIVE);

    private static final long START_TIMEOUT_MS = 120_000L;
    private static final long PROBE_TIMEOUT_MS = 5_000L;
    private static final long WAIT_MS = 100_000L;
    private static final long SLEEP_MS = 3_000L;

    @Override
    public boolean matches(Diagnosis d) {
        return d.logTail() != null && DAEMON_DOWN.matcher(d.logTail()).find();
    }

    @Override
    public Outcome repair(Diagnosis d, Context ctx) {
        List<String> lines = new ArrayList<>();
        if (ctx.inspectCommand() == null || ctx.inspectCommand().isEmpty()
                || ctx.command() == null) {
            return Outcome.notFixed("当前不是容器沙箱模式，跳过 Docker 启动", lines);
        }
        if (ctx.cancelled().getAsBoolean()) {
            return Outcome.notFixed("运行已取消，跳过自动修复", lines);
        }
        String docker = ctx.inspectCommand().get(0);

        // 先只读复核：守护进程其实正常时不要乱启动（日志里的旧文本不代表当前状态）
        if (probe(docker, ctx).errorCode() == null) {
            lines.add("[autofix] Docker 守护进程已可用，本次失败另有原因");
            return Outcome.notFixed("Docker 已运行，当前失败另有原因（交给后续诊断）", lines);
        }

        List<String> start = startCommand(System.getProperty("os.name"), docker);
        if (start.isEmpty()) {
            lines.add("[autofix] ⚠️ 当前系统不支持自动启动 Docker，请手动启动后重跑");
            return Outcome.notFixed("Docker 守护进程未运行，且当前系统不支持自动启动", lines);
        }

        ctx.log().accept("[autofix] 检测到 Docker 守护进程未运行 · 自动执行 "
                + String.join(" ", start) + "（最长 " + START_TIMEOUT_MS / 1000 + "s，可取消）\n");
        ProcessExecutor.StepOutcome launched = ctx.command().run(start, START_TIMEOUT_MS, null);
        if (launched.errorCode() != null) {
            // 启动命令本身失败（旧版 Docker Desktop 没有 desktop 子命令、权限不足等）：
            // 继续等 100s 只会拖长失败，直接给出可执行的下一步。
            lines.add("[autofix] ❌ 启动命令失败（" + launched.errorCode() + "）："
                    + truncate(String.valueOf(launched.summary()), 240));
            return Outcome.notFixed("自动启动 Docker 失败，请手动启动 Docker 后重跑", lines);
        }

        long deadline = System.currentTimeMillis() + WAIT_MS;
        ProcessExecutor.StepOutcome last = launched;
        while (System.currentTimeMillis() < deadline) {
            if (ctx.cancelled().getAsBoolean()) {
                return Outcome.notFixed("运行已取消，停止等待 Docker 启动", lines);
            }
            last = probe(docker, ctx);
            if (last.errorCode() == null) {
                lines.add("[autofix] ✅ Docker 守护进程已启动并通过 docker version 复核");
                return Outcome.fixed("Docker 守护进程已启动", lines);
            }
            try {
                Thread.sleep(SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        lines.add("[autofix] ❌ Docker 仍未就绪：" + truncate(String.valueOf(last.summary()), 240));
        return Outcome.notFixed("Docker 守护进程启动后仍不可用", lines);
    }

    /** 平台复核：与 dry-run 的可用性探测同一条命令。 */
    private static ProcessExecutor.StepOutcome probe(String docker, Context ctx) {
        return ctx.command().run(List.of(docker, "version", "--format", "{{.Server.Version}}"),
                PROBE_TIMEOUT_MS, null);
    }

    /** 各系统的"启动 Docker"命令；不支持的平台返回空（不猜）。 */
    static List<String> startCommand(String osName, String docker) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of(docker, "desktop", "start");
        }
        if (os.contains("mac")) {
            return List.of("open", "-a", "Docker");
        }
        return List.of();
    }

    private static String truncate(String value, int max) {
        String one = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }
}
