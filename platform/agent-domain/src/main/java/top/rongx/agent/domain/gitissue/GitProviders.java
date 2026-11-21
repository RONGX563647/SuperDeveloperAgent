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
        String p = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (!p.equals("GITEE") && !p.equals("GITHUB") && !p.equals("GITLAB")) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT,
                    "provider 只能是 GITEE / GITHUB / GITLAB");
        }
        return p;
    }
}
