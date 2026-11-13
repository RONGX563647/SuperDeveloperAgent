package top.rongx.agent.domain.bugfix;

import java.util.Locale;

/**
 * Bug 自动修复策略。
 *
 * <p>支持平台级默认值与项目级覆盖：项目未配置时回退平台策略。这样多个项目可以各自使用
 * 不同的测试环境与环境绑定，而不会因为一条全局配置把所有项目都指到同一个环境。
 *
 * @param enabled      是否在收到 Bug 任务时自动启动
 * @param autoDeploy   是否允许自动部署测试环境（关闭时阶段会 BLOCKED，等待人工）
 * @param autoCreatePr 是否自动创建 PR（关闭时保留分支，等待人工提 PR）
 * @param testEnv      用于复现/验证的测试环境名
 * @param bindingId    已人工确认的环境绑定 id；为空则无法自动部署
 * @param maxAttempts  失败/验证不通过的最大尝试次数
 * @param baseUrl      测试环境访问地址；配置后验证阶段会做确定性 HTTP 冒烟检查
 * @param buildCommand 修复后的确定性构建/测试命令（在临时 worktree 中执行，如 mvn -q -DskipTests package）
 * @param testCommand  修复后的确定性测试命令；为空时只跑 buildCommand
 * @param updatedAt    最后修改时间
 */
public record BugFixPolicy(
        boolean enabled,
        boolean autoDeploy,
        boolean autoCreatePr,
        String testEnv,
        String bindingId,
        int maxAttempts,
        String baseUrl,
        String buildCommand,
        String testCommand,
        long updatedAt) {

    public static BugFixPolicy defaults() {
        return new BugFixPolicy(false, true, true, "test", "", 2, "", "", "", 0);
    }

    public BugFixPolicy normalized() {
        return new BugFixPolicy(
                enabled,
                autoDeploy,
                autoCreatePr,
                testEnv == null || testEnv.isBlank() ? "test" : testEnv.trim(),
                bindingId == null ? "" : bindingId.trim(),
                maxAttempts <= 0 ? 1 : Math.min(maxAttempts, 5),
                baseUrl == null ? "" : baseUrl.trim(),
                buildCommand == null ? "" : buildCommand.trim(),
                testCommand == null ? "" : testCommand.trim(),
                updatedAt);
    }

    public BugFixPolicy withUpdatedAt(long value) {
        return new BugFixPolicy(enabled, autoDeploy, autoCreatePr, testEnv, bindingId,
                maxAttempts, baseUrl, buildCommand, testCommand, value);
    }

    /** 保存前的强校验：自动修复绝不允许指向生产/预生产环境。 */
    public void validate() {
        if (productionLike(testEnv)) {
            throw new IllegalArgumentException("自动修复禁止使用生产环境：" + testEnv);
        }
    }

    /**
     * 生产环境识别：prod / production / preprod / prd / live / online / 生产。
     * 宁可误判为生产而拒绝，也不能让自动修复部署到线上。
     */
    public static boolean productionLike(String env) {
        String value = env == null ? "" : env.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return false;
        }
        return value.contains("prod") || value.contains("prd") || value.contains("live")
                || value.contains("online") || value.contains("生产")
                || value.equals("pr") || value.startsWith("pr-") || value.endsWith("-pr");
    }
}
