package top.rongx.agent.domain.policy;

import java.util.Locale;
import top.rongx.agent.domain.tool.ToolLevel;

/**
 * 环境自治级别：把「非生产全放行 / 生产只读」的二值开关拆成三档。
 *
 * <pre>
 * AUTO   ：dev。只读 + 构建/测试/镜像 + 受限补丁全部自主执行；L3 仍必须走人工变更单。
 * REVIEW ：test。行为同 AUTO，但部署必须有已确认的环境绑定，补丁只允许 CI/构建与部署配置，
 *          连续失败达到阈值后自动降级为人工（见 DeployGuard）。
 * PROD   ：prod。只读工具面；任何写操作都不在 schema 里，发布必须「测试证据 + 一次性令牌」。
 * </pre>
 *
 * <p>级别可由环境模板覆盖（{@code env_templates.autonomy}），缺省按环境名推导。
 * 放在 domain 是为了让 Agent 装配、工具门禁与部署面板读同一份定义。
 */
public enum AutonomyLevel {

    AUTO,
    REVIEW,
    PROD;

    public static AutonomyLevel defaultFor(String env) {
        return switch (env == null ? "" : env.trim().toLowerCase(Locale.ROOT)) {
            case "dev" -> AUTO;
            case "test" -> REVIEW;
            default -> PROD;
        };
    }

    /** 解析模板里保存的值；空/非法时回落到环境默认（fail-closed：未知环境按 PROD）。 */
    public static AutonomyLevel of(String raw, String env) {
        if (raw != null && !raw.isBlank()) {
            for (AutonomyLevel level : values()) {
                if (level.name().equalsIgnoreCase(raw.trim())) {
                    return level;
                }
            }
        }
        return defaultFor(env);
    }

    /** 是否允许任何写操作（工具面是否装配写工具）。 */
    public boolean writeAllowed() {
        return this != PROD;
    }

    /** 是否需要确认令牌：PROD 的写操作全部需要；REVIEW/TEST 的 L3 本来就必须走变更单。 */
    public boolean requiresConfirm(ToolLevel level) {
        if (this == PROD) {
            return level.mutating();
        }
        return level == ToolLevel.L3_EXECUTE_PROD;
    }

    /** REVIEW 下部署必须有已人工确认的环境绑定（AUTO 允许自动复用最近绑定）。 */
    public boolean requiresConfirmedBinding() {
        return this == REVIEW || this == PROD;
    }
}
