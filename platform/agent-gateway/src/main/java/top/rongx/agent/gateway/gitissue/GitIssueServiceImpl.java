package top.rongx.agent.gateway.gitissue;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueIntegration;
import top.rongx.agent.domain.gitissue.GitIssueIntegrationRepository;
import top.rongx.agent.domain.gitissue.GitIssueRepository;
import top.rongx.agent.domain.gitissue.GitIssueService;

/**
 * Issue 任务服务实现：本地 CRUD + 按集成配置把变更推送到 Git。
 *
 * <p>远端失败不会阻断主流程：任务照常落库，只把 {@code syncStatus} 标成 FAILED
 * 并记录原因，用户可在任务页重试「同步」。
 */
@Service
public class GitIssueServiceImpl implements GitIssueService {

    private static final Logger log = LoggerFactory.getLogger(GitIssueServiceImpl.class);

    private final GitIssueRepository issues;
    private final GitIssueIntegrationRepository integrations;
    private final GitIssueRemoteClient remote;

    public GitIssueServiceImpl(GitIssueRepository issues,
                               GitIssueIntegrationRepository integrations,
                               GitIssueRemoteClient remote) {
        this.issues = issues;
        this.integrations = integrations;
        this.remote = remote;
    }

    @Override
    public List<GitIssue> list(String userId, String provider, String state, String keyword) {
        return issues.list(userId, provider, state, keyword);
    }

    @Override
    public GitIssue get(String userId, String id) {
        return require(userId, id);
    }

    @Override
    public GitIssue create(String userId, GitIssue.Draft draft) {
        if (draft.title().isBlank()) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        long now = System.currentTimeMillis();
        String repo = GitIssueRemoteClient.cleanRepo(draft.repo());
        GitIssue issue = new GitIssue(newId("task"), userId, draft.projectId(), draft.provider(),
                repo, "", draft.title(), draft.body(), draft.state(), draft.labels(), "",
                "PLATFORM", "LOCAL", "", draft.assignee(), now, now);
        if (shouldPush(draft.provider(), repo)) {
            issue = pushCreate(issue, userId);
        } else if (!draft.provider().isBlank() && !repo.isBlank()) {
            issue = copy(issue, "", "", issue.state(), "LOCAL",
                    "未启用 " + draft.provider() + " Issue 同步配置", "PLATFORM");
        }
        return issues.save(issue);
    }

    @Override
    public GitIssue update(String userId, String id, GitIssue.Draft draft) {
        GitIssue current = require(userId, id);
        if (draft.title().isBlank()) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        String provider = draft.provider().isBlank() ? current.provider() : draft.provider();
        String repo = GitIssueRemoteClient.cleanRepo(
                draft.repo().isBlank() ? current.repo() : draft.repo());
        boolean repoChanged = !provider.equalsIgnoreCase(current.provider())
                || !repo.equalsIgnoreCase(current.repo());
        String number = repoChanged ? "" : current.number();
        String url = repoChanged ? "" : current.url();
        long now = System.currentTimeMillis();
        GitIssue updated = new GitIssue(current.id(), current.userId(),
                draft.projectId().isBlank() ? current.projectId() : draft.projectId(),
                provider, repo, number, draft.title(), draft.body(), draft.state(),
                draft.labels(), url, "PLATFORM", repoChanged ? "LOCAL" : current.syncStatus(),
                repoChanged ? "" : current.syncError(),
                draft.assignee().isBlank() ? current.assignee() : draft.assignee(),
                current.createdAt(), now);
        if (shouldPush(provider, repo)) {
            updated = updated.bound() ? pushUpdate(updated, userId) : pushCreate(updated, userId);
        } else if (!provider.isBlank() && !repo.isBlank() && !updated.bound()) {
            updated = copy(updated, "", "", updated.state(), "LOCAL",
                    "未启用 " + provider + " Issue 同步配置", "PLATFORM");
        }
        return issues.save(updated);
    }

    @Override
    public void delete(String userId, String id) {
        require(userId, id);
        issues.delete(id, userId);
    }

    @Override
    public GitIssue setState(String userId, String id, String state) {
        GitIssue current = require(userId, id);
        GitIssue.Draft draft = new GitIssue.Draft(current.projectId(), current.provider(),
                current.repo(), current.title(), current.body(), state, current.labels(),
                current.assignee());
        return update(userId, id, draft);
    }

    @Override
    public List<GitIssueComment> comments(String userId, String issueId) {
        require(userId, issueId);
        return issues.comments(issueId);
    }

