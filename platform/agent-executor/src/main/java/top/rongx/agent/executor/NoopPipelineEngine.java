package top.rongx.agent.executor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;

/**
 * 空实现（已废弃）：仅作为替换模板保留，未注册为 Spring Bean，不会被装配选中。
 *
 * <p>真实流水线由 {@link BuiltInPipelineEngine} 提供（见 {@link PipelineConfig}）。
 * 保留此类的唯一目的是作为"最小可运行骨架"参考，勿在新代码中引用。
 *
 * @deprecated 由 {@link BuiltInPipelineEngine} 取代；本类不实现任何真实编排。
 */
@Deprecated(since = "0.1.0", forRemoval = false)
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
