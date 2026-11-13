package top.rongx.agent.domain.settings;

/**
 * 代码托管账号（GitHub / GitLab / 自定义 Git 服务）。
 *
 * <p>token 只写不读：列表视图只有掩码与 {@code hasToken}，明文只在后端代调远端时解密。
 */
public record GitHostingAccount(
        String id,
        String provider,
        String host,
        String username,
        boolean hasToken,
        String tokenMasked,
        long updatedAt) {
}
