package top.rongx.agent.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型配置绑定：API Key 允许缺失（否则"没配 key → 启不来 → 进不了设置页配 key"），
 * 其余边界仍收敛到安全侧。
 */
class AgentPropertiesTest {

    private static final String BASE_URL = "https://example.com/v1";

    @Test
    @DisplayName("api-key 为空 / null / 全空白 → 绑定成功，hasApiKey=false")
    void blankApiKeyDoesNotBlockStartup() {
        for (String key : new String[]{"", null, "   "}) {
            AgentProperties props = new AgentProperties(BASE_URL, key, "qwen-plus", null, null);
            assertEquals("", props.apiKey(), "缺失的密钥统一归一为空串");
            assertFalse(props.hasApiKey());
        }
    }

    @Test
    @DisplayName("环境变量注入的 key 仍原样保留")
    void envKeyStillWorks() {
        AgentProperties props = new AgentProperties(BASE_URL, "sk-xxx", "qwen-plus", null, null);
        assertTrue(props.hasApiKey());
        assertEquals("sk-xxx", props.apiKey());
    }

    @Test
    @DisplayName("缺省值与上限仍生效；baseUrl / model 缺失仍快速失败")
    void otherGuardsStillApply() {
        AgentProperties defaults = new AgentProperties(BASE_URL, "", "qwen-plus", null, null);
        assertEquals(8, defaults.maxIters());
        assertEquals(0.2d, defaults.temperature(), 1e-9);

        AgentProperties capped = new AgentProperties(BASE_URL, "", "qwen-plus", 999, 5.0d);
        assertEquals(30, capped.maxIters());
        assertEquals(5.0d, capped.temperature(), 1e-9);

        assertThrows(IllegalStateException.class,
                () -> new AgentProperties("", "sk-xxx", "qwen-plus", null, null));
        assertThrows(IllegalStateException.class,
                () -> new AgentProperties(BASE_URL, "sk-xxx", "", null, null));
    }
}
