package top.rongx.agent.domain.run;

/**
 * 统一运行状态。
 *
 * <p>与各编排自己的状态机互补：编排内部仍有更细的阶段状态，这里只保留跨引擎可比的
 * 生命周期，用来做全局列表、告警与重启恢复。
 */
public enum RunState {
    QUEUED,
    RUNNING,
    /** 等待人工确认/处理，不算失败，不会被重启恢复判死。 */
    BLOCKED,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }
}
