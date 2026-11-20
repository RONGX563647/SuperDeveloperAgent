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

    /**
     * 统一门禁（含 CONFIRM 强制点）：DENY 抛 {@link PolicyDeniedException}；
     * CONFIRM 必须传入已确认的变更单号并核验，否则抛
     * {@link ConfirmationRequiredException} 或 {@link PolicyDeniedException}；
     * 策略查询异常时 fail-closed 拒绝。
     *
     * <p>默认实现只处理 DENY/ALLOW（与 {@link #requireNotDenied} 等价）；CONFIRM 的
     * 核验逻辑由具备变更单仓储的实现（{@code EnvPolicyService}）覆写。
     */
    default void requireAllowed(ProjectContext ctx, ToolLevel level, String operation,
                                String confirmedChangeId) {
        requireNotDenied(ctx, level, operation);
    }
}
