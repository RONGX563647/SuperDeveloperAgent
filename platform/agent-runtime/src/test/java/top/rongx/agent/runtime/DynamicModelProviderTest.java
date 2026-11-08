package top.rongx.agent.runtime;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.port.ModelRegistry;
import top.rongx.agent.domain.port.ModelRegistry.ActiveModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        DynamicModelProvider provider = new DynamicModelProvider(registry);

        assertSame(provider.current(), provider.current());
        assertEquals("qwen-plus", provider.activeConfig().modelName());
    }

    @Test
    @DisplayName("配置变化（换模型）→ 重建实例")
    void rebuildsOnChange() {
        MutableRegistry registry = new MutableRegistry(new ActiveModel(
                "mdl-1", "默认", "https://example.com/v1", "sk-test", "qwen-plus", 0.2, 8));
        DynamicModelProvider provider = new DynamicModelProvider(registry);
        Object before = provider.current();

        registry.value.set(new ActiveModel(
                "mdl-2", "备用", "https://example.com/v1", "sk-test", "deepseek-chat", 0.2, 8));
        Object after = provider.current();

        assertNotSame(before, after);
        assertEquals("deepseek-chat", provider.activeConfig().modelName());
    }

    @Test
    @DisplayName("没有可用配置 → 明确报错，不返回 null")
    void failsWhenUnconfigured() {
        MutableRegistry registry = new MutableRegistry(null);
        DynamicModelProvider provider = new DynamicModelProvider(registry);
        assertTrue(assertThrowsMessage(provider).contains("没有可用"));
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
