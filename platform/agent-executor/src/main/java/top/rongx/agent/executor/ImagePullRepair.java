package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 确定性修复：工具链镜像缺失（E-209 / No such image）→ 自动 docker pull → 平台复核。
 *
 * <p>这是「检查构建」失败时最常见、最不需要人判断的故障。原先只能让发布 Agent 读日志
 * 再给建议，或由人手动执行 pull 并点重跑；现在平台自己完成「拉取 → 验证 → 续跑」。
 *
 * <p>安全边界：
 * <ul>
 *   <li>只拉取沙箱配置里的镜像，不采信日志里解析出的镜像名（日志来自仓库/构建输出，不可信）；</li>
 *   <li>pull 有硬超时、可取消、输出实时进日志；</li>
 *   <li>成功与否由 {@code docker image inspect} 复核，不靠 pull 的退出码自述；</li>
 *   <li>先 inspect 再 pull：镜像已在本机时直接交回后续修复器，避免把"别的故障"误报成缺镜像。</li>
 * </ul>
 */
final class ImagePullRepair implements FailureRepair {

    private static final long INSPECT_TIMEOUT_MS = 5_000L;
    /** 拉取进度最多写 40 行，避免大镜像分层输出刷爆运行日志。 */
    private static final int MAX_LIVE_LINES = 40;

    @Override
    public boolean matches(Diagnosis d) {
        // 动作前预检已经尝试过自动拉取仍失败时，不再重复拉同一个镜像（否则白等两轮硬超时）。
        // 这时只保留平台复核与发布 Agent 兜底；预检关闭（precheck-image=false）时才由本修复器补位。
        return d.image() != null && !d.image().isBlank() && !d.precheckTried();
    }

    @Override
    public Outcome repair(Diagnosis d, Context ctx) {
        List<String> lines = new ArrayList<>();
        if (d.precheckTried()) {
            return Outcome.notFixed("动作前预检已尝试自动拉取，跳过重复拉取", lines);
        }
        if (!d.image().equals(ctx.configuredImage())) {
            return Outcome.notFixed("日志中的镜像与平台配置不一致，跳过自动拉取（防提示注入）：日志="
                    + d.image() + " 配置=" + ctx.configuredImage(), lines);
        }
        if (ctx.pullCommand() == null || ctx.inspectCommand() == null) {
            return Outcome.notFixed("当前执行模式不支持自动拉取镜像（非容器沙箱）", lines);
        }
        if (ctx.cancelled().getAsBoolean()) {
            return Outcome.notFixed("运行已取消，跳过自动修复", lines);
        }

        // 先只读复核：镜像已在本机说明这次失败不是缺镜像（日志里可能只是上一轮残留），
        // 必须交回协调器让后续修复器（发布 Agent）继续诊断，不能谎报"已修复"并占用续跑。
        ProcessExecutor.StepOutcome present = ctx.command()
                .run(ctx.inspectCommand(), INSPECT_TIMEOUT_MS, null);
        if (present.errorCode() == null) {
            lines.add("[autofix] 镜像已在本机（docker image inspect 通过），本次失败不是缺镜像");
            return Outcome.notFixed("镜像已就绪，当前失败另有原因（交给后续诊断）", lines);
        }

        // 先写「开始」再跑命令：实时进度行由 live 直接落日志，顺序才不会倒过来
        ctx.log().accept("[autofix] 检测到工具链镜像缺失：" + d.image()
                + " · 自动执行 " + String.join(" ", ctx.pullCommand())
                + "（最长 " + ctx.pullTimeoutMs() / 1000 + "s，可取消）\n");

        AtomicInteger written = new AtomicInteger();
        // live 回调来自 ProcessExecutor 的虚拟线程：直接写运行日志（引擎侧已同步），
        // 不往结果 List 里并发 add，避免线程安全问题，也保证进度实时可见。
        java.util.function.Consumer<String> live = chunk -> {
            if (chunk == null || chunk.isBlank() || written.get() >= MAX_LIVE_LINES) {
                return;
            }
            for (String raw : chunk.split("\\R")) {
                if (written.getAndIncrement() >= MAX_LIVE_LINES) {
                    break;
                }
                String one = raw.trim();
                if (!one.isEmpty()) {
                    ctx.log().accept("[autofix] " + truncate(one, 200) + "\n");
                }
            }
        };

        ProcessExecutor.StepOutcome pull = ctx.command()
                .run(ctx.pullCommand(), ctx.pullTimeoutMs(), live);
        if (pull.errorCode() != null) {
            lines.add("[autofix] ❌ 自动拉取失败（" + pull.errorCode() + "）："
                    + truncate(String.valueOf(pull.summary()), 240));
            return Outcome.notFixed("docker pull 失败：" + pull.errorCode(), lines);
        }

        // 复核：只信平台自己执行的 inspect，不采信 pull 的输出文本
        ProcessExecutor.StepOutcome inspect = ctx.command()
                .run(ctx.inspectCommand(), INSPECT_TIMEOUT_MS, null);
        if (inspect.errorCode() != null) {
            return Outcome.notFixed("拉取后镜像仍不可用（" + inspect.errorCode() + "）", lines);
        }
        lines.add("[autofix] ✅ 镜像已就绪并通过 docker image inspect 复核");
        return Outcome.fixed("镜像已拉取并验证", lines);
    }

    private static String truncate(String value, int max) {
        String one = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }
}
