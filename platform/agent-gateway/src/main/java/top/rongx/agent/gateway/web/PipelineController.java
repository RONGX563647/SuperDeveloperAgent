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

/** 流水线运行查询（CI 的"构建历史/阶段视图"）。 */
@RestController
@RequestMapping("/api/v1/ci")
public class PipelineController {

    private final PipelineEngine engine;
    private final boolean serverConfigured;

    public PipelineController(PipelineEngine engine,
                              @Value("${agent.deploy.server.host:}") String serverHost) {
        this.engine = engine;
        this.serverConfigured = serverHost != null && !serverHost.isBlank();
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
        Map<String, String> triggerParams = new LinkedHashMap<>();
        triggerParams.put("gitSha", sha);
        // 可选：本次运行的部署目标覆盖（docker / host / server），空则由 pipeline.yml 决定
        String deployTarget = params.get("deployTarget");
        if (deployTarget != null && !deployTarget.isBlank()) {
            triggerParams.put("deployTarget", deployTarget.trim().toLowerCase());
        }
        String runId = engine.trigger(projectId, pipelineId, triggerParams);
        return Map.of("runId", runId, "status", engine.status(runId));
    }

    /** 可选部署目标（前端下拉用）；available=false 时给出不可用原因。 */
    @GetMapping("/deploy/targets")
    public List<Map<String, Object>> deployTargets() {
        return List.of(
                Map.of("target", "docker", "label", "本机 Docker 容器", "available", true,
                        "detail", "替换式发布；需要 Dockerfile 与镜像"),
                Map.of("target", "host", "label", "本机直接运行", "available", true,
                        "detail", "仅 Java jar；pid 文件管理"),
                Map.of("target", "server", "label", "远程服务器", "available",
                        serverConfigured, "detail", serverConfigured
                                ? "已配置（部署器待实现）" : "未配置：见《发布编排与平台部署器》§4"));
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
