package top.rongx.agent.domain.bugfix;

/**
 * 确定性检查端口：不依赖模型自述的构建/冒烟验证。
 *
 * <p>实现必须把命令限制在修复提交的临时 worktree 内执行，禁止在生产环境运行；
 * 返回 {@code passed=false} 时由阶段决定回到修复还是停在当前阶段等待人工。
 */
public interface BugFixCheckPort {

    /**
     * 在修复提交上执行构建/测试命令（策略未配置命令时返回 skipped）。
     */
    CheckResult build(BugFixRun run, BugFixPolicy policy, String sha);

    /**
     * 对测试环境做确定性 HTTP 冒烟检查（策略未配置 baseUrl 时返回 skipped）。
     */
    CheckResult smoke(BugFixRun run, BugFixPolicy policy);

    record CheckResult(boolean passed, String summary, String detail) {

        public static CheckResult skipped(String reason) {
            return new CheckResult(true, "未配置确定性检查：" + reason, "");
        }

        public static CheckResult pass(String summary, String detail) {
            return new CheckResult(true, summary, detail);
        }

        public static CheckResult fail(String summary, String detail) {
            return new CheckResult(false, summary, detail);
        }

        public boolean skipped() {
            return summary != null && summary.startsWith("未配置确定性检查");
        }
    }
}
