package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueService;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 任务页写操作工具：新建 / 编辑 / 评论 / 关闭 / 重开 / 同步。
 *
 * <p>只注册在对话 Agent 的写工具面；生产只读会话里根本不存在这些工具。
 * 已绑定 Git 的任务会按「设置 → 高级设置 → Git 应用授权」的配置回写远端。
 */
@Component
public class GitIssueCommandTools {

    private final GitIssueService issues;

    public GitIssueCommandTools(GitIssueService issues) {
        this.issues = issues;
    }

    @Tool(name = "create_git_issue", description = "Create a task-page issue.",
            readOnly = false, concurrencySafe = false)
    public String createGitIssue(
            @ToolParam(name = "title", description = "任务标题") String title,
            @ToolParam(name = "body", description = "任务描述（支持 Markdown），可空",
                    required = false) String body,
            @ToolParam(name = "provider", description = "GITEE / GITHUB / GITLAB，可空",
                    required = false) String provider,
            @ToolParam(name = "repo", description = "owner/repo，可空", required = false) String repo,
            @ToolParam(name = "kind", description = "分类：BUG 或 FEAT（需求），可空", required = false)
            String kind,
            @ToolParam(name = "priority", description = "优先级 P0 / P1 / P2，可空（默认 P2）",
                    required = false) String priority,
            @ToolParam(name = "labels", description = "标签，逗号分隔，可空", required = false)
            String labels,
            ProjectContext ctx) {
        GitIssue issue = issues.create(ctx.userId(), new GitIssue.Draft(ctx.projectId(),
                provider, repo, title, body, "OPEN", kind, priority, labels, ""));
        return "已创建任务 %s（%s，同步状态 %s%s）。evidence: task:%s".formatted(
                issue.id(), issue.title(), issue.syncStatus(),
                issue.syncError().isBlank() ? "" : "：" + issue.syncError(), issue.id());
    }

    @Tool(name = "update_git_issue", description = "Update an issue title, body or labels.",
            readOnly = false, concurrencySafe = false)
    public String updateGitIssue(
            @ToolParam(name = "issue_id", description = "任务 ID") String issueId,
            @ToolParam(name = "title", description = "新标题，可空", required = false) String title,
            @ToolParam(name = "body", description = "新描述，可空", required = false) String body,
            @ToolParam(name = "kind", description = "新分类：BUG / FEAT，可空", required = false)
            String kind,
            @ToolParam(name = "priority", description = "新优先级 P0 / P1 / P2，可空", required = false)
            String priority,
            @ToolParam(name = "labels", description = "新标签，可空", required = false) String labels,
            ProjectContext ctx) {
        GitIssue current = issues.get(ctx.userId(), issueId);
        GitIssue updated = issues.update(ctx.userId(), issueId, new GitIssue.Draft(
                current.projectId(), current.provider(), current.repo(),
                blank(title) ? current.title() : title,
                blank(body) ? current.body() : body,
                current.state(),
                blank(kind) ? current.kind() : kind,
                blank(priority) ? current.priority() : priority,
                blank(labels) ? current.labels() : labels,
                current.assignee()));
        return "已更新任务 %s（同步状态 %s）。evidence: task:%s"
                .formatted(updated.id(), updated.syncStatus(), updated.id());
    }

    @Tool(name = "comment_git_issue", description = "Add a comment to an issue.",
            readOnly = false, concurrencySafe = false)
    public String commentGitIssue(
            @ToolParam(name = "issue_id", description = "任务 ID") String issueId,
            @ToolParam(name = "body", description = "评论内容") String body,
            ProjectContext ctx) {
        GitIssueComment comment = issues.comment(ctx.userId(), issueId, body);
        return "已评论任务 %s（评论 %s）。evidence: task:%s"
                .formatted(issueId, comment.id(), issueId);
    }

    @Tool(name = "close_git_issue", description = "Close an issue and sync to Git.",
            readOnly = false, concurrencySafe = false)
    public String closeGitIssue(
            @ToolParam(name = "issue_id", description = "任务 ID") String issueId,
            ProjectContext ctx) {
        GitIssue issue = issues.setState(ctx.userId(), issueId, "CLOSED");
        return "已关闭任务 %s（同步状态 %s）。evidence: task:%s"
                .formatted(issue.id(), issue.syncStatus(), issue.id());
    }

    @Tool(name = "reopen_git_issue", description = "Reopen an issue and sync to Git.",
            readOnly = false, concurrencySafe = false)
    public String reopenGitIssue(
            @ToolParam(name = "issue_id", description = "任务 ID") String issueId,
            ProjectContext ctx) {
        GitIssue issue = issues.setState(ctx.userId(), issueId, "OPEN");
        return "已重开任务 %s（同步状态 %s）。evidence: task:%s"
                .formatted(issue.id(), issue.syncStatus(), issue.id());
    }

    @Tool(name = "sync_git_issue", description = "Retry pushing an issue to Git.",
            readOnly = false, concurrencySafe = false)
    public String syncGitIssue(
            @ToolParam(name = "issue_id", description = "任务 ID") String issueId,
            ProjectContext ctx) {
        GitIssue issue = issues.sync(ctx.userId(), issueId);
        return "已重试同步任务 %s（状态 %s%s）。evidence: task:%s".formatted(
                issue.id(), issue.syncStatus(),
                issue.syncError().isBlank() ? "" : "：" + issue.syncError(), issue.id());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
