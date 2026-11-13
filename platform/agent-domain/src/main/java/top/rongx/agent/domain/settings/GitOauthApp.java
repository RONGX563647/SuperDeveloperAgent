package top.rongx.agent.domain.settings;

/**
 * Git 平台 OAuth 应用配置（Gitee / GitHub / GitLab）。
 *
 * <p>这是平台级配置（一个部署一套 OAuth App），不是用户级账号：
 * client-id 明文可读，client-secret 只写不读，接口仅返回掩码。
 *
 * @param provider    GITEE / GITHUB / GITLAB
 * @param clientId    应用 Client ID
 * @param redirectUri 授权回调地址（需与平台后台填写的一致）
 * @param scopes      授权范围，空格分隔
 * @param enabled     是否启用该平台的应用授权入口
 * @param hasSecret   是否已保存 Client Secret
 * @param secretMasked Client Secret 掩码
 */
public record GitOauthApp(
        String provider,
        String clientId,
        String redirectUri,
        String scopes,
        boolean enabled,
        boolean hasSecret,
        String secretMasked,
        long updatedAt) {
}
