package top.rongx.agent.domain.gitissue;

import java.util.List;
import java.util.Optional;

/**
 * Issue 任务与评论仓储（SQLite 实现）。
 *
 * <p>事件去重也放在这里：webhook 可能重复投递，{@link #markEvent} 用唯一键
 * 保证同一事件只被处理一次（进程重启后依然有效）。
 */
public interface GitIssueRepository {

    List<GitIssue> list(String userId, String provider, String state, String keyword);

    Optional<GitIssue> find(String id, String userId);

    /** webhook 没有登录态，按远端身份查找绑定记录。 */
    Optional<GitIssue> findByRemote(String provider, String repo, String number);

    /** 新建或按 id 更新。 */
    GitIssue save(GitIssue issue);

    void delete(String id, String userId);

    List<GitIssueComment> comments(String issueId);

    GitIssueComment addComment(GitIssueComment comment);

    void deleteComment(String commentId, String issueId);

    /** 首次见到该事件返回 true；重复事件返回 false。 */
    boolean markEvent(String eventKey);

    /** 统计各状态任务数（任务页角标）。 */
    long countOpen(String userId);
}
