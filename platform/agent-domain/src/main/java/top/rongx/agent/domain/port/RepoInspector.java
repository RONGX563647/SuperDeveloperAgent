package top.rongx.agent.domain.port;

import java.util.List;

/**
 * 仓库只读检视端口：给 projectId，回答"当前分支/提交/未提交改动/工作树"。
 *
 * <p>供工具层复用平台已有的 git 能力（与「工作区」页展示的是同一份数据），
 * 只读、不改仓库；写操作（checkout / worktree 增删）不在本端口内。
 */
public interface RepoInspector {

    /** 一条提交摘要。 */
    record Commit(String sha, String author, String relativeTime, String subject) {
    }

    /** 一个工作树。 */
    record Worktree(String name, String path, String head, String role) {
    }

    /**
     * 仓库状态快照。
     *
     * @param storageMode DIRECT / MANAGED / NONE（未接入）
     * @param uncommitted {@code git status --porcelain} 行（DIRECT 才有；MANAGED 为空）
     */
    record RepoStatus(String storageMode, String head, String branch, String upstream,
                      long ahead, long behind, List<String> uncommitted,
                      List<Commit> recentCommits, List<Worktree> worktrees) {
    }

    /** 当前状态；未接入或不可读时返回 {@code storageMode=NONE} 的空快照。 */
    RepoStatus status(String projectId);

    /** 提交历史（按时间倒序）。 */
    List<Commit> commits(String projectId, int limit, int skip);

    /** from..to 的提交列表（oneline）。 */
    List<String> logRange(String projectId, String from, String to);

    /** from..to 的 diff stat。 */
    List<String> diffStat(String projectId, String from, String to);
}
