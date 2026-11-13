package top.rongx.agent.domain.bugfix;

/**
 * 单个阶段的执行结果。
 *
 * <p>字段为 null 表示「该阶段没有更新这个字段」，编排器会保留运行记录里的原值。
 * 这样各阶段只需关心自己产出的证据，不必回传完整运行记录。
 */
public record BugFixStageResult(
        Outcome outcome,
        String message,
        String evidence,
        String analysis,
        String baseSha,
        String branch,
        String fixSha,
        String env,
        String prUrl) {

    public enum Outcome {
        /** 阶段成功，进入下一阶段。 */
        CONTINUE,
        /** 前置条件不满足，停在当前阶段等待人工补齐。 */
        BLOCKED,
        /** 执行失败，停在当前阶段等待重试。 */
        FAILED
    }

    public static BugFixStageResult ok(String message) {
        return new BugFixStageResult(Outcome.CONTINUE, message, null, null, null, null, null, null, null);
    }

    public static BugFixStageResult ok(String message, String evidence) {
        return new BugFixStageResult(Outcome.CONTINUE, message, evidence, null, null, null, null, null, null);
    }

    public static BugFixStageResult blocked(String message) {
        return new BugFixStageResult(Outcome.BLOCKED, message, null, null, null, null, null, null, null);
    }

    public static BugFixStageResult failed(String message) {
        return new BugFixStageResult(Outcome.FAILED, message, null, null, null, null, null, null, null);
    }

    public BugFixStageResult withAnalysis(String value) {
        return new BugFixStageResult(outcome, message, evidence, value, baseSha, branch,
                fixSha, env, prUrl);
    }

    public BugFixStageResult withBaseSha(String value) {
        return new BugFixStageResult(outcome, message, evidence, analysis, value, branch,
                fixSha, env, prUrl);
    }

    public BugFixStageResult withBranch(String value) {
        return new BugFixStageResult(outcome, message, evidence, analysis, baseSha, value,
                fixSha, env, prUrl);
    }

    public BugFixStageResult withFixSha(String value) {
        return new BugFixStageResult(outcome, message, evidence, analysis, baseSha, branch,
                value, env, prUrl);
    }

    public BugFixStageResult withEnv(String value) {
        return new BugFixStageResult(outcome, message, evidence, analysis, baseSha, branch,
                fixSha, value, prUrl);
    }

    public BugFixStageResult withPrUrl(String value) {
        return new BugFixStageResult(outcome, message, evidence, analysis, baseSha, branch,
                fixSha, env, value);
    }
}
