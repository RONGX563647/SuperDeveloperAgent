package top.rongx.agent.gateway.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvAddressing;
import top.rongx.agent.domain.env.EnvBinding;
import top.rongx.agent.domain.env.EnvNames;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvRuntime;
import top.rongx.agent.domain.env.EnvTemplate;
import top.rongx.agent.domain.env.EnvVariable;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.MiddlewareImages;
import top.rongx.agent.domain.env.RuntimeMode;
import top.rongx.agent.domain.env.ScenarioCatalog;
import top.rongx.agent.domain.env.ServiceRuntime;
import top.rongx.agent.domain.env.StackAdapter;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.domain.settings.SshAccess;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.settings.EnvBindingService;
import top.rongx.agent.gateway.settings.EnvironmentDiscoveryService;
import top.rongx.agent.gateway.settings.LocalServiceManager;

/**
 * 环境模板 / 中间件资源 / 环境变量 / 项目覆盖 / Agent 绑定。
 *
 * <p>安全：值加密落库；列表/详情只返回掩码，只有显式"查看"接口
 * （settings:write / settings:reveal）才回显明文，并写审计日志。
 */
@RestController
@RequestMapping("/api/v1/settings")
public class EnvironmentController {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentController.class);

    private final EnvironmentStore envStore;
    private final EnvironmentDiscoveryService discovery;
    private final EnvBindingService bindings;
    private final DeployServerRepository servers;
    private final SshAccess ssh;
    private final EnvRuntime envRuntime;
    private final LocalServiceManager localServices;
    private final CurrentUser currentUser;
    private final PipelineEngine pipelineEngine;

    /** 发现候选暂存：密码留在后端，前端只拿 candidateId。 */
    private final Map<String, EnvResource> candidates = new ConcurrentHashMap<>();

    public EnvironmentController(EnvironmentStore envStore, EnvironmentDiscoveryService discovery,
                                 EnvBindingService bindings, DeployServerRepository servers,
                                 SshAccess ssh, EnvRuntime envRuntime,
                                 LocalServiceManager localServices, CurrentUser currentUser,
                                 PipelineEngine pipelineEngine) {
        this.envStore = envStore;
        this.discovery = discovery;
        this.bindings = bindings;
        this.servers = servers;
        this.ssh = ssh;
        this.envRuntime = envRuntime;
        this.localServices = localServices;
        this.currentUser = currentUser;
        this.pipelineEngine = pipelineEngine;
    }

    // ---------------------------------------------------------------- 模板

    @GetMapping("/envs")
    public List<Map<String, Object>> templates() {
        requireRead();
        List<Map<String, Object>> out = new ArrayList<>();
        for (EnvTemplate t : envStore.templates()) {
            // 首次进入时把资源连接信息落成模板变量，卡片上的变量数才和详情一致。
            syncResourceVars(t.env());
            List<EnvResource> resources = envStore.resources(t.env());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("template", t);
            row.put("resourceCount", resources.size());
            // 未启动不是异常；只有"缺连接信息"才计入异常
            row.put("runningCount", resources.stream()
                    .filter(r -> "RUNNING".equals(r.status())).count());
            row.put("stoppedCount", resources.stream()
                    .filter(r -> "STOPPED".equals(r.status())).count());
            row.put("missingCount", resources.stream()
                    .filter(r -> "MISSING".equals(r.status())).count());
            // 模板以镜像为身份：卡片直接展示这套环境会拉起哪些镜像，容器只是运行态。
            row.put("images", imagesOf(resources));
            var vars = envStore.envVars(t.env());
            row.put("varCount", vars.size());
            row.put("secretCount", vars.stream().filter(v -> v.secret()).count());
            out.add(row);
        }
        return out;
    }

    @GetMapping("/envs/{env}")
    public Map<String, Object> detail(@PathVariable String env) {
        requireRead();
        // 每次打开详情时把中间件连接信息同步成模板变量（幂等），
        // 让"发现/导入"得到的账号密码可以直接看到、也可以在部署时注入。
        syncResourceVars(env);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template", envStore.template(env).orElseThrow(() ->
                new IllegalArgumentException("环境不存在：" + env)));
        out.put("resources", envStore.resources(env).stream().map(this::resourceView).toList());
        out.put("vars", envStore.envVars(env));
        out.put("imageDefaults", MiddlewareImages.defaultsByKind());
        return out;
    }

    @PutMapping("/envs/{env}")
    public EnvTemplate saveTemplate(@PathVariable String env, @RequestBody Map<String, Object> body) {
        requireWrite();
        EnvTemplate current = envStore.template(env).orElse(
                new EnvTemplate(env, null, "docker", 8080, "/healthz", 0));
        int port = body.get("defaultPort") instanceof Number n ? n.intValue() : current.defaultPort();
        envStore.saveTemplate(new EnvTemplate(env,
                str(body, "network", current.network()),
                str(body, "defaultTarget", current.defaultTarget()),
                port,
                str(body, "healthPath", current.healthPath()),
                str(body, "runtimeMode", current.runtimeMode()),
                str(body, "namespace", current.namespace()),
                str(body, "serverId", current.serverId()),
                System.currentTimeMillis()));
        // 形态/命名空间变化会改变中间件寻址，变量必须跟着重算。
        syncResourceVars(env);
        return envStore.template(env).orElseThrow();
    }

    // ---------------------------------------------------------------- 场景配置词条

    /**
     * 内置场景配置词条 + 当前环境是否已配置该键。
     *
     * <p>只返回键名、说明与是否已配置，不返回值；Agent 通过同一份目录（{@code config_catalog}）读取。
     */
    @GetMapping("/scenarios")
    public List<Map<String, Object>> scenarios(@RequestParam(defaultValue = "dev") String env) {
        requireRead();
        Map<String, String> present = new LinkedHashMap<>();
        for (EnvVariable v : envStore.envVars(env)) {
            present.put(v.key(), v.source());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScenarioCatalog.Scenario scenario : ScenarioCatalog.all()) {
            List<Map<String, Object>> keys = new ArrayList<>();
            for (ScenarioCatalog.Key k : scenario.keys()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", k.key());
                row.put("label", k.label());
                row.put("description", k.description());
                row.put("secret", k.secret());
                row.put("required", k.required());
                row.put("defaultValue", k.defaultValue());
                row.put("example", k.example());
                row.put("configured", present.containsKey(k.key()));
                row.put("source", present.get(k.key()));
                keys.add(row);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", scenario.id());
            row.put("name", scenario.name());
            row.put("summary", scenario.summary());
            row.put("keys", keys);
            row.put("configuredCount",
                    keys.stream().filter(k -> Boolean.TRUE.equals(k.get("configured"))).count());
            out.add(row);
        }
        return out;
    }

    /**
     * 把某个场景的词条写入环境模板变量。
     *
     * <p>只写入请求里带值的键，空值跳过（不落空变量，避免部署时注入空串）；
     * 敏感标记取自词条定义，值与项目密钥一样加密保存。
     */
    @PostMapping("/scenarios/{id}/import")
    public Map<String, Object> importScenario(@PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        requireWrite();
        ScenarioCatalog.Scenario scenario = ScenarioCatalog.find(id).orElseThrow(() ->
                new IllegalArgumentException("未知场景：" + id));
        String env = EnvNames.normalize(str(body, "env", "dev"));
        Object raw = body == null ? null : body.get("values");
        Map<?, ?> values = raw instanceof Map<?, ?> map ? map : Map.of();
        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ScenarioCatalog.Key k : scenario.keys()) {
            Object value = values.get(k.key());
            String text = value == null ? null : String.valueOf(value).trim();
            if (text == null || text.isBlank()) {
                skipped.add(k.key());
                continue;
            }
            envStore.saveEnvVar(env, k.key(), text, k.secret(), "TEMPLATE");
            imported.add(k.key());
        }
        if (imported.isEmpty()) {
            throw new IllegalArgumentException("至少填写一项配置值后再导入");
        }
        log.info("[audit] 导入场景配置 scenario={} env={} keys={} by={}",
                id, env, imported, currentUser.requireUserId());
        return Map.of("ok", true, "scenario", id, "env", env,
                "imported", imported, "skipped", skipped);
    }

    // ---------------------------------------------------------------- 发现 / 导入

    @PostMapping("/envs/{env}/discover")
    public List<Map<String, Object>> discover(@PathVariable String env,
                                              @RequestBody(required = false) Map<String, Object> body) {
        requireWrite();
        String source = str(body, "source", "local");
        EnvTemplate template = envStore.template(env).orElse(null);
        String serverId = str(body, "serverId",
                template == null ? null : template.serverId());
        List<EnvResource> found = "remote".equalsIgnoreCase(source)
                ? discovery.discoverRemote(env, serverId, template)
                : discovery.discoverLocal(env, template);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EnvResource r : found) {
            String id = "cand-" + UUID.randomUUID().toString().substring(0, 8);
            candidates.put(id, r);
            Map<String, Object> row = resourceView(r);
            row.put("candidateId", id);
            out.add(row);
        }
        return out;
    }

    @PostMapping("/envs/{env}/import")
    public List<Map<String, Object>> importCandidates(@PathVariable String env,
                                                      @RequestBody Map<String, Object> body) {
        requireWrite();
        Object raw = body.get("ids");
        List<String> ids = raw instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            EnvResource candidate = candidates.remove(id);
            if (candidate == null) {
                continue;
            }
            EnvResource saved = envStore.saveResource(new EnvResource(null, env,
                    candidate.kind(), candidate.name(), candidate.host(), candidate.port(),
                    candidate.containerPort(), candidate.internalHost(),
                    candidate.databaseName(), candidate.username(), candidate.password(),
                    candidate.network(), candidate.serverId(), candidate.source(),
                    candidate.running(), candidate.lastCheckAt(), candidate.lastError()));
            out.add(resourceView(saved));
        }
        syncResourceVars(env);
        return out;
    }

    @PostMapping("/envs/{env}/resources")
    public Map<String, Object> saveResource(@PathVariable String env,
                                            @RequestBody Map<String, Object> body) {
        requireWrite();
        EnvResource saved = envStore.saveResource(new EnvResource(
                str(body, "id", null), env,
                str(body, "kind", "mysql"),
                str(body, "name", "中间件"),
                str(body, "host", "127.0.0.1"),
                body.get("port") instanceof Number n ? n.intValue() : 0,
                body.get("containerPort") instanceof Number c ? c.intValue() : 0,
                str(body, "internalHost", null),
                str(body, "databaseName", null),
                str(body, "username", null),
                str(body, "password", null),
                str(body, "network", null),
                str(body, "serverId", null),
                "MANUAL", false, 0, null, str(body, "image", null)));
        syncResourceVars(env);
        return resourceView(saved);
    }

    @DeleteMapping("/envs/{env}/resources/{id}")
    public Map<String, Object> deleteResource(@PathVariable String env, @PathVariable String id) {
        requireWrite();
        envStore.deleteResource(id);
        syncResourceVars(env);
        return Map.of("ok", true);
    }

    @PostMapping("/envs/{env}/resources/{id}/check")
    public Map<String, Object> checkResource(@PathVariable String env, @PathVariable String id) {
        requireRead();
        EnvResource r = envStore.resource(id).orElseThrow(() ->
                new IllegalArgumentException("资源不存在：" + id));
        Health health = check(r);
        envStore.recordHealth(id, health.running(), health.error());
        EnvResource updated = envStore.resource(id).orElseThrow();
        Map<String, Object> out = resourceView(updated);
        out.put("checked", true);
        return out;
    }

    /** 启动某个中间件（本机 docker / 远程 SSH）。 */
    @PostMapping("/envs/{env}/resources/{id}/start")
    public Map<String, Object> startResource(@PathVariable String env, @PathVariable String id) {
        requireWrite();
        EnvRuntime.Result result = envRuntime.start(id);
        syncResourceVars(env);
        return runtimeView(id, result);
    }

    /** 停止某个中间件。 */
    @PostMapping("/envs/{env}/resources/{id}/stop")
    public Map<String, Object> stopResource(@PathVariable String env, @PathVariable String id) {
        requireWrite();
        EnvRuntime.Result result = envRuntime.stop(id);
        syncResourceVars(env);
        return runtimeView(id, result);
    }

    // ---------------------------------------------------------------- 本地服务（dev）

    /** dev 前后端服务清单（端口、进程状态、访问地址）。 */
    @GetMapping("/projects/{projectId}/services")
    public List<Map<String, Object>> localServices(@PathVariable String projectId,
                                                  @RequestParam(defaultValue = "dev") String env) {
        requireRead();
        return localServices.services(projectId, env);
    }

    /** 启动日志尾部：启动失败时前端直接展示，不用去服务器翻日志文件。 */
    @GetMapping("/projects/{projectId}/services/{service}/logs")
    public Map<String, Object> localServiceLogs(@PathVariable String projectId,
                                                @PathVariable String service,
                                                @RequestParam(defaultValue = "dev") String env,
                                                @RequestParam(defaultValue = "120") int lines) {
        requireRead();
        int size = Math.min(Math.max(lines, 1), 500);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", service);
        out.put("lines", localServices.logs(projectId, env, service, size));
        return out;
    }

    @PostMapping("/projects/{projectId}/services/{service}/start")
    public Map<String, Object> startService(@PathVariable String projectId,
                                            @PathVariable String service,
                                            @RequestParam(defaultValue = "dev") String env) {
        requireWrite();
        log.info("[audit] 启动本地服务 project={} env={} service={} by={}",
                projectId, env, service, currentUser.requireUserId());
        return serviceResult(projectId, env, localServices.start(projectId, env, service));
    }

    @PostMapping("/projects/{projectId}/services/{service}/stop")
    public Map<String, Object> stopService(@PathVariable String projectId,
                                           @PathVariable String service,
                                           @RequestParam(defaultValue = "dev") String env) {
        requireWrite();
        log.info("[audit] 停止本地服务 project={} env={} service={} by={}",
                projectId, env, service, currentUser.requireUserId());
        return serviceResult(projectId, env, localServices.stop(projectId, env, service));
    }

    @PostMapping("/projects/{projectId}/services/{service}/restart")
    public Map<String, Object> restartService(@PathVariable String projectId,
                                              @PathVariable String service,
                                              @RequestParam(defaultValue = "dev") String env) {
        requireWrite();
        log.info("[audit] 重启本地服务 project={} env={} service={} by={}",
                projectId, env, service, currentUser.requireUserId());
        return serviceResult(projectId, env, localServices.restart(projectId, env, service));
    }

    private Map<String, Object> serviceResult(String projectId, String env,
                                              ServiceRuntime.Result result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("message", result.message());
        out.put("port", result.port());
        out.put("pid", result.pid());
        out.put("status", result.status());
        out.put("services", localServices.services(projectId, env));
        return out;
    }

    // ---------------------------------------------------------------- 环境变量

    @GetMapping("/envs/{env}/vars")
    public List<EnvVariable> vars(@PathVariable String env) {
        requireRead();
        return envStore.envVars(env);
    }

    @PutMapping("/envs/{env}/vars")
    public EnvVariable saveVar(@PathVariable String env, @RequestBody Map<String, Object> body) {
        requireWrite();
        String key = str(body, "key", null);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("变量名必填");
        }
        boolean secret = !(body.get("secret") instanceof Boolean b) || b;
        return envStore.saveEnvVar(env, key.trim(), str(body, "value", null), secret, "TEMPLATE");
    }

    @DeleteMapping("/envs/{env}/vars/{key}")
    public Map<String, Object> deleteVar(@PathVariable String env, @PathVariable String key) {
        requireWrite();
        envStore.deleteEnvVar(env, key);
        return Map.of("ok", true);
    }

    /** 查看单个模板变量的明文（含中间件自动保存的账号密码）；写审计日志。 */
    @GetMapping("/envs/{env}/vars/{key}/reveal")
    public Map<String, Object> revealVar(@PathVariable String env, @PathVariable String key) {
        requireReveal();
        String value = envStore.envVarValue(env, key).orElseThrow(() ->
                new IllegalArgumentException("变量不存在：" + key));
        log.info("[audit] 查看环境变量明文 env={} key={} by={}", env, key,
                currentUser.requireUserId());
        return Map.of("env", env, "key", key, "value", value);
    }

    // ---------------------------------------------------------------- 项目覆盖

    @GetMapping("/projects/{projectId}/overrides")
    public List<EnvVariable> overrides(@PathVariable String projectId,
                                       @RequestParam(defaultValue = "test") String env) {
        requireRead();
        return envStore.overrides(projectId, env);
    }

    @PutMapping("/projects/{projectId}/overrides")
    public EnvVariable saveOverride(@PathVariable String projectId,
                                    @RequestBody Map<String, Object> body) {
        requireWrite();
        String env = str(body, "env", "test");
        String key = str(body, "key", null);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("变量名必填");
        }
        boolean secret = !(body.get("secret") instanceof Boolean b) || b;
        return envStore.saveOverride(projectId, env, key.trim(), str(body, "value", null), secret);
    }

    @DeleteMapping("/projects/{projectId}/overrides/{key}")
    public Map<String, Object> deleteOverride(@PathVariable String projectId, @PathVariable String key,
                                              @RequestParam(defaultValue = "test") String env) {
        requireWrite();
        envStore.deleteOverride(projectId, env, key);
        return Map.of("ok", true);
    }

    @GetMapping("/projects/{projectId}/effective")
    public Map<String, Object> effective(@PathVariable String projectId,
                                         @RequestParam(defaultValue = "test") String env) {
        requireRead();
        EffectiveEnvironment e = envStore.effective(projectId, env);
        Map<String, String> projectSource = bindings.sourceValues(projectId);
        Set<String> projectKeys = new HashSet<>();
        for (EnvVariable v : envStore.overrides(projectId, env)) {
            projectKeys.add(v.key());
        }
        List<Map<String, Object>> resources = new ArrayList<>();
        int referencedCount = 0;
        int referencedRunning = 0;
        for (EnvResource r : e.resources()) {
            Map<String, Object> view = resourceView(r);
            boolean referenced = StackAdapter.isReferenced(r, e.values())
                    && StackAdapter.declaredByProject(r, projectKeys, projectSource);
            view.put("referenced", referenced);
            if (referenced) {
                referencedCount++;
                if (r.running()) {
                    referencedRunning++;
                }
            }
            resources.add(view);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("env", env);
        out.put("network", e.network() == null ? "" : e.network());
        // 部署端口/健康路径以流水线定义为准（真正生效的是它），模板默认值只在没有定义时兜底，
        // 否则页面会显示一套、部署用另一套。
        PipelineEngine.DeploySummary deploy = pipelineDeploy(projectId, env);
        boolean deployOverrides = deploy != null;
        out.put("defaultPort", deployOverrides && deploy.port() > 0 ? deploy.port() : e.defaultPort());
        out.put("healthPath", deployOverrides && deploy.healthPath() != null
                && !deploy.healthPath().isBlank() ? deploy.healthPath()
                : (e.healthPath() == null ? "" : e.healthPath()));
        out.put("deployTarget", deployOverrides && deploy.target() != null ? deploy.target() : "");
        out.put("vars", e.vars());
        // 项目源码里能直接读到的变量：部署时自动注入；人工覆盖后以覆盖值为准。
        out.put("sourceVars", sourceVars(projectSource, e.values()));
        out.put("templateCount", e.templateCount());
        out.put("overrideCount", e.overrideCount());
        out.put("referencedCount", referencedCount);
        out.put("referencedRunningCount", referencedRunning);
        out.put("resources", resources);
        return out;
    }

    /** 读取当前环境生效的发布摘要；缺失或解析失败时返回 null（退回环境模板默认值）。 */
    private PipelineEngine.DeploySummary pipelineDeploy(String projectId, String env) {
        return pipelineEngine.deploySummary(projectId, env).orElse(null);
    }

    /**
     * 项目注入前的环境自检。
     *
     * <p>"容器没启动"不算异常（部署时会自动启动）；只有缺必填环境变量、
     * 或被项目引用的中间件缺连接信息时 {@code ok=false}。
     *
     * <p>必填项的口径来自项目源码（{@code entrypoint.sh} 的 require / compose 的
     * {@code ${VAR:?}}），且源码里已声明的值视同已配置——避免把
     * 「模板里空值且标注可选」的变量误报成缺失。
     */
    @GetMapping("/projects/{projectId}/env-check")
    public Map<String, Object> envCheck(@PathVariable String projectId,
                                        @RequestParam(defaultValue = "test") String env) {
        requireRead();
        EffectiveEnvironment e = envStore.effective(projectId, env);
        Map<String, String> sourceValues = bindings.sourceValues(projectId);
        Set<String> projectKeys = new HashSet<>();
        for (EnvVariable v : envStore.overrides(projectId, env)) {
            projectKeys.add(v.key());
        }
        List<String> required = requiredEnv(projectId);
        List<String> missingVars = new ArrayList<>();
        List<String> sourceProvided = new ArrayList<>();
        for (String key : required) {
            String value = e.values().get(key);
            if (value != null && !value.isBlank()) {
                continue;
            }
            String fromSource = sourceValues.get(key);
            if (fromSource != null && !fromSource.isBlank()) {
                sourceProvided.add(key);
                continue;
            }
            missingVars.add(key);
        }
        List<Map<String, Object>> missingResources = new ArrayList<>();
        List<Map<String, Object>> stoppedResources = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int referencedCount = 0;
        int referencedRunning = 0;
        for (EnvResource r : e.resources()) {
            if (!StackAdapter.isReferenced(r, e.values())
                    || !StackAdapter.declaredByProject(r, projectKeys, sourceValues)) {
                continue;
            }
            referencedCount++;
            if ("MISSING".equals(r.status())) {
                missingResources.add(resourceBrief(r));
                problems.add("中间件「" + r.name() + "」缺少连接信息："
                        + String.join("、", r.missingFields()));
            } else if (!r.running()) {
                stoppedResources.add(resourceBrief(r));
            } else {
                referencedRunning++;
            }
        }
        if (!missingVars.isEmpty()) {
            problems.add(0, "缺少必填环境变量：" + String.join("、", missingVars));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", problems.isEmpty());
        out.put("env", env);
        out.put("projectId", projectId);
        out.put("requiredCount", required.size());
        out.put("missingVars", missingVars);
        out.put("sourceProvidedVars", sourceProvided);
        out.put("missingResources", missingResources);
        out.put("stoppedResources", stoppedResources);
        out.put("referencedCount", referencedCount);
        out.put("referencedRunningCount", referencedRunning);
        out.put("middlewareHealthy", missingResources.isEmpty());
        out.put("problems", problems);
        return out;
    }

    /** 查看项目最终注入值的明文（模板/资源/覆盖任一来源）；写审计日志。 */
    @GetMapping("/projects/{projectId}/effective/reveal")
    public Map<String, Object> revealEffective(@PathVariable String projectId,
                                               @RequestParam(defaultValue = "test") String env,
                                               @RequestParam String key) {
        requireReveal();
        String value = envStore.effective(projectId, env).values().get(key);
        if (value == null) {
            throw new IllegalArgumentException("变量不存在或没有值：" + key);
        }
        log.info("[audit] 查看项目注入变量 env={} project={} key={} by={}", env, projectId, key,
                currentUser.requireUserId());
        return Map.of("projectId", projectId, "env", env, "key", key, "value", value);
    }

    private List<String> requiredEnv(String projectId) {
        // 直接读项目 .env.example 声明的变量名（不读值），避免网关层依赖执行层的 YAML 解析。
        return bindings.requiredKeys(projectId);
    }

    /**
     * 项目源码里声明的变量值（{@code .env} / {@code .env.example}）。
     *
     * <p>部署时执行层从同一份扫描结果注入，设置页据此展示"从项目里直接读取"的值；
     * 人工覆盖后该键进入有效变量并优先于源码值，这里标记 {@code overridden=true}。
     * 敏感名的值只返回掩码，避免明文出现在列表接口里。
     */
    private List<Map<String, Object>> sourceVars(Map<String, String> sourceValues,
                                                 Map<String, String> effectiveValues) {
        List<Map<String, Object>> rows = new ArrayList<>();
        sourceValues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    boolean secret = EnvBindingService.looksSecret(entry.getKey());
                    String current = effectiveValues.get(entry.getKey());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", entry.getKey());
                    row.put("value", secret ? maskSourceValue(entry.getValue()) : entry.getValue());
                    row.put("secret", secret);
                    row.put("overridden", current != null && !current.isBlank());
                    rows.add(row);
                });
        return rows;
    }

    private static String maskSourceValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() > 4 ? "****" + value.substring(value.length() - 4) : "****";
    }

    private Map<String, Object> resourceBrief(EnvResource r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.id());
        row.put("name", r.name());
        row.put("kind", r.kind());
        row.put("status", r.status());
        row.put("missingFields", r.missingFields());
        row.put("host", r.host());
        row.put("port", r.port());
        return row;
    }

    private Map<String, Object> runtimeView(String id, EnvRuntime.Result result) {
        Map<String, Object> out = new LinkedHashMap<>();
        envStore.resource(id).ifPresent(r -> out.putAll(resourceView(r)));
        out.put("ok", result.ok());
        out.put("message", result.message());
        out.put("started", result.started());
        out.put("stopped", result.stopped());
        out.put("failed", result.failed());
        return out;
    }

    /** 资源连接信息 → 模板变量（source=RESOURCE），并清掉已失效的资源变量。 */
    private void syncResourceVars(String env) {
        envStore.syncResourceVars(env);
        Set<String> keep = StackAdapter.derive(envStore.resources(env)).keySet();
        for (EnvVariable v : envStore.envVars(env)) {
            if ("RESOURCE".equals(v.source()) && !keep.contains(v.key())) {
                envStore.deleteEnvVar(env, v.key());
            }
        }
    }

    // ---------------------------------------------------------------- Agent 绑定

    @PostMapping("/projects/{projectId}/env-binding/draft")
    public EnvBinding draft(@PathVariable String projectId,
                            @RequestParam(defaultValue = "test") String env) {
        requireWrite();
        return bindings.draft(projectId, env, currentUser.requireUserId());
    }

    @PostMapping("/projects/{projectId}/env-binding/{bindingId}/confirm")
    public EnvBinding confirm(@PathVariable String projectId, @PathVariable String bindingId) {
        requireWrite();
        return bindings.confirm(bindingId, currentUser.requireUserId());
    }

    @GetMapping("/projects/{projectId}/env-binding/{bindingId}")
    public EnvBinding binding(@PathVariable String projectId, @PathVariable String bindingId) {
        requireRead();
        return envStore.binding(bindingId).orElseThrow(() ->
                new IllegalArgumentException("环境绑定不存在：" + bindingId));
    }

    // ---------------------------------------------------------------- 健康检查

    private record Health(boolean running, String error) {
    }

    private Health check(EnvResource r) {
        // 运行形态决定怎么探测：dev 是宿主机端口，test 是本机容器，server 是远端容器。
        RuntimeMode mode = envStore.template(r.env())
                .map(EnvTemplate::mode).orElse(RuntimeMode.defaultFor(r.env()));
        String container = containerName(r);
        if (mode == RuntimeMode.LOCAL_DEV && !"LOCAL_DOCKER".equals(r.source())) {
            return tcpHealth(r);
        }
        if ("LOCAL_DOCKER".equals(r.source()) || mode == RuntimeMode.LOCAL_TEST) {
            Boolean running = dockerRunning(null, null, container);
            return dockerHealth(running, "本机 Docker 不可用或容器不存在：" + container);
        }
        if ("REMOTE_DOCKER".equals(r.source()) || mode == RuntimeMode.SERVER) {
            DeployServer server = (r.serverId() == null || r.serverId().isBlank()
                    ? servers.findDefault() : servers.find(r.serverId())).orElse(null);
            if (server == null) {
                return new Health(false, "找不到远程服务器：" + r.serverId());
            }
            String secret = servers.secretOf(server.id()).orElse(null);
            Boolean running = dockerRunning(server, secret, container);
            return dockerHealth(running, "远端 Docker 不可用或容器不存在：" + container);
        }
        return tcpHealth(r);
    }

    private static Health tcpHealth(EnvResource r) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(r.host(), r.port()), 1500);
            return new Health(true, null);
        } catch (Exception e) {
            return new Health(false, "TCP 连接失败 " + r.host() + ":" + r.port() + "：" + e.getMessage());
        }
    }

    /** 容器"没启动"不是异常，只有读不到状态（docker 不可用/容器不存在）才记错误。 */
    private static Health dockerHealth(Boolean running, String failure) {
        if (running == null) {
            return new Health(false, failure);
        }
        return new Health(running, null);
    }

    /** docker inspect 需要容器名/ID，不能用应用连接用的 host（可能是 IP）。 */
    private static String containerName(EnvResource r) {
        return r.name() == null || r.name().isBlank() ? r.host() : r.name();
    }

    /** @return true/false 为容器运行状态；null 表示命令失败（docker 不可用或容器不存在）。 */
    private Boolean dockerRunning(DeployServer server, String secret, String container) {
        String command = "docker inspect --format '{{.State.Running}}' " + container;
        try {
            if (server == null) {
                Process p = new ProcessBuilder("docker", "inspect", "--format",
                        "{{.State.Running}}", container).redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return null;
                }
                return p.exitValue() == 0 ? "true".equalsIgnoreCase(out.trim()) : null;
            }
            SshAccess.Result result = ssh.run(server, secret, command, 10_000);
            return result.ok() ? "true".equalsIgnoreCase(result.output().trim()) : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- 视图

    private Map<String, Object> resourceView(EnvResource raw) {
        RuntimeMode mode = envStore.template(raw.env())
                .map(EnvTemplate::mode).orElse(RuntimeMode.defaultFor(raw.env()));
        EnvResource r = EnvAddressing.resolve(raw, mode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.id());
        out.put("env", r.env());
        out.put("kind", r.kind());
        out.put("name", r.name());
        out.put("host", r.host());
        out.put("port", r.port());
        out.put("containerPort", r.containerPort());
        out.put("internalHost", r.internalHost());
        out.put("runtimeMode", mode.name());
        out.put("databaseName", r.databaseName());
        out.put("username", r.username());
        out.put("hasPassword", r.hasPassword());
        out.put("passwordMasked", r.passwordMasked());
        out.put("network", r.network());
        out.put("serverId", r.serverId());
        out.put("source", r.source());
        out.put("image", r.displayImage());
        out.put("running", r.running());
        out.put("status", r.status());
        out.put("missingFields", r.missingFields());
        out.put("lastCheckAt", r.lastCheckAt());
        out.put("lastError", r.lastError());
        return out;
    }

    // ---------------------------------------------------------------- 工具

    /** 环境模板的镜像清单（去重；没有登记资源时返回空，不假装已配置）。 */
    private static List<String> imagesOf(List<EnvResource> resources) {
        return resources.stream()
                .map(EnvResource::displayImage)
                .filter(image -> image != null && !image.isBlank())
                .distinct()
                .toList();
    }

    private void requireRead() {
        currentUser.checkPermission("settings:read");
    }

    private void requireWrite() {
        currentUser.checkPermission("settings:write");
    }

    /**
     * 查看明文单独授权：管理员（settings:write / *）与运维（settings:reveal）可看，
     * 只读账号仍然拒绝。运维能排障但改不了环境配置，且每次查看都会写审计日志。
     */
    private void requireReveal() {
        if (!currentUser.hasPermission("settings:reveal")) {
            currentUser.checkPermission("settings:write");
        }
    }

    private static String str(Map<String, Object> body, String key, String fallback) {
        Object value = body == null ? null : body.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