    @Override
    public GitIssueComment comment(String userId, String issueId, String body) {
        GitIssue issue = require(userId, issueId);
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("评论内容不能为空");
        }
        GitIssueComment comment = new GitIssueComment(newId("c"), issueId, userId,
                body.trim(), "PLATFORM", System.currentTimeMillis());
        issues.addComment(comment);
        if (issue.bound() && pushEnabled(issue.provider())) {
            try {
                GitIssueRemoteClient.Credentials credentials = credentials(userId, issue);
                remote.comment(credentials, issue.number(), comment.body());
                markSync(issue, "SYNCED", "");
            } catch (RuntimeException e) {
                log.warn("Issue 评论回写失败（task={}）：{}", issue.id(), e.getMessage());
                markSync(issue, "FAILED", message(e));
            }
        }
        return comment;
    }

    @Override
    public void deleteComment(String userId, String issueId, String commentId) {
        require(userId, issueId);
        issues.deleteComment(commentId, issueId);
    }

    @Override
    public GitIssue sync(String userId, String id) {
        GitIssue issue = require(userId, id);
        if (issue.provider().isBlank() || issue.repo().isBlank()) {
            throw new IllegalArgumentException("任务未配置 Git 仓库，无法同步");
        }
        GitIssue pushed = issue.bound() ? pushUpdate(issue, userId) : pushCreate(issue, userId);
        return issues.save(pushed);
    }

    @Override
    public long openCount(String userId) {
        return issues.countOpen(userId);
    }

    // ---------------------------------------------------------------- 同步

    private boolean shouldPush(String provider, String repo) {
        if (provider == null || provider.isBlank() || repo == null || repo.isBlank()) {
            return false;
        }
        GitIssueIntegration config = integrations.find(provider).orElse(null);
        return config != null && config.pushEnabled();
    }

    private boolean pushEnabled(String provider) {
        return integrations.find(provider).map(GitIssueIntegration::pushEnabled).orElse(false);
    }

    private GitIssue pushCreate(GitIssue issue, String userId) {
        try {
            GitIssueRemoteClient.Credentials credentials = credentials(userId, issue);
            GitIssueRemoteClient.RemoteIssue remoteIssue = remote.create(credentials,
                    issue.title(), issue.body(), issue.labels());
            return copy(issue, remoteIssue.number(), remoteIssue.url(), remoteIssue.state(),
                    "SYNCED", "", "PLATFORM");
        } catch (RuntimeException e) {
            log.warn("Issue 创建远端失败（task={}）：{}", issue.id(), e.getMessage());
            return copy(issue, issue.number(), issue.url(), issue.state(), "FAILED",
                    message(e), "PLATFORM");
        }
    }

    private GitIssue pushUpdate(GitIssue issue, String userId) {
        try {
            GitIssueRemoteClient.Credentials credentials = credentials(userId, issue);
            remote.update(credentials, issue.number(), issue.title(), issue.body(),
                    issue.state(), issue.labels());
            return copy(issue, issue.number(), issue.url(), issue.state(), "SYNCED", "",
                    "PLATFORM");
        } catch (RuntimeException e) {
            log.warn("Issue 更新远端失败（task={}）：{}", issue.id(), e.getMessage());
            return copy(issue, issue.number(), issue.url(), issue.state(), "FAILED",
                    message(e), "PLATFORM");
        }
    }

    private GitIssueRemoteClient.Credentials credentials(String userId, GitIssue issue) {
        return remote.credentials(userId, issue.provider(), issue.repo())
                .orElseThrow(() -> new IllegalStateException(
                        "未绑定 " + issue.provider() + " 账号，无法同步到 Git"));
    }

    private void markSync(GitIssue issue, String status, String error) {
        issues.save(copy(issue, issue.number(), issue.url(), issue.state(), status, error,
                issue.source()));
    }

    private GitIssue require(String userId, String id) {
        return issues.find(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + id));
    }

    private static GitIssue copy(GitIssue issue, String number, String url, String state,
                                 String syncStatus, String syncError, String source) {
        return new GitIssue(issue.id(), issue.userId(), issue.projectId(), issue.provider(),
                issue.repo(), number, issue.title(), issue.body(), state, issue.labels(), url,
                source, syncStatus, syncError, issue.assignee(), issue.createdAt(),
                System.currentTimeMillis());
    }

    private static String message(RuntimeException e) {
        String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static String newId(String prefix) {
        byte[] raw = new byte[5];
        new SecureRandom().nextBytes(raw);
        return prefix + "-" + HexFormat.of().formatHex(raw);
    }
}
