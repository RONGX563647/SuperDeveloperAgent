package top.rongx.agent.runtime;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.settings.ModelConnection;
import top.rongx.agent.domain.settings.ModelConnectionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型注册表：设置页优先、环境变量兜底、两边都没有时返回 empty（不阻塞启动）。
 * 空密钥的连接是合法的——本地 Ollama / vLLM 等端点不需要 Authorization。
 */
class SettingsModelRegistryTest {

    private static final AgentProperties NO_KEY = props("");
    private static final AgentProperties WITH_KEY = props("sk-env");

    private static AgentProperties props(String key) {
        return new AgentProperties("https://env.example/v1", key, "qwen-env", null, null);
    }

    @Test
    @DisplayName("无连接且无环境变量 → active() 为空，由调用方提示去设置页")
    void emptyWhenNothingConfigured() {
        assertTrue(new SettingsModelRegistry(new FakeRepo(null, ""), NO_KEY).active().isEmpty());
    }

    @Test
    @DisplayName("无连接但有环境变量 → 回退到环境变量配置")
    void fallsBackToEnvKey() {
        var active = new SettingsModelRegistry(new FakeRepo(null, ""), WITH_KEY)
                .active().orElseThrow();
        assertEquals("env", active.id());
        assertEquals("sk-env", active.apiKey());
    }

    @Test
    @DisplayName("设置页连接优先；即使密钥为空（免鉴权端点）也不退回环境变量")
    void configuredConnectionWinsEvenWithoutKey() {
        var active = new SettingsModelRegistry(new FakeRepo(connection(), ""), WITH_KEY)
                .active().orElseThrow();
        assertEquals("mdl-1", active.id());
        assertEquals("", active.apiKey());
        assertEquals("http://127.0.0.1:11434/v1", active.baseUrl());
    }

    private static ModelConnection connection() {
        return new ModelConnection("mdl-1", "本地 Ollama", "ollama",
                "http://127.0.0.1:11434/v1", "qwen2.5", 0.2, 8,
                true, true, false, null, null, null, null, null, "admin", 0L);
    }

    /** 只实现运行时读取路径，写方法不在本用例范围内。 */
    private static final class FakeRepo implements ModelConnectionRepository {
        private final ModelConnection connection;
        private final String apiKey;

        FakeRepo(ModelConnection connection, String apiKey) {
            this.connection = connection;
            this.apiKey = apiKey;
        }

        @Override
        public List<ModelConnection> findAll() {
            return connection == null ? List.of() : List.of(connection);
        }

        @Override
        public Optional<ModelConnection> find(String id) {
            return Optional.ofNullable(connection).filter(c -> c.id().equals(id));
        }

        @Override
        public Optional<ModelConnection> findDefault() {
            return Optional.ofNullable(connection);
        }

        @Override
        public ModelConnection save(ModelConnectionView view, String key, String updatedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markDefault(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordTest(String id, boolean ok, String message, Long latencyMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> apiKeyOf(String id) {
            return Optional.ofNullable(apiKey).filter(k -> !k.isBlank());
        }
    }
}
