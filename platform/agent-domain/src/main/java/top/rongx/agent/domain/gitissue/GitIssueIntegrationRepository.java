package top.rongx.agent.domain.gitissue;

import java.util.List;
import java.util.Optional;

/** Git Issue 同步配置仓储（按 provider 一行；webhook 密钥加密存储）。 */
public interface GitIssueIntegrationRepository {

    List<GitIssueIntegration> findAll();

    Optional<GitIssueIntegration> find(String provider);

    /** webhook 验签使用的明文密钥（仅供后端，不返回前端）。 */
    Optional<String> secretOf(String provider);

    /** 保存；webhookSecret 留空表示沿用旧值。 */
    GitIssueIntegration save(Draft draft, String webhookSecret, String ownerUserId);

    void delete(String provider);

    record Draft(
            String provider,
            boolean enabled,
            String repoWhitelist,
            boolean autoCreateTask,
            String syncDirection,
            String conflictPolicy) {
    }
}
