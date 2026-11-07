package org.example.agent.executor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.example.agent.domain.artifact.ArtifactRef;
import org.example.agent.domain.port.PipelineEngine;
import org.example.agent.domain.run.StageResult;
import org.example.agent.domain.run.StageStatus;
import org.springframework.stereotype.Component;

/**
 * 空实现：跑通链路用，不真正执行构建。
 *
 * <p>替换为真实实现时保持端口不变；工具层与前端无需改动。
 */
@Component
public class NoopPipelineEngine implements PipelineEngine {

    private final Map<String, StageStatus> runs = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "builtin-noop";
    }

    @Override
    public List<String> pipelines(String projectId) {
        return List.of("backend-build", "frontend-build");
    }

    @Override
    public String trigger(String projectId, String pipelineId, Map<String, String> params) {
        String runId = "run-" + UUID.randomUUID();
        runs.put(runId, StageStatus.SUCCEEDED);
        return runId;
    }

    @Override
    public StageStatus status(String runId) {
        return runs.getOrDefault(runId, StageStatus.PENDING);
    }

    @Override
    public List<StageResult> stages(String runId) {
        return List.of(StageResult.success("noop", 0, List.<ArtifactRef>of(), 0L));
    }

    @Override
    public void cancel(String runId) {
        runs.put(runId, StageStatus.CANCELLED);
    }

    @Override
    public List<ArtifactRef> artifacts(String runId) {
        return List.of();
    }
}
