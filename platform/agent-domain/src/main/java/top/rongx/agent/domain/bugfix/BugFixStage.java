package top.rongx.agent.domain.bugfix;

/**
 * Bug 自动修复流水线的阶段。
 *
 * <p>顺序固定，编排器按 {@link #next()} 推进；每一阶段都要求「先有证据、再进下一阶段」，
 * 例如没有部署成功就不能进入复现，没有复现就不能进入修复。
 */
public enum BugFixStage {

    /** 分析：定位版本/提交范围、疑似原因、复现步骤。 */
    ANALYZE,
    /** 部署用于复现的测试环境。 */
    REPRO_DEPLOY,
    /** 在测试环境尝试复现 Bug。 */
    REPRODUCE,
    /** 在独立分支完成代码修复。 */
    FIX,
    /** 部署修复后的版本。 */
    VERIFY_DEPLOY,
    /** 验证 Bug 是否解决。 */
    VERIFY,
    /** 推送分支并创建 PR/MR。 */
    PULL_REQUEST,
    /** 全流程完成（PR 已创建，等待人工合并）。 */
    DONE;

    /** 下一阶段；DONE 之后仍是 DONE。 */
    public BugFixStage next() {
        BugFixStage[] values = values();
        return this == DONE ? DONE : values[ordinal() + 1];
    }

    /** 面向用户的中文名。 */
    public String label() {
        return switch (this) {
            case ANALYZE -> "分析 Bug";
            case REPRO_DEPLOY -> "部署复现环境";
            case REPRODUCE -> "尝试复现";
            case FIX -> "修复代码";
            case VERIFY_DEPLOY -> "部署修复版本";
            case VERIFY -> "验证修复";
            case PULL_REQUEST -> "创建 PR";
            case DONE -> "完成";
        };
    }
}
