package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Optional;

/**
 * Git 平台 OAuth 应用配置仓储。
 *
 * <p>平台级单例配置，按 provider 唯一；secret 加密落库，读取时单独解密，
 * 只在换取 token 的后端流程里使用。
 */
public interface GitOauthAppRepository {

    List<GitOauthApp> findAll();

    Optional<GitOauthApp> find(String provider);

    /** 新建或更新；clientSecret 为空表示沿用已保存的 secret。 */
    GitOauthApp save(GitOauthAppView view, String clientSecret);

    /** 解密取 Client Secret（仅供后端换取 token）。 */
    Optional<String> secretOf(String provider);

    /** 清除某平台的配置（回到环境变量兜底）。 */
    void delete(String provider);

    /** 写入模型。 */
    record GitOauthAppView(String provider, String clientId, String redirectUri,
                           String scopes, boolean enabled) {
    }
}
