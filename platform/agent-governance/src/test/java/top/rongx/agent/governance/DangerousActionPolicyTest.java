package top.rongx.agent.governance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.policy.DangerousActionPolicy;
import top.rongx.agent.domain.policy.PolicyDeniedException;
import top.rongx.agent.domain.project.ProjectContext;

/** 非生产破坏性动作防护：命令识别 + 频次上限。 */
class DangerousActionPolicyTest {

    private final ProjectContext ctx =
            new ProjectContext("p1", "dev", "u1", "git@example.com:a/b.git", null);

    @Test
    void normalBuildDoesNotNeedConfirmation() {
        DangerousActionPolicy policy = new DangerousActionPolicy(3, 600);
        assertFalse(policy.requiresConfirmation("backend_build", "mvn -B -DskipTests package"));
        assertDoesNotThrow(() -> policy.checkFrequency(ctx, "backend_build", "mvn -B test"));
    }

    @Test
    void destructiveCommandNeedsConfirmation() {
        DangerousActionPolicy policy = new DangerousActionPolicy(3, 600);
        assertTrue(policy.requiresConfirmation("backend_test", "rm -rf target && mvn test"));
        assertTrue(policy.requiresConfirmation("db_reset", null));
        assertFalse(policy.requiresConfirmation("backend_build", null));
    }

    @Test
    void sameDestructiveActionIsLimitedPerWindow() {
        DangerousActionPolicy policy = new DangerousActionPolicy(2, 600);
        policy.checkFrequency(ctx, "db_reset", null);
        policy.checkFrequency(ctx, "db_reset", null);
        assertThrows(PolicyDeniedException.class,
                () -> policy.checkFrequency(ctx, "db_reset", null));
        // 换个动作不受影响
        assertDoesNotThrow(() -> policy.checkFrequency(ctx, "backend_build", null));
    }
}
