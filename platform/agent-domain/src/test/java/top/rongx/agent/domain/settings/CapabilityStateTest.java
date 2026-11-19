package top.rongx.agent.domain.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 能力可用状态的口径。
 *
 * <p>「会话能力 skill/mcp 不实用要大改」的核心是：接口只给名字，
 * 用户装完发现用不上却不知道为什么。这里把四种状态与它们的原因码钉死。
 */
class CapabilityStateTest {

    @Test
    @DisplayName("可用：无原因码、无原因、无缺失变量")
    void usableHasNoReason() {
        CapabilityState state = CapabilityState.usable("web-search", "MCP");

        assertTrue(state.available());
        assertTrue(state.enabled());
        assertTrue(state.installed());
        assertTrue(state.reasonCode().isEmpty());
        assertTrue(state.reason().isEmpty());
        assertTrue(state.missingEnv().isEmpty());
        assertTrue(state.consistent());
    }

    @Test
    @DisplayName("未开启：给出 DISABLED 与去哪打开")
    void disabledExplains() {
        CapabilityState state = CapabilityState.disabled("web-search", "MCP", true);

        assertFalse(state.available());
        assertTrue(state.installed(), "未开启不代表没装");
        assertEquals("CAPABILITY_DISABLED", state.reasonCode());
        assertTrue(state.reason().contains("设置"), state.reason());
        assertTrue(state.consistent());
    }

    @Test
    @DisplayName("未安装：给出 NOT_INSTALLED 与接入入口")
    void notInstalledExplains() {
        CapabilityState state = CapabilityState.notInstalled("pdf-reader", "SKILL", true);

        assertFalse(state.available());
        assertFalse(state.installed());
        assertEquals("CAPABILITY_NOT_INSTALLED", state.reasonCode());
        assertTrue(state.reason().contains("接入"), state.reason());
        assertTrue(state.consistent());
    }

    @Test
    @DisplayName("缺环境变量：列出缺哪几个，而不是只说不可用")
    void missingEnvListsKeys() {
        CapabilityState state = CapabilityState.missingEnv("web-search", "MCP",
                List.of("SEARCH_API_KEY", "SEARCH_ENDPOINT"));

        assertFalse(state.available());
        assertEquals("CAPABILITY_MISSING_ENV", state.reasonCode());
        assertEquals(List.of("SEARCH_API_KEY", "SEARCH_ENDPOINT"), state.missingEnv());
        assertTrue(state.reason().contains("SEARCH_API_KEY"), state.reason());
        assertTrue(state.reason().contains("SEARCH_ENDPOINT"), state.reason());
        assertTrue(state.consistent());
    }

    @Test
    @DisplayName("连接方式不可用：COMMAND/URL 都没有时给出 TRANSPORT_UNAVAILABLE")
    void transportUnavailableExplains() {
        CapabilityState state = CapabilityState.transportUnavailable("remote-mcp", "MCP", null);

        assertFalse(state.available());
        assertEquals("CAPABILITY_TRANSPORT_UNAVAILABLE", state.reasonCode());
        assertTrue(state.reason().contains("未配置"), state.reason());
        assertTrue(state.consistent());
    }

    @Test
    @DisplayName("kind 归一化大写，null 安全")
    void kindIsNormalized() {
        assertEquals("MCP", CapabilityState.usable("x", "mcp").kind());
        assertEquals("", CapabilityState.usable("x", null).kind());
        assertTrue(CapabilityState.usable(null, null).name().isEmpty());
        assertTrue(CapabilityState.missingEnv("x", "MCP", null).missingEnv().isEmpty());
    }
}
