package top.rongx.agent.domain.run;

/**
 * 跨编排的统一运行记录。
 *
 * @param id          全局唯一的运行 ID（直接复用各编排自己的运行 ID）
 * @param kind        运行类型
 * @param projectId   项目
 * @param subjectId   业务主体（会话 ID / Issue ID / 流水线 ID）
 * @param ownerUserId 发起人
 * @param state       生命周期状态
 * @param summary     最新摘要（阶段、结果或等待原因）
 * @param error       失败原因（成功时为空）
 * @param createdAt   创建时间
 * @param updatedAt   最近更新时间（恢复逻辑据此判断是否孤儿）
 */
public record RunRecord(String id, RunKind kind, String projectId, String subjectId,
                        String ownerUserId, RunState state, String summary, String error,
                        long createdAt, long updatedAt) {

    public RunRecord withState(RunState next, String nextSummary, String nextError, long now) {
        return new RunRecord(id, kind, projectId, subjectId, ownerUserId, next, nextSummary,
                nextError == null ? "" : nextError, createdAt, now);
    }
}
