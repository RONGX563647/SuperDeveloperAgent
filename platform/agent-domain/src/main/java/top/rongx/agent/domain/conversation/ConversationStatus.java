package top.rongx.agent.domain.conversation;

/** 会话状态：侧栏与工作区首页用它展示运行中/待确认/最近结果。 */
public enum ConversationStatus {
    IDLE,
    RUNNING,
    NEEDS_CONFIRMATION,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
