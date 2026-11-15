package top.rongx.agent.executor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.run.StageResult;

/**
 * 容器内执行模式（生产用）。
 *
 * <p>每条命令都被包成 {@code docker run}，工作区挂载进容器；语义与宿主模式一致
 * （预算、超时、日志、产物、错误分类），差别只有隔离。
 *
 * <p>容器带固定名字并在动作结束后 {@code docker rm -f}：动作超时只杀 docker CLI 时，
 * 容器仍会继续跑（表现为"运行已失败但容器还在 RUNNING"），必须显式清理。
 *
 * <p><b>准备阶段也受预算约束</b>：依赖缓存卷初始化在 {@code super.run} 之前完成，
 * 走同一套超时 / 取消 / 实时日志；失败直接返回结构化失败（而不是抛到引擎外层
 * 变成一条没有原因的"引擎异常"），并把准备耗时从动作预算里扣掉。
 */
public class DockerActionRunner extends AbstractActionRunner {

    private final DockerSandbox sandbox;

    public DockerActionRunner(DockerSandbox sandbox) {
        this.sandbox = sandbox;
    }

    /** 供引擎的自动修复层读取「管理员配置的镜像与复核命令」（不是日志里的字符串）。 */
    public DockerSandbox sandbox() {
        return sandbox;
    }

    @Override
    public ActionResult run(Path workspace, ActionSpec spec) {
        return run(workspace, spec, () -> false, null);
    }

    @Override
    public ActionResult run(Path workspace, ActionSpec spec, BooleanSupplier cancelled) {
        return run(workspace, spec, cancelled, null);
    }

    @Override
    public ActionResult run(Path workspace, ActionSpec spec, BooleanSupplier cancelled,
                            Consumer<String> live) {
        String container = sandbox.beginContainer();
        try {
            long prepareStart = System.nanoTime();
            // 先确认工具链镜像在本机，再动缓存卷：缺镜像是配置问题，应在数秒内明确报出
            sandbox.ensureImageReady(cancelled, live);
            sandbox.prepareCacheVolume(workspace, cancelled, live);
            long spentMs = (System.nanoTime() - prepareStart) / 1_000_000L;
            long leftMs = Math.max(1, spec.timeout().toMillis() - spentMs);
            ActionSpec withBudget = new ActionSpec(spec.action(), spec.steps(), spec.produce(),
                    Duration.ofMillis(leftMs), spec.source());
            return super.run(workspace, withBudget, cancelled, live);
        } catch (RuntimeException e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            String code = why.contains("E-302") ? "E-302"
                    : why.contains("E-303") ? "E-303" : "E-209";
            int exit = "E-302".equals(code) ? 124 : "E-303".equals(code) ? 143 : 1;
            if (live != null) {
                live.accept("[sandbox] ❌ " + code + " " + why + "\n");
            }
            return new ActionResult(StageResult.failed(spec.action().wireName(), exit,
                    code, why, 0), why);
        } finally {
            sandbox.endContainer(container);
        }
    }

    @Override
    protected List<String> effectiveArgv(Path workspace, List<String> argv) {
        return sandbox.wrap(workspace, argv);
    }
}
