package top.rongx.agent.domain.conversation;

/** 一次异步运行（工作区调度 Agent 发起的会话执行）的状态。 */
public enum RunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
