package top.rongx.agent.gateway.bugfix.stage;

import java.util.Map;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixGitPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.gateway.bugfix.BugFixPromptTemplates;
import top.rongx.agent.gateway.bugfix.BugFixStageHandler;

/**
 * PR 阶段：推送修复分支并创建 PR/MR，等待人工合并。
 *
 * <p>平台永远不自动合并；关闭自动建 PR 时保留分支并停在 BLOCKED，用户可手动提 PR。
 */
@Component
public class PullRequestStage implements BugFixStageHandler {

    private final BugFixGitPort git;
    private final BugFixPromptTemplates prompts;

    public PullRequestStage(BugFixGitPort git, BugFixPromptTemplates prompts) {
        this.git = git;
        this.prompts = prompts;
    }

    @Override
    public BugFixStage stage() {
        return BugFixStage.PULL_REQUEST;
    }

    @Override
    public BugFixStageResult execute(BugFixRun run, BugFixPolicy policy) {
        if (!policy.autoCreatePr()) {
            return BugFixStageResult.blocked("已生成修复分支 " + run.branch() + "，请人工创建 PR");
        }
        if (run.fixSha() == null || run.fixSha().isBlank()) {
            return BugFixStageResult.blocked("缺少修复提交，无法创建 PR");
        }
        String title = "fix: " + AnalyzeStage.safe(run.issueTitle());
        // 模型输出可能复述日志中的 token/密码：评论与 PR 描述离开平台前统一脱敏
        String body = top.rongx.agent.gateway.bugfix.BugFixRedactor.redact(
                prompts.render("pull-request", Map.of(
                        "issueNumber", AnalyzeStage.safe(run.issueNumber()),
                        "baseSha", AnalyzeStage.safe(run.baseSha()),
                        "branch", AnalyzeStage.safe(run.branch()),
                        "fixSha", AnalyzeStage.safe(run.fixSha()),
                        "analysis", AnalyzeStage.clip(run.analysis(), 4000),
                        "fixSummary", AnalyzeStage.clip(run.fixSummary(), 2000),
                        "evidence", AnalyzeStage.clip(run.evidence(), 2000))));
        try {
            String url = git.openPullRequest(run, title, body);
            return BugFixStageResult.ok("PR 已创建，等待人工合并", url).withPrUrl(url);
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("创建 PR 失败（分支已保留）：" + AnalyzeStage.message(e));
        }
    }
}
