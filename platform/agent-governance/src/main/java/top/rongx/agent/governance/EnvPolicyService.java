package top.rongx.agent.governance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.policy.EnvPolicy;
import top.rongx.agent.domain.policy.PolicyDecision;
import top.rongx.agent.domain.policy.PolicyDeniedException;
import top.rongx.agent.domain.policy.PolicyPort;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.tool.ToolLevel;

/**
 * 分环境策略的唯一出口。
 *
 * <p>默认矩阵（小团队，不引入外部策略引擎）：
 * <pre>
 * dev  : L0/L1/L2 ALLOW, L3 DENY,  dry-run 后自动 apply
 * test : L0/L1/L2 ALLOW, L3 CONFIRM, dry-run 后自动 apply
 * prod : L0/L1 ALLOW, L2 DENY, L3 CONFIRM
 * </pre>
 *
 * <p>prod 的 L3 不允许 ALLOW；未知环境按 prod 处理（fail-closed）。
 */
@Service
public class EnvPolicyService implements PolicyPort {

    private static final String DEV = "dev";
    private static final String TEST = "test";
    private static final String PROD = "prod";

    private final Map<String, EnvPolicy> policies = defaults();

    public EnvPolicy policy(String env) {
        String key = normalize(env);
        return policies.getOrDefault(key, policies.get(PROD));
    }

    public PolicyDecision decide(String env, ToolLevel level) {
        return policy(env).decide(level);
    }

    public boolean canAutoApply(String env) {
        return policy(env).autoApplyAfterDryRun();
    }

    public boolean requireBinding(String env) {
        return policy(env).requireBinding();
    }

    /** 统一门禁：DENY 直接拒绝；CONFIRM 由调用方走审批，不能在此静默放行。 */
    public void requireNotDenied(ProjectContext ctx, ToolLevel level, String operation) {
        PolicyDecision decision = decide(ctx.env(), level);
        if (decision == PolicyDecision.DENY) {
            String where = "prod".equals(ctx.env()) ? "生产环境 " + ctx.env()
                    : "当前环境 " + ctx.env();
            throw new PolicyDeniedException("E-707",
                    where + " 的策略拒绝 " + operation + "（" + level + "=" + decision + "）");
        }
    }

    public boolean requiresConfirmation(String env, ToolLevel level) {
        return decide(env, level) == PolicyDecision.CONFIRM;
    }

    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        policies.forEach((env, p) -> out.put(env, Map.of(
                "L0", p.l0(), "L1", p.l1(), "L2", p.l2(), "L3", p.l3(),
                "autoApplyAfterDryRun", p.autoApplyAfterDryRun(),
                "requireBinding", p.requireBinding())));
        return out;
    }

    public List<String> envs() {
        return List.of(DEV, TEST, PROD);
    }

    private static Map<String, EnvPolicy> defaults() {
        Map<String, EnvPolicy> out = new LinkedHashMap<>();
        out.put(DEV, new EnvPolicy(DEV,
                PolicyDecision.ALLOW, PolicyDecision.ALLOW, PolicyDecision.ALLOW,
                PolicyDecision.DENY, true, true));
        out.put(TEST, new EnvPolicy(TEST,
                PolicyDecision.ALLOW, PolicyDecision.ALLOW, PolicyDecision.ALLOW,
                PolicyDecision.CONFIRM, true, true));
        out.put(PROD, new EnvPolicy(PROD,
                PolicyDecision.ALLOW, PolicyDecision.ALLOW, PolicyDecision.DENY,
                PolicyDecision.CONFIRM, false, true));
        return Map.copyOf(out);
    }

    private static String normalize(String env) {
        return env == null ? PROD : env.trim().toLowerCase(Locale.ROOT);
    }

    /** 策略拒绝；HTTP 层映射为 403 / 409。 */
    // 异常类型在 domain：tools/runtime 只能依赖 domain，但需要捕获/抛出同一类型。
}
