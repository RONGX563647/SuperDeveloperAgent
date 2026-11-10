package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSpec;

/**
 * 容器内执行模式（生产用）。
 *
 * <p>每条命令都被包成 {@code docker run}，工作区挂载进容器；语义与宿主模式一致
 * （预算、超时、日志、产物、错误分类），差别只有隔离。
 *
 * <p>容器带固定名字并在动作结束后 {@code docker rm -f}：动作超时只杀 docker CLI 时，
 * 容器仍会继续跑（表现为"运行已失败但容器还在 RUNNING"），必须显式清理。
 */
public class DockerActionRunner extends AbstractActionRunner {

    private final DockerSandbox sandbox;

    public DockerActionRunner(DockerSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public ActionResult run(Path workspace, ActionSpec spec, BooleanSupplier cancelled) {
        String container = sandbox.beginContainer();
        try {
            return super.run(workspace, spec, cancelled);
        } finally {
            sandbox.endContainer(container);
        }
    }

    @Override
    protected List<String> effectiveArgv(Path workspace, List<String> argv) {
        return sandbox.wrap(workspace, argv);
    }

    public DockerSandbox sandbox() {
        return sandbox;
    }
}
