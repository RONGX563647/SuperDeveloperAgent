package top.rongx.agent.domain.deploy;

import java.util.List;
import java.util.Optional;

/**
 * 部署运行仓储：每 (project, env) 同时只允许一个未结束的部署。
 */
public interface DeployRunStore {

    /**
     * 原子抢占部署位。
     *
     * @return 抢到返回记录；已有未结束部署时返回 {@link Optional#empty()}
     */
    Optional<DeployRun> tryStart(String projectId, String env, String sha, String pipelineId,
                                 int attempt);

    /** 回填流水线 runId。 */
    void bindRunId(String id, String runId);

    void finish(String id, DeployRunStatus status, String reasonCode, String message);

    /** 当前未结束的部署（PENDING / RUNNING）。 */
    Optional<DeployRun> active(String projectId, String env);

    /** 从 sinceMillis 起的部署次数（预算）。 */
    int countSince(String projectId, String env, long sinceMillis);

    /** 最近连续失败次数（熔断）：从最新往前数，遇到非 FAILED/ROLLED_BACK 即停止。 */
    int consecutiveFailures(String projectId, String env);

    /** 最近的部署记录（含终态）。 */
    List<DeployRun> recent(String projectId, String env, int limit);
}
