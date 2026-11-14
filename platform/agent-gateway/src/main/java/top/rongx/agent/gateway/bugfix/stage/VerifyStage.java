package top.rongx.agent.gateway.bugfix.stage;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixCheckPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.gateway.bugfix.BugFixOutputValidation;
import top.rongx.agent.gateway.bugfix.BugFixPromptTemplates;
import top.rongx.agent.gateway.bugfix.BugFixStageHandler;
import top.rongx.agent.gateway.bugfix.structured.VerifyOutput;

/**
 * 验证阶段：确定性冒烟检查 + 运维 Agent 重新执行复现步骤，双重确认是否真的修好。
 *
 * <p>两条硬性约束：
 * <ul>
 *   <li>先做与模型无关的 HTTP 冒烟检查（策略配置了 baseUrl 时），应用起不来直接判失败；</li>
 *   <li>模型结论必须带<b>证据</b>与<b>置信度</b>；不达标返回 FAILED，编排器会回到修复阶段，
 *       而不是让「可能修好了」直接进入 PR。</li>
 * </ul>
 */
@Component
public class VerifyStage implements BugFixStageHandler {

    /** 验证结论的最低置信度。 */
    static final double MIN_CONFIDENCE = 0.6;

    private final BugFixAgentPort agent;
    private final BugFixCheckPort checks;
    private final BugFixPromptTemplates prompts;

    public VerifyStage(BugFixAgentPort agent, BugFixCheckPort checks,
                       BugFixPromptTemplates prompts) {
        this.agent = agent;
        this.checks = checks;
        this.prompts = prompts;
    }

    @Override
    public BugFixStage stage() {
        return BugFixStage.VERIFY;
    }

    @Override
    public BugFixStageResult execute(BugFixRun run, BugFixPolicy policy) {
        // 闸门 1：确定性冒烟检查，不允许模型「看到页面正常」就通过
        BugFixCheckPort.CheckResult smoke = checks.smoke(run, policy);
        if (!smoke.passed()) {
            return BugFixStageResult.failed("测试环境冒烟检查未通过：" + smoke.summary()
                    + (smoke.detail() == null || smoke.detail().isBlank() ? ""
                    : "\n" + AnalyzeStage.clip(smoke.detail(), 1500)));
        }
        String prompt = prompts.render("verify", Map.of(
                "testEnv", AnalyzeStage.safe(policy.testEnv()),
                "fixSha", AnalyzeStage.safe(run.fixSha()),
                "issueTitle", AnalyzeStage.safe(run.issueTitle()),
                "analysis", FixStage.untrusted(AnalyzeStage.clip(run.analysis(), 6000)),
                "fixSummary", FixStage.untrusted(AnalyzeStage.clip(run.fixSummary(), 4000)),
                "smoke", AnalyzeStage.safe(smoke.summary())));
        VerifyOutput output;
        try {
            output = agent.runStructured(run, prompt, VerifyOutput.class);
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("验证 Agent 不可用：" + AnalyzeStage.message(e));
        }
        if (output == null) {
            return BugFixStageResult.blocked("验证 Agent 未返回结构化结论，请人工确认");
        }
        String evidence = BugFixOutputValidation.text(output.evidence());
        if (!Boolean.TRUE.equals(output.fixed())) {
            return BugFixStageResult.failed("验证未通过：Bug 仍然存在"
                    + (evidence.isBlank() ? "" : "（" + AnalyzeStage.clip(evidence, 300) + "）"));
        }
        if (evidence.isBlank()) {
            return BugFixStageResult.blocked("验证结论缺少证据，已停在 PR 创建前");
        }
        double confidence = BugFixOutputValidation.confidence(output.confidence());
        if (confidence < MIN_CONFIDENCE) {
            return BugFixStageResult.blocked("验证结论置信度不足（"
                    + ReproduceStage.percent(confidence) + "），请人工确认后再提 PR");
        }
        String risk = BugFixOutputValidation.text(output.remainingRisk());
        List<String> steps = output.steps() == null ? List.of() : output.steps();
        StringBuilder detail = new StringBuilder("确定性冒烟：").append(smoke.summary());
        if (!steps.isEmpty()) {
            detail.append("\n\n验证步骤：\n").append(AnalyzeStage.bullets(steps));
        }
        detail.append("\n\n证据：\n").append(evidence);
        if (!risk.isBlank()) {
            detail.append("\n\n剩余风险：\n").append(risk);
        }
        return BugFixStageResult.ok("验证通过，Bug 已解决（置信度 "
                + ReproduceStage.percent(confidence) + "）", detail.toString());
    }
}
