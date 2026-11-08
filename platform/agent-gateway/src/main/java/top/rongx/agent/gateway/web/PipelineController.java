package top.rongx.agent.gateway.web;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;

/** 流水线运行查询（CI 的"构建历史/阶段视图"）。 */
@RestController
@RequestMapping("/api/v1/ci")
public class PipelineController {

    private final PipelineEngine engine;

    public PipelineController(PipelineEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/pipelines")
    public Map<String, Object> pipelines(String projectId) {
        return Map.of("engine", engine.id(), "pipelines", engine.pipelines(projectId));
    }

    @PostMapping("/pipelines/{pipelineId}/run")
    public Map<String, Object> run(@PathVariable String pipelineId,
                                   @RequestBody Map<String, String> params) {
        String runId = engine.trigger(String.valueOf(params.getOrDefault("projectId", "RY")),
                pipelineId, Map.of("gitSha", String.valueOf(params.get("gitSha"))));
        return Map.of("runId", runId, "status", engine.status(runId));
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> status(@PathVariable String runId) {
        return toView(runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Map<String, Object> cancel(@PathVariable String runId) {
        engine.cancel(runId);
        return toView(runId);
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

    private Map<String, Object> toView(String runId) {
        StageStatus status = engine.status(runId);
        return Map.of(
                "runId", runId,
                "status", status,
                "stages", engine.stages(runId).stream().map(this::stageView).toList(),
                "artifacts", engine.artifacts(runId).stream().map(this::artifactView).toList(),
                "log", engine.logTail(runId));
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
