package top.rongx.agent.domain.deploy;

/**
 * 一次部署运行的持久化记录：互斥、预算、熔断与审计共用。
 *
 * @param id          平台侧部署运行 id（dr-xxx）
 * @param runId       流水线运行 id（引擎返回后回填）
 * @param projectId   项目
 * @param env         环境
 * @param sha         提交
 * @param pipelineId  流水线
 * @param status      状态
 * @param reasonCode  失败原因码（成功为空）
 * @param message     面向人的摘要
 * @param attempt     同一 (project, env, sha) 的第几次尝试
 * @param startedAt   开始时间
 * @param finishedAt  结束时间（0 = 未结束）
 */
public record DeployRun(
        String id,
        String runId,
        String projectId,
        String env,
        String sha,
        String pipelineId,
        DeployRunStatus status,
        String reasonCode,
        String message,
        int attempt,
        long startedAt,
        long finishedAt) {

    public DeployRun {
        reasonCode = reasonCode == null ? "" : reasonCode;
        message = message == null ? "" : message;
        runId = runId == null ? "" : runId;
    }

    public DeployRun withRunId(String newRunId) {
        return new DeployRun(id, newRunId, projectId, env, sha, pipelineId, status,
                reasonCode, message, attempt, startedAt, finishedAt);
    }

    public DeployRun finished(DeployRunStatus newStatus, String newReasonCode, String newMessage) {
        return new DeployRun(id, runId, projectId, env, sha, pipelineId, newStatus,
                newReasonCode, newMessage, attempt, startedAt, System.currentTimeMillis());
    }
}
