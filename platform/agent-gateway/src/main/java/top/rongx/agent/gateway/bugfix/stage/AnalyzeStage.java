package top.rongx.agent.gateway.bugfix.stage;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixGitPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.gateway.bugfix.BugFixJson;
import top.rongx.agent.gateway.bugfix.BugFixOutputValidation;
import top.rongx.agent.gateway.bugfix.BugFixPromptTemplates;
import top.rongx.agent.gateway.bugfix.BugFixStageHandler;
import top.rongx.agent.gateway.bugfix.structured.AnalyzeOutput;

/**
 * 分析阶段：同步仓库 → 锁定基线提交 → 让只读 Agent 输出「版本范围 / 根因 / 复现步骤 / 目标文件」。
 *
 * <p>基线 sha 由平台从仓库读取，不相信模型给出的提交号；模型只负责分析内容。
 * 分析前会 best-effort 同步远端，避免用过期代码得出错误根因；同步失败只告警，仍用本地基线。
 */
@Component
public class AnalyzeStage implements BugFixStageHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeStage.class);

    /** 分析置信度低于此值时不进入修复：错误根因会让后续所有阶段白跑。 */
    static final double MIN_CONFIDENCE = 0.3;

    private final BugFixAgentPort agent;
    private final BugFixGitPort git;
    private final BugFixJson json;
    private final BugFixPromptTemplates prompts;

    public AnalyzeStage(BugFixAgentPort agent, BugFixGitPort git, BugFixJson json,
                        BugFixPromptTemplates prompts) {
        this.agent = agent;
        this.git = git;
        this.json = json;
        this.prompts = prompts;
    }

    @Override
    public BugFixStage stage() {
        return BugFixStage.ANALYZE;
    }

    @Override
    public BugFixStageResult execute(BugFixRun run, BugFixPolicy policy) {
        try {
            git.sync(run.projectId());
        } catch (RuntimeException e) {
            log.warn("分析前同步仓库失败，继续使用本地基线 project={}：{}", run.projectId(),
                    message(e));
        }
        String baseSha;
        try {
            baseSha = git.headSha(run.projectId());
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("无法读取仓库基线提交：" + message(e));
        }
        if (baseSha == null || baseSha.isBlank()) {
            return BugFixStageResult.blocked("仓库没有可用提交，无法分析版本");
        }
        String prompt = prompts.render("analyze", Map.of(
                "projectId", safe(run.projectId()),
                "issueNumber", safe(run.issueNumber()),
                "issueTitle", safe(run.issueTitle()),
                "issueBody", clip(run.issueBody(), 8000),
                "baseSha", safe(baseSha)));
        AnalyzeOutput output;
        try {
            output = agent.runStructured(run, prompt, AnalyzeOutput.class);
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("分析 Agent 不可用：" + message(e));
        }
        if (output == null) {
            return BugFixStageResult.blocked("分析 Agent 未返回结构化结论");
        }
        String rootCause = BugFixOutputValidation.text(output.rootCause());
        if (rootCause.isBlank()) {
            return BugFixStageResult.blocked("分析结论缺少 rootCause，请补充根因后再重试");
        }
        List<String> targetFiles;
        try {
            targetFiles = BugFixOutputValidation.paths(output.targetFiles());
        } catch (IllegalArgumentException e) {
            return BugFixStageResult.blocked("分析目标文件不合法：" + e.getMessage());
        }
        double confidence = BugFixOutputValidation.confidence(output.confidence());
        if (confidence < MIN_CONFIDENCE) {
            return BugFixStageResult.blocked("分析置信度不足（" + ReproduceStage.percent(confidence)
                    + "），请人工确认根因或补充信息后重试");
        }
        // 结构化结论单独落库：修复阶段据此做改动范围校验，验证阶段据此复述复现步骤，
        // 不依赖模型自然语言里可能被截断/改写的部分。
        String artifact = json.toJson(output);
        String summary = """
                影响版本：%s
                提交范围：%s
                根因：%s
                复现步骤：
                %s
                目标文件：%s
                置信度：%s
                """.formatted(blankAsUnknown(output.version()),
                blankAsUnknown(output.commitRange()), rootCause,
                bullets(output.reproSteps()), bullets(targetFiles),
                ReproduceStage.percent(confidence));
        return BugFixStageResult.ok("分析完成，基线 " + shortSha(baseSha), summary)
                .withAnalysis(summary)
                .withAnalysisJson(artifact)
                .withArtifact(artifact)
                .withBaseSha(baseSha);
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    static String blankAsUnknown(String value) {
        return value == null || value.isBlank() ? "未确定" : value;
    }

    static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(8, sha.length()));
    }

    static String bullets(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "未提供";
        }
        StringBuilder builder = new StringBuilder();
        for (String item : values) {
            if (item != null && !item.isBlank()) {
                builder.append("- ").append(item.strip()).append('\n');
            }
        }
        return builder.isEmpty() ? "未提供" : builder.toString().stripTrailing();
    }

    static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n...(已截断)";
    }

    static String message(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }
}
