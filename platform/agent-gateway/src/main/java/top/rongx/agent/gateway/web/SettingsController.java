package top.rongx.agent.gateway.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.domain.settings.ModelCatalogEntry;
import top.rongx.agent.domain.settings.ModelCatalogRepository;
import top.rongx.agent.domain.settings.ModelConnection;
import top.rongx.agent.domain.settings.ModelConnectionRepository;
import top.rongx.agent.domain.settings.SshAccess;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.settings.ModelProbe;
import top.rongx.agent.infra.settings.ModelDiscoveryClient;

/**
 * 设置：服务器配置 + 模型连接。
 *
 * <p>安全：密钥/密码只写不读（接口只返回掩码）；写操作仅 ADMIN；
 * 测试连接返回状态、延迟与可读原因，用于"保存前验证"。
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final DeployServerRepository servers;
    private final ModelConnectionRepository models;
    private final ModelCatalogRepository catalog;
    private final ModelDiscoveryClient discovery;
    private final SshAccess ssh;
    private final ModelProbe modelProbe;
    private final CurrentUser currentUser;

    public SettingsController(DeployServerRepository servers,
                              ModelConnectionRepository models,
                              ModelCatalogRepository catalog,
                              ModelDiscoveryClient discovery,
                              SshAccess ssh,
                              ModelProbe modelProbe,
                              CurrentUser currentUser) {
        this.servers = servers;
        this.models = models;
        this.catalog = catalog;
        this.discovery = discovery;
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
        servers.delete(id);
        // 默认服务器也允许删除：否则只剩一台默认服务器时，删除按钮被禁用、
        // 「设默认」又不可用于它本身，用户会陷入谁都删不掉的死局。
        // 删掉后自动把剩余启用服务器中最新的一台提升为默认（findAll 已按更新时间倒序）。
        if (server.isDefault()) {
            servers.findAll().stream()
                    .filter(DeployServer::enabled)
                    .findFirst()
                    .ifPresent(next -> servers.markDefault(next.id()));
        }
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
        return runServerTest(server, servers.secretOf(id).orElse(null), id);
    }

    /**
     * 当前表单的测试（保存前验证 + 编辑态验证）。
     *
     * <p>编辑态会带上已保存的 id：密码/口令留空时沿用库里的密钥，
     * 测试结果写回该行，列表里的「可连接 / 连接失败」才是本次结果而不是第一次的。
     */
    @PostMapping("/servers/test")
    public Map<String, Object> testServerDraft(@RequestBody Map<String, Object> body) {
        requireWrite();
        DeployServerRepository.DeployServerView view = serverView(body);
        String secret = str(body, "secret");
        String sourceId = str(body, "id");
        boolean saved = sourceId != null && !sourceId.isBlank()
                && servers.find(sourceId).isPresent();
        if ((secret == null || secret.isBlank()) && saved) {
            secret = servers.secretOf(sourceId).orElse(null);
        }
        DeployServer draft = new DeployServer(
                saved ? sourceId : "draft", view.name(), view.host(), view.port(),
                view.sshUser(), view.authType(), view.keyPath(), view.remoteDir(), true, false,
                secret != null && !secret.isBlank(), null,
                null, null, null, null, System.currentTimeMillis());
        return runServerTest(draft, secret, saved ? sourceId : null);
    }

    /** @param persistId 需要写回测试结果的行 id；纯草稿传 null。 */
    private Map<String, Object> runServerTest(DeployServer server, String secret, String persistId) {
        long start = System.nanoTime();
        SshAccess.Result result = ssh.probe(server, secret);
        long latency = (System.nanoTime() - start) / 1_000_000L;
        if (persistId != null && !persistId.isBlank()) {
            servers.recordTest(persistId, result.ok(), result.ok()
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
        ModelConnection saved = models.save(modelView(body), str(body, "apiKey"),
                currentUser.requireUserId());
        syncCatalog(saved);
        return saved;
    }

    @PutMapping("/models/{id}")
    public ModelConnection updateModel(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireWrite();
        Map<String, Object> withId = new LinkedHashMap<>(body);
        withId.put("id", id);
        ModelConnection saved = models.save(modelView(withId), str(body, "apiKey"),
                currentUser.requireUserId());
        syncCatalog(saved);
        return saved;
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
        catalog.deleteByConnection(id);
        return Map.of("ok", true);
    }

    /**
     * 同步网关模型目录：{@code GET {baseUrl}/models} 自动发现。
     *
     * <p>发现失败不阻塞保存（本地网关可能没有 /models），只把该连接配置的模型名登记为手工条目。
     */
    @PostMapping("/models/{id}/discover")
    public Map<String, Object> discoverModels(@PathVariable String id) {
        requireWrite();
        ModelConnection connection = models.find(id).orElseThrow(
                () -> new IllegalArgumentException("模型不存在：" + id));
        List<String> ids;
        try {
            ids = discovery.discover(connection.baseUrl(),
                    models.apiKeyOf(id).orElse(""));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("模型发现失败：" + e.getMessage());
        }
        List<ModelCatalogEntry> entries = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String modelId : ids) {
            entries.add(new ModelCatalogEntry(id, modelId, modelId, "discover", now));
        }
        catalog.replaceDiscovered(id, entries);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", entries.size());
        out.put("models", entries);
        return out;
    }

    /**
     * 保存后尽力同步目录：手工模型名立即登记，{@code /models} 自动发现在后台补。
     *
     * <p>发现可能超时（内网/本地网关），不能阻塞保存；失败只记日志，
     * 模型仍可用连接配置里的模型名。
     */
    private void syncCatalog(ModelConnection connection) {
        if (connection == null) {
            return;
        }
        catalog.upsert(new ModelCatalogEntry(connection.id(), connection.modelName(),
                connection.modelName(), "manual", System.currentTimeMillis()));
        if (!connection.enabled()) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                List<String> ids = discovery.discover(connection.baseUrl(),
                        models.apiKeyOf(connection.id()).orElse(""));
                if (!ids.isEmpty()) {
                    long now = System.currentTimeMillis();
                    List<ModelCatalogEntry> entries = new ArrayList<>();
                    for (String modelId : ids) {
                        entries.add(new ModelCatalogEntry(connection.id(), modelId, modelId,
                                "discover", now));
                    }
                    catalog.replaceDiscovered(connection.id(), entries);
                }
            } catch (RuntimeException e) {
                log.info("模型目录自动同步失败（保存已成功）connection={}：{}",
                        connection.id(), e.getMessage());
            }
        });
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
        boolean saved = connectionId != null && !connectionId.isBlank()
                && models.find(connectionId).isPresent();
        if ((apiKey == null || apiKey.isBlank()) && connectionId != null && !connectionId.isBlank()) {
            apiKey = models.apiKeyOf(connectionId).orElse("");
        }
        ModelProbe.Result result = modelProbe.probe(baseUrl, apiKey, modelName);
        // 编辑态测的是当前表单，结果写回该行，列表状态才不会停在第一次保存时的结果
        if (saved) {
            models.recordTest(connectionId, result.ok(),
                    result.ok() ? "连接正常" : result.message(), result.latencyMs());
        }
        return modelTestResponse(result);
    }

    /**
     * 对话栏选择器数据：按连接分组返回可选模型（不含任何密钥）。
     *
     * <p>{@code active*} 是设置页默认项；{@code connections[].models} 是每个网关下
     * 自动发现 + 手工填写的模型。前端据此做「网关 → 模型」两级选择。
     */
    @GetMapping("/models/options")
    public Map<String, Object> modelOptions(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        currentUser.requireUserId();
        ModelConnection active = models.findDefault().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeId", active == null ? null : active.id());
        out.put("activeName", active == null ? null : active.name());
        out.put("modelName", active == null ? null : active.modelName());
        out.put("configured", active != null);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (ModelConnection connection : models.findAll()) {
            // 对话栏只列启用的；设置页编辑停用连接时需要看到它的目录
            if (!includeDisabled && !connection.enabled()) {
                continue;
            }
            List<Map<String, Object>> options = new ArrayList<>();
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            // 配置里的模型名始终可选（即使 /models 里没有）
            if (connection.modelName() != null && !connection.modelName().isBlank()) {
                options.add(modelOption(connection.modelName(), "config"));
                seen.add(connection.modelName());
            }
            for (ModelCatalogEntry entry : catalog.findByConnection(connection.id())) {
                if (seen.add(entry.modelId())) {
                    options.add(modelOption(entry.modelId(), entry.source()));
                }
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("id", connection.id());
            group.put("name", connection.name());
            group.put("provider", connection.provider());
            group.put("modelName", connection.modelName());
            group.put("isDefault", connection.isDefault());
            group.put("models", options);
            groups.add(group);
        }
        out.put("connections", groups);
        return out;
    }

    private static Map<String, Object> modelOption(String id, String source) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", id);
        option.put("label", id);
        option.put("source", source);
        return option;
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
