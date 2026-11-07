package top.rongx.agent.domain.change;

/** 变更单状态机（PRD §4.10 确认模型）。 */
public enum ChangeStatus {
    /** Agent 已产出计划，尚未请求确认。 */
    DRAFT,
    /** 已请求确认，等待人操作。 */
    PENDING_CONFIRM,
    /** 人已确认，令牌已签发。 */
    CONFIRMED,
    /** 执行中。 */
    EXECUTING,
    /** 观察窗内。 */
    OBSERVING,
    /** 完成。 */
    DONE,
    /** 已回滚。 */
    ROLLED_BACK,
    /** 执行失败。 */
    FAILED,
    /** 确认超时（令牌过期）。 */
    EXPIRED;

    public boolean isTerminal() {
        return this == DONE || this == ROLLED_BACK || this == FAILED || this == EXPIRED;
    }
}
