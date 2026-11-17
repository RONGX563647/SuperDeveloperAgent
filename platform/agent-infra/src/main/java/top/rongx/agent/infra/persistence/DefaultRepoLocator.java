package top.rongx.agent.infra.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.infra.git.GitWorktreeManager;

/**
 * 仓库定位器（v2 直接模式）：
 * <ul>
 *   <li>DIRECT：仓库就在用户本地目录（localPath）——构建 worktree 放
 *       {@code <用户目录>/.agent/worktrees/<runId>}（随项目走、不占平台盘）；</li>
 *   <li>MANAGED：平台 bare（{@code workspaces/<id>/git}），worktree 放平台侧。</li>
 * </ul>
 * sync 语义：DIRECT/REMOTE 在用户目录内 fetch origin；DIRECT/LOCAL 由用户本地推进
 * （无远端时 no-op）；MANAGED 在 bare 内 fetch。
 */
@Component
public class DefaultRepoLocator implements ProjectRepoLocator {

    private static final Logger log = LoggerFactory.getLogger(DefaultRepoLocator.class);

    private final IntakeProjectStore store;
    private final Path workspaceRoot;
    private final GitWorktreeManager git;
    private final top.rongx.agent.domain.settings.GitHostingAccountRepository accounts;

    public DefaultRepoLocator(IntakeProjectStore store,
                              GitWorktreeManager git,
                              top.rongx.agent.domain.settings.GitHostingAccountRepository accounts,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.store = store;
        this.git = git;
        this.accounts = accounts;
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
    }

    @Override
    public Optional<Path> repoDir(String projectId) {
        // 预置（yml）项目走 MANAGED 布局
        var intake = store.find(projectId);
        if (intake.isEmpty()) {
            Path bare = workspaceRoot.resolve(projectId).resolve("git");
            return Files.isDirectory(bare.resolve("objects")) ? Optional.of(bare) : Optional.empty();
        }
        var p = intake.get();
        if ("DIRECT".equals(p.mode())) {
            Path dir = Path.of(p.localPath());
            return Files.isDirectory(dir.resolve(".git"))
                    || Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
        }
        Path bare = workspaceRoot.resolve(projectId).resolve("git");
        return Files.isDirectory(bare.resolve("objects")) ? Optional.of(bare) : Optional.empty();
    }

    @Override
    public Optional<Path> worktreeRoot(String projectId) {
        var intake = store.find(projectId);
        if (intake.isPresent() && "DIRECT".equals(intake.get().mode())) {
            // 工作树随项目走：用户目录/.agent/worktrees（.agent 已是隐藏约定目录）
            return Optional.of(Path.of(intake.get().localPath()).resolve(".agent").resolve("worktrees"));
        }
        return Optional.of(workspaceRoot.resolve(projectId).resolve("worktrees"));
    }

    @Override
    public void sync(String projectId) {
        var intake = store.find(projectId);
        if (intake.isEmpty()) {
            // 预置项目：bare 内 fetch
            git.fetchRemote(projectId);
            return;
        }
        var p = intake.get();
        if ("DIRECT".equals(p.mode())) {
            Path dir = Path.of(p.localPath());
            if (!Files.isDirectory(dir.resolve(".git"))) {
                throw new IllegalStateException("项目目录不是 git 仓库：" + dir);
            }
            if (p.repoUrl() != null && !p.repoUrl().isBlank()) {
                // REMOTE clone 到本地目录：fetch 远端拿最新
                git.fetchRemoteIn(dir);
            }
            // LOCAL/DIRECT：代码就在本地，无需同步（构建直接用当前目录状态）
            log.debug("DIRECT 项目 {} 使用本地目录现状：{}", projectId, dir);
        } else {
            git.fetchRemote(projectId);
        }
    }

    @Override
    public StorageMode modeOf(String projectId) {
        var intake = store.find(projectId);
        if (intake.isPresent() && "DIRECT".equals(intake.get().mode())) {
            return StorageMode.DIRECT;
        }
        return StorageMode.MANAGED;
    }

    @Override
    public java.util.List<String> listTags(String projectId, int limit) {
        var repo = repoDir(projectId);
        return repo.map(dir -> git.listTags(dir, limit)).orElse(java.util.List.of());
    }

    @Override
    public java.util.List<String> listBranches(String projectId) {
        var repo = repoDir(projectId);
        return repo.map(git::localBranches).orElse(java.util.List.of());
    }

    @Override
    public boolean isCommitOnBranch(String projectId, String branch, String sha) {
        var repo = repoDir(projectId);
        return repo.map(dir -> git.isAncestor(dir, sha, "refs/heads/" + branch)).orElse(false);
    }

