package top.rongx.agent.domain.policy;

import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.tool.ToolLevel;

/**
 * 策略端口：工具层/Runtime 只依赖这个接口，具体实现放在 governance。
 *
 * <p>这样既不破坏「tools 只能依赖 domain」的架构规则，又能让所有入口共用同一套
 * 分环境裁决。
 */
public interface PolicyPort {

    PolicyDecision decide(String env, ToolLevel level);

    boolean canAutoApply(String env);

    /** DENY 直接抛 {@link PolicyDeniedException}；CONFIRM 由调用方走审批。 */
    void requireNotDenied(ProjectContext ctx, ToolLevel level, String operation);
}
