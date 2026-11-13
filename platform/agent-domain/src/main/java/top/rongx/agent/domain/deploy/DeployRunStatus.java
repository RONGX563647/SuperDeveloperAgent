package top.rongx.agent.domain.deploy;

/**
 * 部署运行状态。
 */
public enum DeployRunStatus {
    /** 已抢占互斥位，尚未拿到流水线 runId。 */
    PENDING,
    /** 流水线正在跑。 */
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** 失败后已自动/人工回滚到上一版。 */
    ROLLED_BACK;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == ROLLED_BACK;
    }
}
