package top.rongx.agent.runtime;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.port.ModelRegistry;
import top.rongx.agent.domain.port.ModelRegistry.ActiveModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 动态模型：配置不变复用实例；配置变化重建（热切换）。 */
class DynamicModelProviderTest {

    private static final class MutableRegistry implements ModelRegistry {
        final AtomicReference<ActiveModel> value = new AtomicReference<>();

        MutableRegistry(ActiveModel initial) {
            value.set(initial);
        }

        @Override
        public Optional<ActiveModel> active() {
            return Optional.ofNullable(value.get());
        }
    }

    @Test
    @DisplayName("同一配置多次取用 → 复用同一 Model 实例")
    void reusesInstance() {
        MutableRegistry registry = new MutableRegistry(new ActiveModel(
                "mdl-1", "默认", "https://example.com/v1", "sk-test", "qwen-plus", 0.2, 8));
        DynamicModelProvider provider = new DynamicModelProvider(registry, true);

        assertSame(provider.current(), provider.current());
        assertEquals("qwen-plus", provider.activeConfig().modelName());
    }

    @Test
    @DisplayName("配置变化（换模型）→ 重建实例")
    void rebuildsOnChange() {
        MutableRegistry registry = new MutableRegistry(new ActiveModel(
                "mdl-1", "默认", "https://example.com/v1", "sk-test", "qwen-plus", 0.2, 8));
        DynamicModelProvider provider = new DynamicModelProvider(registry, true);
        Object before = provider.current();

        registry.value.set(new ActiveModel(
                "mdl-2", "备用", "https://example.com/v1", "sk-test", "deepseek-chat", 0.2, 8));
        Object after = provider.current();

        assertNotSame(before, after);
        assertEquals("deepseek-chat", provider.activeConfig().modelName());
    }

    @Test
    @DisplayName("没有可用配置 → 明确报错并给出设置页指引，不返回 null")
    void failsWhenUnconfigured() {
        MutableRegistry registry = new MutableRegistry(null);
        DynamicModelProvider provider = new DynamicModelProvider(registry, true);
        String message = assertThrowsMessage(provider);
        assertTrue(message.contains("没有可用"));
        assertTrue(message.contains("设置"), "要指明配置入口");
        assertTrue(message.contains("AGENT_MODEL_API_KEY"), "要保留环境变量兜底说明");
    }

    @Test
    @DisplayName("设置页显式配置的免鉴权端点（空密钥）→ 仍可用")
    void allowsKeylessConfiguredEndpoint() {
        MutableRegistry registry = new MutableRegistry(new ActiveModel(
                "mdl-ollama", "本地 Ollama", "http://127.0.0.1:11434/v1", "", "qwen2.5", 0.2, 8));
        DynamicModelProvider provider = new DynamicModelProvider(registry, true);

        assertNotNull(provider.current());
        assertEquals("", provider.activeConfig().apiKey());
    }

    @Test
    @DisplayName("设置页保存模型后 → 无需重启即可取到模型")
    void picksUpConfigWithoutRestart() {
        MutableRegistry registry = new MutableRegistry(null);
        DynamicModelProvider provider = new DynamicModelProvider(registry, true);
        assertThrows(IllegalStateException.class, provider::current);

        registry.value.set(new ActiveModel(
                "mdl-1", "设置页配置", "https://example.com/v1", "sk-web", "qwen-plus", 0.2, 8));
        assertNotNull(provider.current());
        assertEquals("sk-web", provider.activeConfig().apiKey());
    }

    private String assertThrowsMessage(DynamicModelProvider provider) {
        try {
            provider.current();
            return "";
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }
}
