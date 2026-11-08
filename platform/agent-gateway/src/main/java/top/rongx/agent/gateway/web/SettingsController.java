package top.rongx.agent.gateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.domain.settings.ModelConnection;
import top.rongx.agent.domain.settings.ModelConnectionRepository;
import top.rongx.agent.domain.settings.SshAccess;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.settings.ModelProbe;

/**
 * 设置：服务器配置 + 模型连接。
 *
 * <p>安全：密钥/密码只写不读（接口只返回掩码）；写操作仅 ADMIN；
 * 测试连接返回状态、延迟与可读原因，用于"保存前验证"。
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final DeployServerRepository servers;
    private final ModelConnectionRepository models;
    private final SshAccess ssh;
    private final ModelProbe modelProbe;
    private final CurrentUser currentUser;

    public SettingsController(DeployServerRepository servers,
                              ModelConnectionRepository models,
                              SshAccess ssh,
                              ModelProbe modelProbe,
                              CurrentUser currentUser) {
        this.servers = servers;
        this.models = models;
        this.ssh = ssh;
        this.modelProbe = modelProbe;
        this.currentUser = currentUser;
    }

    // ---------------------------------------------------------------- 服务器

    @GetMapping("/servers")
    public List<DeployServer> listServers() {
        requireRead();
        return servers.findAll();
    }

    @PostMapping("/servers")
    public DeployServer createServer(@RequestBody Map<String, Object> body) {
        requireWrite();
        return servers.save(serverView(body), str(body, "secret"), currentUser.requireUserId());
    }

    @PutMapping("/servers/{id}")
    public DeployServer updateServer(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireWrite();
        Map<String, Object> withId = new LinkedHashMap<>(body);
        withId.put("id", id);
        return servers.save(serverView(withId), str(body, "secret"), currentUser.requireUserId());
    }

    @DeleteMapping("/servers/{id}")
    public Map<String, Object> deleteServer(@PathVariable String id) {
        requireWrite();
        DeployServer server = servers.find(id).orElseThrow(
                () -> new IllegalArgumentException("服务器不存在：" + id));
        if (server.isDefault()) {
            throw new IllegalStateException("默认服务器不可删除，请先把其它服务器设为默认");
        }
        servers.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/servers/{id}/default")
    public Map<String, Object> defaultServer(@PathVariable String id) {
        requireWrite();
        servers.find(id).orElseThrow(() -> new IllegalArgumentException("服务器不存在：" + id));
        servers.markDefault(id);
        return Map.of("ok", true);
    }

    /** 已保存服务器的连通性测试。 */
    @PostMapping("/servers/{id}/test")
    public Map<String, Object> testServer(@PathVariable String id) {
        requireWrite();
        DeployServer server = servers.find(id).orElseThrow(
                () -> new IllegalArgumentException("服务器不存在：" + id));
        return runServerTest(server, servers.secretOf(id).orElse(null), true);
    }

    /** 未保存配置的测试（保存前验证）。 */
    @PostMapping("/servers/test")
    public Map<String, Object> testServerDraft(@RequestBody Map<String, Object> body) {
        requireWrite();
        DeployServerRepository.DeployServerView view = serverView(body);
        String secret = str(body, "secret");
        DeployServer draft = new DeployServer(
                "draft", view.name(), view.host(), view.port(), view.sshUser(), view.authType(),
                view.keyPath(), view.remoteDir(), true, false,
                secret != null && !secret.isBlank(), null,
                null, null, null, null, System.currentTimeMillis());
        return runServerTest(draft, secret, false);
    }

    private Map<String, Object> runServerTest(DeployServer server, String secret, boolean persist) {
        long start = System.nanoTime();
        SshAccess.Result result = ssh.probe(server, secret);
        long latency = (System.nanoTime() - start) / 1_000_000L;
        if (persist && !"draft".equals(server.id())) {
            servers.recordTest(server.id(), result.ok(), result.ok()
                    ? "连接正常（" + result.output().trim() + "）" : result.error(), latency);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("latencyMs", latency);
        out.put("dockerVersion", result.ok() ? result.output().trim() : null);
        out.put("message", result.ok() ? "连接正常" : result.error());
        return out;
    }

    private DeployServerRepository.DeployServerView serverView(Map<String, Object> body) {
        String host = str(body, "host");
        String user = str(body, "sshUser");
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host 必填");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("sshUser 必填");
        }
        String authType = str(body, "authType");
        authType = authType == null || authType.isBlank() ? "KEY" : authType.toUpperCase();
        if (!"KEY".equals(authType) && !"PASSWORD".equals(authType)) {
            throw new IllegalArgumentException("authType 只能是 KEY 或 PASSWORD");
        }
        int port = body.get("port") instanceof Number n ? n.intValue() : 22;
        String remoteDir = str(body, "remoteDir");
        String name = str(body, "name");
        return new DeployServerRepository.DeployServerView(
                str(body, "id"),
                name == null || name.isBlank() ? host : name,
                host,
                port,
                user,
                authType,
                str(body, "keyPath"),
                remoteDir == null || remoteDir.isBlank() ? "/opt/agent/deploy" : remoteDir,
                !(body.get("enabled") instanceof Boolean b) || b,
                Boolean.TRUE.equals(body.get("isDefault")));
    }

    // ---------------------------------------------------------------- 模型

    @GetMapping("/models")
    public List<ModelConnection> listModels() {
        requireRead();
        return models.findAll();
    }

    @PostMapping("/models")
    public ModelConnection createModel(@RequestBody Map<String, Object> body) {
        requireWrite();
        return models.save(modelView(body), str(body, "apiKey"), currentUser.requireUserId());
    }

    @PutMapping("/models/{id}")
    public ModelConnection updateModel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireWrite();
        Map<String, Object> withId = new LinkedHashMap<>(body);
        withId.put("id", id);
        return models.save(modelView(withId), str(body, "apiKey"), currentUser.requireUserId());
    }

    @DeleteMapping("/models/{id}")
    public Map<String, Object> deleteModel(@PathVariable String id) {
        requireWrite();
        ModelConnection connection = models.find(id).orElseThrow(
                () -> new IllegalArgumentException("模型不存在：" + id));
        if (connection.isDefault()) {
            throw new IllegalStateException("默认模型不可删除，请先把其它模型设为默认");
        }
        models.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/models/{id}/default")
    public Map<String, Object> defaultModel(@PathVariable String id) {
        requireWrite();
        models.find(id).orElseThrow(() -> new IllegalArgumentException("模型不存在：" + id));
        models.markDefault(id);
        return Map.of("ok", true);
    }

    /** 已保存模型的连通性测试。 */
    @PostMapping("/models/{id}/test")
    public Map<String, Object> testModel(@PathVariable String id) {
        requireWrite();
        ModelConnection connection = models.find(id).orElseThrow(
                () -> new IllegalArgumentException("模型不存在：" + id));
        String apiKey = models.apiKeyOf(id).orElse("");
        ModelProbe.Result result = modelProbe.probe(
                connection.baseUrl(), apiKey, connection.modelName());
        models.recordTest(id, result.ok(), result.ok() ? "连接正常" : result.message(),
                result.latencyMs());
        return modelTestResponse(result);
    }

    /** 未保存配置的测试（保存前验证）。 */
    @PostMapping("/models/test")
    public Map<String, Object> testModelDraft(@RequestBody Map<String, Object> body) {
        requireWrite();
        String baseUrl = str(body, "baseUrl");
        String modelName = str(body, "modelName");
        if (baseUrl == null || baseUrl.isBlank() || modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("baseUrl 与 modelName 必填");
        }
        String apiKey = str(body, "apiKey");
        String connectionId = str(body, "connectionId");
        if ((apiKey == null || apiKey.isBlank()) && connectionId != null && !connectionId.isBlank()) {
            apiKey = models.apiKeyOf(connectionId).orElse("");
        }
        return modelTestResponse(modelProbe.probe(baseUrl, apiKey, modelName));
    }

    /** 轻量选项：给对话页展示当前模型名（不含任何密钥）。 */
    @GetMapping("/models/options")
    public Map<String, Object> modelOptions() {
        currentUser.requireUserId();
        ModelConnection active = models.findDefault().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeId", active == null ? null : active.id());
        out.put("activeName", active == null ? null : active.name());
        out.put("modelName", active == null ? null : active.modelName());
        out.put("configured", active != null);
        return out;
    }

    private Map<String, Object> modelTestResponse(ModelProbe.Result result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("latencyMs", result.latencyMs());
        out.put("reply", result.reply());
        out.put("message", result.message());
        return out;
    }

    private ModelConnectionRepository.ModelConnectionView modelView(Map<String, Object> body) {
        String baseUrl = str(body, "baseUrl");
        String modelName = str(body, "modelName");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 必填");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName 必填");
        }
        double temperature = body.get("temperature") instanceof Number n ? n.doubleValue() : 0.2d;
        int maxIters = body.get("maxIters") instanceof Number n ? n.intValue() : 8;
        String provider = str(body, "provider");
        String name = str(body, "name");
        return new ModelConnectionRepository.ModelConnectionView(
                str(body, "id"),
                name == null || name.isBlank() ? modelName : name,
                provider == null || provider.isBlank() ? "custom" : provider,
                baseUrl.trim(),
                modelName.trim(),
                Math.max(0, Math.min(2, temperature)),
                Math.max(1, Math.min(30, maxIters)),
                !(body.get("enabled") instanceof Boolean b) || b,
                Boolean.TRUE.equals(body.get("isDefault")));
    }

    // ---------------------------------------------------------------- 权限 / 工具

    private void requireRead() {
        currentUser.checkPermission("settings:read");
    }

    private void requireWrite() {
        currentUser.checkPermission("settings:write");
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
