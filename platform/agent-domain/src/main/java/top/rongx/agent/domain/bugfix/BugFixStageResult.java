package top.rongx.agent.domain.bugfix;

/**
 * 单个阶段的执行结果。
 *
 * <p>字段为 null 表示「该阶段没有更新这个字段」，编排器会保留运行记录里的原值。
 * 这样各阶段只需关心自己产出的证据，不必回传完整运行记录。
 *
 * <p>证据分层：
 * <ul>
 *   <li>{@code evidence}：本阶段的可读证据（部署日志、复现日志、验证结论），追加到阶段记录；</li>
 *   <li>{@code artifact}：结构化产物（分析阶段的 JSON），落库到阶段记录供后续阶段/前端读取；</li>
 *   <li>{@code analysisJson} / {@code fixSummary}：需要跨阶段复用的结构化字段。</li>
 * </ul>
 */
public record BugFixStageResult(
        Outcome outcome,
        String message,
        String evidence,
        String analysis,
        String analysisJson,
        String fixSummary,
        String baseSha,
        String branch,
        String fixSha,
        String env,
        String prUrl,
        String artifact) {

    public enum Outcome {
        /** 阶段成功，进入下一阶段。 */
        CONTINUE,
        /** 前置条件不满足，停在当前阶段等待人工补齐。 */
        BLOCKED,
        /** 执行失败，停在当前阶段等待重试。 */
        FAILED
    }

    public static BugFixStageResult ok(String message) {
        return new BugFixStageResult(Outcome.CONTINUE, message, null, null, null, null, null,
                null, null, null, null, null);
    }

    public static BugFixStageResult ok(String message, String evidence) {
        return new BugFixStageResult(Outcome.CONTINUE, message, evidence, null, null, null,
                null, null, null, null, null, null);
    }

    public static BugFixStageResult blocked(String message) {
        return new BugFixStageResult(Outcome.BLOCKED, message, null, null, null, null, null,
                null, null, null, null, null);
    }

    public static BugFixStageResult failed(String message) {
        return new BugFixStageResult(Outcome.FAILED, message, null, null, null, null, null,
                null, null, null, null, null);
    }

    private BugFixStageResult copy(String evidence, String analysis, String analysisJson,
                                   String fixSummary, String baseSha, String branch,
                                   String fixSha, String env, String prUrl, String artifact) {
        return new BugFixStageResult(outcome, message,
                evidence == null ? this.evidence : evidence,
                analysis == null ? this.analysis : analysis,
                analysisJson == null ? this.analysisJson : analysisJson,
                fixSummary == null ? this.fixSummary : fixSummary,
                baseSha == null ? this.baseSha : baseSha,
                branch == null ? this.branch : branch,
                fixSha == null ? this.fixSha : fixSha,
                env == null ? this.env : env,
                prUrl == null ? this.prUrl : prUrl,
                artifact == null ? this.artifact : artifact);
    }

    public BugFixStageResult withEvidence(String value) {
        return copy(value, null, null, null, null, null, null, null, null, null);
    }

    public BugFixStageResult withAnalysis(String value) {
        return copy(null, value, null, null, null, null, null, null, null, null);
    }

    public BugFixStageResult withAnalysisJson(String value) {
        return copy(null, null, value, null, null, null, null, null, null, null);
    }

    public BugFixStageResult withFixSummary(String value) {
        return copy(null, null, null, value, null, null, null, null, null, null);
    }

    public BugFixStageResult withBaseSha(String value) {
        return copy(null, null, null, null, value, null, null, null, null, null);
    }

    public BugFixStageResult withBranch(String value) {
        return copy(null, null, null, null, null, value, null, null, null, null);
    }

    public BugFixStageResult withFixSha(String value) {
        return copy(null, null, null, null, null, null, value, null, null, null);
    }

    public BugFixStageResult withEnv(String value) {
        return copy(null, null, null, null, null, null, null, value, null, null);
    }

    public BugFixStageResult withPrUrl(String value) {
        return copy(null, null, null, null, null, null, null, null, value, null);
    }

    public BugFixStageResult withArtifact(String value) {
        return copy(null, null, null, null, null, null, null, null, null, value);
    }
}
