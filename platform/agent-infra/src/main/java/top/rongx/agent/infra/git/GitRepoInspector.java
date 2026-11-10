package top.rongx.agent.infra.git;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.RepoInspector;

/**
 * 仓库只读检视的实现：复用 {@link GitWorktreeManager} 与 {@link ProjectRepoLocator}。
 *
 * <p>与「工作区」页的数据来源一致；DIRECT 模式读用户目录，MANAGED 模式读平台 bare。
 * 未接入或读取失败时返回空快照，而不是抛错——"项目还没接入 git"是正常状态。
 */
@Component
public class GitRepoInspector implements RepoInspector {

    private final GitWorktreeManager git;
    private final ProjectRepoLocator locator;

    public GitRepoInspector(GitWorktreeManager git, ProjectRepoLocator locator) {
        this.git = git;
        this.locator = locator;
    }

    private record Repo(Path dir, boolean direct) {
    }

    private Optional<Repo> repo(String projectId) {
        return locator.repoDir(projectId)
                .map(dir -> new Repo(dir,
                        locator.modeOf(projectId) == ProjectRepoLocator.StorageMode.DIRECT));
    }

    @Override
    public RepoStatus status(String projectId) {
        Optional<Repo> found = repo(projectId);
        if (found.isEmpty()) {
            return new RepoStatus("NONE", "", "", null, 0, 0,
                    List.of(), List.of(), List.of());
        }
        Repo repo = found.get();
        try {
            String head = safe(() -> git.revParse(repo.dir(), "HEAD"), "");
            String branch = safe(() -> git.currentBranch(repo.dir()), "");
            String upstream = "HEAD".equals(branch) || branch.isBlank()
                    ? null : safe(() -> git.upstreamOf(repo.dir(), branch), null);
            long ahead = 0;
            long behind = 0;
            if (upstream != null) {
                long[] ab = safe(() -> git.aheadBehind(repo.dir(), upstream), new long[]{0, 0});
                ahead = ab[0];
                behind = ab[1];
            }
            List<String> uncommitted = repo.direct()
                    ? safe(() -> git.statusPorcelain(repo.dir()), List.<String>of())
                    : List.of();
            List<Worktree> worktrees = (repo.direct()
                    ? git.listWorktreesAt(repo.dir()) : git.listWorktrees(projectId)).stream()
                    .map(w -> new Worktree(w.name(), w.path(), w.head(), w.role()))
                    .toList();
            List<Commit> recent = git.recentCommits(repo.dir(), 5).stream()
                    .map(GitRepoInspector::toCommit)
                    .toList();
            return new RepoStatus(repo.direct() ? "DIRECT" : "MANAGED", head, branch, upstream,
                    ahead, behind, uncommitted, recent, worktrees);
        } catch (RuntimeException e) {
            return new RepoStatus(repo.direct() ? "DIRECT" : "MANAGED", "", "", null, 0, 0,
                    List.of(), List.of(), List.of());
        }
    }

    @Override
    public List<Commit> commits(String projectId, int limit, int skip) {
        return repo(projectId)
                .map(r -> git.commits(r.dir(), Math.max(0, skip), limit).stream()
                        .map(GitRepoInspector::toCommit)
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<String> logRange(String projectId, String from, String to) {
        return repo(projectId).map(r -> git.logRange(r.dir(), from, to)).orElse(List.of());
    }

    @Override
    public List<String> diffStat(String projectId, String from, String to) {
        return repo(projectId).map(r -> git.diffStat(r.dir(), from, to)).orElse(List.of());
    }

    private static Commit toCommit(GitWorktreeManager.CommitInfo info) {
        return new Commit(info.sha(), info.author(), info.relativeTime(), info.subject());
    }

    private static <T> T safe(SupplierWithException<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get();
    }
}
