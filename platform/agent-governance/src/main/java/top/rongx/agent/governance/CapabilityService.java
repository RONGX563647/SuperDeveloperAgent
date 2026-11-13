package top.rongx.agent.governance;

import java.util.List;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.patch.PatchTier;
import top.rongx.agent.domain.policy.AutonomyLevel;
import top.rongx.agent.domain.tool.ToolLevel;

/**
 * 能力视图：把「环境自治级别」翻译成工具面、写权限与确认强度。
 *
 * <p>Agent 装配、单步动作、补丁范围与部署门禁都从这里取同一份判断，
 * 避免提示词、工具注册与控制器各写一套规则。
 */
@Component
public class CapabilityService {

    private final EnvironmentStore environments;

    public CapabilityService(EnvironmentStore environments) {
        this.environments = environments;
    }

    /** 该环境的自治级别（模板可覆盖，缺省按环境名推导；未知环境 fail-closed 为 PROD）。 */
    public AutonomyLevel autonomy(String env) {
        try {
            return environments.template(env)
                    .map(t -> AutonomyLevel.of(t.autonomy(), env))
                    .orElseGet(() -> AutonomyLevel.defaultFor(env));
        } catch (RuntimeException e) {
            return AutonomyLevel.defaultFor(env);
        }
    }

    public boolean writeAllowed(String env) {
        return autonomy(env).writeAllowed();
    }

    public boolean requiresConfirm(String env, ToolLevel level) {
        return autonomy(env).requiresConfirm(level);
    }

    public boolean requiresConfirmedBinding(String env) {
        return autonomy(env).requiresConfirmedBinding();
    }

    /** 补丁是否落在该环境允许的档位内（AUTO 全档、REVIEW 到部署配置、PROD 全禁）。 */
    public boolean patchAllowed(String env, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return false;
        }
        List<PatchTier> tiers = paths.stream().map(PatchTier::classify).toList();
        return PatchTier.allowedFor(autonomy(env), tiers);
    }

    /** 返回路径中第一个越界的档位说明（用于拒绝时提示），全部允许时返回 null。 */
    public String patchProblem(String env, List<String> paths) {
        AutonomyLevel level = autonomy(env);
        if (level == AutonomyLevel.PROD) {
            return "该环境为 PROD（只读），不允许任何补丁";
        }
        for (String path : paths) {
            PatchTier tier = PatchTier.classify(path);
            if (tier == PatchTier.NONE) {
                return path + " 不在补丁白名单内";
            }
            if (tier == PatchTier.SOURCE && level != AutonomyLevel.AUTO) {
                return path + " 属于业务源码，只有 AUTO 环境允许修改（当前 " + level + "）";
            }
        }
        return null;
    }

    /** 面向提示词/审计的一句话说明。 */
    public String describe(String env) {
        return switch (autonomy(env)) {
            case AUTO -> "AUTO（自主执行：只读/构建/测试/部署脚本自主，L3 仍需人工变更单）";
            case REVIEW -> "REVIEW（受控执行：构建测试自主，部署需已确认绑定，补丁只到部署配置）";
            case PROD -> "PROD（只读：写工具不在工具面，发布需测试证据 + 人工令牌）";
        };
    }
}
