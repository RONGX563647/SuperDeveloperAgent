package top.rongx.agent.gateway.bugfix.stage;

import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.gateway.bugfix.BugFixJson;
import top.rongx.agent.gateway.bugfix.BugFixStageHandler;

/**
 * 验证阶段：修复版本部署后，让运维 Agent 重新执行复现步骤并给出是否解决的结论。
 *
 * <p>结论必须是明确 JSON；无法判断时停住而不是冒险提 PR。
 */
@Component
public class VerifyStage implements BugFixStageHandler {

    private final BugFixAgentPort agent;
    private final BugFixJson json;

    public VerifyStage(BugFixAgentPort agent, BugFixJson json) {
        this.agent = agent;
        this.json = json;
    }

    @Override
    public BugFixStage stage() {
        return BugFixStage.VERIFY;
    }

    @Override
    public BugFixStageResult execute(BugFixRun run, BugFixPolicy policy) {
        String prompt = """
                你是运维验证工程师。修复版本已部署到测试环境（%s，修复提交 %s）。
                请重新执行下面 Bug 的复现步骤，判断问题是否已解决。

                Bug：%s
                复现与修复分析：
                %s
                修复说明：
                %s

                最后必须输出 JSON 代码块：
                ```json
                {"fixed":true,"steps":["..."],"evidence":"验证证据","remainingRisk":"..."}
                ```
                """.formatted(policy.testEnv(), AnalyzeStage.safe(run.fixSha()),
                AnalyzeStage.safe(run.issueTitle()), AnalyzeStage.clip(run.analysis(), 6000),
                AnalyzeStage.clip(run.evidence(), 4000));
        String text;
        try {
            text = agent.run(run, BugFixAgentPort.Role.VERIFY, prompt);
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("验证 Agent 不可用：" + AnalyzeStage.message(e));
        }
        var node = json.extract(text);
        if (node.isEmpty()) {
            return BugFixStageResult.blocked("无法解析验证结论，请人工确认");
        }
        if (!json.bool(node.get(), "fixed", false)) {
            return BugFixStageResult.failed("验证未通过：Bug 仍然存在或证据不足");
        }
        return BugFixStageResult.ok("验证通过，Bug 已解决", text);
    }
}
