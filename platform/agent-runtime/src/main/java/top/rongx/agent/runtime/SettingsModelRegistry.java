package top.rongx.agent.runtime;

import java.util.Optional;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.ModelRegistry;
import top.rongx.agent.domain.settings.ModelConnection;
import top.rongx.agent.domain.settings.ModelConnectionRepository;

/**
 * 模型注册表实现：设置页配置优先，环境变量（application.yml）兜底。
 *
 * <p>只做"取当前生效配置"，不做缓存（缓存与热切换在 {@link DynamicModelProvider}）。
 * 两边都没配时返回 {@link Optional#empty()}——这是"调用模型时才提示去配置"的语义，
 * <b>不是启动失败</b>：否则没配密钥就进不了设置页这个配置入口。
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
            // 空 key 是合法配置：Ollama / 本地 vLLM 等免鉴权端点不需要 Authorization 头，
            // 因此这里不因"没密钥"而拒绝设置页显式建好的连接。
            String apiKey = connections.apiKeyOf(c.id()).orElse("");
            return Optional.of(new ActiveModel(
                    c.id(), c.name(), c.baseUrl(), apiKey, c.modelName(),
                    c.temperature(), c.maxIters()));
        }
        // 设置页还没建连接时，只有环境变量注入了 key 才算有可用模型。
        if (!fallback.hasApiKey()) {
            return Optional.empty();
        }
        return Optional.of(new ActiveModel(
                "env", "环境变量配置", fallback.baseUrl(), fallback.apiKey(),
                fallback.model(), fallback.temperature(), fallback.maxIters()));
    }

    @Override
    public Optional<ActiveModel> resolve(String connectionId, String modelName) {
        String picked = modelName == null || modelName.isBlank() ? null : modelName.trim();
        if (connectionId == null || connectionId.isBlank()) {
            // 只给了模型名：默认连接上覆盖模型；连默认连接都没有时才走环境变量兜底
            Optional<ModelConnection> configured = connections.findDefault();
            if (configured.isPresent()) {
                return Optional.of(toActive(configured.get(), picked));
            }
            return active().map(a -> picked == null ? a : new ActiveModel(
                    a.id(), a.name(), a.baseUrl(), a.apiKey(), picked,
                    a.temperature(), a.maxIters()));
        }
        return connections.find(connectionId)
                .filter(ModelConnection::enabled)
                .map(c -> toActive(c, picked));
    }

    private ActiveModel toActive(ModelConnection c, String modelName) {
        return new ActiveModel(c.id(), c.name(), c.baseUrl(),
                connections.apiKeyOf(c.id()).orElse(""),
                modelName == null ? c.modelName() : modelName,
                c.temperature(), c.maxIters());
    }
}
