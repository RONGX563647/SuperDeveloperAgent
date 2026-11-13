package top.rongx.agent.gateway.gitissue;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueIntegration;
import top.rongx.agent.domain.gitissue.GitIssueIntegrationRepository;
import top.rongx.agent.domain.gitissue.GitIssueRepository;
import top.rongx.agent.domain.gitissue.GitIssueService;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;

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
    private final ProjectRepository projects;

    public GitIssueServiceImpl(GitIssueRepository issues,
                               GitIssueIntegrationRepository integrations,
                               GitIssueRemoteClient remote,
                               ProjectRepository projects) {
        this.issues = issues;
        this.integrations = integrations;
        this.remote = remote;
        this.projects = projects;
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
        // 任务归属项目时自动使用项目托管的仓库，不再让用户手选平台/仓库
        GitIssue.Draft resolved = resolveRemote(draft, null);
        String repo = GitIssueRemoteClient.cleanRepo(resolved.repo());
        GitIssue issue = new GitIssue(newId("task"), userId, resolved.projectId(), resolved.provider(),
                repo, "", resolved.title(), resolved.body(), resolved.state(), resolved.labels(), "",
                "PLATFORM", "LOCAL", "", resolved.assignee(), now, now);
        if (shouldPush(resolved.provider(), repo)) {
            issue = pushCreate(issue, userId);
        } else if (!resolved.provider().isBlank() && !repo.isBlank()) {
            issue = copy(issue, "", "", issue.state(), "LOCAL",
                    "未启用 " + resolved.provider() + " Issue 同步配置", "PLATFORM");
        }
        return issues.save(issue);
    }

    @Override
    public GitIssue update(String userId, String id, GitIssue.Draft draft) {
        GitIssue current = require(userId, id);
        if (draft.title().isBlank()) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        GitIssue.Draft resolved = resolveRemote(draft, current);
        String provider = resolved.provider().isBlank() ? current.provider() : resolved.provider();
        String repo = GitIssueRemoteClient.cleanRepo(
                resolved.repo().isBlank() ? current.repo() : resolved.repo());
        boolean repoChanged = !provider.equalsIgnoreCase(current.provider())
                || !repo.equalsIgnoreCase(current.repo());
        String number = repoChanged ? "" : current.number();
        String url = repoChanged ? "" : current.url();
        long now = System.currentTimeMillis();
        GitIssue updated = new GitIssue(current.id(), current.userId(), resolved.projectId(),
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

    // ---------------------------------------------------------------- 仓库解析

    /**
     * 解析任务对应的 Git 仓库：显式指定优先，否则取项目托管仓库。
     * 项目第一次关联或换绑时自动推导 provider / owner/repo，用户无需再选平台。
     */
    private GitIssue.Draft resolveRemote(GitIssue.Draft draft, GitIssue current) {
        String projectId = !draft.projectId().isBlank()
                ? draft.projectId()
                : (current == null ? "" : current.projectId());
        boolean projectChanged = current == null || !Objects.equals(projectId, current.projectId());
        boolean repoMissing = draft.provider().isBlank() && draft.repo().isBlank();
        // 只有项目新绑定、或本地任务还没绑仓库时才自动推导；已有绑定保持不变
        boolean needDerive = repoMissing && !projectId.isBlank()
                && (projectChanged || current == null || current.provider().isBlank());
        if (!needDerive) {
            return new GitIssue.Draft(projectId, draft.provider(),
                    GitIssueRemoteClient.cleanRepo(draft.repo()), draft.title(), draft.body(),
                    draft.state(), draft.labels(), draft.assignee());
        }
        return projects.findById(projectId)
                .filter(p -> p.repoUrl() != null && !p.repoUrl().isBlank())
                .map(p -> new GitIssue.Draft(projectId, providerOf(p),
                        GitIssueRemoteClient.cleanRepo(p.repoUrl()), draft.title(), draft.body(),
                        draft.state(), draft.labels(), draft.assignee()))
                .filter(d -> !d.provider().isBlank())
                .orElseGet(() -> new GitIssue.Draft(projectId, draft.provider(),
                        GitIssueRemoteClient.cleanRepo(draft.repo()), draft.title(), draft.body(),
                        draft.state(), draft.labels(), draft.assignee()));
    }

    private static String providerOf(Project project) {
        String type = project.repoType() == null ? "" : project.repoType().toUpperCase(Locale.ROOT);
        if (type.contains("GITEE")) {
            return "GITEE";
        }
        if (type.contains("GITLAB")) {
            return "GITLAB";
        }
        if (type.contains("GITHUB")) {
            return "GITHUB";
        }
        String url = project.repoUrl() == null ? "" : project.repoUrl().toLowerCase(Locale.ROOT);
        if (url.contains("gitee.com")) {
            return "GITEE";
        }
        if (url.contains("gitlab")) {
            return "GITLAB";
        }
        return url.contains("github") ? "GITHUB" : "";
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
