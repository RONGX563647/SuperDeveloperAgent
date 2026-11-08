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

    public DefaultRepoLocator(IntakeProjectStore store,
                              GitWorktreeManager git,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.store = store;
        this.git = git;
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
}
