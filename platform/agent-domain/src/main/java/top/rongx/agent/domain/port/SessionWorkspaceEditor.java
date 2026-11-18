package top.rongx.agent.domain.port;

import java.util.List;

/**
 * 会话工作树编辑器：Agent 只能改「本会话独占的 worktree」，碰不到用户当前分支。
 *
 * <p>分层约束：tools 层只依赖 domain，文件写入、git 进程全部由 infra 实现。
 *
 * <p>约定：{@code sessionKey} 来自服务端注入的 {@code ProjectContext.worktree()}，
 * 模型无法提供；同一 key 重复调用返回同一个 worktree（幂等）。
 */
public interface SessionWorkspaceEditor {

    /**
     * 会话工作树。
     *
     * @param worktree 绝对路径
     * @param branch   会话分支（agent/session-*）
     * @param head     当前提交
     * @param created  本次是否新建（false = 复用已有工作树）
     */
    record Session(String worktree, String branch, String head, boolean created) {
    }

    /** 未提交改动：porcelain 行 + diff --stat 摘要。 */
    record Diff(List<String> changes, String stat) {
    }

    /** 提交结果。 */
    record Commit(String sha, String branch, List<String> files) {
    }

    /** 建或复用会话工作树与分支。 */
    Session open(String projectId, String sessionKey);

    /** 列目录（相对路径，目录以 / 结尾）；depth 夹到 1..3。 */
    List<String> list(String projectId, String sessionKey, String dir, int depth);

    /** 读文件（UTF-8，相对工作树根）。 */
    String read(String projectId, String sessionKey, String path);

    /** 写文件（UTF-8，自动建父目录；上限 200_000 字符）。 */
    void write(String projectId, String sessionKey, String path, String content);

    /** 删文件；不存在返回 false（目录不允许删除）。 */
    boolean delete(String projectId, String sessionKey, String path);

    /** 未提交改动。 */
    Diff diff(String projectId, String sessionKey);

    /** 暂存全部并提交（保留仓库 hook 校验）。 */
    Commit commit(String projectId, String sessionKey, String message);
}
