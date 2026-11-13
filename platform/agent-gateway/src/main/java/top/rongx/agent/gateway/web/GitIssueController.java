package top.rongx.agent.gateway.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueService;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * 对话页「任务」Tab 的 Issue 任务接口（与 Agent 工具共用同一份数据）。
 *
 * <p>列表 / 详情 / 新建 / 编辑 / 关闭重开 / 评论 / 删除 / 手动同步。
 * 已绑定远端的任务由 {@link GitIssueService} 按集成配置双向同步。
 */
@RestController
@RequestMapping("/api/v1/git-issues")
public class GitIssueController {

    private final GitIssueService issues;
    private final CurrentUser currentUser;

    public GitIssueController(GitIssueService issues, CurrentUser currentUser) {
        this.issues = issues;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<GitIssue> list(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String keyword) {
        return issues.list(currentUser.requireUserId(), provider, state, keyword);
    }

    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("open", issues.openCount(currentUser.requireUserId()));
    }

    @GetMapping("/{id}")
    public GitIssue get(@PathVariable String id) {
        return issues.get(currentUser.requireUserId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GitIssue create(@RequestBody Map<String, Object> body) {
        return issues.create(currentUser.requireUserId(), draft(body));
    }

    @PutMapping("/{id}")
    public GitIssue update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return issues.update(currentUser.requireUserId(), id, draft(body));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        issues.delete(currentUser.requireUserId(), id);
        return Map.of("deleted", true);
    }

    @PostMapping("/{id}/close")
    public GitIssue close(@PathVariable String id) {
        return issues.setState(currentUser.requireUserId(), id, "CLOSED");
    }

    @PostMapping("/{id}/reopen")
    public GitIssue reopen(@PathVariable String id) {
        return issues.setState(currentUser.requireUserId(), id, "OPEN");
    }

    @PostMapping("/{id}/sync")
    public GitIssue sync(@PathVariable String id) {
        return issues.sync(currentUser.requireUserId(), id);
    }

    @GetMapping("/{id}/comments")
    public List<GitIssueComment> comments(@PathVariable String id) {
        return issues.comments(currentUser.requireUserId(), id);
    }

    @PostMapping("/{id}/comments")
    public GitIssueComment comment(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String text = str(body, "body");
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评论内容不能为空");
        }
        return issues.comment(currentUser.requireUserId(), id, text);
    }

    @DeleteMapping("/{id}/comments/{commentId}")
    public Map<String, Object> deleteComment(@PathVariable String id, @PathVariable String commentId) {
        issues.deleteComment(currentUser.requireUserId(), id, commentId);
        return Map.of("deleted", true);
    }

    private static GitIssue.Draft draft(Map<String, Object> body) {
        String title = str(body, "title");
        if (title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务标题不能为空");
        }
        return new GitIssue.Draft(
                str(body, "projectId"),
                str(body, "provider"),
                str(body, "repo"),
                title,
                str(body, "body"),
                str(body, "state"),
                str(body, "kind"),
                str(body, "labels"),
                str(body, "assignee"));
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
