package top.rongx.agent.gateway.bugfix.stage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixAgentPort;
import top.rongx.agent.domain.bugfix.BugFixCheckPort;
import top.rongx.agent.domain.bugfix.BugFixEdit;
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
import top.rongx.agent.gateway.bugfix.structured.FixOutput;

/**
 * 修复阶段：修复 Agent 输出受限补丁，平台在基线提交上应用并提交到独立分支。
 *
 * <p>三道闸门，任何一道不过都不会进入部署：
 * <ol>
 *   <li>补丁格式：只允许精确片段替换/新建完整文件，路径与凭证文件由 infra 层拒绝；</li>
 *   <li>改动范围：实际改动文件必须落在分析阶段确认的 targetFiles 内，防止顺手改无关代码；</li>
 *   <li>确定性构建/测试：策略配置了命令时在临时 worktree 执行，失败直接判 FAILED，
 *       不把「编译不过」的提交部署到测试环境。</li>
 * </ol>
 */
@Component
public class FixStage implements BugFixStageHandler {

    /** 目标文件为空（模型未给出）时不做过严的范围校验，但仍要求真实改动。 */
    static final int MAX_CHANGED_FILES = 15;

    private final BugFixAgentPort agent;
    private final BugFixGitPort git;
    private final BugFixJson json;
    private final BugFixCheckPort checks;
    private final BugFixPromptTemplates prompts;

    public FixStage(BugFixAgentPort agent, BugFixGitPort git, BugFixJson json,
                    BugFixCheckPort checks, BugFixPromptTemplates prompts) {
        this.agent = agent;
        this.git = git;
        this.json = json;
        this.checks = checks;
        this.prompts = prompts;
    }

    @Override
    public BugFixStage stage() {
        return BugFixStage.FIX;
    }

