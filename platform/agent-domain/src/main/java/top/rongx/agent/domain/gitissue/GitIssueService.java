package top.rongx.agent.domain.gitissue;

import java.util.List;

/**
 * Issue 任务服务：任务页（HTTP）与 Agent 工具共用的唯一入口。
 *
 * <p>实现位于网关层：本地增删改查 + 按集成配置尝试同步到 Gitee / GitHub / GitLab。
 * 远端失败 <b>不抛出</b>——任务保留在本地并标记 {@code syncStatus=FAILED}，
 * 不阻塞主流程（与架构约定一致）。
 */
public interface GitIssueService {

    List<GitIssue> list(String userId, String provider, String state, String keyword);

    GitIssue get(String userId, String id);

    /** 创建任务；配置允许时同步创建远端 Issue。 */
    GitIssue create(String userId, GitIssue.Draft draft);

    /** 更新标题/描述/标签/状态；配置允许时同步远端。 */
    GitIssue update(String userId, String id, GitIssue.Draft draft);

    /** 删除任务；已绑定远端的只解除绑定，不删除 Git Issue。 */
    void delete(String userId, String id);

    GitIssue setState(String userId, String id, String state);

    List<GitIssueComment> comments(String userId, String issueId);

    /** 添加评论并（允许时）回写远端。 */
    GitIssueComment comment(String userId, String issueId, String body);

    void deleteComment(String userId, String issueId, String commentId);

    /** 手动重试一次同步（推送本地最新内容）。 */
    GitIssue sync(String userId, String id);

    long openCount(String userId);
}
