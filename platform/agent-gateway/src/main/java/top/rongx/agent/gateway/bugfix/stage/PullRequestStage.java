package top.rongx.agent.gateway.bugfix.stage;

import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixGitPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.gateway.bugfix.BugFixStageHandler;

/**
 * PR 阶段：推送修复分支并创建 PR/MR，等待人工合并。
 *
 * <p>平台永远不自动合并；关闭自动建 PR 时保留分支并停在 BLOCKED，用户可手动提 PR。
 */
@Component
public class PullRequestStage implements BugFixStageHandler {

    private final BugFixGitPort git;

    public PullRequestStage(BugFixGitPort git) {
        this.git = git;
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
        String body = """
                ## 自动修复说明

                - Bug 任务：%s
                - 基线提交：%s
                - 修复分支：%s

                ### 分析结论
                %s

                ### 验证证据
                %s

                > 本 PR 由平台自动修复流程创建，请人工 Review 后合并。
                """.formatted(AnalyzeStage.safe(run.issueNumber()), AnalyzeStage.safe(run.baseSha()),
                run.branch(), AnalyzeStage.clip(run.analysis(), 4000),
                AnalyzeStage.clip(run.evidence(), 2000));
        try {
            String url = git.openPullRequest(run, title, body);
            return BugFixStageResult.ok("PR 已创建，等待人工合并", url).withPrUrl(url);
        } catch (RuntimeException e) {
            return BugFixStageResult.blocked("创建 PR 失败（分支已保留）：" + AnalyzeStage.message(e));
        }
    }
}
