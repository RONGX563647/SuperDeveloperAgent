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
        assertThrows(IllegalArgumentException.class, () -> new top.rongx.agent.domain.policy.EnvPolicy(
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
}
