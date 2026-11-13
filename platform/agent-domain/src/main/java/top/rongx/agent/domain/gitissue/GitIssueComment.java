package top.rongx.agent.domain.gitissue;

import java.util.Locale;

/**
 * Issue 评论（平台与 Git 双向同步）。
 *
 * @param id       平台评论 ID；来自 Git 的评论使用 {@code git-<provider>-<remoteId>}
 * @param issueId  所属平台任务 ID
 * @param author   作者（平台用户名或 Git 登录名）
 * @param source   PLATFORM / AGENT / GIT
 * @param createdAt 创建时间（毫秒）
 */
public record GitIssueComment(
        String id,
        String issueId,
        String author,
        String body,
        String source,
        long createdAt) {

    public GitIssueComment {
        body = body == null ? "" : body;
        author = author == null || author.isBlank() ? "unknown" : author.trim();
        source = source == null || source.isBlank() ? "PLATFORM" : source.toUpperCase(Locale.ROOT);
    }
}
