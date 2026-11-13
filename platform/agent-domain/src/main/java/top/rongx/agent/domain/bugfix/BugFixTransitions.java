package top.rongx.agent.domain.bugfix;

/**
 * 阶段结果的纯状态迁移：给定「当前阶段 + 阶段结果」，算出下一阶段与状态。
 *
 * <p>把这段决策从编排器里独立出来后，迁移规则可以做表驱动单测，不再依赖线程、租约与数据库。
 * 两个关键约定：
 * <ul>
 *   <li>PR 创建成功后停在 {@link BugFixStatus#WAITING_MERGE}，只有确认远端已合并才进入
 *       {@link BugFixStatus#SUCCEEDED}——「提了 PR」不等于「已完成」；</li>
 *   <li>验证失败不是终态：仍有尝试次数时回到修复阶段，用新的验证证据重新修。</li>
 * </ul>
 */
public final class BugFixTransitions {

    /** 部署抖动重试的初始/最大退避时间。 */
    private static final long BASE_RETRY_MS = 5_000L;
    private static final long MAX_RETRY_MS = 60_000L;

    private BugFixTransitions() {
    }

    /** 一次迁移决策；{@code finished} 表示本次执行应停下（等人工/等合并/终态）。 */
    public record Decision(
            BugFixStage nextStage,
            BugFixStatus status,
            int attempt,
            boolean autoRetry,
            long retryDelayMs,
            boolean finished) {
    }

    public static Decision decide(BugFixStage stage, BugFixStageResult.Outcome outcome,
                                  int attempt, int maxAttempts) {
        int limit = Math.max(1, maxAttempts);
        return switch (outcome) {
            case CONTINUE -> continued(stage, attempt);
            case BLOCKED -> new Decision(stage, BugFixStatus.BLOCKED, attempt, false, 0, true);
            case FAILED -> failed(stage, attempt, limit);
        };
    }

    private static Decision continued(BugFixStage stage, int attempt) {
        if (stage == BugFixStage.PULL_REQUEST) {
            // PR 已创建但未合并：等待人工合并，由合并追踪器推进到 DONE
            return new Decision(stage, BugFixStatus.WAITING_MERGE, attempt, false, 0, true);
        }
        BugFixStage next = stage.next();
        if (next == BugFixStage.DONE) {
            return new Decision(BugFixStage.DONE, BugFixStatus.SUCCEEDED, attempt, false, 0, true);
        }
        return new Decision(next, BugFixStatus.RUNNING, attempt, false, 0, false);
    }

    private static Decision failed(BugFixStage stage, int attempt, int maxAttempts) {
        int nextAttempt = attempt + 1;
        if (stage == BugFixStage.VERIFY && nextAttempt < maxAttempts) {
            return new Decision(BugFixStage.FIX, BugFixStatus.RUNNING, nextAttempt,
                    false, 0, false);
        }
        if ((stage == BugFixStage.REPRO_DEPLOY || stage == BugFixStage.VERIFY_DEPLOY)
                && nextAttempt < maxAttempts) {
            return new Decision(stage, BugFixStatus.QUEUED, nextAttempt,
                    true, retryDelay(nextAttempt), false);
        }
        return new Decision(stage, BugFixStatus.FAILED, nextAttempt, false, 0, true);
    }

    /** 指数退避：5s、10s、20s、40s，上限 60s。 */
    public static long retryDelay(int attempt) {
        long delay = BASE_RETRY_MS * (1L << Math.min(Math.max(attempt, 1), 4));
        return Math.min(MAX_RETRY_MS, delay);
    }
}