    @Override
    public BugFixStageResult execute(BugFixRun run, BugFixPolicy policy) {
        String prompt = prompts.render("fix", Map.of(
                "issueTitle", AnalyzeStage.safe(run.issueTitle()),
                "issueBody", untrusted(AnalyzeStage.clip(run.issueBody(), 6000)),
                "baseSha", AnalyzeStage.safe(run.baseSha()),
                "branch", AnalyzeStage.safe(run.branch()),
                "analysis", untrusted(AnalyzeStage.clip(analysisForPrompt(run), 8000))));
        FixOutput output;
        try {
            output = agent.runStructured(run, prompt, FixOutput.class);
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("修复 Agent 不可用：" + AnalyzeStage.message(e));
        }
        if (output == null) {
            return BugFixStageResult.blocked("修复 Agent 未返回结构化补丁");
        }
        List<BugFixEdit> edits;
        try {
            edits = BugFixOutputValidation.edits(output);
        } catch (IllegalArgumentException e) {
            return BugFixStageResult.blocked("补丁格式不合法：" + e.getMessage());
        }
        if (edits.isEmpty()) {
            return BugFixStageResult.blocked("修复 Agent 未产出文件改动（edits/files 均为空）");
        }
        if (edits.stream().map(BugFixEdit::path).distinct().count() > MAX_CHANGED_FILES) {
            return BugFixStageResult.blocked("单次修复改动文件过多（超过 " + MAX_CHANGED_FILES
                    + " 个），请拆分为更聚焦的修复");
        }
        // 闸门 2（前置）：提交前先校验补丁路径是否落在分析确认的目标文件内。
        // 原来先 commit 再校验，越界改动会在分支上留下一个提交，重试还会叠加上去。
        List<String> targets = targetFiles(run);
        if (!targets.isEmpty()) {
            List<String> unexpected = edits.stream()
                    .map(BugFixEdit::path)
                    .filter(path -> targets.stream().noneMatch(target -> sameFile(target, path)))
                    .distinct()
                    .toList();
            if (!unexpected.isEmpty()) {
                return BugFixStageResult.blocked("修复改动了分析未确认的文件，已拒绝提交："
                        + String.join(", ", unexpected)
                        + "；如确属必要，请在分析结论中补充 targetFiles 后重试");
            }
        }
        String sha;
        try {
            String title = AnalyzeStage.safe(run.issueTitle()).replaceAll("\\s+", " ").trim();
            sha = git.commitFix(run, edits,
                    "fix(bug): " + (title.isBlank() ? run.id() : title));
        } catch (IllegalArgumentException e) {
            return BugFixStageResult.blocked("补丁未通过校验：" + e.getMessage());
        } catch (RuntimeException e) {
            return BugFixStageResult.failed("提交修复失败：" + AnalyzeStage.message(e));
        }
        // 闸门 2（提交后复核）：实际改动范围必须与分析结论一致
        if (!targets.isEmpty()) {
            List<String> changed;
            try {
                changed = git.changedFiles(run, sha);
            } catch (RuntimeException e) {
                return BugFixStageResult.blocked("无法读取修复改动文件列表："
                        + AnalyzeStage.message(e));
            }
            List<String> unexpected = changed.stream()
                    .filter(path -> targets.stream().noneMatch(target -> sameFile(target, path)))
                    .toList();
            if (!unexpected.isEmpty()) {
                return BugFixStageResult.blocked("修复改动了分析未确认的文件，已停在部署前："
                        + String.join(", ", unexpected)
                        + "；如确属必要，请在分析结论中补充 targetFiles 后重试");
            }
        }
        // 闸门 3：确定性构建/测试（未配置命令时跳过，不让自动修复因缺少配置而卡死）
        BugFixCheckPort.CheckResult check = checks.build(run, policy, sha);
        if (!check.passed()) {
            return BugFixStageResult.failed("确定性构建/测试未通过：" + check.summary()
                    + (check.detail() == null || check.detail().isBlank() ? ""
                    : "\n" + AnalyzeStage.clip(check.detail(), 2000)));
        }
        String summary = BugFixOutputValidation.text(output.summary());
        String evidence = (summary.isBlank() ? "修复已提交到 " + run.branch()
                : summary + "\n\n修复已提交到 " + run.branch())
                + (check.skipped() ? "" : "\n\n确定性检查：" + check.summary());
        return BugFixStageResult.ok("修复已提交到 " + run.branch() + "（" + AnalyzeStage.shortSha(sha)
                        + "）", evidence)
                .withFixSha(sha)
                .withFixSummary(summary.isBlank()
                        ? "改动 " + edits.size() + " 处：" + changedPaths(edits) : summary);
    }

    private static String analysisForPrompt(BugFixRun run) {
        String structured = run.analysisJson();
        String raw = run.analysis();
        if (structured == null || structured.isBlank()) {
            return raw;
        }
        return "结构化分析（JSON）：\n" + AnalyzeStage.clip(structured, 6000)
                + "\n\n完整分析：\n" + AnalyzeStage.clip(raw, 4000);
    }

    /** 把不可信文本（Issue 正文、日志、上一阶段结论）包进数据边界，提示模型只当资料。 */
    public static String untrusted(String text) {
        return "<untrusted-data>\n" + (text == null ? "" : text) + "\n</untrusted-data>";
    }

    /** 分析阶段确认的目标文件；缺失/无法解析时返回空列表（不阻断修复）。 */
    List<String> targetFiles(BugFixRun run) {
        Optional<AnalyzeOutput> parsed = json.read(run.analysisJson(), AnalyzeOutput.class);
        if (parsed.isEmpty()) {
            return List.of();
        }
        try {
            return BugFixOutputValidation.paths(parsed.get().targetFiles());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private static String changedPaths(List<BugFixEdit> edits) {
        return edits.stream().map(BugFixEdit::path).distinct()
                .reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static boolean sameFile(String target, String changed) {
        return normalize(target).equals(normalize(changed));
    }

    private static String normalize(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        return value;
    }
}
