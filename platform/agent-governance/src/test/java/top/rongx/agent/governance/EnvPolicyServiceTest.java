package top.rongx.agent.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.policy.PolicyDecision;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.tool.ToolLevel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import top.rongx.agent.domain.error.PlatformException;

class EnvPolicyServiceTest {

    private final EnvPolicyService policy = new EnvPolicyService();

    @Test
    @DisplayName("dev/test 放行 L2，prod 拒绝 L2；prod L3 必须确认")
    void matrix() {
        assertEquals(PolicyDecision.ALLOW,
                policy.decide("dev", ToolLevel.L2_EXECUTE_ISOLATED));
        assertEquals(PolicyDecision.ALLOW,
                policy.decide("test", ToolLevel.L2_EXECUTE_ISOLATED));
        assertEquals(PolicyDecision.DENY,
                policy.decide("prod", ToolLevel.L2_EXECUTE_ISOLATED));
        assertEquals(PolicyDecision.CONFIRM,
                policy.decide("prod", ToolLevel.L3_EXECUTE_PROD));
        assertEquals(PolicyDecision.DENY,
                policy.decide("dev", ToolLevel.L3_EXECUTE_PROD));

        assertTrue(policy.canAutoApply("dev"));
        assertTrue(policy.canAutoApply("test"));
        assertFalse(policy.canAutoApply("prod"));
    }

    @Test
    @DisplayName("未知环境按 prod fail-closed；prod L3 不能配成 ALLOW")
    void failClosed() {
        assertEquals(PolicyDecision.DENY,
                policy.decide("staging", ToolLevel.L2_EXECUTE_ISOLATED));
        assertThrows(PlatformException.class, () -> new top.rongx.agent.domain.policy.EnvPolicy(
                "prod", PolicyDecision.ALLOW, PolicyDecision.ALLOW, PolicyDecision.DENY,
                PolicyDecision.ALLOW, false, true));
    }

    @Test
    @DisplayName("requireNotDenied 抛 E-707")
    void deniesWithCode() {
        ProjectContext prod = new ProjectContext("p", "prod", "u", null, null);
        var ex = assertThrows(top.rongx.agent.domain.policy.PolicyDeniedException.class,
                () -> policy.requireNotDenied(prod, ToolLevel.L2_EXECUTE_ISOLATED, "ci_run"));
        assertEquals("E-707", ex.code());
    }

    // ================================================================ BUG-52

    @Test
    @DisplayName("requireAllowed：prod L2 直接 DENY（E-707），test L2 放行，dev L2 放行")
    void requireAllowedMatrix() {
        ProjectContext prod = new ProjectContext("p", "prod", "u", null, null);
        var denied = assertThrows(top.rongx.agent.domain.policy.PolicyDeniedException.class,
                () -> policy.requireAllowed(prod, ToolLevel.L2_EXECUTE_ISOLATED, "deploy_run", null));
        assertEquals("E-707", denied.code());

        ProjectContext test = new ProjectContext("p", "test", "u", null, null);
        policy.requireAllowed(test, ToolLevel.L2_EXECUTE_ISOLATED, "deploy_run", null);

        ProjectContext dev = new ProjectContext("p", "dev", "u", null, null);
        policy.requireAllowed(dev, ToolLevel.L2_EXECUTE_ISOLATED, "env_var_set", null);
    }

    @Test
    @DisplayName("requireAllowed：prod L3 CONFIRM 没有确认单 → ConfirmationRequiredException")
    void requireAllowedConfirmWithoutChange() {
        EnvPolicyService withStore = new EnvPolicyService(new MemoryChanges());
        ProjectContext prod = new ProjectContext("p", "prod", "u", null, null);
        var ex = assertThrows(top.rongx.agent.domain.policy.ConfirmationRequiredException.class,
                () -> withStore.requireAllowed(prod, ToolLevel.L3_EXECUTE_PROD, "stack_deploy", null));
        assertEquals("E-602", ex.code());
    }

    @Test
    @DisplayName("requireAllowed：prod L3 CONFIRM 有已确认单 → 放行")
    void requireAllowedConfirmWithConfirmedChange() {
        MemoryChanges store = new MemoryChanges();
        top.rongx.agent.domain.change.ChangePlan plan = store.request("p", "prod",
                "stack_deploy", "发布", null, null, false);
        store.updateStatus(plan.changeId(), top.rongx.agent.domain.change.ChangePlan.Status.CONFIRMED);
        EnvPolicyService withStore = new EnvPolicyService(store);
        ProjectContext prod = new ProjectContext("p", "prod", "u", null, null);
        withStore.requireAllowed(prod, ToolLevel.L3_EXECUTE_PROD, "stack_deploy", plan.changeId());
    }

