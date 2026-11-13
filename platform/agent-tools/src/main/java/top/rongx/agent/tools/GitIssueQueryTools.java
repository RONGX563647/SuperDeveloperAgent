package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueService;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 任务页只读工具：让 Agent 能读取平台 / Git 双向同步的 Issue 任务。
 *
 * <p>数据与对话页「任务」Tab 完全一致（同一个 {@link GitIssueService}），
 * 因此 Agent 看到的列表、状态、评论与用户界面同步。
 */
@Component
public class GitIssueQueryTools {

    private final GitIssueService issues;

    public GitIssueQueryTools(GitIssueService issues) {
        this.issues = issues;
    }

    @Tool(name = "list_git_issues",
            description = """
                    列出平台任务页的 Issue 任务（含从 Gitee/GitHub/GitLab 同步来的工单）。
                    只读。可按关键词、状态(OPEN/CLOSED)、平台(GITEE/GITHUB/GITLAB)过滤。
                    用户提到「任务」「工单」「issue」「bug 单」时先用它查看。""",
            readOnly = true, concurrencySafe = true)
    public String listGitIssues(
            @ToolParam(name = "keyword", description = "标题/描述/仓库关键词，可空",
                    required = false) String keyword,
            @ToolParam(name = "state", description = "OPEN 或 CLOSED，可空",
                    required = false) String state,
            @ToolParam(name = "provider", description = "GITEE / GITHUB / GITLAB，可空",
                    required = false) String provider,
            ProjectContext ctx) {
        List<GitIssue> list = issues.list(ctx.userId(), provider, state, keyword);
        if (list.isEmpty()) {
            return "当前没有匹配的任务。evidence: user:" + ctx.userId();
        }
        return list.stream()
                .map(i -> "- [%s/%s] %s  %s%s  %s/%s#%s  labels:%s  id:%s"
                        .formatted(i.state(), i.kind(), i.title(), i.syncStatus(),
                                i.syncError().isBlank() ? "" : "(" + i.syncError() + ")",
                                i.provider(), i.repo().isBlank() ? "-" : i.repo(),
                                i.number().isBlank() ? "-" : i.number(),
                                i.labels().isBlank() ? "-" : i.labels(), i.id()))
                .collect(Collectors.joining("\n", "tasks:\n", "\nevidence: user:" + ctx.userId()));
    }

    @Tool(name = "get_git_issue",
            description = "读取单个 Issue 任务的详情与评论。只读。需要操作任务前先确认其内容。",
            readOnly = true, concurrencySafe = true)
    public String getGitIssue(
            @ToolParam(name = "issue_id", description = "任务 ID，形如 task-xxxx") String issueId,
            ProjectContext ctx) {
        GitIssue issue = issues.get(ctx.userId(), issueId);
        List<GitIssueComment> comments = issues.comments(ctx.userId(), issueId);
        String body = comments.isEmpty() ? "（暂无评论）" : comments.stream()
                .map(c -> "[%s] %s: %s".formatted(c.source(), c.author(), c.body()))
                .collect(Collectors.joining("\n"));
        return """
                id: %s
                title: %s
                state: %s
                kind: %s
                provider/repo/number: %s / %s / %s
                sync: %s %s
                url: %s
                labels: %s
                project: %s
                description:
                %s
                comments:
                %s
                evidence: task:%s
                """.formatted(issue.id(), issue.title(), issue.state(), issue.kind(), issue.provider(),
                issue.repo(), issue.number().isBlank() ? "-" : issue.number(),
                issue.syncStatus(), issue.syncError(), issue.url(),
                issue.labels().isBlank() ? "-" : issue.labels(),
                issue.projectId().isBlank() ? "-" : issue.projectId(),
                issue.body().isBlank() ? "（无）" : issue.body(), body, issue.id());
    }
}
