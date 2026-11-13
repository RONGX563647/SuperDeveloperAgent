package top.rongx.agent.domain.gitissue;

import java.util.List;
import java.util.Locale;

/**
 * 单个 Git 平台的 Issue 同步配置（webhook 密钥只写不读，接口只回掩码）。
 *
 * @param provider          GITEE / GITHUB / GITLAB
 * @param ownerUserId       配置人；webhook 自动建任务时归属到该用户
 * @param repoWhitelist     仓库白名单，逗号/换行分隔；{@code *} 表示全部
 * @param autoCreateTask    Git 新建 Issue 时是否自动生成平台任务
 * @param syncDirection     BIDIRECTIONAL / PUSH_ONLY / PULL_ONLY
 * @param conflictPolicy    GIT_WINS / PLATFORM_WINS
 */
public record GitIssueIntegration(
        String provider,
        String ownerUserId,
        boolean enabled,
        String repoWhitelist,
        boolean hasWebhookSecret,
        String webhookSecretMasked,
        boolean autoCreateTask,
        String syncDirection,
        String conflictPolicy,
        long updatedAt) {

    public GitIssueIntegration {
        provider = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        ownerUserId = ownerUserId == null ? "" : ownerUserId;
        repoWhitelist = repoWhitelist == null ? "" : repoWhitelist.trim();
        syncDirection = direction(syncDirection);
        conflictPolicy = "PLATFORM_WINS".equalsIgnoreCase(conflictPolicy)
                ? "PLATFORM_WINS" : "GIT_WINS";
        webhookSecretMasked = webhookSecretMasked == null ? null : webhookSecretMasked;
    }

    /** 仓库是否允许同步：空白名单 = 仅允许已在平台绑定的任务（不放行新仓库）。 */
    public boolean allowsRepo(String repo) {
        String value = repo == null ? "" : repo.trim();
        if (value.isBlank()) {
            return false;
        }
        for (String item : repoWhitelist.split("[,\\n;\\s]+")) {
            String pattern = item.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            if ("*".equals(pattern) || pattern.equalsIgnoreCase(value)) {
                return true;
            }
            // 支持 owner/* 与 *.git 后缀
            if (pattern.endsWith("/*")
                    && value.toLowerCase(Locale.ROOT)
                    .startsWith(pattern.substring(0, pattern.length() - 1).toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (pattern.endsWith(".git") && pattern.substring(0, pattern.length() - 4)
                    .equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** 是否允许平台 → Git 推送。 */
    public boolean pushEnabled() {
        return enabled && !"PULL_ONLY".equals(syncDirection);
    }

    /** 是否允许 Git → 平台回写。 */
    public boolean pullEnabled() {
        return enabled && !"PUSH_ONLY".equals(syncDirection);
    }

    public List<String> whitelistEntries() {
        if (repoWhitelist.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(repoWhitelist.split("[,\\n;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String direction(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if ("PUSH_ONLY".equals(v) || "PULL_ONLY".equals(v) || "BIDIRECTIONAL".equals(v)) {
            return v;
        }
        return "BIDIRECTIONAL";
    }
}
