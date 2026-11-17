package top.rongx.agent.domain.gitissue;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台任务 ↔ Git Issue 的绑定记录。
 *
 * <p>一张记录同时承载平台侧任务字段与远端 Issue 的身份（provider + repo + number），
 * {@code number} 为空表示只在平台存在、尚未推送或推送失败的任务。
 *
 * <p>编号用字符串保存：Gitee 的 Issue 编号形如 {@code I1ABCD}，GitHub / GitLab 为纯数字。
 *
 * @param id         平台任务 ID（本地主键）
 * @param code       任务编码（项目内可读编号，如 FEAT-1 / BUG-3），创建后不再变化
 * @param userId    所属用户（webhook 自动建任务时取集成配置的配置人）
 * @param projectId 关联项目（可空）
 * @param provider  GITEE / GITHUB / GITLAB
 * @param repo      owner/repo（GitLab 为 path_with_namespace）
 * @param number    远端 Issue 编号；空 = 未绑定远端
 * @param state     OPEN / CLOSED
 * @param kind      BUG / FEAT，任务分类
 * @param priority  P0 / P1 / P2，任务优先级
 * @param source    PLATFORM / AGENT / GIT，最近一次变更来源
 * @param syncStatus SYNCED / LOCAL / PENDING / FAILED
 * @param version   关联版本号（如 0.1.2）；空 = 未指定，创建时默认取当前分支版本
 */
