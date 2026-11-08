package top.rongx.agent.runtime;

import java.util.Optional;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.ModelRegistry;
import top.rongx.agent.domain.settings.ModelConnection;
import top.rongx.agent.domain.settings.ModelConnectionRepository;

/**
 * 模型注册表实现：设置页配置优先，application.yml 兜底。
 *
 * <p>只做"取当前生效配置"，不做缓存（缓存与热切换在 {@link DynamicModelProvider}）。
 */
@Component
public class SettingsModelRegistry implements ModelRegistry {

    private final ModelConnectionRepository connections;
    private final AgentProperties fallback;

    public SettingsModelRegistry(ModelConnectionRepository connections, AgentProperties fallback) {
        this.connections = connections;
        this.fallback = fallback;
    }

    @Override
    public Optional<ActiveModel> active() {
        Optional<ModelConnection> configured = connections.findDefault();
        if (configured.isPresent()) {
            ModelConnection c = configured.get();
            String apiKey = connections.apiKeyOf(c.id()).orElse("");
            return Optional.of(new ActiveModel(
                    c.id(), c.name(), c.baseUrl(), apiKey, c.modelName(),
                    c.temperature(), c.maxIters()));
        }
        return Optional.of(new ActiveModel(
                "env", "环境变量配置", fallback.baseUrl(), fallback.apiKey(),
                fallback.model(), fallback.temperature(), fallback.maxIters()));
    }
}
