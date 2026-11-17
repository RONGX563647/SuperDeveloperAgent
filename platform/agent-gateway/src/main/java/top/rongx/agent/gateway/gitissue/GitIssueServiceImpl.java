package top.rongx.agent.gateway.gitissue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueIntegration;
import top.rongx.agent.domain.gitissue.GitIssueIntegrationRepository;
import top.rongx.agent.domain.gitissue.GitIssueRepository;
import top.rongx.agent.domain.gitissue.GitIssueService;
import top.rongx.agent.domain.bugfix.BugFixTrigger;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.IntakeException;

/**
 * Issue 任务服务实现：本地 CRUD + 按集成配置把变更推送到 Git。
 *
 * <p>远端失败不会阻断主流程：任务照常落库，只把 {@code syncStatus} 标成 FAILED
 * 并记录原因，用户可在任务页重试「同步」。
 */
@Service
public class GitIssueServiceImpl implements GitIssueService {

    private static final Logger log = LoggerFactory.getLogger(GitIssueServiceImpl.class);
    /** 版本线分支：0.1.0-snapshot → 0.1（与流水线版本号建议保持一致）。 */
    private static final Pattern VERSION_BRANCH =
            Pattern.compile("^(\\d+)\\.(\\d+)\\.0-snapshot$");

    private final GitIssueRepository issues;
    private final GitIssueIntegrationRepository integrations;
    private final GitIssueRemoteClient remote;
    private final BugFixTrigger bugFixTrigger;
    private final ProjectRepository projects;
    private final ProjectRepoLocator repoLocator;

    public GitIssueServiceImpl(GitIssueRepository issues,
                               GitIssueIntegrationRepository integrations,
                               GitIssueRemoteClient remote,
                               BugFixTrigger bugFixTrigger,
                               ProjectRepository projects,
                               ProjectRepoLocator repoLocator) {
        this.issues = issues;
        this.integrations = integrations;
        this.remote = remote;
        this.bugFixTrigger = bugFixTrigger;
        this.projects = projects;
        this.repoLocator = repoLocator;
    }

    @Override
    public List<GitIssue> list(String userId, String provider, String state, String keyword) {
        List<GitIssue> rows = issues.list(userId, provider, state, keyword);
        // 历史任务没有版本号：读取时按当前分支默认值展示（不落库、不动 updatedAt），
        // 用户编辑保存后才会真正写入。
        Map<String, String> defaults = new HashMap<>();
        List<GitIssue> out = new ArrayList<>(rows.size());
        for (GitIssue row : rows) {
            if (!row.version().isBlank() || row.projectId().isBlank()) {
                out.add(row);
                continue;
            }
            String version = defaults.computeIfAbsent(row.projectId(),
                    id -> resolveVersion("", id));
            out.add(version.isBlank() ? row : withVersion(row, version));
        }
        return out;
    }

    private static GitIssue withVersion(GitIssue issue, String version) {
        return new GitIssue(issue.id(), issue.code(), issue.userId(), issue.projectId(),
                issue.provider(), issue.repo(), issue.number(), issue.title(), issue.body(),
                issue.state(), issue.kind(), issue.priority(), issue.labels(), issue.url(),
                issue.source(), issue.syncStatus(), issue.syncError(), issue.assignee(),
                issue.createdAt(), issue.updatedAt(), version);
    }

    @Override
    public GitIssue get(String userId, String id) {
        return require(userId, id);
    }

