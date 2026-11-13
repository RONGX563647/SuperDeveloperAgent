package top.rongx.agent.domain.bugfix;

/**
 * Bug 自动修复策略（平台级，按部署实例一份）。
 *
 * @param enabled      是否在收到 Bug 任务时自动启动
 * @param autoDeploy   是否允许自动部署测试环境（关闭时阶段会 BLOCKED，等待人工）
 * @param autoCreatePr 是否自动创建 PR（关闭时保留分支，等待人工提 PR）
 * @param testEnv      用于复现/验证的测试环境名
 * @param bindingId    已人工确认的环境绑定 id；为空则无法自动部署
 * @param maxAttempts  验证失败后的最大重试次数
 */
public record BugFixPolicy(
        boolean enabled,
        boolean autoDeploy,
        boolean autoCreatePr,
        String testEnv,
        String bindingId,
        int maxAttempts,
        long updatedAt) {

    public static BugFixPolicy defaults() {
        return new BugFixPolicy(false, true, true, "test", "", 2, 0);
    }

    public BugFixPolicy normalized() {
        return new BugFixPolicy(
                enabled,
                autoDeploy,
                autoCreatePr,
                testEnv == null || testEnv.isBlank() ? "test" : testEnv.trim(),
                bindingId == null ? "" : bindingId.trim(),
                maxAttempts <= 0 ? 1 : Math.min(maxAttempts, 5),
                updatedAt);
    }

    public BugFixPolicy withUpdatedAt(long value) {
        return new BugFixPolicy(enabled, autoDeploy, autoCreatePr, testEnv, bindingId,
                maxAttempts, value);
    }
}
