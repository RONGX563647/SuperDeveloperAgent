package top.rongx.agent.domain.port;

import java.nio.file.Path;
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
}
