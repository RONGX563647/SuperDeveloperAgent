package top.rongx.agent.governance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.policy.ConfirmationRequiredException;
import top.rongx.agent.domain.policy.EnvPolicy;
import top.rongx.agent.domain.policy.PolicyDecision;
import top.rongx.agent.domain.policy.PolicyDeniedException;
import top.rongx.agent.domain.policy.PolicyPort;
import top.rongx.agent.domain.port.ChangeStore;
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

    /** 变更单仓储（可空）：CONFIRM 级别核验"已确认的变更单"时使用。 */
    private final ChangeStore changes;

    public EnvPolicyService() {
        this(null);
    }

    @Autowired
    public EnvPolicyService(ChangeStore changes) {
        this.changes = changes;
    }

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

    /** 统一门禁：DENY 直接拒绝；CONFIRM 由调用方走审批，不能在此静默放行。 */
    // @handbook governance-env-policy/require-not-denied | 环境策略拒绝违规动作
    public void requireNotDenied(ProjectContext ctx, ToolLevel level, String operation) {
        PolicyDecision decision = decide(ctx.env(), level);
        if (decision == PolicyDecision.DENY) {
            String where = "prod".equals(ctx.env()) ? "生产环境 " + ctx.env()
                    : "当前环境 " + ctx.env();
            throw new PolicyDeniedException(ErrorCode.ENV_POLICY_DENIED,
                    where + " 的策略拒绝 " + operation + "（" + level + "=" + decision + "）");
        }
    }

    /**
     * 统一门禁（fail-closed 版）：DENY 拒绝；CONFIRM 要求提供"已确认的变更单号"并核验；
     * 策略查询异常一律拒绝。
     *
     * <p>CONFIRM 的唯一解释者在这里：调用方不再"自觉走审批"，而是必须传入
     * {@code confirmedChangeId}（用户在确认卡上确认过的变更单）。核验规则：
     * <ul>
     *   <li>变更单存在、属于当前项目且目标环境一致；</li>
     *   <li>status ∈ {CONFIRMED, APPLIED}（PENDING/FAILED/未知一律拒绝）；</li>
     *   <li>kind 与 operation 匹配（防止拿 A 操作的确认单放行 B 操作）。</li>
     * </ul>
     *
     * @param confirmedChangeId CONFIRM 级别必须提供已确认的变更单号；ALLOW 级别可空
     */
    @Override
    public void requireAllowed(ProjectContext ctx, ToolLevel level, String operation,
                               String confirmedChangeId) {
        PolicyDecision decision;
        try {
            decision = decide(ctx.env(), level);
        } catch (RuntimeException e) {
            // fail-closed：策略解析异常（未知环境配置损坏等）一律拒绝，而不是放行
            throw new PolicyDeniedException(ErrorCode.ENV_POLICY_DENIED,
                    "策略查询失败，按 fail-closed 拒绝 " + operation + "：" + e.getMessage(), e);
        }
        if (decision == PolicyDecision.DENY) {
            requireNotDenied(ctx, level, operation);
            return;
        }
        if (decision == PolicyDecision.CONFIRM) {
            requireConfirmedChange(ctx, operation, confirmedChangeId);
        }
        // ALLOW：直接放行（dev 的 L2 自动放行语义保留）
    }

    /** CONFIRM 的强制点：没有已确认且匹配的变更单号就拒绝，并提示如何获得。 */
    private void requireConfirmedChange(ProjectContext ctx, String operation,
                                        String confirmedChangeId) {
        if (confirmedChangeId == null || confirmedChangeId.isBlank()) {
            throw new ConfirmationRequiredException(null, """
                    操作 %s 在 %s 环境需要人工确认，未执行。
                    next: 先登记变更（request_confirmation / 确认卡），由用户确认后，
                          把确认后的 changeId 作为 confirmed_change_id 传入本工具。
                    """.formatted(operation, ctx.env()));
        }
        if (changes == null) {
            // 仓储不可用 = 无法核验 = fail-closed 拒绝
            throw new PolicyDeniedException(ErrorCode.ENV_POLICY_DENIED,
                    "变更单存储不可用，无法核验确认单，拒绝 " + operation);
        }
        ChangePlan plan;
        try {
            plan = changes.find(confirmedChangeId.trim()).orElse(null);
        } catch (RuntimeException e) {
            throw new PolicyDeniedException(ErrorCode.ENV_POLICY_DENIED,
                    "确认单查询失败，按 fail-closed 拒绝 " + operation + "：" + e.getMessage(), e);
        }
        if (plan == null) {
            throw new PolicyDeniedException(ErrorCode.ENV_POLICY_DENIED,
                    "确认单不存在或已过期：" + confirmedChangeId + "，拒绝 " + operation);
        }
        if (!plan.projectId().equals(ctx.projectId())) {
            throw new PolicyDeniedException(ErrorCode.ENV_POLICY_DENIED,
                    "确认单 " + confirmedChangeId + " 属于项目 " + plan.projectId()
                            + "，与当前项目 " + ctx.projectId() + " 不一致，拒绝 " + operation);
        }
        if (!plan.env().equalsIgnoreCase(ctx.env())) {
            throw new PolicyDeniedException(ErrorCode.ENV_POLICY_DENIED,
                    "确认单 " + confirmedChangeId + " 的目标环境是 " + plan.env()
                            + "，与当前环境 " + ctx.env() + " 不一致，拒绝 " + operation);
        }
        if (plan.status() != ChangePlan.Status.CONFIRMED
                && plan.status() != ChangePlan.Status.APPLIED) {
            throw new PolicyDeniedException(ErrorCode.ENV_POLICY_DENIED,
                    "确认单 " + confirmedChangeId + " 状态为 " + plan.status()
                            + "（需 CONFIRMED/APPLIED），拒绝 " + operation);
        }
        if (plan.kind() == null || !plan.kind().equalsIgnoreCase(operation)) {
            throw new PolicyDeniedException(ErrorCode.ENV_POLICY_DENIED,
                    "确认单 " + confirmedChangeId + " 的 kind 是 " + plan.kind()
                            + "，与本次操作 " + operation + " 不匹配，拒绝 " + operation);
        }
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
}
