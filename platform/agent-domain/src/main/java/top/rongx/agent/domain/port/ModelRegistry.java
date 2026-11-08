package top.rongx.agent.domain.port;

import java.util.Optional;

/**
 * 运行时模型注册表：返回"当前生效的模型连接"。
 *
 * <p>优先级：设置页配置（enabled 的默认项）→ application.yml 的 agent.model.*（兜底）。
 * 实现负责缓存与热切换（保存后无需重启）。
 */
public interface ModelRegistry {

    /** 生效模型快照（含解密后的 apiKey；只在进程内使用，不得出接口）。 */
    record ActiveModel(
            String id,
            String name,
            String baseUrl,
            String apiKey,
            String modelName,
            double temperature,
            int maxIters) {
    }

    Optional<ActiveModel> active();
}
