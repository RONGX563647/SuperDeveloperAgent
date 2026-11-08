package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Optional;

/** 模型连接仓储（设置页 + 运行时共用）。 */
public interface ModelConnectionRepository {

    List<ModelConnection> findAll();

    Optional<ModelConnection> find(String id);

    /** 运行时使用的连接：enabled 的默认项；没有则第一个 enabled 的。 */
    Optional<ModelConnection> findDefault();

    /** 新建或更新；apiKey 非空时更新密钥。 */
    ModelConnection save(ModelConnectionView view, String apiKey, String updatedBy);

    void delete(String id);

    void markDefault(String id);

    void recordTest(String id, boolean ok, String message, Long latencyMs);

    /** 解密取 API Key（仅供运行时与测试连接；不返回给前端）。 */
    Optional<String> apiKeyOf(String id);

    /** 写入模型（id 为空 = 新建）。 */
    record ModelConnectionView(
            String id,
            String name,
            String provider,
            String baseUrl,
            String modelName,
            double temperature,
            int maxIters,
            boolean enabled,
            boolean isDefault) {
    }
}
