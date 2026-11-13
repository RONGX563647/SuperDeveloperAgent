package top.rongx.agent.domain.gitissue;

import java.util.Locale;

/**
 * 平台任务 ↔ Git Issue 的绑定记录。
 *
 * <p>一张记录同时承载平台侧任务字段与远端 Issue 的身份（provider + repo + number），
 * {@code number} 为空表示只在平台存在、尚未推送或推送失败的任务。
 *
 * <p>编号用字符串保存：Gitee 的 Issue 编号形如 {@code I1ABCD}，GitHub / GitLab 为纯数字。
 *
 * @param id         平台任务 ID（本地主键）
 * @param userId    所属用户（webhook 自动建任务时取集成配置的配置人）
 * @param projectId 关联项目（可空）
 * @param provider  GITEE / GITHUB / GITLAB
 * @param repo      owner/repo（GitLab 为 path_with_namespace）
 * @param number    远端 Issue 编号；空 = 未绑定远端
 * @param state     OPEN / CLOSED
 * @param kind      BUG / FEAT，任务分类
 * @param source    PLATFORM / AGENT / GIT，最近一次变更来源
 * @param syncStatus SYNCED / LOCAL / PENDING / FAILED
 */
public record GitIssue(
        String id,
        String userId,
        String projectId,
        String provider,
        String repo,
        String number,
        String title,
        String body,
        String state,
        String kind,
        String labels,
        String url,
        String source,
        String syncStatus,
        String syncError,
        String assignee,
        long createdAt,
        long updatedAt) {

    public GitIssue {
        provider = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        repo = repo == null ? "" : repo.trim();
        number = number == null ? "" : number.trim();
        title = title == null ? "" : title.trim();
        body = body == null ? "" : body;
        labels = labels == null ? "" : labels.trim();
        url = url == null ? "" : url.trim();
        state = "CLOSED".equalsIgnoreCase(state) ? "CLOSED" : "OPEN";
        kind = kind == null || kind.isBlank() ? kindOfLabels(labels) : normalizeKind(kind);
        source = source == null || source.isBlank() ? "PLATFORM" : source.toUpperCase(Locale.ROOT);
        syncStatus = syncStatus == null || syncStatus.isBlank()
                ? "LOCAL" : syncStatus.toUpperCase(Locale.ROOT);
        syncError = syncError == null ? "" : syncError;
        assignee = assignee == null ? "" : assignee;
    }

    /** 兼容旧调用：未显式给分类时按标签推导（Git 同步来的 Issue 走这里）。 */
    public GitIssue(String id, String userId, String projectId, String provider, String repo,
                    String number, String title, String body, String state, String labels,
                    String url, String source, String syncStatus, String syncError,
                    String assignee, long createdAt, long updatedAt) {
        this(id, userId, projectId, provider, repo, number, title, body, state,
                kindOfLabels(labels), labels, url, source, syncStatus, syncError, assignee,
                createdAt, updatedAt);
    }

    /** 是否已经绑定远端 Issue。 */
    public boolean bound() {
        return !number.isBlank() && !repo.isBlank() && !provider.isBlank();
    }

    public boolean open() {
        return "OPEN".equals(state);
    }

    /** 按标签识别任务分类：含 feat/feature/需求 → FEAT，否则按 BUG。 */
    public static String kindOfLabels(String labels) {
        String text = labels == null ? "" : labels.toLowerCase(Locale.ROOT);
        return text.contains("feat") || text.contains("enhancement") || text.contains("需求")
                ? "FEAT" : "BUG";
    }

    private static String normalizeKind(String kind) {
        String value = kind.trim().toUpperCase(Locale.ROOT);
        return "FEAT".equals(value) || "FEATURE".equals(value) ? "FEAT" : "BUG";
    }

    /** 写入模型（创建/更新共用）。 */
    public record Draft(
            String projectId,
            String provider,
            String repo,
            String title,
            String body,
            String state,
            String kind,
            String labels,
            String assignee) {

        public Draft {
            provider = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
            repo = repo == null ? "" : repo.trim();
            title = title == null ? "" : title.trim();
            body = body == null ? "" : body;
            state = "CLOSED".equalsIgnoreCase(state) ? "CLOSED" : "OPEN";
            labels = labels == null ? "" : labels.trim();
            kind = kind == null || kind.isBlank() ? kindOfLabels(labels) : normalizeKind(kind);
            assignee = assignee == null ? "" : assignee.trim();
            projectId = projectId == null ? "" : projectId.trim();
        }

        /** 兼容旧调用：未指定分类时按标签推导。 */
        public Draft(String projectId, String provider, String repo, String title, String body,
                     String state, String labels, String assignee) {
            this(projectId, provider, repo, title, body, state, "", labels, assignee);
        }
    }
}
