package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Docker 网络就绪保证（BUG-35）：部署器与沙箱共用同一份 ensure 实现。
 *
 * <p>过去只有部署侧会 {@code ensureNetwork}，沙箱把 {@code agent.sandbox.network}
 * 原样塞进 {@code docker run --network} 却从不确保存在——配置成 {@code build-net}
 * （文档推荐的生产档位）后构建会以 {@code network not found} 失败，且错误被归因为代码问题。
 * 抽出公共入口后，沙箱在 prepare 阶段先 ensure，失败在跑构建前就明确报"网络未就绪"。
 */
final class DockerNetwork {

    private DockerNetwork() {
    }

    /** 命令执行抽象：与 {@link DockerSandbox.CommandExecutor} / ProcessExecutor.exec 同形，便于注入替身。 */
    @FunctionalInterface
    interface Exec {
        ProcessExecutor.StepOutcome run(List<String> cmd, long timeoutMs,
                                        BooleanSupplier cancelled, Consumer<String> live);
    }

    private static Exec of(ProcessExecutor executor, Path dir) {
        return (cmd, timeoutMs, cancelled, live) ->
                executor.exec(dir, cmd, timeoutMs, new StringBuilder(), cancelled, live);
    }

    /**
     * 确保网络存在：已存在直接返回；缺失则创建（幂等）。
     *
     * @return true = 已存在或创建成功
     */
    static boolean ensure(Exec exec, DockerCli docker, Path dir, String network,
                          StringBuilder log) {
        if (network == null || network.isBlank() || "bridge".equals(network)
                || "host".equals(network) || "none".equals(network)) {
            return true; // docker 内置网络，永远存在
        }
        BooleanSupplier never = () -> false;
        ProcessExecutor.StepOutcome inspect =
                exec.run(docker.cmd("network", "inspect", network), 15_000, never, null);
        if (inspect.exitCode() == 0) {
            return true;
        }
        ProcessExecutor.StepOutcome created =
                exec.run(docker.cmd("network", "create", network), 30_000, never, null);
        if (created.exitCode() == 0) {
            log.append("[deploy] 已创建环境网络 ").append(network).append('\n');
            return true;
        }
        return false;
    }

    /** 供部署器（持有 ProcessExecutor）调用。 */
    static boolean ensure(ProcessExecutor executor, DockerCli docker, Path dir, String network,
                          StringBuilder log) {
        return ensure(of(executor, dir), docker, dir, network, log);
    }
}
