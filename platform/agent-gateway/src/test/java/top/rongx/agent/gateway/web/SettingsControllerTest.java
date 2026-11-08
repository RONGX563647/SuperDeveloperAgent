package top.rongx.agent.gateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.domain.settings.ModelConnection;
import top.rongx.agent.domain.settings.ModelConnectionRepository;
import top.rongx.agent.domain.settings.SshAccess;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.settings.ModelProbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置接口：权限、掩码、未保存测试。
 *
 * <p>用内存替身替换存储与 SSH，不依赖 Spring 上下文。
 */
class SettingsControllerTest {

    private final Map<String, DeployServer> serverStore = new LinkedHashMap<>();
    private int seq = 0;

    private final DeployServerRepository servers = new DeployServerRepository() {
        @Override
        public List<DeployServer> findAll() {
            return List.copyOf(serverStore.values());
        }

        @Override
        public Optional<DeployServer> find(String id) {
            return Optional.ofNullable(serverStore.get(id));
        }

        @Override
        public Optional<DeployServer> findDefault() {
            return serverStore.values().stream().filter(DeployServer::isDefault).findFirst();
        }

        @Override
        public DeployServer save(DeployServerView view, String secret, String updatedBy) {
            String id = view.id() == null || view.id().isBlank() ? "srv-" + (++seq) : view.id();
            DeployServer server = new DeployServer(id, view.name(), view.host(), view.port(),
                    view.sshUser(), view.authType(), view.keyPath(), view.remoteDir(),
                    view.enabled(), view.isDefault(), secret != null && !secret.isBlank(), "****",
                    null, null, null, updatedBy, System.currentTimeMillis());
            serverStore.put(id, server);
            return server;
        }

        @Override
        public void delete(String id) {
            serverStore.remove(id);
        }

        @Override
        public void markDefault(String id) {
            serverStore.replaceAll((k, v) -> new DeployServer(v.id(), v.name(), v.host(),
                    v.port(), v.sshUser(), v.authType(), v.keyPath(), v.remoteDir(), v.enabled(),
                    k.equals(id), v.hasSecret(), v.secretMasked(), v.lastTestAt(), v.lastTestOk(),
                    v.lastTestMsg(), v.updatedBy(), v.updatedAt()));
        }

        @Override
        public void recordTest(String id, boolean ok, String message, Long latencyMs) {
            // no-op（测试断言用）
        }

        @Override
        public Optional<String> secretOf(String id) {
            return Optional.of("secret-value");
        }
    };

    private final ModelConnectionRepository models = new ModelConnectionRepository() {
        @Override
        public List<ModelConnection> findAll() {
            return List.of();
        }

        @Override
        public Optional<ModelConnection> find(String id) {
            return Optional.empty();
        }

        @Override
        public Optional<ModelConnection> findDefault() {
            return Optional.empty();
        }

        @Override
        public ModelConnection save(ModelConnectionView view, String apiKey, String updatedBy) {
            return null;
        }

        @Override
        public void delete(String id) {
            // no-op
        }

        @Override
        public void markDefault(String id) {
            // no-op
        }

        @Override
        public void recordTest(String id, boolean ok, String message, Long latencyMs) {
            // no-op
        }

        @Override
        public Optional<String> apiKeyOf(String id) {
            return Optional.empty();
        }
    };

    private final SshAccess ssh = new SshAccess() {
        boolean called;

        @Override
        public Result probe(DeployServer server, String secret) {
            called = true;
            return Result.ok("docker 29.2.1");
        }

        @Override
        public Result run(DeployServer server, String secret, String remoteCommand, long timeoutMs) {
            return Result.ok("");
        }

        @Override
        public Result upload(DeployServer server, String secret, java.nio.file.Path local,
                             String remotePath, long timeoutMs) {
            return Result.ok("");
        }
    };

    private final CurrentUser allowed = new CurrentUser() {
        @Override
        public String requireUserId() {
            return "tester";
        }

        @Override
        public void checkPermission(String permission) {
            // 放行
        }
    };

    private final CurrentUser denied = new CurrentUser() {
        @Override
        public String requireUserId() {
            return "viewer";
        }

        @Override
        public void checkPermission(String permission) {
            throw new SecurityException("无权限：" + permission);
        }
    };

    private SettingsController controller(CurrentUser user) {
        return new SettingsController(servers, models, ssh, new ModelProbe(
                new com.fasterxml.jackson.databind.ObjectMapper()), user);
    }

    @Test
    @DisplayName("写入需要 settings:write；只读权限被拒")
    void writeRequiresPermission() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("host", "10.0.0.12");
        body.put("sshUser", "deploy");
        assertThrows(SecurityException.class, () -> controller(denied).createServer(body));
    }

    @Test
    @DisplayName("新增服务器：密钥只回 hasSecret/掩码，不回明文")
    void createServerMasksSecret() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "生产");
        body.put("host", "10.0.0.12");
        body.put("sshUser", "deploy");
        body.put("authType", "PASSWORD");
        body.put("secret", "p@ssw0rd");
        body.put("isDefault", true);

        DeployServer created = controller(allowed).createServer(body);

        assertTrue(created.hasSecret());
        assertEquals("****", created.secretMasked());
        assertFalse(String.valueOf(created).contains("p@ssw0rd"));
        assertTrue(controller(allowed).listServers().get(0).isDefault());
    }

    @Test
    @DisplayName("未保存配置也能测试连接（保存前验证）")
    void testDraftServer() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("host", "10.0.0.12");
        body.put("sshUser", "deploy");
        body.put("keyPath", "~/.ssh/id_ed25519");

        Map<String, Object> result = controller(allowed).testServerDraft(body);

        assertEquals(true, result.get("ok"));
        assertEquals("docker 29.2.1", result.get("dockerVersion"));
    }
}
