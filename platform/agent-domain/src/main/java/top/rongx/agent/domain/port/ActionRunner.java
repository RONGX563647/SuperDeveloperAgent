package top.rongx.agent.domain.port;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSpec;

/**
 * 动作执行端口（执行面实现，确定性代码，不调用任何模型）。
 */
public interface ActionRunner {

    /**
     * 在指定工作区执行一个动作。
     *
     * <p>实现必须：按 argv 执行（不经 shell）、有超时、捕获输出、把失败分类为
     * E-1xx（用户代码）/ E-2xx（平台）/ E-3xx（中断）/ E-4xx（产物）。
     *
     * @param workspace 工作区根目录
     * @param spec      动作规格
     * @return 结构化结果 + 日志尾部
     */
    ActionResult run(Path workspace, ActionSpec spec);

    /** 执行是否被容器/沙箱隔离（false = 宿主进程，无隔离）。fail-closed 据此判定。 */
    default boolean isolated() {
        return false;
    }

    /**
     * 可取消的执行：执行期间轮询 {@code cancelled}，为真时终止进程并返回 E-303。
     *
     * <p>默认实现不支持取消（向后兼容）；真实实现应覆盖。
     */
    default ActionResult run(Path workspace, ActionSpec spec, BooleanSupplier cancelled) {
        return run(workspace, spec);
    }

    /**
     * 带实时输出的可取消执行：{@code live} 在命令执行过程中逐段收到输出。
     *
     * <p>流水线用它在构建时边跑边写运行日志；不关心的调用方沿用三参数版本即可。
     */
    default ActionResult run(Path workspace, ActionSpec spec, BooleanSupplier cancelled,
                             Consumer<String> live) {
        return run(workspace, spec, cancelled);
    }
}