    @Override
    public GitIssue create(String userId, GitIssue.Draft draft) {
        if (draft.title().isBlank()) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "任务标题不能为空");
        }
        long now = System.currentTimeMillis();
        // 任务归属项目时自动使用项目托管的仓库，不再让用户手选平台/仓库
        GitIssue.Draft resolved = resolveRemote(draft, null);
        String repo = GitIssueRemoteClient.cleanRepo(resolved.repo());
        String version = resolveVersion(resolved.version(), resolved.projectId());
        GitIssue issue = new GitIssue(newId("task"), "", userId, resolved.projectId(),
                resolved.provider(),
                repo, "", resolved.title(), resolved.body(), resolved.state(), resolved.kind(),
                resolved.priority(), resolved.labels(), "", "PLATFORM", "LOCAL", "",
                resolved.assignee(), now, now, version);
        if (shouldPush(resolved.provider(), repo)) {
            issue = pushCreate(issue, userId);
        } else if (!resolved.provider().isBlank() && !repo.isBlank()) {
            issue = copy(issue, "", "", issue.state(), "LOCAL",
                    "未启用 " + resolved.provider() + " Issue 同步配置", "PLATFORM");
        }
        GitIssue saved = issues.save(issue);
        // 平台新建的 Bug 任务同样进入自动修复（策略关闭时触发器内部直接返回）
        bugFixTrigger.onIssueCreated(saved);
        return saved;
    }

    @Override
    public GitIssue update(String userId, String id, GitIssue.Draft draft) {
        GitIssue current = require(userId, id);
        if (draft.title().isBlank()) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "任务标题不能为空");
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
        String priority = draft.priority().isBlank() ? current.priority() : draft.priority();
        // 版本号留空 = 保持原值；显式填写才改（例如把 Feat 规划到下一个版本）
        String version = draft.version().isBlank() ? current.version() : draft.version();
        GitIssue updated = new GitIssue(current.id(), current.code(), current.userId(),
                resolved.projectId(),
                provider, repo, number, draft.title(), draft.body(), draft.state(), resolved.kind(),
                priority, draft.labels(), url, "PLATFORM",
                repoChanged ? "LOCAL" : current.syncStatus(),
                repoChanged ? "" : current.syncError(),
                draft.assignee().isBlank() ? current.assignee() : draft.assignee(),
                current.createdAt(), now, version);
        if (shouldPush(provider, repo)) {
            updated = updated.bound() ? pushUpdate(updated, userId) : pushCreate(updated, userId);
        } else if (!provider.isBlank() && !repo.isBlank() && !updated.bound()) {
            updated = copy(updated, "", "", updated.state(), "LOCAL",
                    "未启用 " + provider + " Issue 同步配置", "PLATFORM");
        }
        GitIssue saved = issues.save(updated);
        // 编辑/重开/改标签后重新评估自动修复（触发器内部会校验状态与幂等）
        bugFixTrigger.onIssueUpdated(saved);
        return saved;
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
                current.repo(), current.title(), current.body(), state, current.kind(),
                current.priority(), current.labels(), current.assignee(), current.version());
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
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "评论内容不能为空");
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
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "任务未配置 Git 仓库，无法同步");
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
                    draft.state(), draft.kind(), draft.priority(), draft.labels(),
                    draft.assignee(), draft.version());
        }
        return projects.findById(projectId)
                .filter(p -> p.repoUrl() != null && !p.repoUrl().isBlank())
                .map(p -> new GitIssue.Draft(projectId, providerOf(p),
                        GitIssueRemoteClient.cleanRepo(p.repoUrl()), draft.title(), draft.body(),
                        draft.state(), draft.kind(), draft.priority(), draft.labels(),
                        draft.assignee(), draft.version()))
                .filter(d -> !d.provider().isBlank())
                .orElseGet(() -> new GitIssue.Draft(projectId, draft.provider(),
                        GitIssueRemoteClient.cleanRepo(draft.repo()), draft.title(), draft.body(),
                        draft.state(), draft.kind(), draft.priority(), draft.labels(),
                        draft.assignee(), draft.version()));
    }

    /**
     * 任务默认版本号：创建时未显式指定，则按当前分支的版本线推导。
     *
     * <p>版本线分支（{@code 0.1.0-snapshot}）取该线最大补丁号 +1，与流水线打 tag 的推荐值一致；
     * 其他分支退回最新标签。仓库不可用时返回空串，不影响任务创建。
     */
    private String resolveVersion(String requested, String projectId) {
        String version = GitIssue.normalizeVersion(requested);
        if (!version.isBlank() || projectId == null || projectId.isBlank()) {
            return version;
        }
        try {
            return suggestVersion(repoLocator.currentBranch(projectId),
                    repoLocator.listTags(projectId, 100));
        } catch (RuntimeException e) {
            log.debug("任务默认版本推导失败（project={}）：{}", projectId, e.getMessage());
            return "";
        }
    }

    /** 版本建议：版本线分支取最大补丁 +1；非版本分支取最新标签。 */
    static String suggestVersion(String branch, List<String> tags) {
        List<String> safeTags = tags == null ? List.of() : tags;
        var matcher = VERSION_BRANCH.matcher(branch == null ? "" : branch.trim());
        if (!matcher.matches()) {
            return safeTags.isEmpty() ? "" : safeTags.get(0);
        }
        String line = matcher.group(1) + "." + matcher.group(2);
        Pattern tagOfLine = Pattern.compile("^" + Pattern.quote(line) + "\\.(\\d+)$");
        int best = -1;
        for (String tag : safeTags) {
            var m = tagOfLine.matcher(tag == null ? "" : tag.trim());
            if (m.matches()) {
                try {
                    best = Math.max(best, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // 补丁号超长时不参与比较
                }
            }
        }
        return best < 0 ? line + ".0" : line + "." + (best + 1);
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
                    issue.title(), issue.body(), labelsForGit(issue), issue.kind());
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
                    issue.state(), labelsForGit(issue), issue.kind());
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
                .orElseThrow(() -> new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "未绑定 " + issue.provider() + " 账号，无法同步到 Git"));
    }

    /** 把分类与版本写进远端标签，Git 侧也能按 bug / feat / 版本筛选。 */
    private static String labelsForGit(GitIssue issue) {
        String base = issue.labels();
        String tag = "FEAT".equals(issue.kind()) ? "feat" : "bug";
        String labels = base.toLowerCase(Locale.ROOT).contains(tag)
                ? base : (base.isBlank() ? tag : base + "," + tag);
        if (issue.version().isBlank() || labels.toLowerCase(Locale.ROOT).contains("version:")) {
            return labels;
        }
        return labels.isBlank() ? "version:" + issue.version()
                : labels + ",version:" + issue.version();
    }

    private void markSync(GitIssue issue, String status, String error) {
        issues.save(copy(issue, issue.number(), issue.url(), issue.state(), status, error,
                issue.source()));
    }

    private GitIssue require(String userId, String id) {
        return issues.find(id, userId)
                .orElseThrow(() -> new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "任务不存在：" + id));
    }

    private static GitIssue copy(GitIssue issue, String number, String url, String state,
                                 String syncStatus, String syncError, String source) {
        return new GitIssue(issue.id(), issue.code(), issue.userId(), issue.projectId(),
                issue.provider(),
                issue.repo(), number, issue.title(), issue.body(), state, issue.kind(),
                issue.priority(), issue.labels(), url, source, syncStatus, syncError,
                issue.assignee(), issue.createdAt(), System.currentTimeMillis(), issue.version());
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