public record GitIssue(
        String id,
        String code,
        String userId,
        String projectId,
        String provider,
        String repo,
        String number,
        String title,
        String body,
        String state,
        String kind,
        String priority,
        String labels,
        String url,
        String source,
        String syncStatus,
        String syncError,
        String assignee,
        long createdAt,
        long updatedAt,
        String version) {

    private static final Pattern VERSION_IN_LABELS = Pattern.compile(
            "(?:^|[,\\s;])(?:version\\s*[:=]\\s*)?v?(\\d+\\.\\d+\\.\\d+)(?:$|[,\\s;])",
            Pattern.CASE_INSENSITIVE);

    public GitIssue {
        code = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        provider = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        repo = repo == null ? "" : repo.trim();
        number = number == null ? "" : number.trim();
        title = title == null ? "" : title.trim();
        body = body == null ? "" : body;
        labels = labels == null ? "" : labels.trim();
        url = url == null ? "" : url.trim();
        state = "CLOSED".equalsIgnoreCase(state) ? "CLOSED" : "OPEN";
        kind = kind == null || kind.isBlank() ? kindOfLabels(labels) : normalizeKind(kind);
        String normalizedPriority = normalizePriority(priority);
        priority = normalizedPriority.isBlank() ? priorityOfLabels(labels) : normalizedPriority;
        source = source == null || source.isBlank() ? "PLATFORM" : source.toUpperCase(Locale.ROOT);
        syncStatus = syncStatus == null || syncStatus.isBlank()
                ? "LOCAL" : syncStatus.toUpperCase(Locale.ROOT);
        syncError = syncError == null ? "" : syncError;
        assignee = assignee == null ? "" : assignee;
        version = normalizeVersion(version);
        // Git 同步来的 Issue 没有独立字段：从版本标签（如 version:0.1.2 / v0.1.2）回填
        if (version.isBlank()) {
            version = versionOfLabels(labels);
        }
    }

    /** 兼容旧调用：未携带版本号时从标签推导，保持既有构造点不变。 */
    public GitIssue(String id, String code, String userId, String projectId, String provider,
                    String repo, String number, String title, String body, String state,
                    String kind, String priority, String labels, String url, String source,
                    String syncStatus, String syncError, String assignee, long createdAt,
                    long updatedAt) {
        this(id, code, userId, projectId, provider, repo, number, title, body, state, kind,
                priority, labels, url, source, syncStatus, syncError, assignee, createdAt,
                updatedAt, "");
    }

    /** 兼容旧调用：未显式给优先级时按标签推导（Git 同步来的 Issue 走这里）。 */
    public GitIssue(String id, String userId, String projectId, String provider, String repo,
                    String number, String title, String body, String state, String kind,
                    String labels, String url, String source, String syncStatus, String syncError,
                    String assignee, long createdAt, long updatedAt) {
        this(id, "", userId, projectId, provider, repo, number, title, body, state, kind,
                priorityOfLabels(labels), labels, url, source, syncStatus, syncError, assignee,
                createdAt, updatedAt);
    }

    /** 兼容旧调用：未显式给分类时按标签推导（Git 同步来的 Issue 走这里）。 */
    public GitIssue(String id, String userId, String projectId, String provider, String repo,
                    String number, String title, String body, String state, String labels,
                    String url, String source, String syncStatus, String syncError,
                    String assignee, long createdAt, long updatedAt) {
        this(id, "", userId, projectId, provider, repo, number, title, body, state,
                kindOfLabels(labels), priorityOfLabels(labels), labels, url, source,
                syncStatus, syncError, assignee, createdAt, updatedAt);
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

    /** 按标签识别优先级：含 p0/紧急 → P0，含 p1/高优 → P1，其余 P2。 */
    public static String priorityOfLabels(String labels) {
        String text = labels == null ? "" : labels.toLowerCase(Locale.ROOT);
        if (text.contains("p0") || text.contains("紧急") || text.contains("urgent")) {
            return "P0";
        }
        if (text.contains("p1") || text.contains("高优") || text.contains("high")) {
            return "P1";
        }
        return "P2";
    }

    /**
     * 归一化优先级：只接受 P0/P1/P2（兼容旧任务的 1-5 数字，1 最高）。
     * 无法识别时返回空串，由调用方决定默认值。
     */
    public static String normalizePriority(String priority) {
        String value = priority == null ? "" : priority.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "0", "1", "P0", "HIGHEST" -> "P0";
            case "2", "P1", "HIGH" -> "P1";
            case "3", "4", "5", "P2", "NORMAL", "LOW" -> "P2";
            default -> "";
        };
    }

    /**
     * 归一化版本号：去空白、去掉前缀 v（平台标签统一不带 v），限制长度。
     *
     * <p>不做严格的 x.y.z 校验：历史版本或预发布标识也应能关联到任务。
     */
    public static String normalizeVersion(String version) {
        String value = version == null ? "" : version.trim();
        if (value.length() > 1 && (value.charAt(0) == 'v' || value.charAt(0) == 'V')
                && Character.isDigit(value.charAt(1))) {
            value = value.substring(1);
        }
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    /** 从标签里识别版本号：兼容 {@code version:0.1.2} 与裸 semver {@code v0.1.2}。 */
    public static String versionOfLabels(String labels) {
        if (labels == null || labels.isBlank()) {
            return "";
        }
        Matcher matcher = VERSION_IN_LABELS.matcher(labels.trim());
        return matcher.find() ? matcher.group(1) : "";
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
            String priority,
            String labels,
            String assignee,
            String version) {

        public Draft {
            provider = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
            repo = repo == null ? "" : repo.trim();
            title = title == null ? "" : title.trim();
            body = body == null ? "" : body;
            state = "CLOSED".equalsIgnoreCase(state) ? "CLOSED" : "OPEN";
            labels = labels == null ? "" : labels.trim();
            kind = kind == null || kind.isBlank() ? kindOfLabels(labels) : normalizeKind(kind);
            // 空串表示「沿用当前值」，由服务层决定；无法识别的取值也按空串处理
            priority = normalizePriority(priority);
            assignee = assignee == null ? "" : assignee.trim();
            projectId = projectId == null ? "" : projectId.trim();
            // 空串 = 创建时取当前分支版本 / 更新时保持原值
            version = normalizeVersion(version);
        }

        /** 兼容旧调用：未携带版本号。 */
        public Draft(String projectId, String provider, String repo, String title, String body,
                     String state, String kind, String priority, String labels, String assignee) {
            this(projectId, provider, repo, title, body, state, kind, priority, labels, assignee, "");
        }

        /** 兼容旧调用：未指定优先级时沿用当前值。 */
        public Draft(String projectId, String provider, String repo, String title, String body,
                     String state, String kind, String labels, String assignee) {
            this(projectId, provider, repo, title, body, state, kind, "", labels, assignee, "");
        }

        /** 兼容旧调用：未指定分类时按标签推导。 */
        public Draft(String projectId, String provider, String repo, String title, String body,
                     String state, String labels, String assignee) {
            this(projectId, provider, repo, title, body, state, "", "", labels, assignee, "");
        }
    }
}
