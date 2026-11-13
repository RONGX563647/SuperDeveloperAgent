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
 * 复现阶段：让运维 Agent 在测试环境按分析的复现步骤验证 Bug 是否存在。
 *
 * <p>没复现就不进入修复——避免「改了一个根本不存在的问题」。Agent 无法给出明确结论时
 * 同样停住（BLOCKED），交由人工补充复现信息。
 */
@Component
public class ReproduceStage implements BugFixStageHandler {

    private final BugFixAgentPort agent;
    private final BugFixJson json;

    public ReproduceStage(BugFixAgentPort agent, BugFixJson json) {
        this.agent = agent;
        this.json = json;
    }

    @Override
    public BugFixStage stage() {
        return BugFixStage.REPRODUCE;
    }

    @Override
    public BugFixStageResult execute(BugFixRun run, BugFixPolicy policy) {
        String prompt = """
                你是运维排障工程师。请验证下面这个 Bug 在测试环境（%s，基线提交 %s）是否能够复现。

                Bug：%s
                分析结论：
                %s

                请通过日志、运行时状态、健康检查与端口探测判断，不要修改代码。
                最后必须输出 JSON 代码块：
                ```json
                {"reproduced":true,"steps":["..."],"evidence":"关键日志/现象","confidence":0.8}
                ```
                如果无法判断，把 confidence 设为 0 并说明缺少哪些信息。
                """.formatted(policy.testEnv(), AnalyzeStage.safe(run.baseSha()),
                AnalyzeStage.safe(run.issueTitle()), AnalyzeStage.clip(run.analysis(), 6000));
        String text;
        try {
            text = agent.run(run, BugFixAgentPort.Role.REPRODUCE, prompt);
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("复现 Agent 不可用：" + AnalyzeStage.message(e));
        }
        var node = json.extract(text);
        if (node.isEmpty()) {
            return BugFixStageResult.blocked("无法解析复现结论，请人工确认");
        }
        boolean reproduced = json.bool(node.get(), "reproduced", false);
        if (!reproduced) {
            return BugFixStageResult.blocked("测试环境未复现该 Bug，已停止自动修复");
        }
        return BugFixStageResult.ok("已在测试环境复现 Bug", text);
    }
}
