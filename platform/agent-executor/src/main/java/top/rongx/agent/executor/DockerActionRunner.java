package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.List;

/**
 * 容器内执行模式（生产用）。
 *
 * <p>每条命令都被包成 {@code docker run}，工作区挂载进容器；语义与宿主模式一致
 * （预算、超时、日志、产物、错误分类），差别只有隔离。
 */
public class DockerActionRunner extends AbstractActionRunner {

    private final DockerSandbox sandbox;

    public DockerActionRunner(DockerSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    protected List<String> effectiveArgv(Path workspace, List<String> argv) {
        return sandbox.wrap(workspace, argv);
    }

    public DockerSandbox sandbox() {
        return sandbox;
    }
}
