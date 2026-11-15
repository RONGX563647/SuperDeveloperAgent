package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;
import top.rongx.agent.domain.run.RunRecoveryAdvisor;

/**
 * 兜底修复：确定性修复没成功时，交发布 Agent 读日志 / 调工具诊断。
 *
 * <p>与原先「失败 → 直接调 Agent」的区别：Agent 的自述**不构成**恢复证据。
 * 诊断结束后平台重新执行前置检查（如 docker image inspect），只有复核通过才允许
 * 自动续跑；否则保留其结论，运行仍是 FAILED，由人决定是否介入。
 *
 * <p>每个失败周期只调用一次（attempt == 1），避免慢路径在重试里反复触发。
 */
final class AgentDiagnosisRepair implements FailureRepair {

    private final boolean enabled;

    AgentDiagnosisRepair(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean matches(Diagnosis d) {
        return enabled && d.agentEnabled() && d.attempt() == 1;
    }

    @Override
    public Outcome repair(Diagnosis d, Context ctx) {
        List<String> lines = new ArrayList<>();
        RunRecoveryAdvisor advisor = ctx.advisor();
        if (advisor == null || !advisor.available()) {
            return Outcome.notFixed("未装配发布 Agent，跳过模型诊断", lines);
        }
        lines.add("[autofix] 确定性修复未成功，转交发布 Agent 诊断（最长 5 分钟，不阻塞其它运行）");
        List<String> advice = advisor.advise(new RunRecoveryAdvisor.Context(
                d.runId(), ctx.projectId(), ctx.env(), ctx.pipelineId(), ctx.gitSha(),
                ctx.actor(), d.logTail() == null ? "" : d.logTail()));
        if (advice == null || advice.isEmpty()) {
            lines.add("[agent] 本次未产生诊断步骤");
        } else {
            for (String line : advice) {
                lines.add("[agent] " + (line == null ? "" : line));
            }
        }

        // 不信任模型自述：平台自己复核前置条件
        if (d.image() != null && ctx.inspectCommand() != null) {
            ProcessExecutor.StepOutcome inspect = ctx.command()
                    .run(ctx.inspectCommand(), 5_000L, null);
            if (inspect.errorCode() == null) {
                lines.add("[autofix] ✅ 复核通过：诊断后工具链镜像已就绪");
                return Outcome.fixed("发布 Agent 修复经平台复核通过", lines);
            }
        }
        return Outcome.notFixed("发布 Agent 已诊断，但平台未检测到可验证的环境恢复", lines);
    }
}
