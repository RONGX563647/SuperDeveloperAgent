package top.rongx.agent.domain.bugfix;

/**
 * 自动修复运行的状态。
 *
 * <p>{@link #BLOCKED} 与 {@link #FAILED} 的区别：BLOCKED 是「缺人可以补的前置条件」
 * （未配置模型、未确认环境绑定、未复现等），重试往往能继续；FAILED 是执行出错或验证不通过。
 */
public enum BugFixStatus {

    /** 已入队，等待执行。 */
    QUEUED,
    /** 正在执行某一阶段。 */
    RUNNING,
    /** PR 已创建，等待人工合并（平台永不自动合并）。 */
    WAITING_MERGE,
    /** PR 已合并，闭环真正完成。 */
    SUCCEEDED,
    /** 执行失败（异常/验证不通过）。 */
    FAILED,
    /** 被前置条件阻塞，补齐后可重试。 */
    BLOCKED,
    /** 用户取消。 */
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }
}
