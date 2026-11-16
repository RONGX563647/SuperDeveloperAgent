package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;
import top.rongx.agent.domain.run.RunRecoveryAdvisor;

/**
 * 兜底修复：确定性修复没成功时，交发布 Agent 读日志 / 调工具诊断。
 *
 * <p>与原先「失败 → 直接调 Agent」的区别：Agent 的自述**不构成**恢复证据。
 * 诊断结束后平台自己复核：镜像类故障跑 {@code docker image inspect}，其它故障跑只读
 * dry-run（缺环境变量 / Docker 不可用 / 阶段定义缺失等阻塞项）。只有复核通过才允许
 * 自动续跑；否则保留其结论，运行仍是 FAILED，由人决定是否介入。
 *
 * <p>同一次失败只诊断一次：按「阶段 + 错误码 + 错误摘要」判重。自动续跑后如果
 * 出现**新的**失败（例如镜像修好后部署又因端口失败），仍然允许 Agent 继续修，
 * 这样修复链不会因为写死 attempt == 1 而错过第二类故障。
 */
final class AgentDiagnosisRepair implements FailureRepair {

    private final boolean enabled;
    /** 最近一次已诊断的失败签名；只在 repair() 里更新，matches() 保持无副作用。 */
    private String diagnosed;

    AgentDiagnosisRepair(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean matches(Diagnosis d) {
        return enabled && d.agentEnabled() && !signature(d).equals(diagnosed);
    }

    @Override
    public Outcome repair(Diagnosis d, Context ctx) {
        diagnosed = signature(d);
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

        // 不信任模型自述：平台自己复核前置条件。
        // 镜像类故障看 inspect；其它故障（端口、健康路径、缺环境变量、阶段定义不匹配）
        // 用只读 dry-run 复核——Agent 已经通过工具改好配置时，这里才允许自动续跑。
        if (d.image() != null && ctx.inspectCommand() != null) {
            ProcessExecutor.StepOutcome inspect = ctx.command()
                    .run(ctx.inspectCommand(), 5_000L, null);
            if (inspect.errorCode() == null) {
                lines.add("[autofix] ✅ 复核通过：诊断后工具链镜像已就绪");
                return Outcome.fixed("发布 Agent 修复经平台复核通过", lines);
            }
            return Outcome.notFixed("发布 Agent 已诊断，但平台复核显示工具链镜像仍不可用", lines);
        }
        if (ctx.preconditionsOk() != null && preconditionsOk(ctx)) {
            lines.add("[autofix] ✅ 复核通过：平台只读预检已无阻塞项");
            return Outcome.fixed("发布 Agent 修复经平台预检复核通过", lines);
        }
        lines.add("[autofix] 平台只读预检仍有阻塞项，不自动续跑");
        return Outcome.notFixed("发布 Agent 已诊断，但平台未检测到可验证的环境恢复", lines);
    }

    /** 平台预检视为证据：把异常统一折叠成"未通过"，不让修复链因诊断异常中断。 */
    private static boolean preconditionsOk(Context ctx) {
        try {
            return ctx.preconditionsOk().getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 失败签名：阶段 + 错误码 + 摘要；同一签名不重复调用慢路径。 */
    private static String signature(Diagnosis d) {
        return (d.stageId() == null ? "" : d.stageId())
                + "|" + (d.errorCode() == null ? "" : d.errorCode())
                + "|" + (d.message() == null ? "" : d.message());
    }
}
