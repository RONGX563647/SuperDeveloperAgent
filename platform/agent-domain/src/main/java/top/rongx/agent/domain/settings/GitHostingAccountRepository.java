package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Optional;

/**
 * 代码托管账号仓储（设置页 + 项目接入共用）。
 *
 * <p>凭据按「用户 + provider + host」唯一；{@link #credentialFor} 用于 clone/fetch 时
 * 按仓库 URL 自动匹配 token，用户无需在接入表单里重复粘贴。
 */
public interface GitHostingAccountRepository {

    List<GitHostingAccount> findAll(String userId);

    Optional<GitHostingAccount> find(String id, String userId);

    /** 新建或更新；token 为空表示沿用旧 token。 */
    GitHostingAccount save(GitHostingAccountView view, String token, String userId);

    void delete(String id, String userId);

    /** 解密取 token（仅供后端代调远端；不返回给前端）。 */
    Optional<String> tokenOf(String id, String userId);

    /** 按仓库 URL 的 host 匹配可用凭据（HTTPS 场景）。 */
    Optional<Credential> credentialFor(String userId, String repoUrl);

    /** 写入模型（id 为空 = 新建）。 */
    record GitHostingAccountView(String id, String provider, String host, String username) {
    }

    /** clone/fetch 使用的凭据。 */
    record Credential(String username, String token) {
    }
}