    @Test
    @DisplayName("requireAllowed：PENDING 状态的单不生效；跨项目/跨环境/kind 不匹配拒绝")
    void requireAllowedValidatesChange() {
        MemoryChanges store = new MemoryChanges();
        top.rongx.agent.domain.change.ChangePlan pending = store.request("p", "prod",
                "stack_deploy", "发布", null, null, false);
        EnvPolicyService withStore = new EnvPolicyService(store);
        ProjectContext prod = new ProjectContext("p", "prod", "u", null, null);

        // PENDING 不算已确认
        assertThrows(top.rongx.agent.domain.policy.PolicyDeniedException.class,
                () -> withStore.requireAllowed(prod, ToolLevel.L3_EXECUTE_PROD,
                        "stack_deploy", pending.changeId()));

        // 已确认但 kind 不匹配
        store.updateStatus(pending.changeId(), top.rongx.agent.domain.change.ChangePlan.Status.CONFIRMED);
        assertThrows(top.rongx.agent.domain.policy.PolicyDeniedException.class,
                () -> withStore.requireAllowed(prod, ToolLevel.L3_EXECUTE_PROD,
                        "stack_rollback", pending.changeId()));

        // 跨项目
        ProjectContext other = new ProjectContext("other", "prod", "u", null, null);
        assertThrows(top.rongx.agent.domain.policy.PolicyDeniedException.class,
                () -> withStore.requireAllowed(other, ToolLevel.L3_EXECUTE_PROD,
                        "stack_deploy", pending.changeId()));

        // 跨环境
        ProjectContext testCtx = new ProjectContext("p", "test", "u", null, null);
        assertThrows(top.rongx.agent.domain.policy.PolicyDeniedException.class,
                () -> withStore.requireAllowed(testCtx, ToolLevel.L3_EXECUTE_PROD,
                        "stack_deploy", pending.changeId()));

        // 单号不存在
        assertThrows(top.rongx.agent.domain.policy.PolicyDeniedException.class,
                () -> withStore.requireAllowed(prod, ToolLevel.L3_EXECUTE_PROD,
                        "stack_deploy", "chg-missing"));

        // 正确匹配放行
        withStore.requireAllowed(prod, ToolLevel.L3_EXECUTE_PROD, "stack_deploy", pending.changeId());
    }

    @Test
    @DisplayName("fail-closed：策略查询异常（配置损坏）时 requireAllowed 拒绝而不是放行")
    void requireAllowedFailClosedOnPolicyError() {
        EnvPolicyService broken = new EnvPolicyService(new MemoryChanges()) {
            @Override
            public PolicyDecision decide(String env, ToolLevel level) {
                throw new IllegalStateException("策略配置损坏");
            }
        };
        ProjectContext dev = new ProjectContext("p", "dev", "u", null, null);
        var ex = assertThrows(top.rongx.agent.domain.policy.PolicyDeniedException.class,
                () -> broken.requireAllowed(dev, ToolLevel.L2_EXECUTE_ISOLATED, "env_var_set", null));
        assertEquals("E-707", ex.code());
    }

    /** 内存变更单（仅本测试使用）。 */
    static final class MemoryChanges implements top.rongx.agent.domain.port.ChangeStore {
        private final java.util.Map<String, top.rongx.agent.domain.change.ChangePlan> rows =
                new java.util.LinkedHashMap<>();

        @Override
        public top.rongx.agent.domain.change.ChangePlan request(String projectId, String env,
                String kind, String summary, String imageDigest, String frontendDistHash,
                boolean highRisk) {
            return registerWithHash(projectId, env, kind, summary, "hash", highRisk);
        }

        @Override
        public top.rongx.agent.domain.change.ChangePlan registerWithHash(String projectId,
                String env, String kind, String summary, String planHash, boolean highRisk) {
            top.rongx.agent.domain.change.ChangePlan plan =
                    new top.rongx.agent.domain.change.ChangePlan("chg-" + rows.size(),
                            projectId, env, kind, planHash, summary, null, null, highRisk,
                            java.time.Instant.now(),
                            top.rongx.agent.domain.change.ChangePlan.Status.PENDING);
            rows.put(plan.changeId(), plan);
            return plan;
        }

        @Override
        public java.util.Optional<top.rongx.agent.domain.change.ChangePlan> find(String changeId) {
            return java.util.Optional.ofNullable(rows.get(changeId));
        }

        @Override
        public java.util.Optional<top.rongx.agent.domain.change.ChangePlan> findLatest(
                String projectId, String kindPrefix) {
            return rows.values().stream()
                    .filter(p -> p.projectId().equals(projectId))
                    .filter(p -> p.kind().startsWith(kindPrefix))
                    .reduce((a, b) -> b);
        }

        @Override
        public void updateStatus(String changeId, top.rongx.agent.domain.change.ChangePlan.Status status) {
            rows.computeIfPresent(changeId, (k, v) -> new top.rongx.agent.domain.change.ChangePlan(
                    v.changeId(), v.projectId(), v.env(), v.kind(), v.planHash(), v.summary(),
                    v.imageDigest(), v.frontendDistHash(), v.highRisk(), v.createdAt(), status));
        }
    }
}
