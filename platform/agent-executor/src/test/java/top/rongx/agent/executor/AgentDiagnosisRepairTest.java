package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.run.RunRecoveryAdvisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 兜底诊断：模型的「已修复」自述不算证据，平台必须自己复核。 */
class AgentDiagnosisRepairTest {

    private static final List<String> INSPECT =
            List.of("docker", "image", "inspect", "maven:cfg");

    private static FailureRepair.Diagnosis diagnosis(int attempt, boolean agentEnabled) {
        return new FailureRepair.Diagnosis("run-1", "build", "E-209",
                "构建镜像未就绪（maven:cfg）", "[build] 失败", "maven:cfg",
                attempt, agentEnabled);
    }

    private static FailureRepair.Context context(RunRecoveryAdvisor advisor,
                                                 ProcessExecutor.StepOutcome inspectResult,
                                                 List<String> inspectCommand) {
        return new FailureRepair.Context(() -> false, line -> {
        }, (argv, timeout, live) -> inspectResult, 60_000L,
                null, inspectCommand, "maven:cfg", advisor,
                "P", "test", "verify", "abcdef1", "ui:admin", "[build] 失败");
    }

    @Test
    @DisplayName("Agent 自称已修复但 inspect 失败 → 不自动续跑")
    void agentClaimIsNotEvidence() {
        List<String> seen = new ArrayList<>();
        RunRecoveryAdvisor advisor = ctx -> {
            seen.add(ctx.log());
            return List.of("根因：镜像缺失", "已修复：我已执行 docker pull", "下一步：重跑即可");
        };
        AgentDiagnosisRepair repair = new AgentDiagnosisRepair(true);
        FailureRepair.Outcome outcome = repair.repair(diagnosis(1, true), context(advisor,
                ProcessExecutor.StepOutcome.failed(1, "E-101", "no such image"), INSPECT));

        assertFalse(outcome.verified());
        assertEquals(1, seen.size(), "每轮只调用一次模型诊断");
        assertTrue(outcome.lines().stream().anyMatch(l -> l.contains("[agent] 已修复")),
                outcome.lines().toString());
    }

    @Test
    @DisplayName("Agent 修复后平台复核通过 → 才允许自动续跑")
    void verifiedAfterPlatformCheck() {
        RunRecoveryAdvisor advisor = ctx -> List.of("已用 env_ensure 补齐镜像");
        FailureRepair.Outcome outcome = new AgentDiagnosisRepair(true)
                .repair(diagnosis(1, true),
                        context(advisor, ProcessExecutor.StepOutcome.ok(), INSPECT));

        assertTrue(outcome.verified(), outcome.summary());
        assertTrue(outcome.lines().stream().anyMatch(l -> l.contains("复核通过")),
                outcome.lines().toString());
    }

    @Test
    @DisplayName("同一失败周期只诊断一次；关闭开关或未装配模型时不介入")
    void switchesAndIdempotence() {
        AgentDiagnosisRepair repair = new AgentDiagnosisRepair(true);
        assertFalse(repair.matches(diagnosis(2, true)), "第二轮不再走慢路径");
        assertFalse(repair.matches(diagnosis(1, false)), "agent-fallback 关闭");
        assertFalse(new AgentDiagnosisRepair(false).matches(diagnosis(1, true)), "策略关闭");

        FailureRepair.Outcome outcome = repair.repair(diagnosis(1, true),
                context(null, ProcessExecutor.StepOutcome.ok(), INSPECT));
        assertFalse(outcome.verified());
        assertTrue(outcome.summary().contains("未装配"));
    }
}
