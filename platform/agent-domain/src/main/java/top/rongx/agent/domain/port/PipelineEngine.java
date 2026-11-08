package top.rongx.agent.domain.port;

import java.util.List;
import java.util.Map;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;

/**
 * 流水线引擎端口（由执行面实现）。
 *
 * <p>放在 domain 的原因：工具层需要"触发构建"，但按分层规约 tools 只能依赖 domain。
 * 于是把契约定在 domain（端口），实现放执行面（适配器）——依赖方向保持单向。
 *
 * <p>实现必须是确定性代码，不得调用任何模型（规约 §1.1 A3）。
 */
public interface PipelineEngine {

    /** 引擎标识，例如 {@code builtin}。 */
    String id();

    /** 该项目下可用的流水线（白名单，不返回任意 job）。 */
    List<String> pipelines(String projectId);

    /** 触发一次运行，返回 runId。参数只允许枚举/正则约束后的值。 */
    String trigger(String projectId, String pipelineId, Map<String, String> params);

    /** 运行状态。 */
    StageStatus status(String runId);

    /** 该 runId 是否真实存在（把"不存在"与"排队中"区分开；默认保守返回 true）。 */
    default boolean hasRun(String runId) {
        return true;
    }

    /** 各 stage 结果（按执行顺序）。 */
    List<StageResult> stages(String runId);

    /** 取消运行。 */
    void cancel(String runId);

    /** 运行产出物。 */
    List<ArtifactRef> artifacts(String runId);

    /** 运行日志尾部（供构建历史查看；默认无）。 */
    default String logTail(String runId) {
        return "";
    }

    /** 最近构建历史（含重启前落盘的运行）；默认无。 */
    default List<RunSummary> history() {
        return List.of();
    }

    /** 历史视图（domain 层数据结构，避免 gateway 依赖执行面具体类）。 */
    record RunSummary(String runId, String projectId, String pipelineId, String gitSha,
                      StageStatus status, long createdAt, int stageCount) {
    }
}
