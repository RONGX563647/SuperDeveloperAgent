package top.rongx.agent.domain.gitissue;

import java.util.Locale;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.IntakeException;

/**
 * Git 托管方名称的统一规范化（GITEE / GITHUB / GITLAB）。
 *
 * <p>Issue 同步配置与 OAuth 应用两个 Store 曾各写一份完全相同的校验，
 * 任一处新增托管方而另一处忘记就会导致"能登录但同步页报 provider 非法"。
 */
public final class GitProviders {

    private GitProviders() {
    }

    public static String normalize(String provider) {
        return normalize(provider, false);
    }

    /**
     * @param allowCustom 是否允许自建托管方（{@code CUSTOM}；空值视为 CUSTOM）
     */
    public static String normalize(String provider, boolean allowCustom) {
        String p = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (allowCustom && p.isEmpty()) {
            p = "CUSTOM";
        }
        boolean known = p.equals("GITEE") || p.equals("GITHUB") || p.equals("GITLAB")
                || (allowCustom && p.equals("CUSTOM"));
        if (!known) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT,
                    allowCustom ? "provider 只能是 GITHUB / GITLAB / CUSTOM"
                            : "provider 只能是 GITEE / GITHUB / GITLAB");
        }
        return p;
    }
}
