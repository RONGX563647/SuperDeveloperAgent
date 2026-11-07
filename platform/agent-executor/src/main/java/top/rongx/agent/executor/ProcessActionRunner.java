package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.List;

/**
 * 宿主进程执行模式。
 *
 * <p>只做预算、日志、产物，**不做隔离**。用于本地开发或没有 Docker 的环境；
 * 有 Docker 时由 {@code agent.sandbox.mode=auto} 自动切到 {@link DockerActionRunner}。
 */
public class ProcessActionRunner extends AbstractActionRunner {

    @Override
    protected List<String> effectiveArgv(Path workspace, List<String> argv) {
        return argv;
    }
}