    @Override
    public BranchOutcome createBranchFrom(String projectId, String branch, String startPoint,
                                          String userId) {
        var repo = repoDir(projectId);
        if (repo.isEmpty()) {
            return new BranchOutcome(false, false, "项目仓库不存在，无法创建分支 " + branch);
        }
        Path dir = repo.get();
        boolean created;
        try {
            created = git.createBranchAt(dir, branch, startPoint);
        } catch (GitWorktreeManager.GitIntakeException e) {
            return new BranchOutcome(false, false, "创建分支失败：" + e.getMessage());
        }
        String remoteUrl = originUrl(dir);
        if (remoteUrl == null) {
            return new BranchOutcome(created, false,
                    (created ? "已创建本地分支 " : "分支已存在 ") + branch + "（仓库没有 origin，未推送）");
        }
        try {
            git.pushBranchTo(dir, branch, remoteUrl, authFor(userId, remoteUrl));
            return new BranchOutcome(created, true, "分支 " + branch + " 已推送到 " + remoteUrl);
        } catch (GitWorktreeManager.GitIntakeException e) {
            String detail = e.getMessage() == null ? "" : e.getMessage().lines()
                    .filter(line -> !line.isBlank()).findFirst().orElse("");
            return new BranchOutcome(created, false,
                    (created ? "已创建本地分支 " : "分支已存在 ") + branch + "，推送失败：" + detail);
        }
    }

    @Override
    public TagOutcome createTag(String projectId, String tag, String sha) {
        return createTag(projectId, tag, sha, null);
    }

    @Override
    public TagOutcome createTag(String projectId, String tag, String sha, String userId) {
        var repo = repoDir(projectId);
        if (repo.isEmpty() || sha == null || sha.isBlank()) {
            return new TagOutcome(false, false, "仓库或提交不存在，未创建 git 标签 " + tag);
        }
        Path dir = repo.get();
        try {
            git.createTag(dir, tag, sha);
        } catch (GitWorktreeManager.GitIntakeException e) {
            return new TagOutcome(false, false, e.getMessage());
        }
        String remoteUrl = originUrl(dir);
        if (remoteUrl == null) {
            return new TagOutcome(true, false, "已创建本地标签 " + tag + "（仓库没有 origin，未推送）");
        }
        return push(projectId, dir, tag, remoteUrl, userId);
    }

    @Override
    public TagOutcome pushTag(String projectId, String tag, String userId) {
        if (tag == null || tag.isBlank()) {
            return new TagOutcome(false, false, "标签名不能为空");
        }
        var repo = repoDir(projectId);
        if (repo.isEmpty()) {
            return new TagOutcome(false, false, "项目仓库不存在，无法推送标签 " + tag);
        }
        Path dir = repo.get();
        if (!git.listTags(dir, 500).contains(tag)) {
            return new TagOutcome(false, false,
                    "本地不存在标签 " + tag + "，请重新发布生成标签后再推送");
        }
        String remoteUrl = originUrl(dir);
        if (remoteUrl == null) {
            return new TagOutcome(true, false, "仓库没有 origin，标签 " + tag + " 仍只在本地");
        }
        return push(projectId, dir, tag, remoteUrl, userId);
    }

    /** 推送标签：优先用该用户保存的托管账号 token，未配置时退回本机凭据。 */
    private TagOutcome push(String projectId, Path dir, String tag, String remoteUrl, String userId) {
        try {
            git.pushTagTo(dir, tag, remoteUrl, authFor(userId, remoteUrl));
            return new TagOutcome(true, true, "已创建并推送标签 " + tag + " → " + remoteUrl);
        } catch (GitWorktreeManager.GitIntakeException e) {
            log.warn("标签 {} 推送失败 project={}：{}", tag, projectId, e.getMessage());
            String detail = e.getMessage() == null ? "" : e.getMessage().lines()
                    .filter(line -> !line.isBlank()).findFirst().orElse("");
            return new TagOutcome(true, false,
                    "已创建本地标签 " + tag + "，推送远端失败：" + detail
                    + "（可在流水线历史里手动重推）");
        }
    }

    private String originUrl(Path dir) {
        return git.remotes(dir).stream()
                .filter(remote -> "origin".equals(remote.name()))
                .map(GitWorktreeManager.RemoteInfo::url)
                .filter(url -> url != null && !url.isBlank())
                .findFirst().orElse(null);
    }

    private GitWorktreeManager.GitAuth authFor(String userId, String remoteUrl) {
        if (userId == null || userId.isBlank() || accounts == null) {
            return null;
        }
        return accounts.credentialFor(userId, remoteUrl)
                .map(c -> new GitWorktreeManager.GitAuth(c.username(), c.token()))
                .orElse(null);
    }
}
