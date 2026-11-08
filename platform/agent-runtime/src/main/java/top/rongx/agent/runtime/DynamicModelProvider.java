package top.rongx.agent.runtime;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.ModelRegistry;
import top.rongx.agent.domain.port.ModelRegistry.ActiveModel;

/**
 * 动态模型提供者：设置页保存后**无需重启**即可切换模型。
 *
 * <p>实现要点：
 * <ul>
 *   <li>持有不可变快照 {@code {fingerprint, model}}；指纹 = id + baseUrl + model + apiKey 哈希；</li>
 *   <li>{@link #current()} 每次读取注册表指纹，变化时重建 {@link Model}（旧实例自然被回收）；</li>
 *   <li>{@code volatile} + 局部变量赋值，避免并发读到半成品。</li>
 * </ul>
 */
@Component
public class DynamicModelProvider {

    private final ModelRegistry registry;

    private volatile Snapshot snapshot;

    public DynamicModelProvider(ModelRegistry registry) {
        this.registry = registry;
    }

    private record Snapshot(String fingerprint, Model model, ActiveModel config) {
    }

    /** 当前模型（线程安全；配置未变时复用实例）。 */
    public Model current() {
        ActiveModel config = registry.active().orElseThrow(
                () -> new IllegalStateException("没有可用的模型配置"));
        String fingerprint = fingerprint(config);
        Snapshot local = snapshot;
        if (local != null && local.fingerprint().equals(fingerprint)) {
            return local.model();
        }
        synchronized (this) {
            local = snapshot;
            if (local != null && local.fingerprint().equals(fingerprint)) {
                return local.model();
            }
            Model built = build(config);
            snapshot = new Snapshot(fingerprint, built, config);
            return built;
        }
    }

    /** 当前生效配置（供对话页展示模型名）。 */
    public ActiveModel activeConfig() {
        return registry.active().orElseThrow(
                () -> new IllegalStateException("没有可用的模型配置"));
    }

    private Model build(ActiveModel config) {
        return OpenAIChatModel.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .temperature(config.temperature()).build())
                .build();
    }

    private String fingerprint(ActiveModel c) {
        return c.id() + "|" + c.baseUrl() + "|" + c.modelName() + "|"
                + Integer.toHexString(String.valueOf(c.apiKey()).hashCode())
                + "|" + c.temperature();
    }
}
