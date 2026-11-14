package top.rongx.agent.gateway.bugfix.stage;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.gateway.bugfix.BugFixOutputValidation;
import top.rongx.agent.gateway.bugfix.BugFixPromptTemplates;
import top.rongx.agent.gateway.bugfix.BugFixStageHandler;
import top.rongx.agent.gateway.bugfix.structured.ReproduceOutput;

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
    private final BugFixPromptTemplates prompts;

    public ReproduceStage(BugFixAgentPort agent, BugFixPromptTemplates prompts) {
        this.agent = agent;
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
        ReproduceOutput output;
        try {
            output = agent.runStructured(run, prompt, ReproduceOutput.class);
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("复现 Agent 不可用：" + AnalyzeStage.message(e));
        }
        if (output == null) {
            return BugFixStageResult.blocked("复现 Agent 未返回结构化结论，请人工确认");
        }
        if (!Boolean.TRUE.equals(output.reproduced())) {
            return BugFixStageResult.blocked("测试环境未复现该 Bug，已停止自动修复");
        }
        String evidence = BugFixOutputValidation.text(output.evidence());
        if (evidence.isBlank()) {
            return BugFixStageResult.blocked("复现结论缺少证据（日志/现象），不进入修复阶段");
        }
        double confidence = BugFixOutputValidation.confidence(output.confidence());
        if (confidence < MIN_CONFIDENCE) {
            return BugFixStageResult.blocked("复现结论置信度不足（" + percent(confidence)
                    + "），请人工确认复现步骤或补充日志");
        }
        String detail = evidence;
        List<String> steps = output.steps() == null ? List.of() : output.steps();
        if (!steps.isEmpty()) {
            detail = "复现步骤：\n" + AnalyzeStage.bullets(steps) + "\n\n证据：\n" + evidence;
        }
        return BugFixStageResult.ok("已在测试环境复现 Bug（置信度 " + percent(confidence) + "）",
                detail);
    }

    static String percent(double value) {
        return Math.round(value * 100) + "%";
    }
}
