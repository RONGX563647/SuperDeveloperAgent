package top.rongx.agent.domain.tool;

/**
 * 工具能力分级（项目规约 §1.2）。
 *
 * <p>新增工具必须声明级别；未声明级别不得注册。
 */
public enum ToolLevel {

    /** 只读，无副作用。 */
    L0_READ,

    /** 产出计划/报告，由人执行。 */
    L1_PROPOSE,

    /** 在沙箱或测试环境真实执行。 */
    L2_EXECUTE_ISOLATED,

    /** 影响生产，必须持有 confirmToken。 */
    L3_EXECUTE_PROD;

    /** 是否属于写操作。 */
    public boolean mutating() {
        return this == L2_EXECUTE_ISOLATED || this == L3_EXECUTE_PROD;
    }

    /** 是否需要确认令牌。 */
    public boolean requiresConfirmToken() {
        return this == L3_EXECUTE_PROD;
    }
}
