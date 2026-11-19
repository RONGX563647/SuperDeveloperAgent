package top.rongx.agent.domain.change;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 变更单是否可执行：只有补丁与 ActionId 动作能弹确认卡。 */
class ChangePlanTest {

    private static ChangePlan plan(String kind) {
        return new ChangePlan("chg-1", "p1", "dev", kind, "hash",
                "summary", null, null, false, Instant.now());
    }

    @Test
    @DisplayName("可执行变更：ActionId 动作与 patch:* 才推确认卡")
    void executableOnlyForActionOrPatch() {
        assertTrue(plan("stack_deploy").executable());
        assertTrue(plan("stack_rollback").executable());
        assertTrue(plan("patch:patch-123").executable());
        assertTrue(plan("editor_branch:agent/session-wt-1").executable());

        // 留痕单没有 /apply 闭环，推卡必然 400
        assertFalse(plan("env_ensure").executable());
        assertFalse(plan("deploy_save_recipe").executable());
        assertFalse(plan("env_mapping_draft").executable());
    }

    @Test
    @DisplayName("状态：未指定按 PENDING，显式状态原样保留")
    void statusDefaultsToPending() {
        assertEquals(ChangePlan.Status.PENDING, plan("stack_deploy").status());
        ChangePlan failed = new ChangePlan("chg-2", "p1", "dev", "stack_deploy", "hash",
                "summary", null, null, false, Instant.now(), ChangePlan.Status.FAILED);
        assertEquals(ChangePlan.Status.FAILED, failed.status());
    }
}
