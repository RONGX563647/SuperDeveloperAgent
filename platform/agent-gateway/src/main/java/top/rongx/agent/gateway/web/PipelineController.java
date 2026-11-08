package top.rongx.agent.gateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.PipelineEngine.LogSlice;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.gateway.deploy.DeployGate;

/** 流水线运行查询（CI 的"构建历史/阶段视图"）。 */
@RestController
@RequestMapping("/api/v1/ci")
public class PipelineController {

    private final PipelineEngine engine;
    private final boolean legacyServerConfigured;
    private final top.rongx.agent.domain.settings.DeployServerRepository deployServers;
    private final DeployGate deployGate;

    public PipelineController(PipelineEngine engine,
                              @Value("${agent.deploy.server.host:}") String serverHost,
                              top.rongx.agent.domain.settings.DeployServerRepository deployServers,
                              DeployGate deployGate) {
        this.engine = engine;
        this.legacyServerConfigured = serverHost != null && !serverHost.isBlank();
        this.deployServers = deployServers;
        this.deployGate = deployGate;
    }

    @GetMapping("/pipelines")
    public Map<String, Object> pipelines(String projectId) {
        return Map.of("engine", engine.id(), "pipelines", engine.pipelines(projectId));
    }

    @PostMapping("/pipelines/{pipelineId}/run")
    public Map<String, Object> run(@PathVariable String pipelineId,
                                   @RequestBody Map<String, String> params) {
        // projectId 必填：不再默认到某个写死的项目（防误触发）
        String projectId = params.get("projectId");
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 必填");
        }
        String sha = params.get("gitSha");
        if (sha == null || sha.isBlank()) {
            throw new IllegalArgumentException("gitSha 必填");
        }
        // 环境：缺省 test（绝不让"没传环境"落到生产）
        String env = params.get("env");
        String normalizedEnv = env == null || env.isBlank() ? "test" : env.trim().toLowerCase();
        // 服务器（prod）操作：测试证据 + 一次性人工确认令牌，两个入口（这里 / deploy/replay）共用同一校验
        deployGate.requireIfNeeded(projectId, normalizedEnv, sha, params.get("confirmToken"));
        Map<String, String> triggerParams = new LinkedHashMap<>();
        triggerParams.put("gitSha", sha);
        triggerParams.put("env", normalizedEnv);
        // 可选：本次运行的部署目标覆盖（docker / host / server），空则由 pipeline.yml 决定
        String deployTarget = params.get("deployTarget");
        if (deployTarget != null && !deployTarget.isBlank()) {
            triggerParams.put("deployTarget", deployTarget.trim().toLowerCase());
        }
        String runId = engine.trigger(projectId, pipelineId, triggerParams);
        return Map.of("runId", runId, "env", normalizedEnv, "status", engine.status(runId));
    }

    /** 可选部署目标（前端下拉用）；available=false 时给出不可用原因。 */
    @GetMapping("/deploy/targets")
    public List<Map<String, Object>> deployTargets() {
        var defaultServer = deployServers.findDefault();
        boolean serverReady = defaultServer.isPresent() || legacyServerConfigured;
        String serverDetail = defaultServer
                .map(s -> "默认服务器：" + s.name() + "（" + s.host() + "）")
                .orElse(legacyServerConfigured
                        ? "已通过环境变量配置" : "未配置：请到「设置 → 服务器」添加");
        return List.of(
                Map.of("target", "docker", "label", "本机 Docker 容器", "available", true,
                        "detail", "替换式发布；需要 Dockerfile 与镜像"),
                Map.of("target", "host", "label", "本机直接运行", "available", true,
                        "detail", "仅 Java jar；pid 文件管理"),
                Map.of("target", "server", "label", "远程服务器", "available",
                        serverReady, "detail", serverDetail));
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> status(@PathVariable String runId,
                                      @RequestParam(defaultValue = "true") boolean withLog) {
        return toView(runId, withLog);
    }

    /**
     * 增量日志片段：从 offset 起返回新增文本。
     *
     * <p>前端按 offset 续拉、追加渲染，避免每次轮询都重传/重绘整段日志。
     * 返回 {@code {offset, end, text, complete}}：complete 表示运行为终态且已读到末尾。
     */
    @GetMapping("/runs/{runId}/log")
    public Map<String, Object> log(@PathVariable String runId,
                                   @RequestParam(defaultValue = "0") int offset) {
        if (!engine.hasRun(runId)) {
            throw new IllegalArgumentException("运行不存在：" + runId);
        }
        LogSlice slice = engine.logFrom(runId, offset);
        boolean complete = engine.status(runId).isTerminal() && slice.end() == slice.offset();
        return Map.of(
                "offset", slice.offset(),
                "end", slice.end(),
                "text", slice.text(),
                "complete", complete);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Map<String, Object> cancel(@PathVariable String runId) {
        engine.cancel(runId);
        return toView(runId, true);
    }

    /** 最近构建历史（按触发时间倒序；projectId 可选过滤）。 */
    @GetMapping("/history")
    public List<Map<String, Object>> history(String projectId) {
        return engine.history().stream()
                .filter(h -> projectId == null || projectId.isBlank()
                        || h.projectId().equals(projectId))
                .limit(50)
                .map(h -> Map.<String, Object>of(
                        "runId", h.runId(),
                        "projectId", h.projectId(),
                        "env", h.env(),
                        "pipelineId", h.pipelineId(),
                        "gitSha", h.gitSha(),
                        "status", h.status().name(),
                        "createdAt", h.createdAt(),
                        "stageCount", h.stageCount()))
                .toList();
    }

    private Map<String, Object> toView(String runId, boolean withLog) {
        StageStatus status = engine.status(runId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", runId);
        view.put("status", status);
        view.put("stages", engine.stages(runId).stream().map(this::stageView).toList());
        view.put("artifacts", engine.artifacts(runId).stream().map(this::artifactView).toList());
        // 增量日志模式下（withLog=false）不重复传输整段日志
        view.put("log", withLog ? engine.logTail(runId) : "");
        return view;
    }

    private Map<String, Object> stageView(StageResult s) {
        return Map.of("id", s.stageId(), "status", s.status(), "exitCode", s.exitCode(),
                "errorCode", s.errorCode() == null ? "" : s.errorCode(),
                "summary", s.errorSummary() == null ? "" : s.errorSummary(),
                "durationMs", s.durationMs(),
                "artifacts", s.artifacts().size());
    }

    private Map<String, Object> artifactView(ArtifactRef a) {
        return Map.of("kind", a.kind(), "ref", a.ref(),
                "digest", a.digest() == null ? "" : a.digest(), "sizeBytes", a.sizeBytes());
    }
}
