package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Optional;

/**
 * 服务器配置仓储（设置页 + 部署器共用）。
 *
 * <p>方法粒度为"读视图 / 写视图"：写入时 secret 由调用方传入明文，仓储负责加密；
 * 读取时永不返回明文（只有掩码与 {@code hasSecret}）。
 */
public interface DeployServerRepository {

    List<DeployServer> findAll();

    Optional<DeployServer> find(String id);

    /** 默认服务器（enabled 且 is_default=1；没有则取第一个 enabled 的）。 */
    Optional<DeployServer> findDefault();

    /** 新建或更新；secret 非空时更新密钥。返回保存后的视图。 */
    DeployServer save(DeployServerView view, String secret, String updatedBy);

    void delete(String id);

    void markDefault(String id);

    /** 记录一次连通性测试结果。 */
    void recordTest(String id, boolean ok, String message, Long latencyMs);

    /** 解密取密码/口令（仅供部署器与连通性测试；不返回给前端）。 */
    Optional<String> secretOf(String id);

    /** 组装用的写入模型（id 为空 = 新建）。 */
    record DeployServerView(
            String id,
            String name,
            String host,
            int port,
            String sshUser,
            String authType,
            String keyPath,
            String remoteDir,
            boolean enabled,
            boolean isDefault) {
    }
}
