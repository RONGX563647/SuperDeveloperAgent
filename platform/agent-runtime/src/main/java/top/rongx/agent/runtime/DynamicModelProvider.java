package top.rongx.agent.runtime;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.ModelRegistry;
import top.rongx.agent.domain.port.ModelRegistry.ActiveModel;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * 动态模型提供者：设置页保存后**无需重启**即可切换模型。
 *
 * <p>实现要点：
 * <ul>
 *   <li>持有不可变快照 {@code {fingerprint, model}}；指纹 = id + baseUrl + model + apiKey 哈希；</li>
 *   <li>{@link #current()} 每次读取注册表指纹，变化时重建 {@link Model}（旧实例自然被回收）；</li>
 *   <li>配置可缺失（设置页随时可配）：不阻塞启动，真正调用模型时才给出配置指引；
 *       已建连接允许空密钥（本地免鉴权端点）；</li>
 *   <li>{@code volatile} + 局部变量赋值，避免并发读到半成品。</li>
 * </ul>
 */
@Component
public class DynamicModelProvider {

    /** 没有任何可用模型配置时的统一指引（只在调用模型时抛出，不阻塞启动）。 */
    private static final String NO_MODEL = "没有可用的模型配置：请在「设置 → 模型连接」中配置，"
            + "或通过环境变量 AGENT_MODEL_API_KEY 注入（规约 §8）";
    /** 缓存上限：模型组合通常只有个位数，超过说明连接被频繁改，清空即可。 */
    private static final int MAX_CACHED = 32;

    private final ModelRegistry registry;
    private final boolean prefixCache;

    /** key = 连接 + 模型 + 端点 + 密钥哈希 + 温度；配置未变时复用同一个 Model 实例。 */
    private final Map<String, Model> cache = new ConcurrentHashMap<>();

    public DynamicModelProvider(ModelRegistry registry,
                                @Value("${agent.model.prefix-cache:true}") boolean prefixCache) {
        this.registry = registry;
        this.prefixCache = prefixCache;
    }

    /** 默认连接 / 默认模型（线程安全；配置未变时复用实例）。 */
    public Model current() {
        return current(registry.active().orElseThrow(() -> new RunException(ErrorCode.RUN_INVALID_STATE, NO_MODEL)));
    }

    /** 会话显式选择的连接 + 模型。 */
    public Model current(String connectionId, String modelName) {
        ActiveModel config = registry.resolve(connectionId, modelName).orElseThrow(
                () -> new RunException(ErrorCode.RUN_INVALID_STATE, "模型连接不可用或已停用："
                        + (connectionId == null ? "默认连接" : connectionId)));
        return current(config);
    }

    private Model current(ActiveModel config) {
        String fingerprint = fingerprint(config);
        if (cache.size() > MAX_CACHED) {
            cache.clear();
        }
        return cache.computeIfAbsent(fingerprint, key -> build(config));
    }

    /** 当前生效配置（供对话页展示模型名）。 */
    public ActiveModel activeConfig() {
        return registry.active().orElseThrow(
                () -> new RunException(ErrorCode.RUN_INVALID_STATE, NO_MODEL));
    }

    /** 会话显式选择对应的生效配置。 */
    public ActiveModel activeConfig(String connectionId, String modelName) {
        return registry.resolve(connectionId, modelName).orElseThrow(
                () -> new RunException(ErrorCode.RUN_INVALID_STATE, "模型连接不可用或已停用："
                        + (connectionId == null ? "默认连接" : connectionId)));
    }

    private Model build(ActiveModel config) {
        return OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .temperature(config.temperature())
                        // 前缀缓存：把稳定的系统提示 + 工具 schema 标记为可复用，
                        // 同一会话后续轮次只计增量 token（服务端不支持时是无害字段）
                        .cacheControl(prefixCache)
                        .build())
                .build();
    }

    private String fingerprint(ActiveModel c) {
        return c.id() + "|" + c.baseUrl() + "|" + c.modelName() + "|"
                + Integer.toHexString(String.valueOf(c.apiKey()).hashCode())
                + "|" + c.temperature();
    }
}
