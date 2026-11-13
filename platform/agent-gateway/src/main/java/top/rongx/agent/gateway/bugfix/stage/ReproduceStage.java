package top.rongx.agent.gateway.bugfix.stage;

import java.util.Map;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.gateway.bugfix.BugFixJson;
import top.rongx.agent.gateway.bugfix.BugFixPromptTemplates;
import top.rongx.agent.gateway.bugfix.BugFixStageHandler;

/**
 * 复现阶段：让运维 Agent 在测试环境按分析的复现步骤验证 Bug 是否存在。
 *
 * <p>没复现就不进入修复——避免「改了一个根本不存在的问题」。同时要求<b>证据非空</b>且
 * <b>置信度达标</b>：只回一个 {@code reproduced:true} 不足以证明真的看到了现象。
 */
@Component
public class ReproduceStage implements BugFixStageHandler {

    /** 复现结论的最低置信度；低于此值交给人工确认。 */
    static final double MIN_CONFIDENCE = 0.6;

    private final BugFixAgentPort agent;
    private final BugFixJson json;
    private final BugFixPromptTemplates prompts;

    public ReproduceStage(BugFixAgentPort agent, BugFixJson json, BugFixPromptTemplates prompts) {
        this.agent = agent;
        this.json = json;
        this.prompts = prompts;
    }

    @Override
    public BugFixStage stage() {
        return BugFixStage.REPRODUCE;
    }

    @Override
    public BugFixStageResult execute(BugFixRun run, BugFixPolicy policy) {
        String prompt = prompts.render("reproduce", Map.of(
                "testEnv", AnalyzeStage.safe(policy.testEnv()),
                "baseSha", AnalyzeStage.safe(run.baseSha()),
                "issueTitle", AnalyzeStage.safe(run.issueTitle()),
                "analysis", AnalyzeStage.clip(run.analysis(), 6000)));
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
        String evidence = json.text(node.get(), "evidence").strip();
        if (evidence.isBlank()) {
            return BugFixStageResult.blocked("复现结论缺少证据（日志/现象），不进入修复阶段");
        }
        double confidence = json.confidence(node.get(), "confidence");
        if (confidence < MIN_CONFIDENCE) {
            return BugFixStageResult.blocked("复现结论置信度不足（" + percent(confidence)
                    + "），请人工确认复现步骤或补充日志");
        }
        return BugFixStageResult.ok("已在测试环境复现 Bug（置信度 " + percent(confidence) + "）", text);
    }

    static String percent(double value) {
        return Math.round(value * 100) + "%";
    }
}
