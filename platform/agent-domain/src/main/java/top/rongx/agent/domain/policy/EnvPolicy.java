package top.rongx.agent.domain.policy;

import top.rongx.agent.domain.tool.ToolLevel;

/**
 * 分环境策略：同一动作在不同环境得到不同裁决，而不是全局二元开关。
 *
 * @param env                  dev / test / prod
 * @param l0                   L0 只读
 * @param l1                   L1 计划/草案
 * @param l2                   L2 隔离执行
 * @param l3                   L3 生产变更
 * @param autoApplyAfterDryRun dry-run 通过后是否允许自动 apply（流水线内部）
 * @param requireBinding       部署是否必须有已确认的环境绑定
 */
public record EnvPolicy(
        String env,
        PolicyDecision l0,
        PolicyDecision l1,
        PolicyDecision l2,
        PolicyDecision l3,
        boolean autoApplyAfterDryRun,
        boolean requireBinding) {

    public EnvPolicy {
        if ("prod".equals(env) && l3 == PolicyDecision.ALLOW) {
            // 生产 L3 永远不能自动放行；即使配置写错也在启动/构造时失败。
            throw new IllegalArgumentException("prod 的 L3 策略不能为 ALLOW");
        }
    }

    public PolicyDecision decide(ToolLevel level) {
        return switch (level) {
            case L0_READ -> l0;
            case L1_PROPOSE -> l1;
            case L2_EXECUTE_ISOLATED -> l2;
            case L3_EXECUTE_PROD -> l3;
        };
    }
}
